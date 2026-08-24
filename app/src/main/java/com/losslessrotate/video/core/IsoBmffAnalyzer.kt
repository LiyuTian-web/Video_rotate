package com.losslessrotate.video.core

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

class UnsupportedVideoException(message: String) : Exception(message)

data class BytePatch(
    val offset: Long,
    val replacement: ByteArray,
)

data class ContainerAnalysis(
    val fileSize: Long,
    val videoTrackCount: Int,
    val detectedRotationDegrees: Int,
    val patches: List<BytePatch>,
)

class IsoBmffAnalyzer {
    private data class Box(
        val type: String,
        val start: Long,
        val size: Long,
        val headerSize: Int,
    ) {
        val dataStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    fun analyze(channel: SeekableByteChannel, target: RotationAngle): ContainerAnalysis {
        val fileSize = try {
            channel.size()
        } catch (error: Exception) {
            throw UnsupportedVideoException("文件来源不支持随机读取，请先下载到本机存储")
        }
        if (fileSize < 16) throw UnsupportedVideoException("文件过小，不是有效的 MOV/MP4")

        val topLevel = readChildren(channel, 0, fileSize)
        if (topLevel.none { it.type == "ftyp" }) {
            throw UnsupportedVideoException("缺少 ftyp，文件不是受支持的 MOV/MP4")
        }
        val moov = topLevel.firstOrNull { it.type == "moov" }
            ?: throw UnsupportedVideoException("缺少 moov，文件可能不完整")

        val moovChildren = readChildren(channel, moov.dataStart, moov.end)
        val patches = mutableListOf<BytePatch>()

        moovChildren.firstOrNull { it.type == "mvhd" }?.let { mvhd ->
            val version = readByte(channel, mvhd.dataStart).toInt() and 0xff
            val matrixOffset = mvhd.dataStart + when (version) {
                0 -> 36
                1 -> 48
                else -> throw UnsupportedVideoException("不支持的 mvhd 版本：$version")
            }
            ensureRange(matrixOffset, MATRIX_BYTES.toLong(), mvhd)
            val matrix = readMatrix(channel, matrixOffset)
            rotationFromMatrix(matrix)
                ?: throw UnsupportedVideoException("影片包含缩放、翻转或倾斜的全局显示矩阵，已停止以避免错误修改")
            if (!matrix.contentEquals(canonicalMatrix(0))) {
                patches += BytePatch(matrixOffset, matrixBytes(0))
            }
        }

        var firstDetectedRotation: Int? = null
        var videoTrackCount = 0
        for (trak in moovChildren.filter { it.type == "trak" }) {
            val trakChildren = readChildren(channel, trak.dataStart, trak.end)
            val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: continue
            val mdiaChildren = readChildren(channel, mdia.dataStart, mdia.end)
            val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: continue
            ensureRange(hdlr.dataStart + 8, 4, hdlr)
            if (readAscii(channel, hdlr.dataStart + 8, 4) != "vide") continue

            val tkhd = trakChildren.firstOrNull { it.type == "tkhd" }
                ?: throw UnsupportedVideoException("视频轨道缺少 tkhd")
            val version = readByte(channel, tkhd.dataStart).toInt() and 0xff
            val matrixOffset = tkhd.dataStart + when (version) {
                0 -> 40
                1 -> 52
                else -> throw UnsupportedVideoException("不支持的 tkhd 版本：$version")
            }
            ensureRange(matrixOffset, MATRIX_BYTES.toLong(), tkhd)
            val matrix = readMatrix(channel, matrixOffset)
            val detected = rotationFromMatrix(matrix)
                ?: throw UnsupportedVideoException("视频轨道包含缩放、翻转或倾斜矩阵，已停止以避免错误修改")
            if (firstDetectedRotation == null) firstDetectedRotation = detected
            patches += BytePatch(matrixOffset, matrixBytes(target.degrees))
            videoTrackCount++
        }

        if (videoTrackCount == 0) throw UnsupportedVideoException("容器中没有可旋转的视频轨道")
        val sorted = patches.sortedBy { it.offset }
        sorted.zipWithNext().forEach { (left, right) ->
            if (left.offset + left.replacement.size > right.offset) {
                throw UnsupportedVideoException("显示矩阵位置重叠，文件结构异常")
            }
        }
        return ContainerAnalysis(
            fileSize = fileSize,
            videoTrackCount = videoTrackCount,
            detectedRotationDegrees = firstDetectedRotation ?: 0,
            patches = sorted,
        )
    }

    fun inspectRotation(channel: SeekableByteChannel): Int {
        val fileSize = channel.size()
        val topLevel = readChildren(channel, 0, fileSize)
        val moov = topLevel.firstOrNull { it.type == "moov" }
            ?: throw UnsupportedVideoException("缺少 moov")
        for (trak in readChildren(channel, moov.dataStart, moov.end).filter { it.type == "trak" }) {
            val children = readChildren(channel, trak.dataStart, trak.end)
            val mdia = children.firstOrNull { it.type == "mdia" } ?: continue
            val mdiaChildren = readChildren(channel, mdia.dataStart, mdia.end)
            val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: continue
            if (readAscii(channel, hdlr.dataStart + 8, 4) != "vide") continue
            val tkhd = children.firstOrNull { it.type == "tkhd" }
                ?: throw UnsupportedVideoException("视频轨道缺少 tkhd")
            val version = readByte(channel, tkhd.dataStart).toInt() and 0xff
            val offset = tkhd.dataStart + if (version == 1) 52 else 40
            return rotationFromMatrix(readMatrix(channel, offset))
                ?: throw UnsupportedVideoException("视频矩阵不是标准旋转")
        }
        throw UnsupportedVideoException("容器中没有视频轨道")
    }

    private fun readChildren(channel: SeekableByteChannel, start: Long, end: Long): List<Box> {
        val result = mutableListOf<Box>()
        var position = start
        while (position < end) {
            if (end - position < 8) {
                throw UnsupportedVideoException("atom 尾部不完整，偏移 $position")
            }
            val header = readBuffer(channel, position, 8)
            var size = header.int.toLong() and UINT32_MAX
            val typeBytes = ByteArray(4).also(header::get)
            val type = typeBytes.toString(Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                size = readBuffer(channel, position + 8, 8).long
                headerSize = 16
            } else if (size == 0L) {
                size = end - position
            }
            if (size < headerSize || size > end - position) {
                throw UnsupportedVideoException("atom $type 的大小越界，偏移 $position")
            }
            result += Box(type, position, size, headerSize)
            position += size
        }
        return result
    }

    private fun readMatrix(channel: SeekableByteChannel, offset: Long): IntArray {
        val buffer = readBuffer(channel, offset, MATRIX_BYTES)
        return IntArray(9) { buffer.int }
    }

    private fun rotationFromMatrix(matrix: IntArray): Int? {
        if (matrix.size != 9 || matrix[2] != 0 || matrix[5] != 0 || matrix[8] != FIXED_2_30_ONE) {
            return null
        }
        return when {
            matrix[0] == FIXED_ONE && matrix[1] == 0 && matrix[3] == 0 && matrix[4] == FIXED_ONE -> 0
            matrix[0] == 0 && matrix[1] == FIXED_ONE && matrix[3] == -FIXED_ONE && matrix[4] == 0 -> 90
            matrix[0] == -FIXED_ONE && matrix[1] == 0 && matrix[3] == 0 && matrix[4] == -FIXED_ONE -> 180
            matrix[0] == 0 && matrix[1] == -FIXED_ONE && matrix[3] == FIXED_ONE && matrix[4] == 0 -> 270
            else -> null
        }
    }

    private fun canonicalMatrix(degrees: Int): IntArray = when (degrees) {
        0 -> intArrayOf(FIXED_ONE, 0, 0, 0, FIXED_ONE, 0, 0, 0, FIXED_2_30_ONE)
        90 -> intArrayOf(0, FIXED_ONE, 0, -FIXED_ONE, 0, 0, 0, 0, FIXED_2_30_ONE)
        180 -> intArrayOf(-FIXED_ONE, 0, 0, 0, -FIXED_ONE, 0, 0, 0, FIXED_2_30_ONE)
        270 -> intArrayOf(0, -FIXED_ONE, 0, FIXED_ONE, 0, 0, 0, 0, FIXED_2_30_ONE)
        else -> error("Unsupported rotation: $degrees")
    }

    private fun matrixBytes(degrees: Int): ByteArray =
        ByteBuffer.allocate(MATRIX_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .also { buffer -> canonicalMatrix(degrees).forEach(buffer::putInt) }
            .array()

    private fun ensureRange(offset: Long, length: Long, box: Box) {
        if (offset < box.dataStart || offset + length > box.end) {
            throw UnsupportedVideoException("atom ${box.type} 内容不完整")
        }
    }

    private fun readByte(channel: SeekableByteChannel, offset: Long): Byte =
        readBuffer(channel, offset, 1).get()

    private fun readAscii(channel: SeekableByteChannel, offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        readBuffer(channel, offset, length).get(bytes)
        return bytes.toString(Charsets.ISO_8859_1)
    }

    private fun readBuffer(channel: SeekableByteChannel, offset: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("读取到文件尾，偏移 $offset")
        }
        buffer.flip()
        return buffer
    }

    private companion object {
        const val MATRIX_BYTES = 36
        const val FIXED_ONE = 0x00010000
        const val FIXED_2_30_ONE = 0x40000000
        const val UINT32_MAX = 0xffffffffL
    }
}
