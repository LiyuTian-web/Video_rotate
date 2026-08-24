package com.losslessrotate.video.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailRepository(private val resolver: ContentResolver) {
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(item: MediaVideoItem, width: Int = 360, height: Int = 240): Bitmap? {
        val key = item.uri.toString()
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key) ?: runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.loadThumbnail(item.uri, Size(width, height), null)
                } else {
                    MediaMetadataRetriever().run {
                        try {
                            resolver.openFileDescriptor(item.uri, "r")?.use { descriptor ->
                                setDataSource(descriptor.fileDescriptor)
                                getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }
                        } finally {
                            release()
                        }
                    }
                }
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    companion object {
        private fun cacheSizeKb(): Int =
            (Runtime.getRuntime().maxMemory() / 1024L / 10L).coerceIn(8 * 1024L, 48 * 1024L).toInt()
    }
}
