package com.losslessrotate.video.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.losslessrotate.video.MainActivity
import com.losslessrotate.video.R
import com.losslessrotate.video.core.AndroidRotationProcessor
import com.losslessrotate.video.core.RotationAngle
import com.losslessrotate.video.core.RotationCancelledException
import com.losslessrotate.video.job.OutputNaming
import com.losslessrotate.video.job.OutputTarget
import com.losslessrotate.video.job.MediaStoreOutputPolicy
import com.losslessrotate.video.job.RotationInput
import com.losslessrotate.video.job.RotationJobSpec
import com.losslessrotate.video.job.RotationJobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RotationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                if (activeJob?.isActive == true) updateNotification("正在取消…", 0, 0, true) else stopSelf()
            }
            ACTION_START -> if (activeJob?.isActive != true) {
                cancelled.set(false)
                startInForeground(buildNotification("正在准备…", 0, 0, true))
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                activeJob = serviceScope.launch { runJob(jobId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true)
        stopSelf(startId)
    }

    private fun runJob(jobId: String?) {
        if (jobId == null) {
            finishBatch(0, 1, false, listOf("没有收到任务编号"))
            return
        }
        val store = RotationJobStore(this)
        val spec = runCatching { store.read(jobId) }.getOrElse {
            finishBatch(0, 1, false, listOf(it.message ?: "无法读取任务"))
            return
        }
        try {
            runBatch(spec)
        } finally {
            store.delete(jobId)
        }
    }

    private fun runBatch(spec: RotationJobSpec) {
        val angle = runCatching { RotationAngle.fromDegrees(spec.angleDegrees) }.getOrElse {
            finishBatch(0, spec.inputs.size, false, listOf(it.message ?: "旋转角度无效"))
            return
        }
        if (spec.inputs.isEmpty()) {
            finishBatch(0, 0, false, listOf("没有可处理的文件"))
            return
        }
        val processor = AndroidRotationProcessor(contentResolver)
        val messages = mutableListOf<String>()
        var completed = 0
        var failed = 0
        for ((index, input) in spec.inputs.withIndex()) {
            if (cancelled.get()) break
            var destination: Destination? = null
            try {
                destination = createDestination(input, spec.outputTarget, angle.degrees)
                var lastNotificationAt = 0L
                processor.rotate(
                    source = Uri.parse(input.sourceUri),
                    destination = destination.uri,
                    angle = angle,
                    isCancelled = cancelled::get,
                    onProgress = { done, size ->
                        RotationJobBus.update(
                            RotationJobState.Running(index + 1, spec.inputs.size, input.displayName, done, size, completed, failed),
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS || done == size) {
                            val percent = if (size > 0) ((done * 100) / size).toInt() else 0
                            updateNotification("${index + 1}/${spec.inputs.size}  ${input.displayName}", percent, 100)
                            lastNotificationAt = now
                        }
                    },
                )
                if (cancelled.get()) throw RotationCancelledException()
                destination.commit()
                completed++
            } catch (_: RotationCancelledException) {
                destination?.abort()
                break
            } catch (error: Exception) {
                destination?.abort()
                failed++
                messages += "${input.displayName}：${error.message ?: error.javaClass.simpleName}"
            }
        }
        finishBatch(completed, failed, cancelled.get(), messages)
    }

    private fun createDestination(input: RotationInput, target: OutputTarget, degrees: Int): Destination =
        when (target) {
            is OutputTarget.SafDirectory -> createSafDestination(Uri.parse(target.uri), input, degrees)
            OutputTarget.AdjacentRotateFolders -> when {
                input.sourceTreeUri != null -> createTreeAdjacentDestination(Uri.parse(input.sourceTreeUri), input, degrees)
                Build.VERSION.SDK_INT >= 29 -> createMediaStoreDestination(input, degrees)
                else -> createLegacyDestination(input, degrees)
            }
        }

    private fun createSafDestination(treeUri: Uri, input: RotationInput, degrees: Int): Destination {
        val directory = DocumentFile.fromTreeUri(this, treeUri)
            ?.takeIf { it.isDirectory && it.canWrite() } ?: error("无法写入自定义输出目录")
        return createDocumentDestination(directory, input, degrees)
    }

    private fun createTreeAdjacentDestination(treeUri: Uri, input: RotationInput, degrees: Int): Destination {
        val sourceDirectory = DocumentFile.fromTreeUri(this, treeUri)
            ?.takeIf { it.isDirectory && it.canWrite() } ?: error("无法写入源目录")
        val rotate = sourceDirectory.listFiles().firstOrNull {
            it.isDirectory && it.name.equals(DEFAULT_OUTPUT_DIRECTORY, true)
        } ?: sourceDirectory.createDirectory(DEFAULT_OUTPUT_DIRECTORY)
        return createDocumentDestination(rotate ?: error("无法创建 rotate 目录"), input, degrees)
    }

    private fun createDocumentDestination(directory: DocumentFile, input: RotationInput, degrees: Int): Destination {
        cleanupStaleTemporaryFiles(directory)
        val finalName = OutputNaming.uniqueName(input.displayName, degrees) { directory.findFile(it) != null }
        val temporary = directory.createFile(input.mimeType, ".lossless_rotate_${System.nanoTime()}.partial")
            ?: error("无法创建临时输出文件")
        return Destination(
            uri = temporary.uri,
            commitAction = { if (!temporary.renameTo(finalName)) error("无法将输出文件重命名为 $finalName") },
            abortAction = { temporary.delete() },
        )
    }

    private fun createMediaStoreDestination(input: RotationInput, degrees: Int): Destination {
        val volume = input.volumeName ?: "external_primary"
        val collection = MediaStore.Video.Media.getContentUri(volume)
        val location = MediaStoreOutputPolicy.resolve(input.relativePath)
        return try {
            createMediaStoreDestinationAt(collection, location.relativePath, input, degrees)
        } catch (error: IllegalArgumentException) {
            // Some vendors further restrict writable media roots. Retry in Movies, which is
            // guaranteed to accept video entries, while preserving the source folder hierarchy.
            if (location.redirected) throw error
            createMediaStoreDestinationAt(
                collection,
                MediaStoreOutputPolicy.fallbackRelativePath(input.relativePath),
                input,
                degrees,
            )
        }
    }

    private fun createMediaStoreDestinationAt(
        collection: Uri,
        outputPath: String,
        input: RotationInput,
        degrees: Int,
    ): Destination {
        val finalName = uniqueMediaStoreName(collection, outputPath, input.displayName, degrees)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Video.Media.MIME_TYPE, input.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, outputPath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: error("无法在 rotate 目录创建输出")
        return Destination(
            uri = uri,
            commitAction = {
                val count = contentResolver.update(
                    uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null,
                )
                if (count <= 0) error("输出完成但无法发布到媒体库")
            },
            abortAction = { contentResolver.delete(uri, null, null) },
        )
    }

    private fun uniqueMediaStoreName(collection: Uri, path: String, sourceName: String, degrees: Int): String =
        OutputNaming.uniqueName(sourceName, degrees) { candidate ->
            contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.RELATIVE_PATH}=? AND ${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(path, candidate),
                null,
            )?.use { it.moveToFirst() } == true
        }

    private fun createLegacyDestination(input: RotationInput, degrees: Int): Destination {
        val source = input.legacyDataPath?.let(::File) ?: error("无法取得原视频路径，请改用自定义输出目录")
        val outputDirectory = File(source.parentFile, DEFAULT_OUTPUT_DIRECTORY)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) error("无法创建 rotate 目录")
        val finalName = OutputNaming.uniqueName(input.displayName, degrees) { File(outputDirectory, it).exists() }
        val finalFile = File(outputDirectory, finalName)
        val temporary = File(outputDirectory, ".lossless_rotate_${System.nanoTime()}.partial")
        return Destination(
            uri = Uri.fromFile(temporary),
            commitAction = {
                if (!temporary.renameTo(finalFile)) error("无法完成安全重命名")
                MediaScannerConnection.scanFile(this, arrayOf(finalFile.absolutePath), arrayOf(input.mimeType), null)
            },
            abortAction = { temporary.delete() },
        )
    }

    private fun cleanupStaleTemporaryFiles(directory: DocumentFile) {
        directory.listFiles().filter {
            it.isFile && it.name?.startsWith(".lossless_rotate_") == true
        }.forEach { it.delete() }
    }

    private fun finishBatch(completed: Int, failed: Int, wasCancelled: Boolean, messages: List<String>) {
        RotationJobBus.update(RotationJobState.Finished(completed, failed, wasCancelled, messages))
        val text = when {
            wasCancelled -> "任务已取消，已完成 $completed 个"
            failed > 0 -> "完成 $completed 个，失败 $failed 个"
            else -> "全部完成，共 $completed 个"
        }
        updateNotification(text, 100, 100, ongoing = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String, progress: Int, max: Int, indeterminate: Boolean = false, ongoing: Boolean = true) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, progress, max, indeterminate, ongoing))
    }

    private fun buildNotification(text: String, progress: Int, max: Int, indeterminate: Boolean = false, ongoing: Boolean = true): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RotationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("无损视频旋转")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(max, progress.coerceIn(0, max.coerceAtLeast(1)), indeterminate)
            .apply { if (ongoing) addAction(0, "取消", cancel) }
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "视频旋转进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示无损视频旋转任务的进度"
            },
        )
    }

    private class Destination(
        val uri: Uri,
        private val commitAction: () -> Unit,
        private val abortAction: () -> Unit,
    ) {
        fun commit() = commitAction()
        fun abort() { runCatching { abortAction() } }
    }

    companion object {
        const val ACTION_START = "com.losslessrotate.video.action.START"
        const val ACTION_CANCEL = "com.losslessrotate.video.action.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "rotation_jobs"
        private const val NOTIFICATION_ID = 270
        private const val DEFAULT_OUTPUT_DIRECTORY = "rotate"
        private const val NOTIFICATION_INTERVAL_MS = 500L
    }
}
