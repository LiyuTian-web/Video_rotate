package com.losslessrotate.video.job

data class RotationInput(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val relativePath: String? = null,
    val volumeName: String? = null,
    val sourceTreeUri: String? = null,
    val legacyDataPath: String? = null,
)

sealed interface OutputTarget {
    data object AdjacentRotateFolders : OutputTarget
    data class SafDirectory(val uri: String) : OutputTarget
}

data class RotationJobSpec(
    val inputs: List<RotationInput>,
    val angleDegrees: Int,
    val outputTarget: OutputTarget,
)

object OutputNaming {
    fun rotatedName(sourceName: String, degrees: Int, sequence: Int = 1): String {
        val dot = sourceName.lastIndexOf('.')
        val stem = if (dot > 0) sourceName.substring(0, dot) else sourceName
        val extension = if (dot > 0) sourceName.substring(dot) else ".mov"
        val suffix = if (sequence <= 1) "" else "_$sequence"
        return "${stem}_rot$degrees$suffix$extension"
    }

    fun uniqueName(sourceName: String, degrees: Int, exists: (String) -> Boolean): String {
        var sequence = 1
        while (true) {
            val candidate = rotatedName(sourceName, degrees, sequence)
            if (!exists(candidate)) return candidate
            sequence++
        }
    }
}
