package app.claudroid.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
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
import app.claudroid.ui.components.BaseSlashCommands
import app.claudroid.ui.components.ClaudeComposer
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.FloatingMenu
import app.claudroid.ui.components.FloatingMenuItem
import app.claudroid.ui.components.FloatingTopBar
import app.claudroid.ui.components.FloatingTopBarHeight
import app.claudroid.ui.components.ModelDropdownMenu
import app.claudroid.ui.components.OrganicHalo
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.RetrySlashCommand
import app.claudroid.ui.components.RotatingChevron
import app.claudroid.ui.components.SlashAction
import app.claudroid.ui.components.StopSlashCommand
import app.claudroid.ui.components.TitleMenuWidth
import app.claudroid.ui.components.chatModelLabel
import app.claudroid.ui.components.hazeSource
import app.claudroid.ui.components.pressFeedback
import app.claudroid.ui.components.quietClickable
import app.claudroid.ui.components.rememberFrostedState
import app.claudroid.ui.components.shortModelLabel
import app.claudroid.ui.md.MarkdownText
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    initialPrompt: String?,
    onInitialPromptConsumed: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
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
    val titleBusy by controller.titleBusy.collectAsState()
    val titleNotice by controller.titleNotice.collectAsState()
    val settings by container.settings.flow.collectAsState(initial = AppSettings())

    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    var editingId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }

    // Editing an earlier message puts its text back in the composer; focusing here
    // also raises the keyboard, matching the expectation of a tap on "编辑".
    LaunchedEffect(editingId) {
        if (editingId != null) runCatching { composerFocus.requestFocus() }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Short, transient feedback for the manual "重新生成标题" action; auto-summary
    // is silent by design, so only explicit failures/progress surface here.
    LaunchedEffect(titleNotice) {
        if (titleNotice != null) {
            android.widget.Toast.makeText(context, titleNotice, android.widget.Toast.LENGTH_SHORT).show()
            controller.consumeTitleNotice()
        }
    }
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
    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id
    val slashCommands = remember(running, lastAssistantId) {
        buildList {
            addAll(BaseSlashCommands)
            if (running) add(StopSlashCommand)
            else if (lastAssistantId != null) add(RetrySlashCommand)
        }
    }

    val frostedState = rememberFrostedState()
    val headerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        FloatingTopBarHeight

    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.appColors.canvas)
        ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Keep the composer outside the sampled layer: its slash palette is a
                // haze child, and Haze forbids a child from living inside its source.
                .hazeSource(frostedState)
        ) {
            if (messages.isEmpty()) {
                EmptyChat(
                    modifier = Modifier.fillMaxSize().padding(top = headerHeight),
                    onPick = { input = it },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = headerHeight + 10.dp,
                        bottom = 24.dp,
                    ),
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
                                canEdit = !running && message.role == Role.USER,
                                canRegenerate = !running && message.id == lastAssistantId,
                                onEdit = {
                                    input = message.blocks
                                        .filterIsInstance<Block.Text>()
                                        .joinToString("\n") { it.text }
                                    editingId = message.id
                                },
                                onRegenerate = {
                                    followBottom = true
                                    // The editing banner targets an older message; keep
                                    // the draft but stop pretending it is an edit.
                                    editingId = null
                                    controller.regenerate(message.id)
                                },
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
            placeholder = if (editingId != null) "修改这条消息…" else "输入回答…",
            running = running,
            onSend = {
                val text = input
                val editId = editingId
                if (text.isNotBlank()) {
                    if (editId != null) {
                        // Keep the draft and the editing banner when the target is gone;
                        // clearing first would lose both without sending anything.
                        if (controller.resendFrom(editId, text)) {
                            input = ""
                            editingId = null
                            followBottom = true
                        }
                    } else {
                        input = ""
                        followBottom = true
                        controller.send(text)
                    }
                }
            },
            onStop = { controller.stop() },
            onAttach = { filePicker.launch(arrayOf("*/*")) },
            editing = editingId != null,
            onCancelEdit = {
                editingId = null
                input = ""
            },
            focusRequester = composerFocus,
            slashCommands = slashCommands,
            onSlashAction = { action ->
                when (action) {
                    SlashAction.NEW_CHAT -> onNewChat()
                    SlashAction.MODEL -> showModelMenu = true
                    SlashAction.ATTACH -> filePicker.launch(arrayOf("*/*"))
                    SlashAction.WORKSPACE -> onOpenWorkspace()
                    SlashAction.SETTINGS -> onOpenSettings()
                    SlashAction.STOP -> controller.stop()
                    SlashAction.RETRY -> lastAssistantId?.let { id ->
                        followBottom = true
                        editingId = null
                        controller.regenerate(id)
                    }
                }
            },
            frostedState = frostedState,
        )
        }

        FloatingTopBar(
            title = title,
            subtitle = if (running) statusLine else null,
            modelLabel = shortModelLabel(settings),
            running = running,
            onOpenDrawer = onOpenDrawer,
            onModelClick = { showModelMenu = !showModelMenu },
            onNewChat = onNewChat,
            modifier = Modifier.align(Alignment.TopCenter),
            frostedState = frostedState,
            titleMenuOpen = menuOpen,
            modelMenuOpen = showModelMenu,
            onTitleClick = { menuOpen = !menuOpen },
            titleMenu = { offset ->
                FloatingMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    width = TitleMenuWidth,
                    offset = offset,
                ) {
                    FloatingMenuItem("工作区文件", AppIcons.Folder) {
                        menuOpen = false
                        onOpenWorkspace()
                    }
                    FloatingMenuItem("清空对话上下文", AppIcons.Trash) {
                        menuOpen = false
                        editingId = null
                        input = ""
                        controller.clear()
                    }
                    FloatingMenuItem(
                        text = if (titleBusy) "正在生成标题…" else "重新生成标题",
                        icon = AppIcons.Sparkle,
                        enabled = !running && !titleBusy && messages.isNotEmpty(),
                    ) {
                        menuOpen = false
                        controller.regenerateTitle()
                    }
                }
            },
            modelMenu = { offset ->
                ModelDropdownMenu(
                    expanded = showModelMenu,
                    settings = settings,
                    offset = offset,
                    onSelectTier = { tier ->
                        scope.launch {
                            container.settings.edit { it.copy(activeTier = tier?.name ?: "") }
                        }
                    },
                    onSelectAuto = {
                        scope.launch {
                            container.settings.edit { it.copy(activeTier = AppSettings.AUTO_MODEL) }
                        }
                    },
                    onManageTiers = onOpenSettings,
                    onDismiss = { showModelMenu = false },
                )
            },
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
                .pressFeedback(shape = CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.appColors.paper)
                .border(1.dp, MaterialTheme.appColors.sand, CircleShape)
                .quietClickable(onClick = onClick),
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
        Box(contentAlignment = Alignment.Center) {
            OrganicHalo(
                size = 78.dp,
                color = MaterialTheme.appColors.orange.copy(alpha = 0.10f),
            )
            ClaudeMark(size = 30.dp)
        }
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
                    .pressFeedback(shape = Radii.lgShape)
                    .clip(Radii.lgShape)
                    .background(MaterialTheme.appColors.paper)
                    .border(1.dp, MaterialTheme.appColors.sand, Radii.lgShape)
                    .quietClickable { onPick(hint) }
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
private fun MessageRow(
    message: ChatMessage,
    model: String,
    statusLine: String,
    canEdit: Boolean,
    canRegenerate: Boolean,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
) {
    when (message.role) {
        Role.USER -> UserBubble(message, canEdit = canEdit, onEdit = onEdit)

        Role.ASSISTANT -> AssistantMessage(
            message = message,
            model = model,
            statusLine = statusLine,
            canRegenerate = canRegenerate,
            onRegenerate = onRegenerate,
        )

        Role.SYSTEM -> message.blocks.forEach { block ->
            if (block is Block.Error) ErrorNotice(block.text)
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage, canEdit: Boolean, onEdit: () -> Unit) {
    val text = message.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.8f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(Radii.lgShape)
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
            if (canEdit) {
                MessageAction(
                    icon = AppIcons.Edit,
                    label = "编辑",
                    onClick = onEdit,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    model: String,
    statusLine: String,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    // Thinking and tool calls are deliberately kept out of the main body. Until the
    // first real answer text arrives they appear as the live thinking pill and compact
    // "activity lines" (verb + icon, never a command/path/JSON). Once output starts —
    // or the turn ends without output — everything folds into one quiet work
    // disclosure at the tail of the turn, default collapsed.
    val tools = remember(message.blocks) { message.blocks.filterIsInstance<Block.Tool>() }
    val thinkingSegments = remember(message.blocks) {
        message.blocks.filterIsInstance<Block.Thinking>()
            .map { it.text }
            .filter { it.isNotBlank() }
    }
    val thinking = thinkingSegments.joinToString("\n")
    val errorCount = remember(tools) { tools.count { it.isError } }
    // WebSearch answers often end with a plain "Sources:" list that duplicates the
    // tappable cards under the tool call; the transcript hides that trailing block.
    val visibleTexts = remember(message.blocks) {
        message.blocks.map { block ->
            if (block is Block.Text) WebSources.stripTrailingSources(block.text) else null
        }
    }
    // A text block that was entirely a source list is not a real answer: the thinking
    // pill and activity lines must stay visible for such a turn.
    val hasOutput = remember(visibleTexts) { visibleTexts.any { it?.isNotBlank() == true } }
    val copyText = remember(visibleTexts) {
        visibleTexts.filterNotNull().filter { it.isNotBlank() }.joinToString("\n\n").trim()
    }

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

        // Thinking is a pre-answer signal only: one compact pill while the model is
        // still reasoning, folding away the moment the first real text arrives or the
        // turn ends (where the work disclosure takes over).
        AnimatedVisibility(
            visible = !hasOutput && message.streaming,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            ThinkingPill(
                text = thinking,
                active = message.streaming,
                placeholder = thinking.isBlank(),
            )
        }

        // The moment real output appears — or the turn ends — the activity lines fold
        // away into the disclosure. AnimatedVisibility keeps that transition from
        // reading as a jump. Both live above the answer text: the model reasons and
        // calls tools first, then writes.
        AnimatedVisibility(
            visible = !hasOutput && message.streaming && tools.isNotEmpty(),
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Column(Modifier.padding(top = 8.dp)) {
                tools.forEach { tool -> ActivityLine(tool) }
            }
        }
        AnimatedVisibility(
            visible = (hasOutput || !message.streaming) &&
                (thinkingSegments.isNotEmpty() || tools.isNotEmpty()),
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            WorkDisclosure(
                messageId = message.id,
                blocks = message.blocks,
                thinkingCount = thinkingSegments.size,
                toolCount = tools.size,
                errorCount = errorCount,
            )
        }

        val lastTextIndex = visibleTexts.indexOfLast { it?.isNotBlank() == true }
        message.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Text -> {
                    val display = visibleTexts.getOrNull(index).orEmpty()
                    if (display.isNotBlank()) {
                        SelectionContainer {
                            MarkdownText(
                                display,
                                Modifier.fillMaxWidth(),
                                streaming = message.streaming && index == lastTextIndex,
                            )
                        }
                    }
                }
                is Block.Thinking -> Unit // surfaced by the work disclosure above
                is Block.Tool -> Unit // surfaced by the activity line / disclosure above
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
            if (meta.isNotEmpty() || canRegenerate || copyText.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString(" · "),
                            style = AppType.caption.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.appColors.stone,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (canRegenerate) {
                        MessageAction(
                            icon = AppIcons.Regenerate,
                            label = "重新回答",
                            onClick = onRegenerate,
                        )
                    }
                    if (copyText.isNotBlank()) {
                        CopyAction(copyText)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.appColors.stone,
) {
    Row(
        modifier
            .clip(CircleShape)
            .pressFeedback(shape = CircleShape)
            .quietClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = AppType.caption, color = tint)
    }
}

@Composable
private fun CopyAction(text: String) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    MessageAction(
        icon = if (copied) AppIcons.Check else AppIcons.Copy,
        label = if (copied) "已复制" else "复制",
        tint = if (copied) MaterialTheme.appColors.accentInk else MaterialTheme.appColors.stone,
        onClick = {
            clipboard.setText(AnnotatedString(text))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            copied = true
        },
    )
}

@Composable
private fun ErrorNotice(text: String) {
    val error = MaterialTheme.appColors.error
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(Radii.smShape)
            .background(error.copy(alpha = 0.06f))
            .border(1.dp, error.copy(alpha = 0.28f), Radii.smShape)
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
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .clip(CircleShape)
                .pressFeedback(shape = CircleShape)
                .background(colors.surface1)
                .quietClickable(enabled = !placeholder) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 7.dp),
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

/**
 * Pre-output "activity line": one compact row with a Chinese verb and a line icon.
 * It intentionally shows no command, file path, input or output — only that the agent
 * is doing something, in language the user can read at a glance.
 */
@Composable
private fun ActivityLine(block: Block.Tool) {
    val colors = MaterialTheme.appColors
    val icon = ToolPresentation.icon(block.name)
    val verb = ToolPresentation.verb(block.name)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (block.isError) colors.error else colors.stone,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            verb,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = colors.graphite,
        )
        if (block.running) {
            Spacer(Modifier.width(8.dp))
            PulsingDot(colors.orange, size = 4.dp)
        } else if (block.isError) {
            Spacer(Modifier.width(8.dp))
            Text(
                "失败",
                style = AppType.caption,
                color = colors.error,
            )
        } else {
            Spacer(Modifier.width(8.dp))
            Icon(
                AppIcons.Check,
                contentDescription = null,
                tint = colors.stone,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * Unified work disclosure above the answer text: once the answer starts (or the turn
 * ends), every thinking segment and tool call folds into one low-key block. Default
 * collapsed; expanding replays `message.blocks` in their original order, where each
 * thinking segment and each tool call is itself a collapsed inner fold. The error count
 * stays in the label so a failed tool is never silently hidden.
 */
@Composable
private fun WorkDisclosure(
    messageId: String,
    blocks: List<Block>,
    thinkingCount: Int,
    toolCount: Int,
    errorCount: Int,
) {
    // Stable per message so streaming recompositions never reset the user's choice.
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val colors = MaterialTheme.appColors
    val shape = Radii.smShape
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 10.dp)
            .clip(shape)
            .border(1.dp, colors.sand.copy(alpha = 0.7f), shape)
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .pressFeedback(shape = shape)
                .fillMaxWidth()
                .clip(shape)
                .quietClickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Sparkle,
                contentDescription = null,
                tint = if (errorCount > 0) colors.error else colors.stone,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                workLabel(thinkingCount, toolCount, errorCount),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                color = if (errorCount > 0) colors.error else colors.graphite,
                modifier = Modifier.weight(1f),
            )
            RotatingChevron(expanded = expanded, size = 15.dp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Column {
                var first = true
                var thinkingIndex = 0
                blocks.forEachIndexed { blockIndex, block ->
                    val isWork = when (block) {
                        is Block.Thinking -> block.text.isNotBlank()
                        is Block.Tool -> true
                        else -> false
                    }
                    if (!isWork) return@forEachIndexed
                    if (!first) HorizontalDivider(color = colors.sand.copy(alpha = 0.6f))
                    first = false
                    when (block) {
                        is Block.Thinking -> {
                            thinkingIndex += 1
                            ThinkingFold(
                                messageId = messageId,
                                blockIndex = blockIndex,
                                text = block.text,
                                index = thinkingIndex,
                                total = thinkingCount,
                            )
                        }
                        is Block.Tool -> ToolCard(block, embedded = true)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * One reasoning segment inside [WorkDisclosure]. It is its own small fold: a single
 * header row ("思考过程 i/n · N 字" + chevron) that expands to the raw text with the
 * sand rule. No border or fill — inside the outer card it must read as a nested row,
 * not as a second card.
 */
@Composable
private fun ThinkingFold(
    messageId: String,
    blockIndex: Int,
    text: String,
    index: Int,
    total: Int,
) {
    // Each segment remembers its own state; streaming appends blocks without shifting
    // the indices of the segments already on screen.
    var expanded by rememberSaveable(messageId, blockIndex) { mutableStateOf(false) }
    val colors = MaterialTheme.appColors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .pressFeedback()
                .fillMaxWidth()
                .quietClickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "思考过程 $index/$total",
                style = AppType.caption,
                color = colors.graphite,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "· ${text.length} 字",
                style = AppType.caption,
                color = colors.stone,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RotatingChevron(expanded = expanded, tint = colors.stone, size = 14.dp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) + fadeIn(tween(Motion.FAST)),
            exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(colors.sand),
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                        color = colors.graphite,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(block: Block.Tool, embedded: Boolean = false) {
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
    val shape = Radii.smShape
    val toolbar = Modifier
        .fillMaxWidth()
        .then(if (embedded) Modifier else Modifier.padding(top = 10.dp))
        .clip(shape)
        .background(if (embedded) Color.Transparent else colors.paper)
        .then(if (embedded) Modifier else Modifier.border(1.dp, borderColor, shape))
        .animateContentSize()
    Column(toolbar) {
        Row(
            Modifier
                .pressFeedback(shape = shape)
                .fillMaxWidth()
                .clip(shape)
                .quietClickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                ToolPresentation.icon(block.name),
                contentDescription = null,
                tint = if (block.isError) colors.error else colors.graphite,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
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
                    .clip(Radii.smShape)
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
                    .clip(Radii.xsShape)
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
            .clip(Radii.xsShape)
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
