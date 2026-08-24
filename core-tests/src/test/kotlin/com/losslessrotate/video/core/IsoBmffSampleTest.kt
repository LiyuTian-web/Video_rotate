package com.losslessrotate.video.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

class IsoBmffSampleTest {
    private val sample = File("DSC_4386.MOV")

    @Test
    fun nikonSampleIsDetectedAsUnrotatedVideo() {
        assumeTrue("需要工作区中的 DSC_4386.MOV", sample.isFile)
        FileInputStream(sample).channel.use { channel ->
            val analysis = IsoBmffAnalyzer().analyze(channel, RotationAngle.CLOCKWISE_270)
            assertEquals(sample.length(), analysis.fileSize)
            assertEquals(1, analysis.videoTrackCount)
            assertEquals(0, analysis.detectedRotationDegrees)
            assertTrue(analysis.patches.isNotEmpty())
        }
    }

    @Test
    fun allSupportedAnglesAreAbsoluteAndOnlyPatchMatrixBytes() {
        assumeTrue("需要工作区中的 DSC_4386.MOV", sample.isFile)
        for (angle in RotationAngle.entries) {
            val destination = Files.createTempFile("nikon-rot-${angle.degrees}-", ".mov").toFile()
            try {
                val analysis = FileInputStream(sample).channel.use { channel ->
                    IsoBmffAnalyzer().analyze(channel, angle)
                }
                FileInputStream(sample).use { input ->
                    FileOutputStream(destination).use { output ->
                        PatchCopier().copy(input, output, analysis.fileSize, analysis.patches)
                    }
                }
                assertEquals(sample.length(), destination.length())
                FileInputStream(destination).channel.use { channel ->
                    assertEquals(angle.degrees, IsoBmffAnalyzer().inspectRotation(channel))
                }
                assertOnlyPatchesDiffer(sample, destination, analysis.patches)
            } finally {
                destination.delete()
            }
        }
    }

    private fun assertOnlyPatchesDiffer(source: File, destination: File, patches: List<BytePatch>) {
        val patchRanges = patches.map { it.offset until (it.offset + it.replacement.size) }
        FileInputStream(source).use { left ->
            FileInputStream(destination).use { right ->
                val leftBuffer = ByteArray(1024 * 1024)
                val rightBuffer = ByteArray(leftBuffer.size)
                var absoluteOffset = 0L
                var changedInsidePatch = false
                while (true) {
                    val leftRead = left.read(leftBuffer)
                    val rightRead = right.read(rightBuffer)
                    assertEquals(leftRead, rightRead)
                    if (leftRead < 0) break
                    for (index in 0 until leftRead) {
                        if (leftBuffer[index] != rightBuffer[index]) {
                            val offset = absoluteOffset + index
                            assertTrue("非矩阵字节发生改变：$offset", patchRanges.any { offset in it })
                            changedInsidePatch = true
                        }
                    }
                    absoluteOffset += leftRead
                }
                assertTrue("目标矩阵应与原矩阵不同", changedInsidePatch)
            }
        }
    }
}
