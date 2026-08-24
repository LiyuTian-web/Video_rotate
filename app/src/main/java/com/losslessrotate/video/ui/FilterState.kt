package com.losslessrotate.video.ui

enum class SourceMode { LIBRARY, FOLDER }
enum class VideoFormatFilter { ALL, MOV, MP4 }
enum class VideoSortOrder { NEWEST, OLDEST, NAME }

data class FilterDraft(
    val mode: SourceMode,
    val format: VideoFormatFilter,
    val sort: VideoSortOrder,
    val angle: Int,
    val sourceTreeUri: String?,
    val sourceDisplayName: String,
    val customOutputTreeUri: String?,
    val customOutputDisplayName: String,
)

fun shouldClearSelection(previous: FilterDraft, next: FilterDraft): Boolean =
    previous.mode != next.mode || previous.format != next.format

fun committedOrOriginal(committed: FilterDraft, draft: FilterDraft, apply: Boolean): FilterDraft =
    if (apply) draft else committed

fun <T> updateVisibleSelection(
    selectedKeys: Set<T>,
    visibleKeys: Set<T>,
    select: Boolean,
): Set<T> = if (select) selectedKeys + visibleKeys else selectedKeys - visibleKeys

fun filterSummary(config: FilterDraft): String {
    val source = when (config.mode) {
        SourceMode.LIBRARY -> "视频库"
        SourceMode.FOLDER -> "文件夹：${config.sourceDisplayName.ifBlank { "尚未选择" }}"
    }
    val format = when (config.format) {
        VideoFormatFilter.ALL -> "全部格式"
        VideoFormatFilter.MOV -> "MOV"
        VideoFormatFilter.MP4 -> "MP4"
    }
    val sort = when (config.sort) {
        VideoSortOrder.NEWEST -> "最新"
        VideoSortOrder.OLDEST -> "最早"
        VideoSortOrder.NAME -> "名称"
    }
    val output = if (config.customOutputTreeUri == null) "默认输出" else "输出：${config.customOutputDisplayName}"
    return "$source · $format · $sort · ${config.angle}° · $output"
}
