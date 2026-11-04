package app.claudroid.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.core.Attachments
import app.claudroid.core.WebSource
import app.claudroid.core.WebSources
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.RotatingChevron
import app.claudroid.ui.components.TypingDots
import app.claudroid.ui.md.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val controller = remember(sessionId) { container.chat(sessionId) }
    LaunchedEffect(sessionId) { controller.load() }

    val messages by controller.messages.collectAsState()
    val running by controller.running.collectAsState()
    val status by controller.status.collectAsState()
    val title by controller.title.collectAsState()

    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Picked files are copied into the workspace and referenced as @attachments/<name>,
    // which is exactly the syntax Claude Code understands for file references.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { Attachments.import(context, uri) }
                result.onSuccess { file ->
                    val rel = Attachments.relativePath(context, file)
                    input = (if (input.isBlank()) "" else input.trimEnd() + " ") + "@$rel "
                }.onFailure { controller.notifyError("附件导入失败：${it.message}") }
            }
        }
    }
    val listState = rememberLazyListState()

    // Streaming text grows inside the last item, so keying only on messages.size leaves
    // the view stuck at the top of a long answer.
    val lastContentLength = messages.lastOrNull()?.blocks?.sumOf { block ->
        when (block) {
            is Block.Text -> block.text.length
            is Block.Thinking -> block.text.length
            is Block.Tool -> block.output?.length ?: 0
            is Block.Error -> block.text.length
        }
    } ?: 0

    // Follow-the-tail is a latch: user drags turn it off, settling at the true end turns
    // it back on. Content growth alone must never re-enable it, or scrolling up during a
    // reply would be yanked back to the bottom on every token.
    var followBottom by remember { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) followBottom = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !listState.canScrollForward) followBottom = true
        }
    }
    LaunchedEffect(messages.size, lastContentLength) {
        if (messages.isNotEmpty() && followBottom) {
            // Wait for the new text to be measured; scrolling against the previous
            // layout leaves a one-frame gap that reads as jitter.
            withFrameNanos { }
            listState.scrollToBottom()
        }
    }

    fun jumpToBottom() {
        followBottom = true
        scope.launch { listState.animateToBottom() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (running) {
                                TypingDots(dotSize = 3.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            AnimatedContent(
                                targetState = status.ifBlank { if (running) "正在思考…" else "Claude Code" },
                                transitionSpec = {
                                    fadeIn(tween(Motion.FAST)) togetherWith fadeOut(tween(Motion.FAST))
                                },
                                label = "status",
                            ) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("工作区文件") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                            onClick = {
                                menuOpen = false
                                onOpenWorkspace()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空对话上下文") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                menuOpen = false
                                controller.clear()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("返回对话列表") },
                            onClick = {
                                menuOpen = false
                                onBack()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyChat(
                        modifier = Modifier.fillMaxSize(),
                        onPick = { input = it },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = tween(240),
                                    fadeOutSpec = tween(140),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            ) {
                                MessageRow(message)
                            }
                        }
                    }
                }

                BackToBottomButton(
                    visible = messages.isNotEmpty() && !followBottom,
                    onClick = { jumpToBottom() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 10.dp),
                )
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                running = running,
                onSend = {
                    val text = input
                    input = ""
                    followBottom = true
                    controller.send(text)
                },
                onStop = { controller.stop() },
                onAttach = { filePicker.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun BackToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.FAST)) + scaleIn(tween(Motion.FAST), initialScale = 0.8f),
        exit = fadeOut(tween(Motion.FAST)) + scaleOut(tween(Motion.FAST), targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    "回到底部",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Keeps the tail of the last (possibly taller-than-viewport) item against the bottom. */
private suspend fun LazyListState.scrollToBottom() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    val last = layoutInfo.visibleItemsInfo.lastOrNull()
    if (last == null || last.index != count - 1) {
        scrollToItem(count - 1)
    }
    val tail = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val overflow = tail.offset + tail.size - layoutInfo.viewportEndOffset
    if (overflow > 0) scrollBy(overflow.toFloat())
}

private suspend fun LazyListState.animateToBottom() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    val last = layoutInfo.visibleItemsInfo.lastOrNull()
    if (last == null || last.index != count - 1) {
        animateScrollToItem(count - 1)
    }
    val tail = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val overflow = tail.offset + tail.size - layoutInfo.viewportEndOffset
    if (overflow > 0) {
        animateScrollBy(overflow.toFloat(), tween(Motion.MEDIUM, easing = Motion.EaseOut))
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val hints = listOf(
        "看看工作区里都有什么文件，帮我整理成清单",
        "写一个脚本：把工作区里的 CSV 转成 Markdown 表格",
        "看看这台手机哪些文件最占空间",
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClaudeMark(size = 34.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            "今天想做什么？",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "它跑在你手机里的 Claude Code：PRoot 免 root 就能读写工作区，root 模式下还能改系统、刷 Magisk 模块。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        hints.forEach { hint ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onPick(hint) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "↗",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    when (message.role) {
        Role.USER -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                }
            }
        }

        Role.ASSISTANT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            ClaudeMark(
                size = 18.dp,
                pulse = message.streaming,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                if (message.streaming) {
                    Row(
                        Modifier.padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypingDots(dotSize = 3.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "正在回复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                message.blocks.forEach { block ->
                    when (block) {
                        is Block.Text -> SelectionContainer {
                            MarkdownText(block.text, Modifier.fillMaxWidth())
                        }
                        is Block.Thinking -> ThinkingRow(block.text)
                        is Block.Tool -> ToolCard(block)
                        is Block.Error -> ErrorText(block.text)
                    }
                }
                if (!message.streaming) {
                    val meta = buildList {
                        message.costUsd?.takeIf { it > 0 }?.let { add("≈$" + "%.4f".format(it)) }
                        message.durationMs?.takeIf { it > 0 }?.let {
                            add(if (it >= 60_000) "${it / 60_000}m ${(it % 60_000) / 1000}s"
                            else "%.1fs".format(it / 1000.0))
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        Role.SYSTEM -> message.blocks.forEach { block ->
            if (block is Block.Error) ErrorText(block.text)
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(2.dp, 16.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(10.dp))
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ThinkingRow(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "思考过程" else "思考过程 · ${text.length} 字",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            RotatingChevron(
                expanded = expanded,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 15.dp,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseOut)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Row(Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .heightIn(min = 18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp))
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(block: Block.Tool) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val detail = toolDetail(block)
    val sources = remember(block.name, block.output) {
        val isSearch = block.name.equals("WebSearch", ignoreCase = true) ||
            block.output?.contains("Web search results", ignoreCase = true) == true
        if (isSearch) WebSources.parse(block.output.orEmpty()) else emptyList()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "›_",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(block.name, style = MaterialTheme.typography.labelLarge)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                block.running -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.6.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                block.isError -> Text(
                    "失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    "完成",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            RotatingChevron(expanded = expanded, size = 16.dp)
        }
        if (sources.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SourcesSection(sources)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseOut)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    MonoLabel("入参")
                    MonoBlockText(block.input)
                    block.output?.let {
                        MonoLabel("输出")
                        MonoBlockText(it)
                    }
                }
            }
        }
    }
}

/**
 * Search hits are the useful part of a WebSearch tool call, so they stay visible
 * outside the raw JSON expansion and open in the browser on tap.
 */
@Composable
private fun SourcesSection(sources: List<WebSource>) {
    val uriHandler = LocalUriHandler.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = if (showAll) sources else sources.take(4)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "来源 · ${sources.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        visible.forEachIndexed { index, source ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { runCatching { uriHandler.openUri(source.url) } }
                    .padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        source.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.host.isNotBlank()) {
                        Text(
                            source.host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "↗",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (sources.size > 4) {
            Text(
                if (showAll) "收起" else "展开全部 ${sources.size} 个来源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showAll = !showAll }
                    .padding(horizontal = 2.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun MonoLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun MonoBlockText(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(9.dp)
    ) {
        SelectionContainer {
            Text(
                text.ifBlank { "（空）" },
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(Motion.FAST),
        label = "composer-border",
    )
    val sendEnabled = running || value.isNotBlank()
    val sendColor by animateColorAsState(
        targetValue = if (sendEnabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "send-color",
    )
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (sendPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "send-scale",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, borderColor, RoundedCornerShape(22.dp))
                .animateContentSize()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    "添加附件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (value.isEmpty()) {
                    Text(
                        "回复 Claudroid…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                    }
                    .clip(CircleShape)
                    .background(sendColor)
                    .clickable(
                        interactionSource = sendInteraction,
                        indication = null,
                        enabled = sendEnabled,
                    ) {
                        if (running) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp))
                    )
                } else {
                    Text(
                        "↑",
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                        color = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Agent 可能修改系统文件，请确认它执行的命令。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun toolDetail(block: Block.Tool): String {
    val obj = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(block.input).let {
            it as? kotlinx.serialization.json.JsonObject
        }
    }.getOrNull()
    val v = obj?.let {
        (it["command"] ?: it["file_path"] ?: it["path"] ?: it["pattern"] ?: it["query"] ?: it["url"])
            ?.let { e -> (e as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }
    return v?.replace('\n', ' ')?.take(70) ?: ""
}
