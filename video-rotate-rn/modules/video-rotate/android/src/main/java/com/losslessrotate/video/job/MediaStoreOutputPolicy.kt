package com.losslessrotate.video.job

data class MediaStoreOutputLocation(
    val relativePath: String,
    val redirected: Boolean,
)

object MediaStoreOutputPolicy {
    private val allowedVideoRoots = setOf("dcim", "movies", "pictures")

    fun resolve(sourceRelativePath: String?): MediaStoreOutputLocation {
        val segments = cleanSegments(sourceRelativePath)
        val canWriteAdjacent = segments.firstOrNull()?.lowercase() in allowedVideoRoots
        return if (canWriteAdjacent) {
            MediaStoreOutputLocation((segments + "rotate").joinToString("/", postfix = "/"), false)
        } else {
            MediaStoreOutputLocation(fallbackRelativePath(sourceRelativePath), true)
        }
    }

    fun fallbackRelativePath(sourceRelativePath: String?): String {
        val sourceSegments = cleanSegments(sourceRelativePath).ifEmpty { listOf("未分类") }
        return (listOf("Movies", "无损视频旋转") + sourceSegments + "rotate")
            .joinToString("/", postfix = "/")
    }

    fun requiresRedirect(sourceRelativePath: String?): Boolean = resolve(sourceRelativePath).redirected

    private fun cleanSegments(path: String?): List<String> = path.orEmpty()
        .replace('\\', '/')
        .split('/')
        .map(String::trim)
        .filter { it.isNotEmpty() && it != "." && it != ".." }
}
