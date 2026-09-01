package com.losslessrotate.video.media

import android.net.Uri

data class MediaVideoItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val modifiedSeconds: Long,
    val relativePath: String?,
    val volumeName: String?,
    val mimeType: String,
    val legacyDataPath: String? = null,
    val selected: Boolean = false,
    val sourceTreeUri: Uri? = null,
)
