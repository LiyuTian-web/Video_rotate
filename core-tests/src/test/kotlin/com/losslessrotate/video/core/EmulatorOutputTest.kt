package com.losslessrotate.video.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class EmulatorOutputTest {
    @Test
    fun emulatorOutputKeepsEveryNonMatrixByte() {
        val output = System.getProperty("emulatorOutput")?.let(::File)
        assumeTrue("仅在模拟器输出回传后执行", output?.isFile == true)
        output ?: return
        val source = File("DSC_4386.MOV")
        val angle = RotationAngle.fromDegrees(System.getProperty("emulatorExpectedAngle")?.toIntOrNull() ?: 270)
        assertEquals(source.length(), output.length())
        val analysis = FileInputStream(source).channel.use { IsoBmffAnalyzer().analyze(it, angle) }
        FileInputStream(output).channel.use { assertEquals(angle.degrees, IsoBmffAnalyzer().inspectRotation(it)) }

        val patchRanges = analysis.patches.map { it.offset until (it.offset + it.replacement.size) }
        FileInputStream(source).use { left ->
            FileInputStream(output).use { right ->
                val leftBytes = ByteArray(1024 * 1024)
                val rightBytes = ByteArray(leftBytes.size)
                var offset = 0L
                var changed = false
                while (true) {
                    val leftRead = left.read(leftBytes)
                    val rightRead = right.read(rightBytes)
                    assertEquals(leftRead, rightRead)
                    if (leftRead < 0) break
                    for (index in 0 until leftRead) {
                        if (leftBytes[index] != rightBytes[index]) {
                            val absolute = offset + index
                            assertTrue("模拟器输出改动了非矩阵字节：$absolute", patchRanges.any { absolute in it })
                            changed = true
                        }
                    }
                    offset += leftRead
                }
                assertTrue("模拟器输出应改动显示矩阵", changed)
            }
        }
    }
}
