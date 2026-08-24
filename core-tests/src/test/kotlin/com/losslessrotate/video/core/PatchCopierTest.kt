package com.losslessrotate.video.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PatchCopierTest {
    @Test
    fun replacesBytesWithoutChangingLength() {
        val input = ByteArray(64) { it.toByte() }
        val output = ByteArrayOutputStream()
        PatchCopier(bufferSize = 7).copy(
            input = ByteArrayInputStream(input),
            output = output,
            fileSize = input.size.toLong(),
            patches = listOf(
                BytePatch(4, byteArrayOf(99, 98, 97)),
                BytePatch(31, byteArrayOf(7, 7)),
            ),
        )
        val expected = input.copyOf().apply {
            this[4] = 99
            this[5] = 98
            this[6] = 97
            this[31] = 7
            this[32] = 7
        }
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun cancellationStopsCopy() {
        val input = ByteArray(1024)
        assertThrows(RotationCancelledException::class.java) {
            PatchCopier(bufferSize = 32).copy(
                input = ByteArrayInputStream(input),
                output = ByteArrayOutputStream(),
                fileSize = input.size.toLong(),
                patches = emptyList(),
                isCancelled = { true },
            )
        }
    }
}
