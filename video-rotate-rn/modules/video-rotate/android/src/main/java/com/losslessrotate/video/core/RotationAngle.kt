package com.losslessrotate.video.core

enum class RotationAngle(val degrees: Int) {
    CLOCKWISE_90(90),
    CLOCKWISE_180(180),
    CLOCKWISE_270(270);

    companion object {
        fun fromDegrees(value: Int): RotationAngle =
            entries.firstOrNull { it.degrees == value }
                ?: throw IllegalArgumentException("不支持的旋转角度：$value")
    }
}
