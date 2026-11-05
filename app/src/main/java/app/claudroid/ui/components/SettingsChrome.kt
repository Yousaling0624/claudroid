package app.claudroid.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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

// Picker metrics. The width cap is deliberately below a phone row's width: the panel
// should read as a compact menu unfolding from the value, not as a full-width sheet.
private val PickerMaxWidth = 280.dp

/** LazyColumn inset (2×10) + option inset (2×12) + gap (12) + radio (22) + 4dp slack. */
private val PickerContentChrome = 80.dp

/**
 * Pull-to-dismiss tuning. 220dp is the rubber-band asymptote, not the dismiss point: the
 * panel may keep following the finger but can never be dragged off the anchor. A release
 * dismisses past 72dp of finger travel or a 900dp/s downward flick; the distance is
 * measured before damping so the threshold matches what the gesture reads as, while the
 * panel's own displacement stays compressed by the rubber band.
 */
private val PickerDragLimit = 220.dp
private val PickerDragDismissDistance = 72.dp
private val PickerDragDismissVelocity = 900.dp

/** Frosted body opacity for the picker only; every other glass surface keeps the preset. */
private const val PickerGlassAlpha = 0.55f

/**
 * The settings picker: an anchored liquid-glass list that unfolds out of the value
 * button that opened it.
 *
 * It must stay in the settings window — a Dialog/Popup/ModalBottomSheet would render in
 * its own window, where Haze cannot sample the page behind it. The screen root owns the
 * single instance and opens it through [LocalGlassPicker], so pages do not thread a
 * sheet parameter through every level.
 *
 * The panel is sized once from the longest option title (never narrower than the value
 * button and never wider than its row), then laid out over the button: right edges
 * aligned, top edge covering the button, flipping to a bottom-edge cover when the list
 * would run off-screen. The opening morph is pure graphicsLayer work. A round bubble
 * forms at the button, stretches toward the panel while a button-sized droplet splits
 * away and flows into it, and a second spring squares off the corners. The LazyColumn
 * is never re-laid out per frame, and closing runs the geometry back into the button.
 *
 * Once open, the panel can be pulled down to dismiss. The pull follows the finger with
 * rubber-band damping, stretches/squashes the glass and grows its corners; the list
 * keeps ownership of upward drags and of downward drags while it is scrolled, so normal
 * scrolling is untouched.
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
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val morph = remember { Animatable(0f) }
    val corners = remember { Animatable(0f) }
    val contentOut = remember { Animatable(1f) }
    // Pull-to-dismiss distance, in overlay pixels. A plain float state rather than an
    // Animatable: during the pull the panel has to track the finger in the very frame
    // the event arrives, which a suspend `snapTo` cannot guarantee from a pointer
    // coroutine. Only the release runs an animation, and it animates this state.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragLimitPx = with(density) { PickerDragLimit.toPx() }
    val dismissDistancePx = with(density) { PickerDragDismissDistance.toPx() }
    val dismissVelocityPx = with(density) { PickerDragDismissVelocity.toPx() }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var started by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var openingJob by remember { mutableStateOf<Job?>(null) }
    // The release animation owns `dragOffset` until it finishes; a new pull cancels it
    // so the spring-back can never fight the finger.
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val selectedIndex = remember(request) {
        request.options.indexOfFirst { it.selected }.coerceAtLeast(0)
    }
    // Always start at the top. Pinning the selected row to the first visible slot makes
    // `canScrollBackward` true even when the whole menu fits, which would hand every
    // downward pull to the list and leave pull-to-dismiss dead. A long list is scrolled
    // to the selection in `open()` only if that selection is not already visible.
    val listState = rememberLazyListState()

    // Menus are title-only, so the width can come from the longest title. Measuring with
    // the option rows' own type plus their fixed chrome makes the panel exactly wide
    // enough without a second layout pass that would make the list jump.
    val textMeasurer = rememberTextMeasurer()
    val contentWidthPx = remember(request, textMeasurer, density) {
        val widest = request.options.maxOfOrNull { option ->
            textMeasurer.measure(
                text = AnnotatedString(option.label),
                style = SettingsType.rowTitle,
                maxLines = 1,
                softWrap = false,
            ).size.width.toFloat()
        } ?: 0f
        widest + with(density) { PickerContentChrome.toPx() }
    }

    fun open() {
        if (started || closing) return
        started = true
        // Bring the selected row into view only when the list actually overflows and the
        // selection sits outside the visible window. A short menu that already fits stays
        // at scroll position 0, so a downward pull belongs to the panel; a long one opens
        // on the selected tier/model and can be scrolled back to the top like any other
        // list. The overflow check keeps a still-empty layout from triggering a scroll
        // that would immediately make `canScrollBackward` true and kill pull-to-dismiss.
        // `open` runs once, on the settled measurement, so user scrolling is never
        // overridden afterwards.
        scope.launch {
            val info = listState.layoutInfo
            val measured = info.visibleItemsInfo.isNotEmpty()
            val selectionVisible = info.visibleItemsInfo.any { it.index == selectedIndex }
            val overflows = info.visibleItemsInfo.size < info.totalItemsCount
            if (measured && !selectionVisible && overflows && selectedIndex > 0) {
                listState.scrollToItem(selectedIndex)
            }
        }
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
        val marginPx = with(density) { 12.dp.toPx() }
        val edgeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val topInsetPx = with(density) { edgeInsets.calculateTopPadding().toPx() } +
            with(density) { 8.dp.toPx() }
        val bottomInsetPx = with(density) { edgeInsets.calculateBottomPadding().toPx() } +
            with(density) { 8.dp.toPx() }
        val effectiveHeight = (viewportHeight - topInsetPx - bottomInsetPx).coerceAtLeast(0f)
        // A row can never be wider than the page; the request's measured row width is
        // the tighter cap when the page has its own margins.
        val maxWidthPx = minOf(
            with(density) { PickerMaxWidth.toPx() },
            request.rowWidth?.takeIf { it > 0f } ?: Float.MAX_VALUE,
        )

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

        val panelWidthPx = if (anchorRect != null) {
            fitPickerWidth(
                anchorWidth = anchorRect.width,
                contentWidth = contentWidthPx,
                maxWidth = maxWidthPx,
                viewportWidth = viewportWidth,
                margin = marginPx,
            )
        } else {
            // Previews have no button to cover; keep the same compact, centred width
            // instead of falling back to the old full-row panel.
            minOf(
                contentWidthPx,
                maxWidthPx,
                (viewportWidth - marginPx * 2f).coerceAtLeast(0f),
            ).coerceAtLeast(0f)
        }

        // The side space is known before the content is measured. Capping the panel at
        // the roomier side means the chosen direction always fits the measured height,
        // so the list needs one layout pass rather than shrinking after the fact. The
        // panel covers the button, so "down" starts at the button's top edge and "up"
        // ends at its bottom edge; there is no gap to subtract.
        val maxSidePx = if (anchorRect != null) {
            val below = effectiveHeight - marginPx - anchorRect.top
            val above = anchorRect.bottom - marginPx
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
                contentWidth = contentWidthPx,
                contentHeight = panelSize.height.toFloat(),
                maxWidth = maxWidthPx,
                margin = marginPx,
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
        // The platter underlay is the paper body behind the glass. The split droplet is
        // drawn above the glass (see below), so it needs its own, stronger colours:
        // paper-on-paper would vanish on the warm page in light mode.
        val platterFill = colors.paper.copy(alpha = 0.92f)
        val platterRim = if (colors.dark) {
            colors.ink.copy(alpha = 0.18f)
        } else {
            colors.sand.copy(alpha = 0.70f)
        }
        val dropletFill = if (colors.dark) {
            colors.paper.copy(alpha = 0.78f)
        } else {
            colors.surface1.copy(alpha = 0.92f)
        }
        val dropletRim = if (colors.dark) {
            colors.ink.copy(alpha = 0.20f)
        } else {
            colors.ink.copy(alpha = 0.12f)
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

        // Paper body behind the glass. Haze cannot refract an arbitrary path, so the
        // panel itself stays a rounded rect; this layer gives the growing platter its
        // paper body until the glass fades in.
        if (started) {
            Canvas(Modifier.fillMaxSize()) {
                val anchor = anchorOverlay ?: return@Canvas
                val height = panelSize.height.toFloat()
                if (height <= 0f) return@Canvas
                val card = PickerRect(
                    finalLeft,
                    finalTop + dragOffset,
                    panelSize.width.toFloat().coerceAtLeast(1f),
                    height,
                )
                val platter = platterMorph(anchor, card, morph.value, cardRadiusPx, corners.value)
                // Like the reference's source-colour fill: the paper silhouette sits
                // behind the glass and fades out as the platter settles, so the bubble
                // starts as paper and ends as glass. At the very start the glass layer
                // is still transparent, hence the full paper fill rather than a ring.
                val fillAlpha = (1f - morph.value).coerceIn(0f, 1f)
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
                drawPath(platterPath, platterFill.copy(alpha = platterFill.alpha * fillAlpha))
                drawPath(
                    platterPath,
                    platterRim.copy(alpha = platterRim.alpha * fillAlpha),
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
                    val drag = dragOffset
                    // A host without a row has no bubble to grow from; show the panel
                    // directly instead of inventing an anchor.
                    if (anchor == null) {
                        alpha = 1f
                        translationY = drag
                        return@graphicsLayer
                    }
                    val width = panelSize.width.toFloat().coerceAtLeast(1f)
                    // The dragged card is the resting card shifted down; the morph
                    // geometry (and therefore the translation) follows it, so the pull
                    // never adds a second offset on top of the platter frame.
                    val cardTop = finalTop + drag
                    val card = PickerRect(finalLeft, cardTop, width, height)
                    val frame = platterMorph(anchor, card, morph.value, cardRadiusPx, corners.value)
                    // Elastic deformation is pure graphicsLayer work: the panel is never
                    // re-laid out. Reduced motion keeps the drag itself (it is direct
                    // manipulation) but drops the stretch and corner swell.
                    val deformation = if (reduceMotion) {
                        PickerDragDeformation(0f, 0f, 0f)
                    } else {
                        pickerDragDeformation(drag, dragLimitPx)
                    }
                    val sx = ((frame.width / width).coerceAtLeast(0.001f)) *
                        (1f - deformation.squash)
                    val sy = ((frame.height / height).coerceAtLeast(0.001f)) *
                        (1f + deformation.stretch)

                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    scaleX = sx
                    scaleY = sy
                    translationX = frame.centerX - (finalLeft + width / 2f)
                    translationY = frame.centerY - (finalTop + height / 2f)
                    // The layer shape lives in local pixels and is then scaled, so the
                    // radius is divided back out to keep the drawn corner round. The
                    // corner grows with the pull, so the stretched glass stays soft.
                    val grownRadius = frame.cornerRadius * (1f + deformation.cornerGrowth)
                    shape = MorphShape(
                        radiusX = (grownRadius / sx).coerceAtLeast(0f),
                        radiusY = (grownRadius / sy).coerceAtLeast(0f),
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
                    // Only the picker runs thinner: a menu is read through, while every
                    // other glass surface keeps the preset body.
                    alpha = PickerGlassAlpha,
                )
                // Pull-to-dismiss. Listens in the Initial pass so it can own a downward
                // drag at the top of the list before the LazyColumn starts scrolling;
                // upward drags and drags while the list is scrolled are left to the
                // list untouched. Taps are never consumed, so option clicks still land.
                //
                // The handler is installed on the first composition and `started` is
                // deliberately not a pointerInput key: the gate is read per gesture, so
                // the morph finishing can never leave a stale, dead pointer coroutine
                // behind.
                .pointerInput(closing, reduceMotion, dragLimitPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        // Only pull after the opening morph has settled. A spring may end
                        // a hair short of 1f, so compare with a tolerance rather than an
                        // exact float; `isRunning` covers the frames before that.
                        if (!started || closing || morph.isRunning || morph.value < 0.98f) {
                            return@awaitEachGesture
                        }
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var claimed = false
                        var rawTravel = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            rawTravel = change.position.y - down.position.y
                            if (!claimed) {
                                if (abs(rawTravel) <= touchSlop) continue
                                // Down at the top belongs to the panel; everything else
                                // (up, or down while the list can scroll back) belongs
                                // to the list and must not be consumed here.
                                if (rawTravel > 0f && !listState.canScrollBackward) {
                                    claimed = true
                                    settleJob?.cancel()
                                    settleJob = null
                                } else {
                                    break
                                }
                            }
                            change.consume()
                            // Direct manipulation: write the state in the event frame so
                            // the glass moves with the finger, with no coroutine hop.
                            dragOffset = pickerDragOffset(rawTravel, dragLimitPx)
                        }
                        if (!claimed) return@awaitEachGesture
                        // Dismiss on the finger's own travel, not the damped visual
                        // offset: the rubber band always shrinks the distance the panel
                        // moves, so a "72dp pull" would otherwise need ~107dp of drag
                        // (72 * 220 / (220 - 72) with the current rubber band).
                        val velocity = tracker.calculateVelocity().y
                        val dismiss = pickerShouldDismiss(
                            offset = rawTravel,
                            velocityY = velocity,
                            distanceThreshold = dismissDistancePx,
                            velocityThreshold = dismissVelocityPx,
                        )
                        settleJob = scope.launch {
                            if (reduceMotion) {
                                dragOffset = 0f
                            } else {
                                animate(
                                    initialValue = dragOffset,
                                    targetValue = 0f,
                                    // A dismissed pull must retract into the anchor
                                    // without overshooting; a kept pull inherits the
                                    // finger's speed, capped so a flick cannot throw
                                    // the panel past its anchor.
                                    initialVelocity = if (dismiss) {
                                        0f
                                    } else {
                                        velocity.coerceIn(0f, 2400f)
                                    },
                                    animationSpec = if (dismiss) {
                                        Motion.PickerRetract
                                    } else {
                                        Motion.PickerDragSettle
                                    },
                                ) { value, _ -> dragOffset = value }
                            }
                        }
                        if (dismiss) close()
                    }
                }
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
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    state = listState,
                    // Menus carry no description line, so the list owns the whole panel
                    // with one even inset on every side.
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 8.dp,
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

        // The split droplet and its goo neck sit above the glass: the glass tint is too
        // close to paper for a paper-on-paper droplet to read in light mode. The layer
        // fades with how much of the droplet the platter has swallowed, so it can never
        // sit on the finished list. The panel's taps still land: a Canvas without a
        // pointerInput does not consume them.
        if (started) {
            Canvas(Modifier.fillMaxSize()) {
                val anchor = anchorOverlay ?: return@Canvas
                val height = panelSize.height.toFloat()
                if (height <= 0f) return@Canvas
                val card = PickerRect(
                    finalLeft,
                    finalTop + dragOffset,
                    panelSize.width.toFloat().coerceAtLeast(1f),
                    height,
                )
                val platter = platterMorph(anchor, card, morph.value, cardRadiusPx, corners.value)
                val droplet = dropletMorph(anchor, card, morph.value, blendPx, rimDepthPx)
                val platterRect = PickerRect(platter.left, platter.top, platter.width, platter.height)
                val dropletRect = PickerRect(droplet.left, droplet.top, droplet.width, droplet.height)
                val absorption = dropletAbsorption(
                    platterRect,
                    platter.cornerRadius,
                    dropletRect,
                    blendPx,
                )
                val alpha = (
                    1f - (absorption - Motion.PICKER_DROPLET_FADE_START) /
                        (Motion.PICKER_DROPLET_FADE_END - Motion.PICKER_DROPLET_FADE_START)
                    ).coerceIn(0f, 1f)
                if (alpha <= 0.01f) return@Canvas
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
                // The fan starts at the droplet centre and stops where the smooth-min
                // field crosses zero, so the neck only paints while the two silhouettes
                // are genuinely apart. The union keeps the neck from cutting the capsule.
                val union = Path().apply { addPath(dropletPath) }
                val bridge = gooBridge(platterRect, platter.cornerRadius, dropletRect, blendPx)
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
                // While the platter is still inside the droplet the whole bubble is
                // painted. Once it pokes out, clipping the droplet against the platter
                // removes the internal capsule edge, so the platter reads as swallowing
                // the bubble instead of two outlines crossing.
                if (platterPokesOut(platterRect, dropletRect)) {
                    clipPath(platterPath, ClipOp.Difference) {
                        drawPath(union, dropletFill.copy(alpha = dropletFill.alpha * alpha))
                        drawPath(
                            union,
                            dropletRim.copy(alpha = dropletRim.alpha * alpha),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                } else {
                    drawPath(union, dropletFill.copy(alpha = dropletFill.alpha * alpha))
                    drawPath(
                        union,
                        dropletRim.copy(alpha = dropletRim.alpha * alpha),
                        style = Stroke(width = 1.dp.toPx()),
                    )
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
        Text(
            option.label,
            style = SettingsType.rowTitle,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
