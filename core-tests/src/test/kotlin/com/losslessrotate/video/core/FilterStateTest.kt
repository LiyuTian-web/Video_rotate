package com.losslessrotate.video.core

import com.losslessrotate.video.ui.FilterDraft
import com.losslessrotate.video.ui.SourceMode
import com.losslessrotate.video.ui.VideoFormatFilter
import com.losslessrotate.video.ui.VideoSortOrder
import com.losslessrotate.video.ui.filterSummary
import com.losslessrotate.video.ui.committedOrOriginal
import com.losslessrotate.video.ui.shouldClearSelection
import com.losslessrotate.video.ui.updateVisibleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterStateTest {
    private val base = FilterDraft(
        SourceMode.LIBRARY,
        VideoFormatFilter.ALL,
        VideoSortOrder.NEWEST,
        270,
        null,
        "尚未选择",
        null,
        "",
    )

    @Test
    fun `format or source changes clear selection but sorting does not`() {
        assertTrue(shouldClearSelection(base, base.copy(format = VideoFormatFilter.MOV)))
        assertTrue(shouldClearSelection(base, base.copy(mode = SourceMode.FOLDER)))
        assertFalse(shouldClearSelection(base, base.copy(sort = VideoSortOrder.NAME)))
    }

    @Test
    fun `summary describes committed filters and output`() {
        assertTrue(filterSummary(base.copy(format = VideoFormatFilter.MOV)).contains("视频库 · MOV · 最新 · 270° · 默认输出"))
        val folder = base.copy(
            mode = SourceMode.FOLDER,
            sourceDisplayName = "NIKON",
            customOutputTreeUri = "content://output",
            customOutputDisplayName = "成片",
        )
        assertTrue(filterSummary(folder).contains("文件夹：NIKON"))
        assertTrue(filterSummary(folder).contains("输出：成片"))
    }

    @Test
    fun `draft is committed only when completed`() {
        val draft = base.copy(angle = 90, format = VideoFormatFilter.MP4)
        assertEquals(draft, committedOrOriginal(base, draft, apply = true))
        assertEquals(base, committedOrOriginal(base, draft, apply = false))
    }

    @Test
    fun `select all changes only visible results`() {
        val original = setOf("hidden-selected")
        val visible = setOf("mov-a", "mov-b")
        val selected = updateVisibleSelection(original, visible, select = true)
        assertEquals(setOf("hidden-selected", "mov-a", "mov-b"), selected)
        assertEquals(setOf("hidden-selected"), updateVisibleSelection(selected, visible, select = false))
    }
}
