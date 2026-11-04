package app.claudroid.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.core.Attachments
import app.claudroid.data.Role
import app.claudroid.ui.components.ClaudeMark
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

    // Streaming text grows inside the last item, so keying only on messages.size leaves the
    // view stuck at the top of a long answer.
    val lastContentLength = messages.lastOrNull()?.blocks?.sumOf { block ->
        when (block) {
            is Block.Text -> block.text.length
            is Block.Thinking -> block.text.length
            is Block.Tool -> block.output?.length ?: 0
            is Block.Error -> block.text.length
        }
    } ?: 0
    val stickToBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || info.totalItemsCount == 0 || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(messages.size, lastContentLength) {
        if (messages.isNotEmpty() && (stickToBottom || running)) {
            runCatching { listState.scrollToItem(messages.lastIndex) }
        }
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
                        Text(
                            text = status.ifBlank { if (running) "正在思考…" else "Claude Code" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
            if (messages.isEmpty()) {
                EmptyChat(
                    modifier = Modifier.weight(1f),
                    onPick = { input = it },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageRow(message)
                    }
                }
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                running = running,
                onSend = {
                    val text = input
                    input = ""
                    controller.send(text)
                },
                onStop = { controller.stop() },
                onAttach = { filePicker.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val hints = listOf(
        "看看当前 Magisk 模块列表，哪些被停用了",
        "把 /sdcard/Download/adblock.zip 刷进去",
        "某个模块开机不生效，帮我查原因",
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
                Text(
                    text = message.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
        }

        Role.ASSISTANT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            ClaudeMark(
                size = 18.dp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                if (message.streaming) {
                    Text(
                        "正在回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                message.blocks.forEach { block ->
                    when (block) {
                        is Block.Text -> MarkdownText(block.text, Modifier.fillMaxWidth())
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
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
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
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        if (expanded) {
            Row(Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .heightIn(min = 18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(block: Block.Tool) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val detail = toolDetail(block)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .animateContentSize()
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
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
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
        Text(
            text.ifBlank { "（空）" },
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
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
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
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
                Text(
                    "+",
                    style = TextStyle(fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (running || value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    .clickable(enabled = running || value.isNotBlank()) {
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
