package com.losslessrotate.video.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

class PatchCopier(
    private val bufferSize: Int = 1024 * 1024,
) {
    fun copy(
        input: InputStream,
        output: OutputStream,
        fileSize: Long,
        patches: List<BytePatch>,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ) {
        var position = 0L
        val buffer = ByteArray(bufferSize)
        for (patch in patches.sortedBy { it.offset }) {
            if (patch.offset < position || patch.offset + patch.replacement.size > fileSize) {
                throw IllegalArgumentException("补丁位置越界：${patch.offset}")
            }
            position = copyExact(input, output, patch.offset - position, position, buffer, isCancelled, onProgress)
            discardExact(input, patch.replacement.size, isCancelled)
            output.write(patch.replacement)
            position += patch.replacement.size
            onProgress(position)
        }
        position = copyExact(input, output, fileSize - position, position, buffer, isCancelled, onProgress)
        if (position != fileSize) throw EOFException("输出大小不正确：$position/$fileSize")
        output.flush()
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        byteCount: Long,
        initialPosition: Long,
        buffer: ByteArray,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Long {
        var remaining = byteCount
        var position = initialPosition
        while (remaining > 0) {
            checkCancelled(isCancelled)
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, requested)
            if (read < 0) throw EOFException("源文件提前结束，偏移 $position")
            output.write(buffer, 0, read)
            remaining -= read
            position += read
            onProgress(position)
        }
        return position
    }

    private fun discardExact(input: InputStream, byteCount: Int, isCancelled: () -> Boolean) {
        val discard = ByteArray(minOf(byteCount, 4096))
        var remaining = byteCount
        while (remaining > 0) {
            checkCancelled(isCancelled)
            val read = input.read(discard, 0, minOf(discard.size, remaining))
            if (read < 0) throw EOFException("源文件在显示矩阵处提前结束")
            remaining -= read
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw RotationCancelledException()
    }
}

class RotationCancelledException : Exception("任务已取消")
