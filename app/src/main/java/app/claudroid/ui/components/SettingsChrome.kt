package app.claudroid.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.SettingsType
import app.claudroid.ui.theme.appColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings-only chrome. These components deliberately sit apart from the generic
 * paper controls: the floating bar, the big page header and the selectable option
 * rows are the new three-level IA's visual vocabulary, not things the workspace
 * needs.
 */

/** Large page title plus one explanatory sentence, directly under the floating bar. */
@Composable
fun SettingsPageHeader(
    title: String,
    intro: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(title, style = SettingsType.h1, color = colors.ink)
        if (intro != null) {
            Text(
                intro,
                style = SettingsType.intro,
                color = colors.stone,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Floating settings chrome: only the round back capsule is glass. The page title is the
 * H1 in the scrolling content, not a title pill — glass is reserved for controls, while
 * headings stay plain paper text.
 */
@Composable
fun SettingsTopBar(
    backLabel: String,
    onBack: () -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = AppIcons.Back,
            label = backLabel,
            onClick = onBack,
            frostedState = frostedState,
        )
    }
}

/**
 * Root category row. Each category is its own capsule instead of a divider-separated
 * line inside one big card: the larger gaps make the root read as a map of settings
 * rather than a form.
 */
@Composable
fun SettingsCategoryCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressFeedback(shape = CircleShape)
            .clip(CircleShape)
            .background(colors.paper)
            .border(1.dp, colors.sand.copy(alpha = 0.85f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(Radii.xsShape)
                .background(colors.surface1)
                .border(1.dp, colors.sand.copy(alpha = 0.55f), Radii.xsShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.graphite, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = SettingsType.rowTitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                color = colors.stone,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.stone,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Full-width selectable option used by third-level pages (permission mode). Selection
 * uses the quiet ink/surface language instead of flooding the page with terracotta;
 * the check mark carries the state for accessibility as a radio button.
 */
@Composable
fun SettingsOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (selected) colors.surface1 else Color.Transparent,
        animationSpec = tween(if (reduceMotion) 0 else Motion.FAST, easing = Motion.EaseChrome),
        label = "option-bg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .pressFeedback(shape = Radii.mdShape)
            .clip(Radii.mdShape)
            .background(background)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SettingsType.rowTitle, color = colors.ink)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = colors.stone,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .size(22.dp)
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
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * In-window replacement for [ClaudeDialog] in settings. A Dialog is a separate window,
 * so Haze cannot sample the page behind it; this overlay stays in the settings window
 * and blurs the real background. Workspace keeps the regular paper dialog.
 */
@Composable
fun SettingsGlassDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.appColors
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

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
        else progress.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.EaseOut))
    }
    BackHandler { close() }

    Box(modifier.fillMaxSize()) {
        GlassScrim(
            frostedState = frostedState,
            onTap = { close() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value },
            blurRadius = 24.dp,
            tintAlpha = 0.20f,
            dimAlpha = 0.12f,
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    alpha = progress.value
                    val scale = 0.96f + 0.04f * progress.value
                    scaleX = scale
                    scaleY = scale
                }
                .glass(
                    state = frostedState,
                    shape = Radii.xlShape,
                    tint = colors.paper.copy(alpha = 0.12f),
                )
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(24.dp),
        ) {
            Text(
                title,
                style = SettingsType.h2,
                color = colors.ink,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.graphite,
            )
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

/**
 * The settings picker: an anchored liquid-glass list that grows out of the row that
 * opened it.
 *
 * It must stay in the settings window — a Dialog/Popup/ModalBottomSheet would render in
 * its own window, where Haze cannot sample the page behind it. The screen root owns the
 * single instance and opens it through [LocalGlassPicker], so pages do not thread a
 * sheet parameter through every level.
 *
 * The panel is laid out once at its final width and capped height; the opening morph is
 * pure graphicsLayer work. A round bubble forms at the tapped row, stretches toward the
 * panel while a row-sized droplet splits away and flows into it, and a second spring
 * squares off the corners once the bubble has left the row. The LazyColumn is never
 * re-laid out per frame, and closing runs the geometry back into the row without a
 * wobble.
 */
@Composable
fun GlassOptionSheet(
    request: GlassPickerRequest,
    onDismiss: () -> Unit,
    frostedState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val reduceMotion = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val morph = remember { Animatable(0f) }
    val corners = remember { Animatable(0f) }
    val contentOut = remember { Animatable(1f) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var started by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var openingJob by remember { mutableStateOf<Job?>(null) }
    val selectedIndex = remember(request) {
        request.options.indexOfFirst { it.selected }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    fun open() {
        if (started || closing) return
        started = true
        // The panel is first measured before the anchored cap is known, sometimes with a
        // viewport tall enough to fit everything below the selected row. LazyColumn then
        // clamps the initial index to the end of the content, and that offset survives
        // the shorter final viewport as a sliver of the previous row above the selection.
        // Pin the selected row once the panel has its final height; `open` only runs on
        // that settled measurement and exactly once, so user scrolling is left alone.
        scope.launch { listState.scrollToItem(selectedIndex) }
        if (reduceMotion) {
            openingJob = scope.launch {
                morph.snapTo(1f)
                corners.snapTo(1f)
                contentOut.snapTo(1f)
            }
        } else {
            // Launch on the composition scope, never inside the trigger effect: the
            // panel often measures twice (fallback width/height, then the anchored
            // cap), which restarts that effect. Animations owned by the effect would
            // be cancelled while `started` stays true, leaving the picker invisible.
            // The job is held so a close can cancel the delayed corner spring cleanly.
            openingJob = scope.launch {
                coroutineScope {
                    launch { morph.animateTo(1f, Motion.PickerMorph) }
                    launch {
                        delay(Motion.PICKER_CORNERS_DELAY)
                        corners.animateTo(1f, Motion.PickerCorners)
                    }
                }
            }
        }
    }

    fun close() {
        if (closing) return
        closing = true
        openingJob?.cancel()
        openingJob = null
        scope.launch {
            if (reduceMotion || !started) {
                morph.snapTo(0f)
                corners.snapTo(0f)
                contentOut.snapTo(0f)
            } else {
                // The list leaves before the drop starts moving; otherwise the text
                // would still be legible while it is squeezed back into the row.
                contentOut.animateTo(0f, tween(Motion.PICKER_CONTENT_OUT, easing = Motion.EaseChrome))
                coroutineScope {
                    launch { morph.animateTo(0f, Motion.PickerRetract) }
                    launch { corners.animateTo(0f, Motion.PickerRetract) }
                }
            }
            onDismiss()
        }
    }

    BackHandler { close() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInWindow() },
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val gapPx = with(density) { 6.dp.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        val edgeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val topInsetPx = with(density) { edgeInsets.calculateTopPadding().toPx() } +
            with(density) { 8.dp.toPx() }
        val bottomInsetPx = with(density) { edgeInsets.calculateBottomPadding().toPx() } +
            with(density) { 8.dp.toPx() }
        val effectiveHeight = (viewportHeight - topInsetPx - bottomInsetPx).coerceAtLeast(0f)
        val maxWidthPx = with(density) { 420.dp.toPx() }

        // Both rects are in window coordinates, so the overlay's own origin removes any
        // status-bar/window offset; the safe-area inset is subtracted separately so the
        // placement math can treat the usable viewport as a plain rectangle.
        val root = rootBounds
        val anchor = request.anchor
        val anchorRect = if (root != null && anchor != null) {
            Rect(
                left = anchor.left - root.left,
                top = anchor.top - root.top - topInsetPx,
                right = anchor.right - root.left,
                bottom = anchor.bottom - root.top - topInsetPx,
            )
        } else {
            null
        }

        val panelWidthPx = minOf(
            anchorRect?.width ?: maxWidthPx,
            maxWidthPx,
            (viewportWidth - marginPx * 2f).coerceAtLeast(0f),
        )

        // The side space is known before the content is measured. Capping the panel at
        // the roomier side means the chosen direction always fits the measured height,
        // so the list needs one layout pass rather than shrinking after the fact.
        val maxSidePx = if (anchorRect != null) {
            val below = effectiveHeight - marginPx - (anchorRect.bottom + gapPx)
            val above = anchorRect.top - gapPx - marginPx
            maxOf(below, above).coerceAtLeast(0f)
        } else {
            effectiveHeight
        }
        val maxPanelHeightPx = minOf(effectiveHeight * 0.6f, maxSidePx).coerceAtLeast(0f)

        val placement = when {
            panelSize.height <= 0 -> null
            anchorRect != null -> placePicker(
                anchor = PickerRect(
                    left = anchorRect.left,
                    top = anchorRect.top,
                    width = anchorRect.width,
                    height = anchorRect.height,
                ),
                viewportWidth = viewportWidth,
                viewportHeight = effectiveHeight,
                contentHeight = panelSize.height.toFloat(),
                gap = gapPx,
                margin = marginPx,
                maxWidth = maxWidthPx,
                maxHeightFraction = 0.6f,
            )
            // Previews or a host without a row: keep the picker usable with a plain
            // centred fade, but never grow it out of a fake rect.
            else -> PickerPlacement(
                left = (viewportWidth - panelWidthPx) / 2f,
                top = (effectiveHeight - panelSize.height) / 2f,
                width = panelWidthPx,
                height = panelSize.height.toFloat(),
                expandsUp = false,
            )
        }

        // Until the panel is measured it still has to be in the tree, so park it at the
        // anchor while fully transparent. finalTop is in overlay coordinates.
        val finalLeft = placement?.left ?: anchorRect?.left ?: ((viewportWidth - panelWidthPx) / 2f)
        val finalTop = placement?.let { it.top + topInsetPx }
            ?: anchorRect?.let { it.top + topInsetPx }
            ?: ((viewportHeight - maxPanelHeightPx) / 2f)

        // Placement works in the safe-area viewport; the morph draws in overlay
        // coordinates, so the anchor and card need the top inset added back once.
        val anchorOverlay = anchorRect?.let {
            PickerRect(it.left, it.top + topInsetPx, it.width, it.height)
        }
        val cardRadiusPx = with(density) { Radii.menu.toPx() }
        val blendPx = with(density) { 28.dp.toPx() }
        val rimDepthPx = with(density) { 8.dp.toPx() }
        val contentBlurPx = with(density) { Motion.PICKER_CONTENT_BLUR.dp.toPx() }
        val dropletFill = colors.paper.copy(alpha = 0.92f)
        val dropletRim = if (colors.dark) {
            colors.ink.copy(alpha = 0.18f)
        } else {
            colors.sand.copy(alpha = 0.70f)
        }

        // Trigger only; the animations run on the composition scope, so the second
        // measurement pass can restart this effect without cancelling them.
        LaunchedEffect(panelSize, placement, rootBounds, reduceMotion) {
            if (rootBounds == null || placement == null) return@LaunchedEffect
            if (abs(panelSize.width - panelWidthPx) > 1f) return@LaunchedEffect
            if (panelSize.height > placement.height + 1f) return@LaunchedEffect
            open()
        }

        // Dim only, no blur: the page should stay legible behind the list so the panel
        // reads as lying on top of it, not as a separate page. Dark mode uses canvas
        // rather than ink (which is near-white there) for the same reason as GlassScrim.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = morph.value.coerceIn(0f, 1f) }
                .background(
                    if (colors.dark) colors.canvas.copy(alpha = 0.30f)
                    else colors.ink.copy(alpha = 0.10f)
                )
                .pointerInput(Unit) { detectTapGestures { close() } },
        )

        // The droplet and bridge are paper silhouettes behind the glass: Haze cannot
        // refract an arbitrary path, so the panel stays a rounded rect and this layer
        // supplies the liquid outline while the two silhouettes are still joined.
        if (started) {
            Canvas(Modifier.fillMaxSize()) {
                val anchor = anchorOverlay ?: return@Canvas
                val height = panelSize.height.toFloat()
                if (height <= 0f) return@Canvas
                val card = PickerRect(
                    finalLeft,
                    finalTop,
                    panelSize.width.toFloat().coerceAtLeast(1f),
                    height,
                )
                val platter = platterMorph(anchor, card, morph.value, cardRadiusPx, corners.value)
                val droplet = dropletMorph(anchor, card, morph.value, blendPx, rimDepthPx)
                // Like the reference's source-colour fill: the paper silhouette sits
                // behind the glass and fades out as the platter settles, so the bubble
                // starts as paper and ends as glass. At the very start the glass layer
                // is still transparent, hence the full paper fill rather than a ring.
                val settle = morph.value.coerceIn(0f, 1f)
                val fillAlpha = 1f - settle
                if (fillAlpha <= 0.01f) return@Canvas
                val platterPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            platter.left,
                            platter.top,
                            platter.right,
                            platter.bottom,
                            CornerRadius(platter.cornerRadius),
                        )
                    )
                }
                val dropletPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            droplet.left,
                            droplet.top,
                            droplet.right,
                            droplet.bottom,
                            CornerRadius(droplet.cornerRadius),
                        )
                    )
                }
                // The goo silhouette is the union of the platter, the droplet and the
                // prism_ui bridge fan. The platter is the glass panel itself, so the fill
                // under it reads through the tint and fades with [settle].
                val union = Path()
                if (!union.op(dropletPath, platterPath, PathOperation.Union)) {
                    union.reset()
                    union.addPath(dropletPath)
                }
                val bridge = gooBridge(
                    PickerRect(platter.left, platter.top, platter.width, platter.height),
                    platter.cornerRadius,
                    PickerRect(droplet.left, droplet.top, droplet.width, droplet.height),
                    blendPx,
                )
                if (bridge != null) {
                    val fan = Path()
                    for (polygon in bridge) {
                        if (polygon.size < 3) continue
                        fan.moveTo(polygon[0].x, polygon[0].y)
                        for (index in 1 until polygon.size) {
                            fan.lineTo(polygon[index].x, polygon[index].y)
                        }
                        fan.close()
                    }
                    val merged = Path()
                    if (merged.op(union, fan, PathOperation.Union)) {
                        union.reset()
                        union.addPath(merged)
                    }
                }
                drawPath(union, dropletFill.copy(alpha = dropletFill.alpha * fillAlpha))
                drawPath(
                    union,
                    dropletRim.copy(alpha = dropletRim.alpha * fillAlpha),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(finalLeft.roundToInt(), finalTop.roundToInt()) }
                .width(with(density) { panelWidthPx.toDp() })
                .heightIn(max = with(density) { maxPanelHeightPx.toDp() })
                .onSizeChanged { panelSize = it }
                .graphicsLayer {
                    val height = panelSize.height.toFloat()
                    val anchor = anchorOverlay
                    if (!started || height <= 0f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    // A host without a row has no bubble to grow from; show the panel
                    // directly instead of inventing an anchor.
                    if (anchor == null) {
                        alpha = 1f
                        return@graphicsLayer
                    }
                    val width = panelSize.width.toFloat().coerceAtLeast(1f)
                    val card = PickerRect(finalLeft, finalTop, width, height)
                    val frame = platterMorph(anchor, card, morph.value, cardRadiusPx, corners.value)
                    val sx = (frame.width / width).coerceAtLeast(0.001f)
                    val sy = (frame.height / height).coerceAtLeast(0.001f)

                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    scaleX = sx
                    scaleY = sy
                    translationX = frame.centerX - (finalLeft + width / 2f)
                    translationY = frame.centerY - (finalTop + height / 2f)
                    // The layer shape lives in local pixels and is then scaled, so the
                    // radius is divided back out to keep the drawn corner round.
                    shape = MorphShape(
                        radiusX = (frame.cornerRadius / sx).coerceAtLeast(0f),
                        radiusY = (frame.cornerRadius / sy).coerceAtLeast(0f),
                    )
                    clip = true
                    // The first bubble is the paper droplet; the glass material fades
                    // in as the platter outgrows it. Otherwise the tiny glass platter
                    // reads as a bright core inside the larger paper capsule.
                    alpha = ((morph.value - Motion.PICKER_GLASS_IN) /
                        (Motion.PICKER_GLASS_FULL - Motion.PICKER_GLASS_IN)).coerceIn(0f, 1f)
                }
                // The shared glass material already owns the lift shadow; adding a
                // second one here would stack two dark halos around the panel.
                .glass(
                    state = frostedState,
                    shape = Radii.menuShape,
                    tint = colors.paper.copy(alpha = 0.10f),
                )
                // The panel is glass itself; taps inside must not fall through to the
                // dim layer and close it while the user is choosing.
                .pointerInput(Unit) { detectTapGestures { } }
                // The page header is gone, so the title still needs a home for TalkBack.
                .semantics { paneTitle = request.title },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // On close the content leaves first, while the glass is still
                        // at its final size; on open it rides the bubble's progress so
                        // the options only become legible once the platter has formed.
                        val reveal = if (closing) contentOut.value else morph.value.coerceIn(0f, 1f)
                        alpha = reveal
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val blur = contentBlurPx * (1f - reveal)
                            renderEffect = if (blur > 0.1f) {
                                BlurEffect(
                                    radiusX = blur,
                                    radiusY = blur,
                                    edgeTreatment = TileMode.Clamp,
                                )
                            } else {
                                null
                            }
                        }
                    },
            ) {
                if (request.description != null) {
                    Text(
                        request.description,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = colors.stone,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = if (request.description == null) 8.dp else 4.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(request.options) { option ->
                        GlassOptionRow(option) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Commit before the retract starts, so the new value is
                            // already visible on the page behind the moving glass.
                            option.onSelect()
                            close()
                        }
                    }
                }
            }
        }
    }
}

/**
 * A rounded rect with independent x/y radii. The morph layer is scaled unevenly, and
 * [androidx.compose.foundation.shape.RoundedCornerShape] only carries one radius per
 * corner, which would draw an ellipse; this keeps the on-screen corner circular.
 */
private data class MorphShape(val radiusX: Float, val radiusY: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(
        RoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            cornerRadius = CornerRadius(radiusX, radiusY),
        )
    )
}

/** 52dp radio row; selected state is carried by the ink check, not a terracotta wash. */
@Composable
private fun GlassOptionRow(option: GlassOption, onClick: () -> Unit) {
    val colors = MaterialTheme.appColors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressFeedback(shape = Radii.mdShape)
            .clip(Radii.mdShape)
            .background(
                if (option.selected) colors.surface1.copy(alpha = 0.80f) else Color.Transparent
            )
            .selectable(
                selected = option.selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = SettingsType.rowTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.subtitle != null) {
                Text(
                    option.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = colors.stone,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (option.selected) colors.ink else Color.Transparent)
                .border(1.dp, if (option.selected) colors.ink else colors.sand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (option.selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已选择",
                    tint = colors.paper,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
