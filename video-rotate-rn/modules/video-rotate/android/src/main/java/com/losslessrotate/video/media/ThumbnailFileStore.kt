package com.losslessrotate.video.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ThumbnailFileStore(private val context: Context) {
    private val directory = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val ioGate = Semaphore(MAX_CONCURRENT)

    suspend fun thumbnailFile(uriString: String, width: Int, height: Int): String? = withContext(Dispatchers.IO) {
        val target = fileFor(uriString)
        if (target.exists() && target.length() > 0) return@withContext uriOf(target)
        ioGate.withPermit {
            if (target.exists()) return@withContext uriOf(target)
            runCatching {
                val source = loadBitmap(Uri.parse(uriString), width, height) ?: return@runCatching null
                val image = scaleToAspect(source, width, height)
                try {
                    FileOutputStream(target).use { out ->
                        image.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    }
                } finally {
                    if (image !== source) image.recycle()
                    source.recycle()
                }
                uriOf(target)
            }.getOrNull()?.also { trimCache() }
        }
    }

    private suspend fun loadBitmap(uri: Uri, width: Int, height: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(width, height), null)
        } else {
            legacyFrame(uri)
        }
    } catch (error: Exception) {
        if (Build.VERSION.SDK_INT >= 29) legacyFrame(uri) else null
    }

    private fun legacyFrame(uri: Uri): Bitmap? = MediaMetadataRetriever().run {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                setDataSource(descriptor.fileDescriptor)
                getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (error: Exception) {
            null
        } finally {
            runCatching { release() }
        }
    }

    private fun scaleToAspect(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val left = (scaledWidth - width) / 2
        val top = (scaledHeight - height) / 2
        if (left < 0 || top < 0 || left + width > scaledWidth || top + height > scaledHeight) {
            return scaled
        }
        val cropped = Bitmap.createBitmap(scaled, left, top, width, height, Matrix(), false)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private fun fileFor(uriString: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(uriString.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.jpg")
    }

    private fun uriOf(file: File): String = "file://${file.absolutePath}"

    private fun trimCache() {
        val files = directory.listFiles() ?: return
        if (files.size <= MAX_CACHED) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_CONCURRENT = 3
        const val MAX_CACHED = 1200
    }
}
