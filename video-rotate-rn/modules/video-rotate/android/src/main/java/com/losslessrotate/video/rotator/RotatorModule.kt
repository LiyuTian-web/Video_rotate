package com.losslessrotate.video.rotator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.losslessrotate.video.job.OutputTarget
import com.losslessrotate.video.job.RotationInput
import com.losslessrotate.video.job.RotationJobSpec
import com.losslessrotate.video.job.RotationJobStore
import com.losslessrotate.video.media.MediaVideoItem
import com.losslessrotate.video.media.ThumbnailFileStore
import com.losslessrotate.video.media.VideoLibraryRepository
import com.losslessrotate.video.media.VideoPermissionState
import com.losslessrotate.video.media.isSupportedVideo
import com.losslessrotate.video.service.RotationJobBus
import com.losslessrotate.video.service.RotationJobState
import com.losslessrotate.video.service.RotationService
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class RotatorModule : Module() {
    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var busJob: Job? = null
    private var pendingFolderPromise: Promise? = null
    private var thumbnails: ThumbnailFileStore? = null

    override fun definition() = ModuleDefinition {
        Name("Rotator")

        Events("onJobState", "onPermissionsChanged")

        OnCreate {
            startJobBusBridge()
        }

        OnActivityEntersForeground {
            val context = appContext.reactContext
            if (context != null) {
                sendEvent("onPermissionsChanged", permissionStateMap(context))
            }
        }

        OnDestroy {
            busJob?.cancel()
            moduleScope.cancel()
        }

        OnActivityResult { _, payload ->
            if (payload.requestCode == REQUEST_FOLDER_PICK) {
                val promise = pendingFolderPromise
                pendingFolderPromise = null
                val data = payload.data
                if (payload.resultCode == Activity.RESULT_OK && data?.dataString != null) {
                    val uriString = data.dataString!!
                    persistFolderPermission(uriString, data.flags)
                    promise?.resolve(uriString)
                } else {
                    promise?.resolve(null)
                }
            }
        }

        AsyncFunction("getPermissionState") { promise: Promise ->
            val context = requireContext(promise) ?: return@AsyncFunction
            promise.resolve(permissionStateMap(context))
        }

        AsyncFunction("requestMediaPermissions") { promise: Promise ->
            requestPermissions(
                when {
                    Build.VERSION.SDK_INT >= 34 -> arrayOf(
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    )
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_IMAGES,
                    )
                    Build.VERSION.SDK_INT <= 28 -> arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    )
                    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
            ) { promise.resolve(it) }
        }

        AsyncFunction("requestImagePermissions") { promise: Promise ->
            requestPermissions(
                if (Build.VERSION.SDK_INT >= 34) {
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                } else {
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                },
            ) { promise.resolve(it) }
        }

        AsyncFunction("requestNotificationPermission") { promise: Promise ->
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { promise.resolve(it) }
        }

        AsyncFunction("scanLibrary") { promise: Promise ->
            val context = requireContext(promise) ?: return@AsyncFunction
            moduleScope.launch {
                runCatching { VideoLibraryRepository(context).queryVideos() }
                    .onSuccess { result ->
                        promise.resolve(
                            mapOf(
                                "videos" to result.videos.map { it.toMap() },
                                "hiddenMotionPhotoCount" to result.hiddenMotionPhotoCount,
                                "motionPhotoFilteringAvailable" to result.motionPhotoFilteringAvailable,
                            ),
                        )
                    }
                    .onFailure { promise.reject("E_SCAN", it.message ?: "读取视频库失败", null) }
            }
        }

        AsyncFunction("scanFolder") { treeUri: String, promise: Promise ->
            val context = requireContext(promise) ?: return@AsyncFunction
            moduleScope.launch {
                runCatching {
                    val directory = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                        ?.takeIf { it.isDirectory } ?: error("无法打开所选目录")
                    val items = directory.listFiles().asSequence()
                        .filter { it.isFile }
                        .mapNotNull { file ->
                            val name = file.name ?: return@mapNotNull null
                            val mime = if (name.endsWith(".mp4", true)) "video/mp4" else "video/quicktime"
                            if (!isSupportedVideo(name, mime)) return@mapNotNull null
                            MediaVideoItem(
                                uri = file.uri,
                                name = name,
                                size = file.length(),
                                durationMs = 0,
                                modifiedSeconds = file.lastModified() / 1000,
                                relativePath = null,
                                volumeName = null,
                                mimeType = mime,
                                sourceTreeUri = Uri.parse(treeUri),
                            )
                        }
                        .sortedWith(compareByDescending<MediaVideoItem> { it.modifiedSeconds }.thenBy { it.name.lowercase() })
                        .toList()
                    directory.name.orEmpty() to items
                }
                    .onSuccess { (name, items) ->
                        promise.resolve(
                            mapOf(
                                "folderName" to name,
                                "videos" to items.map { it.toMap() },
                            ),
                        )
                    }
                    .onFailure { promise.reject("E_SCAN_FOLDER", it.message ?: "目录扫描失败", null) }
            }
        }

        AsyncFunction("pickFolder") { initialUri: String?, promise: Promise ->
            val activity = appContext.currentActivity
            if (activity == null) {
                promise.resolve(null)
                return@AsyncFunction
            }
            if (pendingFolderPromise != null) {
                promise.resolve(null)
                return@AsyncFunction
            }
            pendingFolderPromise = promise
            val intent = Intent("android.intent.action.OPEN_DOCUMENT_TREE")
            initialUri?.takeIf { it.isNotBlank() && Build.VERSION.SDK_INT >= 26 }?.let { initial ->
                runCatching {
                    intent.putExtra(
                        android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse(initial),
                    )
                }
            }
            runCatching { activity.startActivityForResult(intent, REQUEST_FOLDER_PICK) }
                .onFailure {
                    pendingFolderPromise = null
                    promise.reject("E_PICK_FOLDER", "无法打开系统目录选择器：${it.message}", null)
                }
        }

        AsyncFunction("getThumbnail") { uri: String, width: Int, height: Int, promise: Promise ->
            val context = requireContext(promise) ?: return@AsyncFunction
            val store = thumbnails ?: ThumbnailFileStore(context.applicationContext).also { thumbnails = it }
            moduleScope.launch {
                promise.resolve(store.thumbnailFile(uri, width.coerceIn(64, 1024), height.coerceIn(64, 1024)))
            }
        }

        AsyncFunction("startJob") { inputsJson: String, angle: Int, outputTreeUri: String?, promise: Promise ->
            val context = requireContext(promise) ?: return@AsyncFunction
            runCatching {
                val array = org.json.JSONArray(inputsJson)
                val spec = RotationJobSpec(
                    inputs = (0 until array.length()).map { index ->
                        val item = array.getJSONObject(index)
                        RotationInput(
                            sourceUri = item.getString("sourceUri"),
                            displayName = item.optNullableString("displayName") ?: "视频",
                            mimeType = item.optNullableString("mimeType") ?: "video/quicktime",
                            size = item.optLong("size"),
                            relativePath = item.optNullableString("relativePath"),
                            volumeName = item.optNullableString("volumeName"),
                            sourceTreeUri = item.optNullableString("sourceTreeUri"),
                            legacyDataPath = item.optNullableString("legacyDataPath"),
                        )
                    },
                    angleDegrees = angle,
                    outputTarget = outputTreeUri?.takeIf { it.isNotBlank() }
                        ?.let { OutputTarget.SafDirectory(it) }
                        ?: OutputTarget.AdjacentRotateFolders,
                )
                val jobId = RotationJobStore(context).write(spec)
                RotationJobBus.update(
                    RotationJobState.Running(1, spec.inputs.size, "正在准备…", 0, 0, 0, 0),
                )
                val serviceIntent = Intent(context, RotationService::class.java).apply {
                    action = RotationService.ACTION_START
                    putExtra(RotationService.EXTRA_JOB_ID, jobId)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
                .onSuccess { promise.resolve(true) }
                .onFailure { promise.reject("E_START_JOB", it.message ?: "无法创建任务", null) }
        }

        AsyncFunction("cancelJob") { promise: Promise ->
            val context = requireContext(promise) ?: return@AsyncFunction
            runCatching {
                context.startService(
                    Intent(context, RotationService::class.java).setAction(RotationService.ACTION_CANCEL),
                )
            }
            promise.resolve(true)
        }
    }

    private fun startJobBusBridge() {        busJob?.cancel()
        busJob = moduleScope.launch {
            RotationJobBus.state.collectLatest { state ->
                sendEvent("onJobState", state.toMap())
            }
        }
    }

    private fun requireContext(promise: Promise): Context? {
        val context = appContext.reactContext
        if (context == null) {
            promise.reject("E_NO_CONTEXT", "应用上下文不可用，请稍后重试", null)
            return null
        }
        return context.applicationContext
    }

    private fun requestPermissions(permissions: Array<String>, then: (Map<String, Any?>) -> Unit) {
        val activity = appContext.currentActivity as? Activity
        if (activity != null) {
            runCatching { ActivityCompat.requestPermissions(activity, permissions, REQUEST_PERMISSIONS) }
        }
        val context = appContext.reactContext
        then(context?.let { permissionStateMap(it) } ?: emptyMap())
    }

    private fun permissionStateMap(context: Context): Map<String, Any?> {
        val repository = VideoLibraryRepository(context)
        return mapOf(
            "media" to when (repository.permissionState()) {
                VideoPermissionState.FULL -> "full"
                VideoPermissionState.PARTIAL -> "partial"
                VideoPermissionState.DENIED -> "denied"
            },
            "images" to repository.hasImageAccess(),
            "notifications" to (
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ),
        )
    }

    private fun persistFolderPermission(uriString: String, flags: Int) {
        val context = appContext.reactContext ?: return
        val uri = Uri.parse(uriString)
        val grantFlags = (flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, grantFlags) }
    }

    private fun MediaVideoItem.toMap(): Map<String, Any?> = mapOf(
        "uri" to uri.toString(),
        "name" to name,
        "size" to size,
        "durationMs" to durationMs,
        "modifiedSeconds" to modifiedSeconds,
        "relativePath" to relativePath,
        "volumeName" to volumeName,
        "mimeType" to mimeType,
        "legacyDataPath" to legacyDataPath,
        "sourceTreeUri" to sourceTreeUri?.toString(),
    )

    private fun RotationJobState.toMap(): Map<String, Any?> = when (this) {
        RotationJobState.Idle -> mapOf("kind" to "idle")
        is RotationJobState.Running -> mapOf(
            "kind" to "running",
            "currentIndex" to currentIndex,
            "totalFiles" to totalFiles,
            "fileName" to fileName,
            "fileBytesDone" to fileBytesDone,
            "fileBytesTotal" to fileBytesTotal,
            "completedFiles" to completedFiles,
            "failedFiles" to failedFiles,
        )
        is RotationJobState.Finished -> mapOf(
            "kind" to "finished",
            "completedFiles" to completedFiles,
            "failedFiles" to failedFiles,
            "cancelled" to cancelled,
            "messages" to messages,
        )
    }

    private companion object {
        const val REQUEST_FOLDER_PICK = 0x2C71
        const val REQUEST_PERMISSIONS = 0x2C72
    }
}

private fun org.json.JSONObject.optNullableString(name: String): String? {
    val value = opt(name)
    if (value == null || value == org.json.JSONObject.NULL) return null
    return value.toString().takeIf { it.isNotBlank() }
}
