package com.losslessrotate.video.media

import kotlin.math.abs

fun isSupportedVideo(name: String, mimeType: String?): Boolean =
    name.endsWith(".mov", ignoreCase = true) ||
        name.endsWith(".mp4", ignoreCase = true) ||
        mimeType.equals("video/quicktime", ignoreCase = true) ||
        mimeType.equals("video/mp4", ignoreCase = true)

fun compareNewestFirst(modifiedA: Long, idA: Long, modifiedB: Long, idB: Long): Int =
    when {
        modifiedA != modifiedB -> modifiedB.compareTo(modifiedA)
        else -> idB.compareTo(idA)
    }

fun motionPhotoPairKey(relativePath: String?, name: String): String {
    val normalizedPath = relativePath.orEmpty().replace('\\', '/').trim('/').lowercase()
    val dot = name.lastIndexOf('.')
    val stem = (if (dot > 0) name.substring(0, dot) else name).lowercase()
    return "$normalizedPath|$stem"
}

fun isMotionPhotoVideoCompanion(
    durationMs: Long,
    modifiedSeconds: Long,
    imageModifiedSeconds: List<Long>?,
): Boolean {
    if (durationMs > 10_000 || imageModifiedSeconds.isNullOrEmpty()) return false
    return imageModifiedSeconds.any { imageTime ->
        imageTime <= 0 || modifiedSeconds <= 0 || abs(imageTime - modifiedSeconds) <= 300
    }
}
