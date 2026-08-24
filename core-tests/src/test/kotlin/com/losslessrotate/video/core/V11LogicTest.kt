package com.losslessrotate.video.core

import com.losslessrotate.video.job.OutputNaming
import com.losslessrotate.video.job.MediaStoreOutputPolicy
import com.losslessrotate.video.media.compareNewestFirst
import com.losslessrotate.video.media.isSupportedVideo
import com.losslessrotate.video.media.isMotionPhotoVideoCompanion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V11LogicTest {
    @Test
    fun `MOV and MP4 filter accepts extensions and media MIME types`() {
        assertTrue(isSupportedVideo("NIKON_0001.MOV", null))
        assertTrue(isSupportedVideo("clip.mp4", "application/octet-stream"))
        assertTrue(isSupportedVideo("camera-file", "video/quicktime"))
        assertFalse(isSupportedVideo("photo.jpg", "image/jpeg"))
    }

    @Test
    fun `newest timestamp sorts first and id breaks ties`() {
        assertTrue(compareNewestFirst(200, 1, 100, 9) < 0)
        assertTrue(compareNewestFirst(100, 9, 100, 2) < 0)
        assertEquals(0, compareNewestFirst(100, 2, 100, 2))
    }

    @Test
    fun `output name preserves extension and resolves collisions`() {
        assertEquals("DSC_4386_rot270.MOV", OutputNaming.rotatedName("DSC_4386.MOV", 270))
        val existing = setOf("DSC_4386_rot90.MOV", "DSC_4386_rot90_2.MOV")
        assertEquals(
            "DSC_4386_rot90_3.MOV",
            OutputNaming.uniqueName("DSC_4386.MOV", 90, existing::contains),
        )
    }

    @Test
    fun `allowed media roots keep adjacent rotate folder`() {
        val output = MediaStoreOutputPolicy.resolve("DCIM/Nikon/")
        assertFalse(output.redirected)
        assertEquals("DCIM/Nikon/rotate/", output.relativePath)
    }

    @Test
    fun `restricted top level folder redirects under Movies`() {
        val output = MediaStoreOutputPolicy.resolve("临时文件夹/当天素材/")
        assertTrue(output.redirected)
        assertEquals("Movies/无损视频旋转/临时文件夹/当天素材/rotate/", output.relativePath)
    }

    @Test
    fun `download folder also uses video safe fallback`() {
        assertEquals(
            "Movies/无损视频旋转/Download/Nikon/rotate/",
            MediaStoreOutputPolicy.resolve("Download/Nikon/").relativePath,
        )
    }

    @Test
    fun `short same-name image pair is treated as motion photo companion`() {
        assertTrue(isMotionPhotoVideoCompanion(3_000, 1_000, listOf(998)))
        assertTrue(isMotionPhotoVideoCompanion(0, 1_000, listOf(998)))
        assertFalse(isMotionPhotoVideoCompanion(3_000, 1_000, null))
        assertFalse(isMotionPhotoVideoCompanion(30_000, 1_000, listOf(998)))
    }
}
