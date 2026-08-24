package com.losslessrotate.video

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.losslessrotate.video.job.OutputTarget
import com.losslessrotate.video.job.MediaStoreOutputPolicy
import com.losslessrotate.video.job.RotationInput
import com.losslessrotate.video.job.RotationJobSpec
import com.losslessrotate.video.job.RotationJobStore
import com.losslessrotate.video.media.MediaVideoItem
import com.losslessrotate.video.media.ThumbnailRepository
import com.losslessrotate.video.media.VideoLibraryRepository
import com.losslessrotate.video.media.VideoPermissionState
import com.losslessrotate.video.media.isSupportedVideo
import com.losslessrotate.video.service.RotationJobBus
import com.losslessrotate.video.service.RotationJobState
import com.losslessrotate.video.service.RotationService
import com.losslessrotate.video.ui.LosslessRotateTheme
import com.losslessrotate.video.ui.FilterDraft
import com.losslessrotate.video.ui.SourceMode
import com.losslessrotate.video.ui.VideoFormatFilter
import com.losslessrotate.video.ui.VideoSortOrder
import com.losslessrotate.video.ui.filterSummary
import com.losslessrotate.video.ui.shouldClearSelection
import com.losslessrotate.video.ui.updateVisibleSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen { HOME, ABOUT }

class MainActivity : ComponentActivity() {
    private lateinit var libraryRepository: VideoLibraryRepository
    private lateinit var thumbnailRepository: ThumbnailRepository
    private var sourceMode by mutableStateOf(SourceMode.LIBRARY)
    private var permissionState by mutableStateOf(VideoPermissionState.DENIED)
    private var libraryItems by mutableStateOf<List<MediaVideoItem>>(emptyList())
    private var folderItems by mutableStateOf<List<MediaVideoItem>>(emptyList())
    private var sourceTreeUri by mutableStateOf<Uri?>(null)
    private var sourceDisplayName by mutableStateOf("尚未选择")
    private var customOutputTreeUri by mutableStateOf<Uri?>(null)
    private var customOutputDisplayName by mutableStateOf("")
    private var isScanning by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var selectedAngle by mutableIntStateOf(270)
    private var formatFilter by mutableStateOf(VideoFormatFilter.ALL)
    private var sortOrder by mutableStateOf(VideoSortOrder.NEWEST)
    private var hiddenMotionPhotoCount by mutableIntStateOf(0)
    private var motionPhotoFilteringAvailable by mutableStateOf(false)
    private var appScreen by mutableStateOf(AppScreen.HOME)
    private var filterDraft by mutableStateOf<FilterDraft?>(null)
    private var aboutMessage by mutableStateOf<String?>(null)

    private val sourceFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            filterDraft = filterDraft?.copy(
                sourceTreeUri = uri.toString(),
                sourceDisplayName = DocumentFile.fromTreeUri(this, uri)?.name ?: "已选择目录",
            )
        }
    }

    private val outputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            filterDraft = filterDraft?.copy(
                customOutputTreeUri = uri.toString(),
                customOutputDisplayName = DocumentFile.fromTreeUri(this, uri)?.name ?: "已选择目录",
            )
        }
    }

    private val mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionState = libraryRepository.permissionState()
        motionPhotoFilteringAvailable = libraryRepository.hasImageAccess()
        if (permissionState == VideoPermissionState.DENIED) {
            sourceMode = SourceMode.FOLDER
            message = "未获得视频库权限，仍可使用文件夹模式"
        } else {
            sourceMode = SourceMode.LIBRARY
            refreshLibrary()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libraryRepository = VideoLibraryRepository(this)
        thumbnailRepository = ThumbnailRepository(contentResolver)
        permissionState = libraryRepository.permissionState()
        enableEdgeToEdge()
        setContent {
            LosslessRotateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val draft = filterDraft
                    BackHandler(enabled = draft != null || appScreen == AppScreen.ABOUT) {
                        if (draft != null) filterDraft = null else appScreen = AppScreen.HOME
                    }
                    if (appScreen == AppScreen.ABOUT) {
                        AboutScreen(
                            message = aboutMessage,
                            onBack = { appScreen = AppScreen.HOME },
                            onEmail = ::contactAuthor,
                        )
                    } else {
                        MainScreen(
                            mode = sourceMode,
                            permissionState = permissionState,
                            videos = displayedItems(),
                            isScanning = isScanning,
                            message = message,
                            hasCustomOutput = customOutputTreeUri != null,
                            summary = filterSummary(committedFilter()),
                            hiddenMotionPhotoCount = hiddenMotionPhotoCount,
                            motionPhotoFilteringAvailable = motionPhotoFilteringAvailable,
                            thumbnailRepository = thumbnailRepository,
                            onSettings = { appScreen = AppScreen.ABOUT },
                            onOpenFilter = { filterDraft = committedFilter() },
                            onRequestPermission = ::requestMediaPermission,
                            onRequestMotionPhotoAccess = ::requestMotionPhotoAccess,
                            onRefresh = ::refreshLibrary,
                            onToggleVideo = ::toggleVideo,
                            onSelectAll = ::selectAll,
                            onStart = ::startRotation,
                            onCancel = ::cancelRotation,
                            onDismissResult = { RotationJobBus.update(RotationJobState.Idle) },
                        )
                    }
                    if (draft != null && appScreen == AppScreen.HOME) {
                        FilterSheet(
                            draft = draft,
                            onDraftChanged = { filterDraft = it },
                            onPickSource = { sourceFolderLauncher.launch(draft.sourceTreeUri?.let(Uri::parse)) },
                            onPickOutput = {
                                val initial = draft.customOutputTreeUri ?: draft.sourceTreeUri
                                outputFolderLauncher.launch(initial?.let(Uri::parse))
                            },
                            onDismiss = { filterDraft = null },
                            onApply = ::applyFilter,
                        )
                    }
                }
            }
        }
        motionPhotoFilteringAvailable = libraryRepository.hasImageAccess()
        if (permissionState == VideoPermissionState.DENIED) requestMediaPermission() else refreshLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (::libraryRepository.isInitialized) {
            val current = libraryRepository.permissionState()
            motionPhotoFilteringAvailable = libraryRepository.hasImageAccess()
            if (current != permissionState) {
                permissionState = current
                if (current != VideoPermissionState.DENIED) refreshLibrary()
            }
        }
    }

    private fun committedFilter() = FilterDraft(
        mode = sourceMode,
        format = formatFilter,
        sort = sortOrder,
        angle = selectedAngle,
        sourceTreeUri = sourceTreeUri?.toString(),
        sourceDisplayName = sourceDisplayName,
        customOutputTreeUri = customOutputTreeUri?.toString(),
        customOutputDisplayName = customOutputDisplayName,
    )

    private fun applyFilter(next: FilterDraft) {
        val previous = committedFilter()
        val clearSelection = shouldClearSelection(previous, next)
        val sourceChanged = previous.mode != next.mode || previous.sourceTreeUri != next.sourceTreeUri

        sourceMode = next.mode
        formatFilter = next.format
        sortOrder = next.sort
        selectedAngle = next.angle
        sourceTreeUri = next.sourceTreeUri?.let(Uri::parse)
        sourceDisplayName = next.sourceDisplayName
        customOutputTreeUri = next.customOutputTreeUri?.let(Uri::parse)
        customOutputDisplayName = next.customOutputDisplayName
        filterDraft = null
        message = null

        if (clearSelection) {
            libraryItems = libraryItems.map { it.copy(selected = false) }
            folderItems = folderItems.map { it.copy(selected = false) }
        }
        when (next.mode) {
            SourceMode.LIBRARY -> if (permissionState != VideoPermissionState.DENIED) refreshLibrary()
            SourceMode.FOLDER -> sourceTreeUri?.let { uri ->
                if (sourceChanged || folderItems.isEmpty()) scanSourceFolder(uri)
            }
        }
    }

    private fun requestMediaPermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            Build.VERSION.SDK_INT <= 28 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermissionLauncher.launch(permissions)
    }

    private fun requestMotionPhotoAccess() {
        if (Build.VERSION.SDK_INT < 33) return
        val permissions = if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }
        mediaPermissionLauncher.launch(permissions)
    }

    private fun refreshLibrary() {
        if (libraryRepository.permissionState() == VideoPermissionState.DENIED) return
        isScanning = true
        message = null
        lifecycleScope.launch {
            runCatching { libraryRepository.queryVideos() }
                .onSuccess { result ->
                    val items = result.videos
                    val selectedUris = libraryItems.filter { it.selected }.map { it.uri }.toSet()
                    libraryItems = items.map { it.copy(selected = it.uri in selectedUris) }
                    hiddenMotionPhotoCount = result.hiddenMotionPhotoCount
                    motionPhotoFilteringAvailable = result.motionPhotoFilteringAvailable
                    if (items.isEmpty()) message = "当前授权范围内没有 MOV 或 MP4 视频"
                }
                .onFailure { message = it.message ?: "读取视频库失败" }
            isScanning = false
        }
    }

    private fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { message = "目录授权无法长期保存：${it.message}" }
    }

    private fun scanSourceFolder(uri: Uri) {
        isScanning = true
        message = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = DocumentFile.fromTreeUri(this@MainActivity, uri)
                        ?.takeIf { it.isDirectory } ?: error("无法打开所选目录")
                    val items = directory.listFiles().asSequence().filter { it.isFile }.mapNotNull { file ->
                        val name = file.name ?: return@mapNotNull null
                        val mime = if (name.endsWith(".mp4", true)) "video/mp4" else "video/quicktime"
                        if (!isSupportedVideo(name, mime)) return@mapNotNull null
                        MediaVideoItem(
                            uri = file.uri,
                            name = name,
                            size = file.length(),
                            durationMs = 0,
                            modifiedSeconds = file.lastModified() / 1000,
                            relativePath = null,
                            volumeName = null,
                            mimeType = mime,
                            selected = true,
                            sourceTreeUri = uri,
                        )
                    }.sortedWith(compareByDescending<MediaVideoItem> { it.modifiedSeconds }.thenBy { it.name.lowercase() }).toList()
                    directory.name.orEmpty() to items
                }
            }
            result.onSuccess { (name, items) ->
                sourceDisplayName = name.ifBlank { "已选择目录" }
                folderItems = items
                if (items.isEmpty()) message = "当前目录没有 MOV 或 MP4 文件"
            }.onFailure {
                sourceDisplayName = "无法读取"
                message = it.message ?: "目录扫描失败"
            }
            isScanning = false
        }
    }

    private fun toggleVideo(uri: Uri) {
        if (sourceMode == SourceMode.LIBRARY) {
            libraryItems = libraryItems.map { if (it.uri == uri) it.copy(selected = !it.selected) else it }
        } else {
            folderItems = folderItems.map { if (it.uri == uri) it.copy(selected = !it.selected) else it }
        }
    }

    private fun selectAll(selected: Boolean) {
        val visibleUris = displayedItems().map { it.uri }.toSet()
        val currentSelection = (if (sourceMode == SourceMode.LIBRARY) libraryItems else folderItems)
            .filter { it.selected }.map { it.uri }.toSet()
        val nextSelection = updateVisibleSelection(currentSelection, visibleUris, selected)
        if (sourceMode == SourceMode.LIBRARY) {
            libraryItems = libraryItems.map { item ->
                item.copy(selected = item.uri in nextSelection)
            }
        } else {
            folderItems = folderItems.map { item ->
                item.copy(selected = item.uri in nextSelection)
            }
        }
    }

    private fun displayedItems(): List<MediaVideoItem> {
        val source = if (sourceMode == SourceMode.LIBRARY) libraryItems else folderItems
        val filtered = source.filter { item ->
            when (formatFilter) {
                VideoFormatFilter.ALL -> true
                VideoFormatFilter.MOV -> item.name.endsWith(".mov", true)
                VideoFormatFilter.MP4 -> item.name.endsWith(".mp4", true)
            }
        }
        return when (sortOrder) {
            VideoSortOrder.NEWEST -> filtered.sortedWith(
                compareByDescending<MediaVideoItem> { it.modifiedSeconds }.thenByDescending { it.uri.toString() },
            )
            VideoSortOrder.OLDEST -> filtered.sortedWith(
                compareBy<MediaVideoItem> { it.modifiedSeconds }.thenBy { it.uri.toString() },
            )
            VideoSortOrder.NAME -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }

    private fun startRotation() {
        val selected = displayedItems().filter { it.selected }
        if (selected.isEmpty()) {
            message = "请至少选择一个视频"
            return
        }
        if (RotationJobBus.state.value is RotationJobState.Running) {
            message = "已有任务正在运行"
            return
        }
        val spec = RotationJobSpec(
            inputs = selected.map {
                RotationInput(
                    sourceUri = it.uri.toString(),
                    displayName = it.name,
                    mimeType = it.mimeType,
                    size = it.size,
                    relativePath = it.relativePath,
                    volumeName = it.volumeName,
                    sourceTreeUri = it.sourceTreeUri?.toString(),
                    legacyDataPath = it.legacyDataPath,
                )
            },
            angleDegrees = selectedAngle,
            outputTarget = customOutputTreeUri?.let { OutputTarget.SafDirectory(it.toString()) }
                ?: OutputTarget.AdjacentRotateFolders,
        )
        val jobId = runCatching { RotationJobStore(this).write(spec) }.getOrElse {
            message = "无法创建任务：${it.message}"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        RotationJobBus.update(RotationJobState.Running(1, selected.size, "正在准备…", 0, 0, 0, 0))
        ContextCompat.startForegroundService(
            this,
            Intent(this, RotationService::class.java).apply {
                action = RotationService.ACTION_START
                putExtra(RotationService.EXTRA_JOB_ID, jobId)
            },
        )
    }

    private fun cancelRotation() {
        startService(Intent(this, RotationService::class.java).setAction(RotationService.ACTION_CANCEL))
    }

    private fun contactAuthor() {
        aboutMessage = null
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:woshitianyumi@outlook.com"))
        runCatching { startActivity(intent) }
            .onFailure { aboutMessage = "未找到可用的邮件应用，请复制邮箱地址联系作者" }
    }
}

@Composable
private fun MainScreen(
    mode: SourceMode,
    permissionState: VideoPermissionState,
    videos: List<MediaVideoItem>,
    isScanning: Boolean,
    message: String?,
    hasCustomOutput: Boolean,
    summary: String,
    hiddenMotionPhotoCount: Int,
    motionPhotoFilteringAvailable: Boolean,
    thumbnailRepository: ThumbnailRepository,
    onSettings: () -> Unit,
    onOpenFilter: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestMotionPhotoAccess: () -> Unit,
    onRefresh: () -> Unit,
    onToggleVideo: (Uri) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val jobState by RotationJobBus.state.collectAsState()
    val selectedCount = videos.count { it.selected }
    val running = jobState is RotationJobState.Running
    val redirectedCount = if (
        Build.VERSION.SDK_INT >= 29 && mode == SourceMode.LIBRARY && !hasCustomOutput
    ) videos.count { it.selected && MediaStoreOutputPolicy.requiresRedirect(it.relativePath) } else 0

    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val compactHeight = maxHeight < 500.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = if (compactHeight) 4.dp else 10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("无损视频旋转", style = if (compactHeight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (!compactHeight) Text("只修改播放方向，不重新编码视频", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "关于本软件")
                }
            }

            OutlinedButton(
                onClick = onOpenFilter,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(if (compactHeight) 42.dp else 48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("筛选", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("展开")
            }

            if (mode == SourceMode.LIBRARY && permissionState == VideoPermissionState.DENIED) {
                PermissionCard(onRequestPermission)
            } else {
                if (mode == SourceMode.LIBRARY && !motionPhotoFilteringAvailable) {
                    MotionPhotoPermissionCard(onRequestMotionPhotoAccess)
                }
                if (mode == SourceMode.LIBRARY && permissionState == VideoPermissionState.PARTIAL) {
                    PartialAccessCard(onRequestPermission)
                }

                if (redirectedCount > 0) RestrictedOutputCard(redirectedCount)

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("视频  $selectedCount/${videos.size}", fontWeight = FontWeight.SemiBold)
                        if (hiddenMotionPhotoCount > 0) {
                            Text("已隐藏动态照片 $hiddenMotionPhotoCount 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (mode == SourceMode.LIBRARY) OutlinedButton(onClick = onRefresh, enabled = !running) { Text("刷新") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { onSelectAll(true) }, enabled = videos.isNotEmpty() && !running) { Text("全选") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { onSelectAll(false) }, enabled = videos.isNotEmpty() && !running) { Text("清空") }
                }
            }

            if (isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = if (compactHeight) 2.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(videos, key = { it.uri.toString() }) { video ->
                    VideoTile(video, !running, thumbnailRepository, compactHeight) { onToggleVideo(video.uri) }
                }
            }

            JobPanel(jobState, onCancel, onDismissResult)
            Spacer(Modifier.height(if (compactHeight) 2.dp else 6.dp))
            Button(
                onClick = onStart,
                enabled = selectedCount > 0 && !running,
                modifier = Modifier.fillMaxWidth().height(if (compactHeight) 44.dp else 50.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (running) "正在处理" else "开始无损旋转") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    draft: FilterDraft,
    onDraftChanged: (FilterDraft) -> Unit,
    onPickSource: () -> Unit,
    onPickOutput: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (FilterDraft) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val folderReady = draft.mode != SourceMode.FOLDER || draft.sourceTreeUri != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                Text("筛选与旋转设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                FilterSection("来源") {
                    ChoiceRow {
                        FilterChip(draft.mode == SourceMode.LIBRARY, { onDraftChanged(draft.copy(mode = SourceMode.LIBRARY)) }, { Text("视频库") })
                        FilterChip(draft.mode == SourceMode.FOLDER, { onDraftChanged(draft.copy(mode = SourceMode.FOLDER)) }, { Text("文件夹") })
                    }
                    if (draft.mode == SourceMode.FOLDER) {
                        CompactDirectoryRow("源文件夹", draft.sourceDisplayName, "选择源文件夹", true, onPickSource)
                        if (!folderReady) Text("请先选择有效的源文件夹", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                FilterSection("格式") {
                    ChoiceRow {
                        FilterChip(draft.format == VideoFormatFilter.ALL, { onDraftChanged(draft.copy(format = VideoFormatFilter.ALL)) }, { Text("全部") })
                        FilterChip(draft.format == VideoFormatFilter.MOV, { onDraftChanged(draft.copy(format = VideoFormatFilter.MOV)) }, { Text("MOV") })
                        FilterChip(draft.format == VideoFormatFilter.MP4, { onDraftChanged(draft.copy(format = VideoFormatFilter.MP4)) }, { Text("MP4") })
                    }
                }
                FilterSection("排序") {
                    ChoiceRow {
                        FilterChip(draft.sort == VideoSortOrder.NEWEST, { onDraftChanged(draft.copy(sort = VideoSortOrder.NEWEST)) }, { Text("最新") })
                        FilterChip(draft.sort == VideoSortOrder.OLDEST, { onDraftChanged(draft.copy(sort = VideoSortOrder.OLDEST)) }, { Text("最早") })
                        FilterChip(draft.sort == VideoSortOrder.NAME, { onDraftChanged(draft.copy(sort = VideoSortOrder.NAME)) }, { Text("名称") })
                    }
                }
                FilterSection("输出位置") {
                    val outputText = if (draft.customOutputTreeUri != null) {
                        draft.customOutputDisplayName.ifBlank { "已选择自定义目录" }
                    } else if (draft.mode == SourceMode.LIBRARY) {
                        "默认：每个原目录内的 rotate 文件夹"
                    } else {
                        "默认：${draft.sourceDisplayName}/rotate"
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(outputText, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (draft.customOutputTreeUri != null) {
                            OutlinedButton(onClick = { onDraftChanged(draft.copy(customOutputTreeUri = null, customOutputDisplayName = "")) }) { Text("默认") }
                            Spacer(Modifier.width(6.dp))
                        }
                        OutlinedButton(onClick = onPickOutput) { Text("更改") }
                    }
                }
                FilterSection("旋转角度") {
                    ChoiceRow {
                        listOf(90, 180, 270).forEach { angle ->
                            FilterChip(draft.angle == angle, { onDraftChanged(draft.copy(angle = angle)) }, { Text("$angle°") })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onApply(draft) }, enabled = folderReady, modifier = Modifier.weight(1f)) { Text("完成") }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun AboutScreen(message: String?, onBack: () -> Unit, onEmail: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "返回") }
            Text("关于本软件", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(76.dp))
            Spacer(Modifier.height(12.dp))
            Text("无损视频旋转", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("主打无损视频旋转，拯救NIKON用户", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            Text("V ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            InfoRow("软件作者", "顶天立宇")
            Row(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("联系作者", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp))
                Text("woshitianyumi@outlook.com", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onEmail))
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(16.dp))
            Text("赞赏作者", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("请作者喝一杯奶茶吧！", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            DonationCard("支付宝", R.drawable.donate_alipay, 1080f / 1620f)
            Spacer(Modifier.height(14.dp))
            DonationCard("微信支付", R.drawable.donate_wechat, 1304f / 2048f)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(vertical = 8.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp))
        Text(value)
    }
}

@Composable
private fun DonationCard(title: String, drawableId: Int, ratio: Float) {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Image(
                painter = painterResource(drawableId),
                contentDescription = "${title}赞赏码",
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun MotionPhotoPermissionCard(onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("排除动态照片", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("需要读取照片名称，仅用于匹配同名的短视频片段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRequest) { Text("授权") }
        }
    }
}

@Composable
private fun RestrictedOutputCard(count: Int) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("其中 $count 个视频位于 Android 限制写入的目录", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(
                "将自动保存到 Movies/无损视频旋转/原文件夹/rotate；若要保存到其他位置，请点上方“更改”并授权目录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceModeChips(mode: SourceMode, running: Boolean, onModeChanged: (SourceMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == SourceMode.LIBRARY, onClick = { onModeChanged(SourceMode.LIBRARY) }, label = { Text("视频库") }, enabled = !running)
        FilterChip(selected = mode == SourceMode.FOLDER, onClick = { onModeChanged(SourceMode.FOLDER) }, label = { Text("文件夹") }, enabled = !running)
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("允许读取视频后，可按最新顺序显示缩略图", style = MaterialTheme.typography.bodyMedium)
            Text("拒绝也不影响文件夹模式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRequestPermission, modifier = Modifier.align(Alignment.End)) { Text("选择可访问的视频") }
        }
    }
}

@Composable
private fun PartialAccessCard(onManage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前只显示已授权的视频", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onManage) { Text("管理范围") }
        }
    }
}

@Composable
private fun CompactDirectoryRow(title: String, value: String, button: String, enabled: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
    }
}

@Composable
private fun VideoTile(video: MediaVideoItem, enabled: Boolean, repository: ThumbnailRepository, compact: Boolean, onToggle: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, video.uri) { value = repository.load(video) }
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle)) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 1.7f else 1.35f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (bitmap != null) {
                    Image(bitmap!!.asImageBitmap(), video.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("▶", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(
                        if (video.selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f), CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text(if (video.selected) "✓" else "", color = Color.White, fontWeight = FontWeight.Bold) }
                if (video.durationMs > 0) {
                    Text(
                        formatDuration(video.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(video.name, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = if (compact) 3.dp else 5.dp))
            if (!compact && video.modifiedSeconds > 0) {
                Text(formatDate(video.modifiedSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 7.dp).padding(bottom = 5.dp))
            }
        }
    }
}

@Composable
private fun JobPanel(state: RotationJobState, onCancel: () -> Unit, onDismissResult: () -> Unit) {
    when (state) {
        RotationJobState.Idle -> Unit
        is RotationJobState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("${state.currentIndex}/${state.totalFiles}  ${state.fileName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = if (state.fileBytesTotal > 0) state.fileBytesDone.toFloat() / state.fileBytesTotal else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("成功 ${state.completedFiles} · 失败 ${state.failedFiles}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
        is RotationJobState.Finished -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (state.cancelled) "任务已取消" else "处理完成", fontWeight = FontWeight.SemiBold)
                    Text("成功 ${state.completedFiles}，失败 ${state.failedFiles}", style = MaterialTheme.typography.bodySmall)
                    state.messages.take(2).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
                OutlinedButton(onClick = onDismissResult) { Text("关闭") }
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%d:%02d".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
}

private fun formatDate(seconds: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(seconds * 1000))
