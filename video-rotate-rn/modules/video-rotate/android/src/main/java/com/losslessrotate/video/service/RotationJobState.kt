package com.losslessrotate.video.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RotationJobState {
    data object Idle : RotationJobState

    data class Running(
        val currentIndex: Int,
        val totalFiles: Int,
        val fileName: String,
        val fileBytesDone: Long,
        val fileBytesTotal: Long,
        val completedFiles: Int,
        val failedFiles: Int,
    ) : RotationJobState

    data class Finished(
        val completedFiles: Int,
        val failedFiles: Int,
        val cancelled: Boolean,
        val messages: List<String>,
    ) : RotationJobState
}

object RotationJobBus {
    private val mutableState = MutableStateFlow<RotationJobState>(RotationJobState.Idle)
    val state: StateFlow<RotationJobState> = mutableState.asStateFlow()

    internal fun update(value: RotationJobState) {
        mutableState.value = value
    }
}
