package com.losslessrotate.video.core

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.FileOutputStream

class AndroidRotationProcessor(
    private val contentResolver: ContentResolver,
    private val analyzer: IsoBmffAnalyzer = IsoBmffAnalyzer(),
    private val copier: PatchCopier = PatchCopier(),
) {
    fun analyze(source: Uri, angle: RotationAngle): ContainerAnalysis =
        contentResolver.openFileDescriptor(source, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                analyzer.analyze(channel, angle)
            }
        } ?: throw UnsupportedVideoException("无法打开源文件")

    fun rotate(
        source: Uri,
        destination: Uri,
        angle: RotationAngle,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): ContainerAnalysis {
        val analysis = analyze(source, angle)
        contentResolver.openInputStream(source)?.use { input ->
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                copier.copy(
                    input = input,
                    output = output,
                    fileSize = analysis.fileSize,
                    patches = analysis.patches,
                    isCancelled = isCancelled,
                    onProgress = { copied -> onProgress(copied, analysis.fileSize) },
                )
                if (output is FileOutputStream) output.fd.sync()
            } ?: throw UnsupportedVideoException("无法写入输出文件")
        } ?: throw UnsupportedVideoException("无法读取源文件")

        verify(destination, angle, analysis.fileSize)
        return analysis
    }

    fun verify(destination: Uri, angle: RotationAngle, expectedSize: Long) {
        contentResolver.openFileDescriptor(destination, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                if (channel.size() != expectedSize) {
                    throw UnsupportedVideoException("输出大小校验失败：${channel.size()}/$expectedSize")
                }
                val actual = analyzer.inspectRotation(channel)
                if (actual != angle.degrees) {
                    throw UnsupportedVideoException("输出方向校验失败：$actual°")
                }
            }
        } ?: throw UnsupportedVideoException("无法重新打开输出文件进行校验")
    }
}
