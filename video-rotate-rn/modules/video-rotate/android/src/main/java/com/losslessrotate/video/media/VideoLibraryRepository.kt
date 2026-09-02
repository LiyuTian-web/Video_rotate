package com.losslessrotate.video.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VideoPermissionState { FULL, PARTIAL, DENIED }

data class VideoLibraryResult(
    val videos: List<MediaVideoItem>,
    val hiddenMotionPhotoCount: Int,
    val motionPhotoFilteringAvailable: Boolean,
)

class VideoLibraryRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun permissionState(): VideoPermissionState {
        return when {
            Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_VIDEO) ->
                VideoPermissionState.FULL
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
                VideoPermissionState.PARTIAL
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) ->
                VideoPermissionState.FULL
            else -> VideoPermissionState.DENIED
        }
    }

    fun hasImageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_IMAGES) -> true
        Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
        Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> true
        else -> false
    }

    suspend fun queryVideos(): VideoLibraryResult = withContext(Dispatchers.IO) {
        if (permissionState() == VideoPermissionState.DENIED) {
            return@withContext VideoLibraryResult(emptyList(), 0, hasImageAccess())
        }

        val imagePairs = if (hasImageAccess()) queryImagePairs() else emptyMap()

        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
                add(MediaStore.Video.Media.VOLUME_NAME)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()

        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videos = mutableListOf<MediaVideoItem>()
        var hiddenMotionPhotoCount = 0
        resolver.query(
            collection,
            projection,
            if (Build.VERSION.SDK_INT >= 29) "${MediaStore.Video.Media.IS_PENDING}=0" else null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC, ${MediaStore.Video.Media._ID} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val pathIndex = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            val volumeIndex = cursor.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
            @Suppress("DEPRECATION")
            val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val reportedMime = cursor.getString(mimeIndex)
                if (!isSupportedVideo(name, reportedMime)) continue
                // MediaProvider may report video/mp4 for an MP4-compatible .MOV file. Matching
                // the output MIME to its extension avoids Android appending a second ".mp4".
                val mime = mimeForName(name)
                val id = cursor.getLong(idIndex)
                val duration = cursor.getLong(durationIndex)
                val modified = cursor.getLong(modifiedIndex)
                val relativePath = if (pathIndex >= 0) cursor.getString(pathIndex) else null
                if (isMotionPhotoVideoCompanion(duration, modified, imagePairs[motionPhotoPairKey(relativePath, name)])) {
                    hiddenMotionPhotoCount++
                    continue
                }
                val volume = if (volumeIndex >= 0) cursor.getString(volumeIndex) else null
                val itemCollection = if (Build.VERSION.SDK_INT >= 29 && volume != null) {
                    MediaStore.Video.Media.getContentUri(volume)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                videos += MediaVideoItem(
                    uri = ContentUris.withAppendedId(itemCollection, id),
                    name = name,
                    size = cursor.getLong(sizeIndex),
                    durationMs = duration,
                    modifiedSeconds = modified,
                    relativePath = relativePath,
                    volumeName = volume,
                    mimeType = mime,
                    legacyDataPath = if (dataIndex >= 0) cursor.getString(dataIndex) else null,
                )
            }
        }
        val sorted = videos.sortedWith { a, b ->
            compareNewestFirst(
                a.modifiedSeconds,
                a.uri.lastPathSegment?.toLongOrNull() ?: 0,
                b.modifiedSeconds,
                b.uri.lastPathSegment?.toLongOrNull() ?: 0,
            )
        }
        VideoLibraryResult(sorted, hiddenMotionPhotoCount, hasImageAccess())
    }

    private fun queryImagePairs(): Map<String, List<Long>> = runCatching {
        val projection = buildList {
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Images.Media.RELATIVE_PATH)
        }.toTypedArray()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val pairs = mutableMapOf<String, MutableList<Long>>()
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!isMotionPhotoImageName(name)) continue
                val path = if (pathIndex >= 0) cursor.getString(pathIndex) else null
                pairs.getOrPut(motionPhotoPairKey(path, name)) { mutableListOf() }
                    .add(cursor.getLong(modifiedIndex))
            }
        }
        pairs
    }.getOrDefault(emptyMap())

    private fun isMotionPhotoImageName(name: String): Boolean =
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
            name.endsWith(".heic", true) || name.endsWith(".heif", true) ||
            name.endsWith(".avif", true)

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun mimeForName(name: String): String =
        if (name.endsWith(".mp4", true)) "video/mp4" else "video/quicktime"
}
