package app.claudroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.engine.EngineManager
import app.claudroid.engine.TerminalRunner
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.ClaudeTopBar
import app.claudroid.ui.components.glass
import app.claudroid.ui.components.pressFeedback
import app.claudroid.ui.components.quietClickable
import app.claudroid.ui.theme.AppColors
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Workspace shell that runs one-off commands through [TerminalRunner]. Output is kept as
 * a bounded list of lines so a runaway command (`yes`, a huge log) cannot grow Compose
 * state without limit. Pure shaping/trimming logic lives at the bottom of this file and
 * is covered by unit tests; everything above it is only wiring.
 */

internal const val MAX_OUTPUT_LINES = 300
internal const val MAX_LIVE_CHARS = 200_000
internal const val TRUNCATION_NOTICE = "输出过长，已截断"
internal const val EMPTY_HINT = "在 /work 中执行命令；cd 切换目录；clear 清屏。"
internal const val ENGINE_MISSING = "引擎未安装，请先在设置中安装 Claude Code。"
private const val ENGINE_NEXT_STEP = "请在设置中安装引擎或切换引擎模式，然后重新打开终端。"

internal data class TerminalLine(val text: String, val kind: Kind) {
    enum class Kind { Command, Output, Error, Info }
}

internal enum class CommandIntent { Run, Clear, Ignore }

/** `clear` is a UI builtin: the engine has no terminal to clear, so it would just print. */
internal fun classifyCommand(raw: String): CommandIntent = when {
    raw.isBlank() -> CommandIntent.Ignore
    raw.trim() == "clear" -> CommandIntent.Clear
    else -> CommandIntent.Run
}

internal fun terminalSubtitle(label: String?, cwdRel: String): String {
    val rel = cwdRel.trim().trimEnd('/')
    val path = if (rel.isEmpty()) "/work" else "/work/$rel"
    return if (label.isNullOrBlank()) path else "$label · $path"
}

/** Caps streaming output at [maxChars] characters, keeping only the tail. */
internal data class LiveAppend(val text: String, val truncated: Boolean)

internal fun appendLiveOutput(
    current: String,
    chunk: String,
    maxChars: Int = MAX_LIVE_CHARS,
): LiveAppend {
    val next = current + chunk
    return if (next.length > maxChars) LiveAppend(next.takeLast(maxChars), true)
    else LiveAppend(next, false)
}

/** Keeps the newest [max] lines; the head is dropped once the terminal gets busy. */
internal fun trimLines(
    lines: List<TerminalLine>,
    max: Int = MAX_OUTPUT_LINES,
): List<TerminalLine> =
    if (lines.size <= max) lines.toList() else lines.takeLast(max)

/** Turns one finished command into the lines that should be appended to the transcript. */
internal fun completionLines(
    liveOutput: String,
    liveTruncated: Boolean,
    result: TerminalRunner.Result,
): List<TerminalLine> = buildList {
    val text = liveOutput.trimEnd('\n', '\r')
    if (text.isNotEmpty()) add(TerminalLine(text, TerminalLine.Kind.Output))
    if (liveTruncated) {
        add(TerminalLine(TRUNCATION_NOTICE, TerminalLine.Kind.Info))
    }
    result.error?.let { add(TerminalLine(it, TerminalLine.Kind.Error)) }
    if (result.error == null && result.exitCode != 0) {
        add(TerminalLine("退出码 ${result.exitCode}", TerminalLine.Kind.Info))
    }
}

/** Keeps whatever streamed in before the user stopped the command. */
internal fun stoppedLines(liveOutput: String, liveTruncated: Boolean): List<TerminalLine> = buildList {
    val text = liveOutput.trimEnd('\n', '\r')
    if (text.isNotEmpty()) add(TerminalLine(text, TerminalLine.Kind.Output))
    if (liveTruncated) add(TerminalLine(TRUNCATION_NOTICE, TerminalLine.Kind.Info))
    add(TerminalLine("已停止", TerminalLine.Kind.Info))
}

internal fun unavailableReason(issue: String?): String = issue ?: ENGINE_MISSING

@Composable
fun WorkspaceTerminalScreen(
    container: AppContainer,
    plan: EngineManager.ShellPlan?,
    issue: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.appColors
    val runner = remember(context, container) {
        TerminalRunner(context.applicationContext, container.engine.rootExec)
    }
    val scope = rememberCoroutineScope()
    val available = plan != null && issue == null

    val lines = remember { mutableStateListOf<TerminalLine>() }
    var command by remember { mutableStateOf("") }
    var liveOutput by remember { mutableStateOf("") }
    var liveTruncated by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var cwdRel by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    // Leaving the page (back, rotation, switching to a preview) must kill the child
    // process. The engine terminates it from its own finally block on cancellation.
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            job = null
        }
    }

    fun trimToCap() {
        if (lines.size > MAX_OUTPUT_LINES) {
            val keep = trimLines(lines)
            lines.clear()
            lines.addAll(keep)
        }
    }

    fun commit(result: TerminalRunner.Result) {
        lines.addAll(completionLines(liveOutput, liveTruncated, result))
        liveOutput = ""
        liveTruncated = false
        cwdRel = result.cwdRel
        trimToCap()
    }

    fun commitStopped() {
        lines.addAll(stoppedLines(liveOutput, liveTruncated))
        liveOutput = ""
        liveTruncated = false
        trimToCap()
    }

    fun submit() {
        if (running) return
        val active = plan ?: return
        when (classifyCommand(command)) {
            CommandIntent.Ignore -> return
            CommandIntent.Clear -> {
                lines.clear()
                command = ""
                return
            }
            CommandIntent.Run -> Unit
        }
        val cmd = command.trim()
        command = ""
        lines.add(TerminalLine("$ $cmd", TerminalLine.Kind.Command))
        trimToCap()
        running = true
        liveOutput = ""
        liveTruncated = false
        job = scope.launch(Dispatchers.Main) {
            try {
                val result = runner.run(active, cmd, cwdRel) { chunk ->
                    withContext(Dispatchers.Main) {
                        val appended = appendLiveOutput(liveOutput, chunk)
                        liveOutput = appended.text
                        if (appended.truncated) liveTruncated = true
                    }
                }
                commit(result)
            } catch (ce: CancellationException) {
                withContext(NonCancellable) { commitStopped() }
                throw ce
            } catch (e: Exception) {
                lines.add(
                    TerminalLine(
                        "执行失败：${e.message ?: "未知错误"}",
                        TerminalLine.Kind.Error,
                    ),
                )
                liveOutput = ""
                liveTruncated = false
                trimToCap()
            } finally {
                withContext(NonCancellable) { running = false }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
    ) {
        ClaudeTopBar(
            title = "终端",
            subtitle = terminalSubtitle(plan?.label, cwdRel),
            onBack = onBack,
            actions = {
                TextButton(onClick = { lines.clear() }) {
                    Text(
                        "清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentInk,
                    )
                }
            },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (!available) {
                TerminalUnavailable(
                    reason = unavailableReason(issue),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                TerminalOutput(
                    lines = lines,
                    running = running,
                    liveOutput = liveOutput,
                    liveTruncated = liveTruncated,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (available) {
            TerminalInput(
                value = command,
                onValueChange = { command = it },
                running = running,
                onSubmit = ::submit,
                onStop = { job?.cancel() },
            )
        }
    }
}

@Composable
private fun TerminalUnavailable(reason: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClaudeMark(
            size = 34.dp,
            color = MaterialTheme.appColors.orange.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            reason,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.appColors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            ENGINE_NEXT_STEP,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.stone,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TerminalOutput(
    lines: List<TerminalLine>,
    running: Boolean,
    liveOutput: String,
    liveTruncated: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val listState = rememberLazyListState()
    val showHint = lines.isEmpty() && !running
    val liveVisible = running && (liveOutput.isNotEmpty() || liveTruncated)
    val itemCount = lines.size + (if (showHint) 1 else 0) + (if (liveVisible) 1 else 0)

    // Follow-the-tail is a latch: dragging up turns it off, settling at the real bottom
    // turns it back on. Content growth alone must never yank the user back down.
    var followBottom by remember { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) { if (isDragged) followBottom = false }
    LaunchedEffect(listState) {
        snapshotFlow {
            !listState.isScrollInProgress &&
                !listState.canScrollForward
        }.collect { settledAtBottom -> if (settledAtBottom) followBottom = true }
    }
    LaunchedEffect(itemCount, liveOutput.length, followBottom) {
        if (followBottom && itemCount > 0) {
            // Wait for the new text to be measured; scrolling against the previous layout
            // leaves a one-frame gap that reads as jitter.
            withFrameNanos { }
            listState.scrollToBottom()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showHint) {
            item(key = "empty-hint") { TerminalText(EMPTY_HINT, colors.stone) }
        }
        itemsIndexed(lines) { _, line ->
            TerminalText(line.text, lineColor(line.kind, colors))
        }
        if (liveVisible) {
            item(key = "live-output") {
                Column {
                    if (liveTruncated) TerminalText(TRUNCATION_NOTICE, colors.stone)
                    if (liveOutput.isNotEmpty()) {
                        TerminalText(liveOutput.trimEnd('\n', '\r'), colors.graphite)
                    }
                }
            }
        }
    }
}

/** Keeps the tail of the last (possibly taller-than-viewport) item against the bottom. */
private suspend fun LazyListState.scrollToBottom() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    val last = layoutInfo.visibleItemsInfo.lastOrNull()
    if (last == null || last.index != count - 1) scrollToItem(count - 1)
    val tail = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val overflow = tail.offset + tail.size - layoutInfo.viewportEndOffset
    if (overflow > 0) scrollBy(overflow.toFloat())
}

@Composable
private fun TerminalText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AppType.code.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        color = color,
        modifier = modifier,
    )
}

private fun lineColor(kind: TerminalLine.Kind, colors: AppColors): Color = when (kind) {
    TerminalLine.Kind.Command -> colors.accentInk
    TerminalLine.Kind.Output -> colors.graphite
    TerminalLine.Kind.Error -> colors.error
    TerminalLine.Kind.Info -> colors.stone
}

@Composable
private fun TerminalInput(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val haptic = LocalHapticFeedback.current
    val shape = Radii.mdShape
    val enabled = running || value.isNotBlank()

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .glass(null, shape, tintAlpha = 0.72f)
            .padding(6.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$",
            style = AppType.code.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.accentInk,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AppType.code.copy(
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = colors.ink,
            ),
            cursorBrush = SolidColor(colors.orange),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .pressFeedback(shape = CircleShape, dragMorph = true)
                .clip(CircleShape)
                .background(if (enabled) colors.orange else colors.surface2)
                .quietClickable(enabled = enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (running) onStop() else onSubmit()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (running) AppIcons.Stop else AppIcons.ArrowUp,
                contentDescription = if (running) "停止" else "发送",
                tint = if (running) MaterialTheme.colorScheme.onPrimary
                else if (enabled) MaterialTheme.colorScheme.onPrimary else colors.bone,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
