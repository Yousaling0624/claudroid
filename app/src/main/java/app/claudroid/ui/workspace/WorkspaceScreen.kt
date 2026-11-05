package app.claudroid.ui.workspace

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import app.claudroid.AppContainer
import app.claudroid.core.Paths
import app.claudroid.core.WorkspaceEntry
import app.claudroid.core.WorkspaceFiles
import app.claudroid.core.FileKind
import app.claudroid.core.TextPreview
import app.claudroid.data.AppSettings
import app.claudroid.ui.components.ClaudeDialog
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.ClaudeTopBar
import app.claudroid.ui.components.GhostButton
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.PrimaryButton
import app.claudroid.ui.md.MarkdownText
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.Spacing
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A sub-page in the workspace stack. Terminal and preview slide in like file previews. */
private sealed interface WorkspacePage {
    data object Browser : WorkspacePage
    data class Preview(val path: String) : WorkspacePage
    data object Terminal : WorkspacePage
}

/**
 * Read-only browser for the jail's /work directory. The agent writes files there, so
 * this is the fastest way to look at a generated report, image, or script without
 * pulling the phone out of its cable. A terminal sub-page runs one-off shell commands
 * with the engine's frozen shell launch plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val root = remember(context) { Paths.workspace(context.applicationContext) }

    val settings by container.settings.flow.collectAsState(initial = AppSettings())
    val engineState by container.engine.state.collectAsState()
    val plan = remember(settings, engineState) { container.engine.shellPlan(settings) }
    val issue = remember(settings, engineState) { container.engine.startIssue(settings) }

    var currentPath by rememberSaveable { mutableStateOf(root.absolutePath) }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var terminalOpen by rememberSaveable { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val currentDir = remember(currentPath, root) {
        File(currentPath).takeIf { it.isDirectory && WorkspaceFiles.isInside(root, it) } ?: root
    }
    val previewFile = previewPath?.let { File(it) }
        ?.takeIf { it.isFile && WorkspaceFiles.isInside(root, it) }

    val page: WorkspacePage = when {
        terminalOpen -> WorkspacePage.Terminal
        previewFile != null -> WorkspacePage.Preview(previewFile.absolutePath)
        else -> WorkspacePage.Browser
    }

    BackHandler {
        when {
            terminalOpen -> terminalOpen = false
            previewFile != null -> previewPath = null
            currentDir != root -> currentPath = currentDir.parentFile?.absolutePath ?: root.absolutePath
            else -> onBack()
        }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState !is WorkspacePage.Browser) {
                (slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseOut)) { it / 5 } + fadeIn(tween(Motion.FAST)))
                    .togetherWith(fadeOut(tween(Motion.FAST)))
            } else {
                (fadeIn(tween(Motion.FAST)))
                    .togetherWith(slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseOut)) { it / 5 } + fadeOut(tween(Motion.FAST)))
            }
        },
        label = "workspace-page",
    ) { target ->
        when (target) {
            is WorkspacePage.Preview -> {
                val file = File(target.path).takeIf {
                    it.isFile && WorkspaceFiles.isInside(root, it)
                }
                if (file != null) {
                    FilePreview(
                        root = root,
                        file = file,
                        onBack = { previewPath = null },
                        onDeleted = {
                            previewPath = null
                            refreshKey += 1
                        },
                    )
                } else {
                    Browser(
                        root = root,
                        currentDir = currentDir,
                        refreshKey = refreshKey,
                        onRefresh = { refreshKey += 1 },
                        onEnter = { dir -> currentPath = dir.absolutePath },
                        onUp = { currentPath = currentDir.parentFile?.absolutePath ?: root.absolutePath },
                        onRoot = { currentPath = root.absolutePath },
                        onOpen = { entry -> previewPath = entry.file.absolutePath },
                        onOpenTerminal = { terminalOpen = true },
                        onBack = onBack,
                    )
                }
            }
            WorkspacePage.Terminal -> WorkspaceTerminalScreen(
                container = container,
                plan = plan,
                issue = issue,
                onBack = { terminalOpen = false },
            )
            WorkspacePage.Browser -> Browser(
                root = root,
                currentDir = currentDir,
                refreshKey = refreshKey,
                onRefresh = { refreshKey += 1 },
                onEnter = { dir -> currentPath = dir.absolutePath },
                onUp = { currentPath = currentDir.parentFile?.absolutePath ?: root.absolutePath },
                onRoot = { currentPath = root.absolutePath },
                onOpen = { entry -> previewPath = entry.file.absolutePath },
                onOpenTerminal = { terminalOpen = true },
                onBack = onBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Browser(
    root: File,
    currentDir: File,
    refreshKey: Int,
    onRefresh: () -> Unit,
    onEnter: (File) -> Unit,
    onUp: () -> Unit,
    onRoot: () -> Unit,
    onOpen: (WorkspaceEntry) -> Unit,
    onOpenTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var browserMenu by remember { mutableStateOf(false) }
    LaunchedEffect(currentDir, refreshKey) {
        loading = true
        entries = withContext(Dispatchers.IO) { WorkspaceFiles.list(currentDir) }
        loading = false
    }
    val totalBytes = remember(entries) { entries.filterNot { it.isDirectory }.sumOf { it.size } }
    val relative = remember(root, currentDir) {
        WorkspaceFiles.relativePath(root, currentDir).ifBlank { "" }
    }
    val path = if (relative.isEmpty()) "/work" else "/work/$relative"

    Column(Modifier.fillMaxSize()) {
        ClaudeTopBar(
            title = if (currentDir == root) "工作区" else currentDir.name,
            subtitle = if (loading) "读取中…" else "$path · ${entries.size} 项 · ${WorkspaceFiles.humanSize(totalBytes)}",
            onBack = { if (currentDir == root) onBack() else onUp() },
            actions = {
                IconButton(onClick = onOpenTerminal, modifier = Modifier.size(44.dp)) {
                    Icon(
                        AppIcons.Terminal,
                        "终端",
                        tint = MaterialTheme.appColors.graphite,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
                    Icon(
                        AppIcons.Refresh,
                        "刷新",
                        tint = MaterialTheme.appColors.graphite,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Box {
                    IconButton(onClick = { browserMenu = true }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            AppIcons.More,
                            "更多",
                            tint = MaterialTheme.appColors.graphite,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = browserMenu, onDismissRequest = { browserMenu = false }) {
                        if (currentDir != root) {
                            DropdownMenuItem(
                                text = { Text("回到 /work") },
                                leadingIcon = { Icon(AppIcons.Folder, null) },
                                onClick = {
                                    browserMenu = false
                                    onRoot()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("刷新列表") },
                            leadingIcon = { Icon(AppIcons.Refresh, null) },
                            onClick = {
                                browserMenu = false
                                onRefresh()
                            },
                        )
                    }
                }
            },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.appColors.orange,
                )
                entries.isEmpty() -> EmptyWorkspace(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.page - 4.dp,
                        end = Spacing.page - 4.dp,
                        top = 6.dp,
                        bottom = 30.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.file.absolutePath }) { entry ->
                        EntryCard(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) onEnter(entry.file) else onOpen(entry)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: WorkspaceEntry, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Radii.xsShape
    val background by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.appColors.surface1
        else MaterialTheme.appColors.paper,
        animationSpec = tween(Motion.FAST),
        label = "entry-press",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.appColors.sand, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIconBlock(entry)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (entry.isDirectory) "文件夹"
                else "${WorkspaceFiles.humanSize(entry.size)} · ${fileTime(entry.modifiedAt)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.appColors.stone,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.appColors.bone,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun FileIconBlock(entry: WorkspaceEntry) {
    val colors = MaterialTheme.appColors
    val icon = when {
        entry.isDirectory -> AppIcons.Folder
        entry.kind == FileKind.IMAGE -> AppIcons.Image
        entry.kind == FileKind.MARKDOWN || entry.kind == FileKind.HTML -> AppIcons.TextFile
        isCodeName(entry.name) -> AppIcons.Code
        entry.kind == FileKind.TEXT -> AppIcons.TextFile
        else -> AppIcons.File
    }
    val tint = when {
        entry.isDirectory -> colors.orange
        entry.kind == FileKind.IMAGE -> colors.graphite
        isCodeName(entry.name) -> colors.accentInk
        entry.kind == FileKind.TEXT -> colors.graphite
        else -> colors.stone
    }
    val fill = when {
        entry.isDirectory -> colors.orangeSoft
        entry.kind == FileKind.IMAGE -> colors.surface2
        else -> colors.surface1
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(Radii.xsShape)
            .background(fill)
            .border(1.dp, colors.sand.copy(alpha = 0.55f), Radii.xsShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

private val CODE_EXTENSIONS = setOf(
    "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
    "js", "mjs", "ts", "jsx", "tsx", "sh", "bash", "zsh", "sql",
    "json", "jsonl", "yaml", "yml", "toml", "xml", "css", "scss", "gradle",
)

private fun isCodeName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in CODE_EXTENSIONS
}

@Composable
private fun EmptyWorkspace(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClaudeMark(size = 34.dp, color = MaterialTheme.appColors.orange.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))
        Text(
            "工作区还是空的",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.appColors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Claude 在 /work 里生成的文件会出现在这里。也可以从对话输入框的 + 导入手机文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.stone,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePreview(
    root: File,
    file: File,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val kind = remember(file) { WorkspaceFiles.kindOf(file) }
    var reload by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    // HTML artifacts are usually meant to be seen, not read; default to rendered and
    // keep the source one tap away.
    var sourceView by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var textPreview by remember { mutableStateOf<TextPreview?>(null) }
    var loading by remember { mutableStateOf(kind != FileKind.IMAGE) }
    val relative = remember(root, file) { WorkspaceFiles.relativePath(root, file) }
    val entry = remember(file, kind) {
        WorkspaceEntry(file, file.name, kind, file.length(), file.lastModified())
    }

    LaunchedEffect(file.absolutePath, reload) {
        loading = kind != FileKind.IMAGE
        textPreview = if (kind == FileKind.IMAGE) null
        else withContext(Dispatchers.IO) { WorkspaceFiles.readPreview(file) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ClaudeTopBar(
            title = file.name,
            subtitle = "/work/$relative · ${WorkspaceFiles.humanSize(file.length())}",
            onBack = onBack,
            actions = {
                if (kind == FileKind.HTML) {
                    Box(
                        Modifier
                            .clip(Radii.xsShape)
                            .clickable { sourceView = !sourceView }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (sourceView) "预览" else "源码",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.appColors.accentInk,
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            AppIcons.More,
                            "更多",
                            tint = MaterialTheme.appColors.graphite,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("分享文件") },
                            leadingIcon = { Icon(AppIcons.Share, null) },
                            onClick = {
                                menuOpen = false
                                shareFile(context, entry)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("用其他应用打开") },
                            onClick = {
                                menuOpen = false
                                openFile(context, entry)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重新加载") },
                            leadingIcon = { Icon(AppIcons.Refresh, null) },
                            onClick = {
                                menuOpen = false
                                reload += 1
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("删除文件", color = MaterialTheme.appColors.error)
                            },
                            leadingIcon = {
                                Icon(
                                    AppIcons.Trash,
                                    null,
                                    tint = MaterialTheme.appColors.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.appColors.orange,
                )
                kind == FileKind.IMAGE -> ImagePreview(
                    file = file,
                    entry = entry,
                    onOpen = { openFile(context, entry) },
                    onShare = { shareFile(context, entry) },
                )
                kind == FileKind.HTML && !sourceView && textPreview != null ->
                    HtmlPreview(textPreview!!.text, reload)
                textPreview != null -> TextContent(kind, textPreview!!, file.name)
                else -> BinaryContent(
                    file = entry,
                    onOpen = { openFile(context, entry) },
                    onShare = { shareFile(context, entry) },
                )
            }
        }
    }

    if (confirmDelete) {
        ClaudeDialog(
            title = "删除文件",
            message = "将永久删除「${file.name}」。此操作无法撤销。",
            onDismiss = { confirmDelete = false },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(
                    "取消",
                    { confirmDelete = false },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    "删除",
                    {
                        confirmDelete = false
                        val deleted = runCatching { file.delete() }.getOrDefault(false)
                        if (deleted) {
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                            onDeleted()
                        } else {
                            Toast.makeText(context, "删除失败，文件可能仍被占用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun TextContent(kind: FileKind, preview: TextPreview, name: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        AnimatedVisibility(
            visible = preview.truncated,
            enter = expandVertically(tween(Motion.MEDIUM)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(Radii.xsShape)
                    .background(MaterialTheme.appColors.surface1)
                    .border(1.dp, MaterialTheme.appColors.sand, Radii.xsShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    "文件较大，仅预览前 ${WorkspaceFiles.humanSize(WorkspaceFiles.PREVIEW_LIMIT.toLong())}；" +
                        "可分享或导出后查看完整内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.graphite,
                )
            }
        }
        if (kind == FileKind.MARKDOWN) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    MarkdownText(preview.text, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(30.dp))
            }
        } else {
            val extension = if (kind == FileKind.HTML) "HTML"
            else name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(8).ifBlank { "TEXT" }
            CodePreview(
                text = preview.text.ifBlank { "（空文件）" },
                label = extension,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** Dark mono surface for source/log previews, with horizontal scroll and copy. */
@Composable
private fun CodePreview(text: String, label: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    Column(
        modifier
            .clip(Radii.xsShape)
            .background(MaterialTheme.appColors.codeBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.6.sp,
                ),
                color = MaterialTheme.appColors.codeFg.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (copied) AppIcons.Check else AppIcons.Copy,
                    contentDescription = "复制",
                    tint = MaterialTheme.appColors.codeFg.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.appColors.codeFg.copy(alpha = 0.08f))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    ),
                    color = MaterialTheme.appColors.codeFg,
                )
            }
        }
    }
}

/**
 * Renders an HTML artifact the way a browser would. The document is loaded with a null
 * base URL so scripts cannot reach files under the app's private data directory; if an
 * artifact needs sibling assets, 「用其他应用打开」 hands it to a real browser instead.
 */
@Composable
private fun HtmlPreview(html: String, reload: Int) {
    val context = LocalContext.current
    // The first WebView in a process is slow to paint; without this the user stares at
    // an empty page and cannot tell loading from a blank artifact.
    var ready by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready = true
                }
            }
        }
    }
    LaunchedEffect(html, reload) {
        ready = false
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(
            visible = !ready,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.MEDIUM)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.appColors.orange,
            )
        }
    }
}

@Composable
private fun BinaryContent(
    file: WorkspaceEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(Radii.smShape)
                .background(MaterialTheme.appColors.surface1)
                .border(1.dp, MaterialTheme.appColors.sand, Radii.smShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.File,
                contentDescription = null,
                tint = MaterialTheme.appColors.stone,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "无法在应用内预览",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.appColors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "二进制或超大文件可以交给其他应用打开，或直接分享。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.stone,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("打开", onOpen)
            GhostButton("分享", onShare)
        }
    }
}

@Composable
private fun ImagePreview(
    file: File,
    entry: WorkspaceEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    var scale by remember(file) { mutableStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.appColors.canvas)
            .clipToBounds()
            .onSizeChanged { bounds = it }
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 8f)
                    val maxX = (next - 1f) * bounds.width / 2f
                    val maxY = (next - 1f) * bounds.height / 2f
                    scale = next
                    offset = if (next <= 1f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                // Enough detail to stay sharp at ~2x zoom without decoding a 20 MP
                // camera file at full resolution.
                .size(1600)
                .crossfade(180)
                .build(),
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            loading = {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.appColors.orange,
                )
            },
            error = { BinaryContent(entry, onOpen, onShare) },
        )
    }
}

// ---------------------------------------------------------------- file helpers

private fun fileUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun shareFile(context: Context, entry: WorkspaceEntry) {
    runCatching {
        val uri = fileUri(context, entry.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = WorkspaceFiles.mimeOf(entry)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${entry.name}"))
    }.onFailure {
        Toast.makeText(context, "无法分享：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun openFile(context: Context, entry: WorkspaceEntry) {
    runCatching {
        val uri = fileUri(context, entry.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, WorkspaceFiles.mimeOf(entry))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "没有能打开该文件的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun fileTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    if (diff in 0 until 60_000) return "刚刚"
    if (diff in 0 until 3_600_000) return "${diff / 60_000} 分钟前"
    val calendar = Calendar.getInstance()
    val today = calendar.get(Calendar.DAY_OF_YEAR)
    calendar.timeInMillis = ts
    return if (calendar.get(Calendar.DAY_OF_YEAR) == today) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
