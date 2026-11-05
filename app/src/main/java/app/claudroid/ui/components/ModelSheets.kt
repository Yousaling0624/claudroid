package app.claudroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.SettingsType
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

private @Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.appColors.surface2)
        )
    }
}

/**
 * Model switcher shared by home and chat. This used to be a bottom sheet; a dropdown
 * anchored under the model pill matches the top-bar control that opens it and keeps
 * the transcript visible. Selecting a tier only changes [AppSettings.activeTier]; the
 * concrete model stays editable in settings so the same picker works for Anthropic
 * aliases and OpenAI-compatible proxies.
 */
@Composable
fun ModelDropdownMenu(
    expanded: Boolean,
    settings: AppSettings,
    offset: DpOffset,
    onSelectTier: (ModelTier?) -> Unit,
    onSelectAuto: () -> Unit,
    onManageTiers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    FloatingMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        width = ModelMenuWidth,
        offset = offset,
    ) {
        Text(
            "选择模型",
            style = AppType.captionMedium.copy(letterSpacing = 0.4.sp),
            color = colors.stone,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        )
        val active = settings.resolvedActiveTier
        ModelMenuItem(
            title = "默认 / 路由组",
            subtitle = settings.model.ifBlank { "未设置" },
            selected = active == null && !settings.usesAutoModel,
        ) {
            onSelectTier(null)
            onDismiss()
        }
        ModelMenuItem(
            title = "CLI 默认（不指定模型）",
            subtitle = "不传 --model，由 Claude Code 用自身默认模型",
            selected = settings.usesAutoModel,
        ) {
            onSelectAuto()
            onDismiss()
        }
        ModelTier.entries.forEach { tier ->
            ModelMenuItem(
                title = tier.label,
                subtitle = tierMappingLabel(settings, tier),
                selected = active == tier,
            ) {
                onSelectTier(tier)
                onDismiss()
            }
        }
        ClaudeDivider(Modifier.padding(horizontal = 12.dp))
        DropdownMenuItem(
            text = {
                Text(
                    "管理模型档位",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accentInk,
                )
            },
            leadingIcon = {
                Icon(
                    AppIcons.Layers,
                    contentDescription = null,
                    tint = colors.accentInk,
                    modifier = Modifier.size(17.dp),
                )
            },
            onClick = {
                onDismiss()
                onManageTiers()
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ModelMenuItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) colors.accentInk else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = AppType.caption,
                    color = colors.stone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已选择",
                    tint = colors.accentInk,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else null,
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = colors.ink,
            trailingIconColor = colors.accentInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * Searchable model list reused by the default/small model rows and the four tier
 * mappings. Blank writes are meaningful for tiers (official default) and small model.
 *
 * This is a tall in-window panel (88% of the viewport) rather than a ModalBottomSheet:
 * Haze can only sample nodes in the same window, and the point of the panel is to blur
 * the settings page behind it. The scrim, the panel and the page share one HazeState.
 * The panel is liquid glass with a low paper tint so the page still shows through the
 * refraction; it enters on a spring and slides up from the bottom. The header carries
 * the H1, count and a 46dp close capsule; the list owns the weight, and the manual-input
 * block stays pinned above the navigation bar. [frostedState] may be null for previews,
 * in which case the panel and scrim fall back to translucent paper/canvas.
 */
@Composable
fun ModelListSheet(
    title: String,
    description: String?,
    models: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    emptyOption: String? = null,
    frostedState: HazeState? = null,
) {
    val colors = MaterialTheme.appColors
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var manual by remember(title, current) { mutableStateOf(current) }
    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    var closing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (reduceMotion) progress.snapTo(0f)
            else progress.animateTo(0f, tween(Motion.MEDIUM, easing = Motion.EaseChrome))
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (reduceMotion) progress.snapTo(1f)
        // Slightly under-damped so the tall panel settles with a soft overshoot instead
        // of arriving like a rigid drawer.
        else progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
    }
    BackHandler { close() }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val cardShape = RoundedCornerShape(topStart = Radii.xl, topEnd = Radii.xl)
        val screenHeightPx = with(density) { maxHeight.toPx() }
        GlassScrim(
            frostedState = frostedState,
            onTap = { close() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
            blurRadius = 26.dp,
            tintAlpha = 0.20f,
            dimAlpha = 0.10f,
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * 0.88f)
                .graphicsLayer {
                    alpha = progress.value.coerceIn(0f, 1f)
                    // A spring can overshoot past 1. Clamping the slide at its rest line
                    // keeps the square bottom edge on the navigation bar; letting the
                    // overshoot through would lift the sheet and reveal a scrim strip.
                    translationY = ((1f - progress.value) * screenHeightPx).coerceAtLeast(0f)
                }
                // The glass material owns the lift shadow; the old paper panel's extra
                // 20dp shadow would stack a second halo on top of it.
                .glass(
                    state = frostedState,
                    shape = cardShape,
                    tint = colors.paper.copy(alpha = 0.10f),
                )
                .navigationBarsPadding()
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            SheetHandle()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 14.dp, top = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f).padding(top = 6.dp)) {
                    Text(
                        title,
                        style = SettingsType.h1.copy(fontSize = 24.sp, lineHeight = 30.sp),
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description != null) {
                        Text(
                            description,
                            style = SettingsType.intro,
                            color = colors.stone,
                            modifier = Modifier.padding(top = 6.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        countLabel(models.size, filtered.size, query),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        color = colors.stone,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                // The panel is glass; sampling it would punch a blurry window through the
                // material, so the close button uses the translucent-paper fallback.
                GlassIconButton(
                    icon = AppIcons.Close,
                    label = "关闭",
                    onClick = { close() },
                    frostedState = null,
                    iconSize = 18.dp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SheetSearch(query) { query = it }
            }
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (emptyOption != null) {
                    item(key = "empty-option") {
                        ModelOptionRow(
                            title = emptyOption,
                            subtitle = "不指定模型",
                            selected = current.isBlank(),
                            badge = null,
                            icon = AppIcons.Layers,
                            onClick = {
                                onSelect("")
                                close()
                            },
                        )
                    }
                }
                items(filtered, key = { it }) { model ->
                    ModelOptionRow(
                        title = model,
                        subtitle = null,
                        selected = model.trim() == current.trim(),
                        badge = modelProviderBadge(model),
                        icon = null,
                        onClick = {
                            onSelect(model)
                            close()
                        },
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "empty-state") {
                        Text(
                            when {
                                models.isEmpty() ->
                                    "还没有拉取模型列表。回设置页点「拉取模型列表」，或在下方手动输入。"
                                else ->
                                    "没有匹配「${query.trim()}」的模型。清空搜索，或在下方手动输入。"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = colors.stone,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                ClaudeDivider(Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                ClaudeField(
                    label = "手动输入",
                    value = manual,
                    placeholder = "例如 claude-sonnet-5-5 或 auto/hy4free",
                    onChange = { manual = it },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "使用此模型",
                    onClick = {
                        onSelect(manual.trim())
                        close()
                    },
                    enabled = manual.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun countLabel(total: Int, matched: Int, query: String): String = when {
    total == 0 -> "尚未拉取模型"
    query.isBlank() -> "已拉取 $total 个模型"
    else -> "已拉取 $total 个模型 · 匹配 $matched"
}

/** Short provider badge for the round marker; keeps long prefixes out of the circle. */
internal fun modelProviderBadge(id: String): String {
    val prefix = id.substringBefore('/').ifBlank { id }
    return when {
        prefix.isBlank() -> "?"
        prefix.startsWith("auto", ignoreCase = true) -> "A"
        prefix.startsWith("claude", ignoreCase = true) -> "C"
        else -> prefix.take(2).uppercase()
    }
}

@Composable
private fun ModelOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    badge: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    val shape = CircleShape
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .pressFeedback(shape = shape)
            .clip(shape)
            .background(if (selected) colors.surface1 else colors.paper)
            .border(
                1.dp,
                if (selected) colors.ink else colors.sand.copy(alpha = 0.85f),
                shape,
            )
            .quietClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) colors.surface2 else colors.surface1)
                .border(1.dp, colors.sand.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.graphite,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    badge ?: "?",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (selected) colors.ink else colors.graphite,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.stone,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (selected) colors.ink else Color.Transparent)
                .border(1.dp, if (selected) colors.ink else colors.sand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已选择",
                    tint = colors.paper,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetSearch(value: String, onValueChange: (String) -> Unit) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.appColors.ink,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(Radii.smShape)
            .background(MaterialTheme.appColors.surface1)
            .border(1.dp, MaterialTheme.appColors.sand, Radii.smShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            null,
            tint = MaterialTheme.appColors.stone,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "搜索模型",
                    style = textStyle.copy(color = MaterialTheme.appColors.stone),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.appColors.orange),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                "清空",
                tint = MaterialTheme.appColors.stone,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .padding(11.dp),
            )
        }
    }
}
