package app.claudroid.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.core.Attachments
import app.claudroid.core.WebSource
import app.claudroid.core.WebSources
import app.claudroid.data.AppSettings
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.Role
import app.claudroid.ui.components.ClaudeComposer
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.ModelPickerSheet
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.RotatingChevron
import app.claudroid.ui.components.TypingDots
import app.claudroid.ui.components.chatModelLabel
import app.claudroid.ui.components.composerModelLabel
import app.claudroid.ui.md.MarkdownText
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    initialPrompt: String?,
    onInitialPromptConsumed: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val controller = remember(sessionId) { container.chat(sessionId) }
    // Loading must complete before a handed-off first prompt is sent; doing both in
    // one effect avoids the MainActivity/session-load race entirely.
    LaunchedEffect(sessionId, initialPrompt) {
        controller.load()
        if (initialPrompt != null) {
            controller.send(initialPrompt)
            onInitialPromptConsumed()
        }
    }

    val messages by controller.messages.collectAsState()
    val running by controller.running.collectAsState()
    val status by controller.status.collectAsState()
    val title by controller.title.collectAsState()
    val settings by container.settings.flow.collectAsState(initial = AppSettings())

    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

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

    val model = chatModelLabel(settings)
    val statusLine = chatStatusLine(running = running, rawStatus = status, model = model)

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(44.dp)) {
                Icon(
                    AppIcons.Menu,
                    "会话记录",
                    tint = MaterialTheme.appColors.graphite,
                    modifier = Modifier.size(21.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.appColors.ink,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        TypingDots(dotSize = 2.6.dp)
                        Spacer(Modifier.width(5.dp))
                    }
                    AnimatedContent(
                        targetState = statusLine,
                        transitionSpec = {
                            fadeIn(tween(Motion.FAST)) togetherWith fadeOut(tween(Motion.FAST))
                        },
                        label = "status",
                    ) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.appColors.stone,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
                        text = { Text("工作区文件") },
                        leadingIcon = { Icon(AppIcons.Folder, null) },
                        onClick = {
                            menuOpen = false
                            onOpenWorkspace()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("清空对话上下文") },
                        leadingIcon = { Icon(AppIcons.Trash, null) },
                        onClick = {
                            menuOpen = false
                            controller.clear()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("返回首页") },
                        leadingIcon = { Icon(AppIcons.Back, null) },
                        onClick = {
                            menuOpen = false
                            onBack()
                        },
                    )
                }
            }
        }

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
                    verticalArrangement = Arrangement.spacedBy(32.dp),
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
                            MessageRow(
                                message = message,
                                model = model,
                                statusLine = statusLine,
                            )
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

        ClaudeComposer(
            value = input,
            onValueChange = { input = it },
            placeholder = "回复 Claude…",
            model = composerModelLabel(settings),
            running = running,
            onSend = {
                val text = input
                input = ""
                followBottom = true
                controller.send(text)
            },
            onStop = { controller.stop() },
            onAttach = { filePicker.launch(arrayOf("*/*")) },
            onModelClick = { showModelPicker = true },
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            settings = settings,
            onSelectTier = { tier ->
                scope.launch {
                    container.settings.edit { it.copy(activeTier = tier?.name ?: "") }
                }
            },
            onManageTiers = onOpenSettings,
            onDismiss = { showModelPicker = false },
        )
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
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.appColors.paper)
                .border(1.dp, MaterialTheme.appColors.sand, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.ChevronDown,
                "回到底部",
                tint = MaterialTheme.appColors.stone,
                modifier = Modifier.size(19.dp),
            )
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
        animateScrollBy(overflow.toFloat(), tween(Motion.MEDIUM, easing = Motion.EaseChrome))
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
        ClaudeMark(size = 30.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            "开始对话",
            style = AppType.assistantHeadingLarge,
            color = MaterialTheme.appColors.ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "它跑在你手机里的 Claude Code：PRoot 免 root 就能读写工作区，root 模式下还能改系统、刷 Magisk 模块。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.graphite,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        hints.forEach { hint ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.appColors.paper)
                    .border(1.dp, MaterialTheme.appColors.sand, RoundedCornerShape(12.dp))
                    .clickable { onPick(hint) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.appColors.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "↵",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.stone,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, model: String, statusLine: String) {
    when (message.role) {
        Role.USER -> UserBubble(message)

        Role.ASSISTANT -> AssistantMessage(message, model, statusLine)

        Role.SYSTEM -> message.blocks.forEach { block ->
            if (block is Block.Error) ErrorNotice(block.text)
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    val text = message.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.8f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.appColors.surface1)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = AppType.userBody,
                        color = MaterialTheme.appColors.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage, model: String, statusLine: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClaudeMark(size = 18.dp, pulse = message.streaming)
            Spacer(Modifier.width(8.dp))
            Text(
                "Claude",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = MaterialTheme.appColors.ink,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (message.streaming) statusLine else model,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                color = MaterialTheme.appColors.stone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (message.streaming && message.blocks.isEmpty()) {
            ThinkingPill(text = "", active = true, placeholder = true)
        }

        val lastTextIndex = message.blocks.indexOfLast { it is Block.Text }
        message.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Text -> SelectionContainer {
                    MarkdownText(
                        block.text,
                        Modifier.fillMaxWidth(),
                        streaming = message.streaming && index == lastTextIndex,
                    )
                }
                is Block.Thinking -> ThinkingPill(
                    text = block.text,
                    active = message.streaming && index == message.blocks.lastIndex,
                )
                is Block.Tool -> ToolCard(block)
                is Block.Error -> ErrorNotice(block.text)
            }
        }

        if (!message.streaming) {
            val meta = buildList {
                message.costUsd?.takeIf { it > 0 }?.let { add("≈$" + "%.4f".format(it)) }
                message.durationMs?.takeIf { it > 0 }?.let {
                    add(
                        if (it >= 60_000) "${it / 60_000}m ${(it % 60_000) / 1000}s"
                        else "%.1fs".format(it / 1000.0)
                    )
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = AppType.caption.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.appColors.stone,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorNotice(text: String) {
    val error = MaterialTheme.appColors.error
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(error.copy(alpha = 0.06f))
            .border(1.dp, error.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = error,
            )
        }
    }
}

@Composable
private fun ThinkingPill(text: String, active: Boolean, placeholder: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.appColors
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.orangeSoft)
                .clickable(enabled = !placeholder) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClaudeMark(size = 14.dp, pulse = active)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    active -> "思考中…"
                    else -> "思考过程 · ${text.length} 字"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.accentInk,
            )
            if (!placeholder) {
                Spacer(Modifier.width(3.dp))
                RotatingChevron(expanded = expanded, tint = colors.accentInk, size = 14.dp)
            }
        }
        AnimatedVisibility(
            visible = expanded && !placeholder,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    ),
                    color = colors.graphite,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 13.dp, end = 4.dp)
                        .drawBehind {
                            drawRect(
                                color = colors.sand,
                                topLeft = Offset.Zero,
                                size = Size(2.dp.toPx(), size.height),
                            )
                        }
                        .padding(start = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(block: Block.Tool) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val colors = MaterialTheme.appColors
    val detail = toolDetail(block)
    val sources = remember(block.name, block.output) {
        val isSearch = block.name.equals("WebSearch", ignoreCase = true) ||
            block.output?.contains("Web search results", ignoreCase = true) == true
        if (isSearch) WebSources.parse(block.output.orEmpty()) else emptyList()
    }
    // A quiet breathing border replaces a spinner floating in the header.
    val pulseAlpha = if (block.running) {
        val pulse = rememberInfiniteTransition(label = "tool-pulse")
        pulse.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = Motion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tool-border",
        ).value
    } else 0f
    val borderColor by animateColorAsState(
        targetValue = if (block.running) colors.orange.copy(alpha = pulseAlpha) else colors.sand,
        animationSpec = tween(Motion.FAST),
        label = "tool-border-color",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(shape)
            .background(colors.paper)
            .border(1.dp, borderColor, shape)
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface1),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "›_",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.stone,
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    block.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = colors.ink,
                )
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = colors.stone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                block.running -> {
                    PulsingDot(colors.orange, size = 5.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "运行中",
                        style = AppType.caption,
                        color = colors.accentInk,
                    )
                }
                block.isError -> Text(
                    "失败",
                    style = AppType.caption,
                    color = colors.error,
                )
                else -> Text(
                    "完成",
                    style = AppType.caption,
                    color = colors.stone,
                )
            }
            Spacer(Modifier.width(5.dp))
            RotatingChevron(expanded = expanded, size = 15.dp)
        }
        if (sources.isNotEmpty()) {
            HorizontalDivider(color = colors.sand)
            SourcesSection(sources)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Column {
                HorizontalDivider(color = colors.sand)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    MonoLabel("入参")
                    MonoCodeBlock(block.input)
                    block.output?.let {
                        Spacer(Modifier.height(10.dp))
                        MonoLabel("输出")
                        MonoCodeBlock(it, error = block.isError)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color, size: Dp = 5.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = Motion.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-alpha",
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape)
    )
}

/**
 * Search hits are the useful part of a WebSearch tool call, so they stay visible
 * outside the raw JSON expansion and open in the browser on tap.
 */
@Composable
private fun SourcesSection(sources: List<WebSource>) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.appColors
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = if (showAll) sources else sources.take(4)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "来源 · ${sources.size}",
            style = AppType.caption,
            color = colors.stone,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        visible.forEachIndexed { index, source ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface1)
                    .clickable { runCatching { uriHandler.openUri(source.url) } }
                    .padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.orangeSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = AppType.caption.copy(fontSize = 10.sp),
                        color = colors.accentInk,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        source.title,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = colors.graphite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.host.isNotBlank()) {
                        Text(
                            source.host,
                            style = AppType.caption,
                            color = colors.stone,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "↗",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.stone,
                )
            }
        }
        if (sources.size > 4) {
            Text(
                if (showAll) "收起" else "展开全部 ${sources.size} 个来源",
                style = AppType.caption,
                color = colors.accentInk,
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
        style = AppType.caption.copy(letterSpacing = 0.3.sp),
        color = MaterialTheme.appColors.stone,
        modifier = Modifier.padding(bottom = 5.dp),
    )
}

@Composable
private fun MonoCodeBlock(text: String, error: Boolean = false) {
    val colors = MaterialTheme.appColors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBg)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        SelectionContainer {
            Text(
                text.ifBlank { "（空）" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                ),
                color = if (error) colors.error else colors.codeFg,
            )
        }
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
