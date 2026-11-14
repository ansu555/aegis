package app.tapvault

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- glass edges

/** The glass edge: bright where the light hits (top-left), fading round the shape. A tinted [stroke] draws a plain edge instead. */
fun Modifier.glassRim(radius: Dp, stroke: Color = Tv.Stroke, shape: Shape = RoundedCornerShape(radius)): Modifier =
    if (stroke != Tv.Stroke) border(1.dp, stroke, shape)
    else this
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.045f), 0.45f to Color.Transparent), shape)
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.10f))), shape)

// ---------------------------------------------------------------- backdrop blur (floating chrome)

/** The blur source for floating glass: the screen content. Null in previews and before the app sets it. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Frosted chrome: blurs the screen content behind it (Android 12+); a dense tint on older phones.
 * [mask] fades the whole effect (blur and tint) out, so an edge can melt into the screen instead of ending in a line.
 */
@Composable
fun Modifier.frosted(shape: Shape, tint: Color = Color(0xFF141417), strength: Float = 0.62f, mask: Brush? = null): Modifier {
    val haze = LocalHaze.current ?: return background(tint.copy(alpha = 0.96f), shape)
    return clip(shape).hazeEffect(
        haze,
        HazeStyle(
            backgroundColor = Tv.Bg,
            tint = HazeTint(tint.copy(alpha = strength)),
            blurRadius = 26.dp,
            noiseFactor = 0.06f,
            fallbackTint = HazeTint(tint.copy(alpha = 0.95f)),
        ),
    ) { if (mask != null) this.mask = mask }
}

// ---------------------------------------------------------------- pop-up glass

/** The one glass every pop-up is made of (welcome menus, sheets, dialogs, the card tap): the same tint, frost, scrim and corners. */
object PopupGlass {
    val Tint = Color(0xFF0A0A0C)
    const val STRENGTH = 0.82f
    const val SCRIM = 0.6f
    val Radius = 36.dp
    /** Gap between a bottom pop-up and the screen's edges. */
    val Margin = 12.dp
}

/** A pop-up's body: soft shadow, frosted glass in [PopupGlass]'s tint, the lit glass rim. */
@Composable
fun Modifier.popupGlass(shape: Shape = RoundedCornerShape(PopupGlass.Radius), radius: Dp = PopupGlass.Radius): Modifier =
    shadow(30.dp, shape).frosted(shape, tint = PopupGlass.Tint, strength = PopupGlass.STRENGTH).glassRim(radius, shape = shape)

/**
 * Where sheets and dialogs are drawn: above the blur source (so their glass
 * frosts the screen behind them, like the welcome menus) instead of in a
 * window of their own, which can't blur what's under it.
 */
class OverlayHost {
    internal val entries = androidx.compose.runtime.mutableStateListOf<OverlayEntry>()
}

internal class OverlayEntry(val center: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var onDismiss by mutableStateOf(onDismiss)
    var content by mutableStateOf(content)
    /** The caller let go of it: it animates out, then leaves the host. */
    var leaving by mutableStateOf(false)
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * Puts [content] in the [OverlayHost] while this is composed: a bottom sheet,
 * or a centred dialog when [center]. Leaving composition animates it out.
 * Without a host (previews) the content is drawn in place.
 */
@Composable
fun Overlay(center: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) { Column(content = content); return }
    val entry = remember { OverlayEntry(center, onDismiss, content) }
    androidx.compose.runtime.SideEffect { entry.onDismiss = onDismiss; entry.content = content }
    androidx.compose.runtime.DisposableEffect(host) {
        entry.leaving = false
        if (entry !in host.entries) host.entries.add(entry)
        onDispose { entry.leaving = true }
    }
}

/** Draws the host's pop-ups, oldest at the bottom. */
@Composable
fun OverlayLayer(host: OverlayHost) {
    host.entries.forEach { e -> androidx.compose.runtime.key(e) { OverlayFrame(host, e) } }
}

@Composable
private fun OverlayFrame(host: OverlayHost, e: OverlayEntry) {
    val appear = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(e.leaving) {
        if (e.leaving) { appear.animateTo(0f, tween(180)); host.entries.remove(e) }
        else appear.animateTo(1f, if (e.center) spring(dampingRatio = 0.72f, stiffness = 420f) else spring(dampingRatio = 0.86f, stiffness = 420f))
    }
    val dismiss = { if (!e.leaving) e.onDismiss() }
    androidx.activity.compose.BackHandler(enabled = !e.leaving) { dismiss() }
    val swallow = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        // the scrim: tapping it closes the pop-up
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = PopupGlass.SCRIM))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, enabled = !e.leaving) { dismiss() },
        )
        if (e.center) {
            val shape = RoundedCornerShape(32.dp)
            Column(
                Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                    .padding(horizontal = 26.dp).fillMaxWidth()
                    .graphicsLayer { val s = 0.9f + 0.1f * appear.value; scaleX = s; scaleY = s; alpha = appear.value.coerceIn(0f, 1f) }
                    .popupGlass(shape, 32.dp)
                    .clickable(swallow, indication = null, enabled = !e.leaving) {}
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = e.content,
            )
        } else {
            val shape = RoundedCornerShape(PopupGlass.Radius)
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                    .padding(start = PopupGlass.Margin, end = PopupGlass.Margin, top = 28.dp, bottom = PopupGlass.Margin)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = (1f - appear.value) * (size.height + 40.dp.toPx()) + drag.value
                        alpha = appear.value.coerceIn(0f, 1f)
                    }
                    .popupGlass(shape)
                    .clickable(swallow, indication = null, enabled = !e.leaving) {},
            ) {
                // the handle: drag it down to close
                Box(
                    Modifier.fillMaxWidth().height(30.dp).draggable(
                        rememberDraggableState { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        enabled = !e.leaving,
                        onDragStopped = { v ->
                            if (drag.value > with(density) { 110.dp.toPx() } || v > 1800f) dismiss()
                            else drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 420f))
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(40.dp, 4.dp).background(Tv.StrokeStrong, RoundedCornerShape(2.dp))) }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp),
                    content = e.content,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- liquid glass tabs

/**
 * A row of tabs in the tab bar's liquid glass: a frosted track and the
 * bubble, which springs (stretching as it moves) to the chosen tab; dragging
 * along the track slides it. On Android 13+ the bubble is a lens over the
 * labels, as in [GlassTabBar]. Every tab is the same width.
 */
@Composable
fun GlassTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, height: Dp = 46.dp, onSelect: (Int) -> Unit) {
    val n = labels.size.coerceAtLeast(1)
    val pos = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val settle = spring<Float>(dampingRatio = 0.62f, stiffness = 340f)
    LaunchedEffect(selected) { if (!dragging) pos.animateTo(selected.toFloat(), settle) }
    var widthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val pad = 4.dp
    val padPx = with(density) { pad.toPx() }
    val itemPx = ((widthPx - padPx * 2) / n).coerceAtLeast(0f)
    val shape = RoundedCornerShape(50)
    val lens = remember { createLiquidLens() }
    fun stretchX() = 1f + (abs(pos.velocity) * 0.045f).coerceAtMost(0.22f)
    fun stretchY() = 1f - (abs(pos.velocity) * 0.02f).coerceAtMost(0.10f)

    Box(modifier.fillMaxWidth().height(height).onSizeChanged { widthPx = it.width.toFloat() }) {
        Box(
            Modifier.matchParentSize().graphicsLayer {
                if (lens != null && itemPx > 0f && android.os.Build.VERSION.SDK_INT >= 33) {
                    renderEffect = lens.effect(
                        center = Offset(padPx + pos.value * itemPx + itemPx / 2, size.height / 2),
                        half = Offset(itemPx / 2 * stretchX(), (size.height / 2 - padPx) * stretchY()),
                        edge = 12.dp.toPx(),
                    )
                }
            },
        ) {
            Box(Modifier.matchParentSize().frosted(shape, strength = 0.42f).background(Color.White.copy(alpha = 0.05f), shape).glassRim(height / 2, shape = shape))
            Row(
                Modifier.matchParentSize().padding(horizontal = pad).pointerInput(n, itemPx) {
                    if (itemPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            val target = pos.value.roundToInt().coerceIn(0, n - 1)
                            scope.launch { pos.animateTo(target.toFloat(), settle) }
                            if (target != selected) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(target) }
                        },
                        onDragCancel = { dragging = false; scope.launch { pos.animateTo(selected.toFloat(), settle) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { pos.snapTo((pos.value + dx / itemPx).coerceIn(-0.15f, n - 1 + 0.15f)) }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { i, label ->
                    val sel = i == selected
                    val tint by animateColorAsState(if (sel) Tv.Text else Tv.Text2, label = "seg")
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(shape)
                            .pressable(haptic = true) { if (!sel) onSelect(i) }
                            .semantics { contentDescription = label; role = Role.Tab; this.selected = sel },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = Type.CaptionStrong.copy(fontSize = 14.sp), color = tint, maxLines = 1) }
                }
            }
        }
        if (itemPx > 0f) Box(
            Modifier.align(Alignment.CenterStart).padding(start = pad)
                .size(with(density) { itemPx.toDp() }, height - pad * 2)
                .graphicsLayer { translationX = pos.value * itemPx; scaleX = stretchX(); scaleY = stretchY() }
                .liquidBubble(),
        )
    }
}

/**
 * Tabs for more than fit across: plain labels with no track, and the tab bar's liquid bubble
 * behind the chosen one. The bubble springs to the chosen tab's place and width, the row
 * scrolls the choice into view, and the ends fade out softly instead of cutting a label off.
 */
@Composable
fun GlassScrollTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, height: Dp = 42.dp, onSelect: (Int) -> Unit) {
    val lefts = remember(labels.size) { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(labels.size) { add(0f) } } }
    val widths = remember(labels.size) { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(labels.size) { add(0f) } } }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()
    val tx = lefts.getOrElse(selected) { 0f }
    val tw = widths.getOrElse(selected) { 0f }
    LaunchedEffect(selected, tx, tw) {
        if (tw <= 0f) return@LaunchedEffect
        if (viewport > 0) launch { scroll.animateScrollTo((tx + tw / 2 - viewport / 2f).roundToInt().coerceIn(0, scroll.maxValue)) }
        if (!placed) { x.snapTo(tx); w.snapTo(tw); placed = true; return@LaunchedEffect }
        launch { w.animateTo(tw, spring(dampingRatio = 0.7f, stiffness = 340f)) }
        x.animateTo(tx, spring(dampingRatio = 0.62f, stiffness = 340f))
    }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier.fillMaxWidth().height(height).onSizeChanged { viewport = it.width }
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // soft ends: fade a label out where the row runs on past the edge
                val f = (28.dp.toPx() / size.width).coerceAtMost(0.3f)
                val l = if (scroll.value > 0) 0f else 1f
                val r = if (scroll.value < scroll.maxValue) 0f else 1f
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = l), f to Color.Black, 1f - f to Color.Black, 1f to Color.Black.copy(alpha = r)),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            // the bubble sits behind the labels, so they stay crisp
            if (w.value > 0f) Box(
                Modifier.align(Alignment.CenterStart)
                    .size(with(density) { w.value.toDp() }, height - 4.dp)
                    .graphicsLayer {
                        translationX = x.value
                        scaleX = 1f + (abs(x.velocity) / size.width.coerceAtLeast(1f) * 0.012f).coerceAtMost(0.2f)
                        scaleY = 1f - (abs(x.velocity) / size.width.coerceAtLeast(1f) * 0.005f).coerceAtMost(0.08f)
                    }
                    .liquidBubble(),
            )
            Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { i, label ->
                    val sel = i == selected
                    val tint by animateColorAsState(if (sel) Tv.Text else Tv.Text3, label = "stab")
                    Box(
                        Modifier.fillMaxHeight().padding(vertical = 2.dp)
                            .onPlaced { lefts[i] = it.positionInParent().x; widths[i] = it.size.width.toFloat() }
                            .clip(shape).pressable(haptic = true) { if (!sel) onSelect(i) }
                            .semantics { contentDescription = label; role = Role.Tab; this.selected = sel }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = Type.CaptionStrong.copy(fontSize = 15.sp), color = tint, maxLines = 1) }
                }
            }
        }
    }
}

/** How wide [GlassTabBar] is with [count] tabs, so other chrome (Home's header) can line up with it. */
fun tabBarWidth(count: Int): Dp = TAB_ITEM_W * count + TAB_PAD * 2
private val TAB_ITEM_W = 86.dp
private val TAB_PAD = 6.dp

// ---------------------------------------------------------------- ambient background

/** A tile of fine grain, made once: the "frost" in frosted glass, and the brushed texture on the card. */
@Composable
fun rememberGrain(): ImageBitmap = remember {
    val n = 128
    val rnd = java.util.Random(7)
    val px = IntArray(n * n) { (rnd.nextInt(256)).let { v -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v } }
    android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Brushed-metal streaks: one column of soft, low-contrast random greys (a few
 * brighter hairlines), stretched across a surface into fine horizontal grain.
 */
@Composable
fun rememberBrushed(): ImageBitmap = remember {
    val h = 720
    val rnd = java.util.Random(11)
    var v = 128f
    val px = IntArray(h) {
        v += (rnd.nextFloat() - 0.5f) * 10f
        v += (128f - v) * 0.08f
        val hair = if (rnd.nextFloat() < 0.04f) 26 else 0
        val g = (v + (rnd.nextFloat() - 0.5f) * 14f + hair).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
    android.graphics.Bitmap.createBitmap(px, 1, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Where the light sits on each screen: it drifts to its new place on navigation, and stays still otherwise (no idle redraws). */
private fun lightsFor(s: Screen): Pair<Offset, Offset> = when (s) {
    Screen.Home -> Offset(0.5f, 0.12f) to Offset(0.05f, 0.78f)
    Screen.Activity -> Offset(0.92f, 0.05f) to Offset(0.1f, 0.6f)
    Screen.Settings -> Offset(0.1f, 0.04f) to Offset(0.95f, 0.7f)
    Screen.Security -> Offset(0.85f, 0.06f) to Offset(0.1f, 0.85f)
    Screen.Send -> Offset(0.5f, 0.28f) to Offset(0.9f, 0.95f)
    Screen.Trade -> Offset(0.75f, 0.08f) to Offset(0.15f, 0.8f)
    Screen.CardCheck -> Offset(0.5f, 0.2f) to Offset(0.0f, 0.95f)
    Screen.Welcome -> Offset(0.5f, 0.3f) to Offset(0.9f, 0.9f)
    Screen.Limits, Screen.Setup, Screen.Recover, Screen.Guardian, Screen.SeedCreate, Screen.SeedImport -> Offset(0.2f, 0.1f) to Offset(0.85f, 0.85f)
}

/**
 * The app background: near-black with two soft light pools (silver, and a cool
 * steel; ice blue when the wallet is frozen) and fine grain, so glass surfaces
 * have something to frost.
 */
@Composable
fun AppBackground(screen: Screen, frozen: Boolean, content: @Composable BoxScope.() -> Unit) {
    val (a, b) = lightsFor(screen)
    val la by animateOffsetAsState(a, spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow), label = "la")
    val lb by animateOffsetAsState(b, spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow), label = "lb")
    val cool by animateColorAsState(if (frozen) Tv.Ice else Color(0xFF8E9AAE), tween(900), label = "cool")
    val grain = rememberGrain()
    Box(
        Modifier.fillMaxSize().background(Tv.Bg).drawBehind {
            val r1 = size.width * 1.05f
            val c1 = Offset(size.width * la.x, size.height * la.y)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.085f), Color.Transparent), c1, r1), r1, c1)
            val r2 = size.width * 0.95f
            val c2 = Offset(size.width * lb.x, size.height * lb.y)
            drawCircle(Brush.radialGradient(listOf(cool.copy(alpha = if (frozen) 0.14f else 0.075f), Color.Transparent), c2, r2), r2, c2)
            drawRect(ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)), alpha = 0.035f)
        },
        content = content,
    )
}

// ---------------------------------------------------------------- reduced motion

/** True when the user turned animations off (Developer options or Accessibility → Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

// ---------------------------------------------------------------- liquid glass tab bar

/**
 * A floating glass pill with a liquid bubble on the current tab. The bubble
 * springs to a newly chosen tab, stretching while it moves and settling with a
 * small wobble; dragging along the bar slides it, and letting go picks the tab
 * under it. On Android 13+ the bubble is a real lens: it magnifies and bends
 * the frosted bar and icons beneath it ([LiquidLens]).
 */
@Composable
fun GlassTabBar(items: List<NavItem>, current: Screen, badge: Int, go: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val index = items.indexOfFirst { it.screen == current }.coerceAtLeast(0)
    val pos = remember { Animatable(index.toFloat()) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(index) { if (!dragging) pos.animateTo(index.toFloat(), spring(dampingRatio = 0.62f, stiffness = 340f)) }

    val itemW = TAB_ITEM_W
    val barH = 64.dp
    val pad = TAB_PAD
    // the bubble sits inside the bar: the same gap (pad) above, below and at both ends
    val bubbleH = barH - pad * 2
    val density = LocalDensity.current
    val itemPx = with(density) { itemW.toPx() }
    val padPx = with(density) { pad.toPx() }
    val shape = RoundedCornerShape(50)
    val lens = remember { createLiquidLens() }
    fun stretchX() = 1f + (abs(pos.velocity) * 0.045f).coerceAtMost(0.26f)
    fun stretchY() = 1f - (abs(pos.velocity) * 0.02f).coerceAtMost(0.10f)

    Box(modifier.navigationBarsPadding().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(itemW * items.size + pad * 2).height(barH), contentAlignment = Alignment.Center) {
            // what the lens looks through: the frosted bar and the icons
            Box(
                Modifier.matchParentSize().graphicsLayer {
                    if (lens != null && android.os.Build.VERSION.SDK_INT >= 33) {
                        val cx = padPx + pos.value * itemPx + itemPx / 2
                        renderEffect = lens.effect(
                            center = Offset(cx, size.height / 2),
                            half = Offset(itemPx / 2 * stretchX(), bubbleH.toPx() / 2 * stretchY()),
                            edge = 16.dp.toPx(),
                        )
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(barH).shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                        .frosted(shape, strength = 0.42f).glassRim(barH / 2, shape = shape),
                )
                Row(
                    Modifier.fillMaxWidth().height(barH).padding(horizontal = pad)
                        .pointerInput(items.size) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    dragging = false
                                    val target = pos.value.roundToInt().coerceIn(0, items.lastIndex)
                                    scope.launch { pos.animateTo(target.toFloat(), spring(dampingRatio = 0.62f, stiffness = 340f)) }
                                    if (items[target].screen != current) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); go(items[target].screen) }
                                },
                                onDragCancel = { dragging = false; scope.launch { pos.animateTo(index.toFloat(), spring(dampingRatio = 0.62f, stiffness = 340f)) } },
                            ) { change, dx ->
                                change.consume()
                                scope.launch { pos.snapTo((pos.value + dx / itemPx).coerceIn(-0.15f, items.lastIndex + 0.15f)) }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { i, item ->
                        val sel = i == index
                        val tint by animateColorAsState(if (sel) Tv.Text else Tv.Text3, label = "tab")
                        Column(
                            Modifier.width(itemW).fillMaxHeight().clip(RoundedCornerShape(50))
                                .pressable(haptic = true) { if (!sel) go(item.screen) }
                                .semantics { contentDescription = item.label; role = Role.Tab; selected = sel },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Box {
                                TvIcon(item.icon, 23.dp, tint)
                                if (item.screen == Screen.Activity && badge > 0) Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-6).dp).size(17.dp).background(Tv.Warn, CircleShape).border(2.dp, Color(0xFF141417), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (badge > 9) "9+" else "$badge", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 9.sp), color = Tv.OnAccent) }
                            }
                            AnimatedVisibility(sel, enter = fadeIn(tween(220, 80)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(item.label, style = Type.Overline.copy(fontSize = 11.sp, letterSpacing = 0.2.sp), color = tint)
                                }
                            }
                        }
                    }
                }
            }
            // the bubble's glass (fill, rim, highlight) sits on top of everything it bends
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = pad).size(itemW, bubbleH)
                    .graphicsLayer { translationX = pos.value * itemPx; scaleX = stretchX(); scaleY = stretchY() }
                    .liquidBubble(),
            )
        }
    }
}

/**
 * The liquid-glass lens (Android 13+, AGSL): inside a rounded rectangle the
 * content is magnified a little, and much more near the rim, as through a
 * thick convex edge, with a faint colour split there. Null on older Android.
 */
@androidx.annotation.RequiresApi(33)
class LiquidLens internal constructor(private val shader: android.graphics.RuntimeShader) {
    fun effect(center: Offset, half: Offset, edge: Float): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("center", center.x, center.y)
        shader.setFloatUniform("halfSize", half.x, half.y)
        shader.setFloatUniform("radius", kotlin.math.min(half.x, half.y))
        shader.setFloatUniform("edge", edge)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** The lens shader, or null below Android 13 (AGSL) or if it fails to compile. */
fun createLiquidLens(): LiquidLens? =
    if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { LiquidLens(android.graphics.RuntimeShader(LENS_SRC)) }.getOrNull() else null

private const val LENS_SRC = """
    uniform shader content;
    uniform float2 center;
    uniform float2 halfSize;
    uniform float radius;
    uniform float edge;

    float roundRect(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    }

    half4 main(float2 xy) {
        float2 p = xy - center;
        float d = roundRect(p, halfSize, radius);
        if (d > 0.0) return content.eval(xy);
        float t = clamp(1.0 + d / edge, 0.0, 1.0);
        float k = 0.08 + 0.26 * t * t * t;
        half4 c = content.eval(center + p * (1.0 - k));
        c.r = content.eval(center + p * (1.0 - k * 1.06)).r;
        c.b = content.eval(center + p * (1.0 - k * 0.94)).b;
        return c;
    }
"""

/**
 * The look of a liquid-glass lens: a clear body with a bright specular rim
 * (strongest top-left and bottom-right, like light bending through a thick
 * edge), an inner glow at the top and a soft contact shadow.
 */
fun Modifier.liquidBubble(): Modifier {
    val shape = RoundedCornerShape(50)
    return this
        .shadow(4.dp, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.5f))
        .background(Color.White.copy(alpha = 0.10f), shape)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.White.copy(alpha = 0.06f)), shape)
        .border(
            1.2.dp,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.75f), 0.22f to Color.White.copy(alpha = 0.12f),
                0.62f to Color.White.copy(alpha = 0.06f), 1f to Color.White.copy(alpha = 0.45f),
            ),
            shape,
        )
}

// ---------------------------------------------------------------- liquid glass button

/**
 * A full-width pill made of the tab bar's liquid glass: a translucent body, the
 * bubble's specular rim (bright top-left and bottom-right), a glow along the top
 * and a soft contact shadow. Pressing squashes it like a drop (wider, flatter)
 * and a highlight blooms where the finger is; letting go it springs back with
 * a small wobble. [light] is the call to action: milky white glass, dark text.
 */
@Composable
fun LiquidGlassButton(text: String, modifier: Modifier = Modifier, light: Boolean = false, height: Dp = 60.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val wobble = spring<Float>(dampingRatio = 0.42f, stiffness = 380f)
    val sx by androidx.compose.animation.core.animateFloatAsState(if (pressed && !reduce) 1.03f else 1f, wobble, label = "sx")
    val sy by androidx.compose.animation.core.animateFloatAsState(if (pressed && !reduce) 0.93f else 1f, wobble, label = "sy")
    val glow by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 120 else 420), label = "glow")
    var touch by remember { mutableStateOf(Offset.Unspecified) }
    val haptic = LocalHapticFeedback.current
    val body = if (light) Modifier.background(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.97f), 0.55f to Color(0xFFEDEFF2).copy(alpha = 0.92f), 1f to Color(0xFFD9DCE1).copy(alpha = 0.90f)), shape,
    ) else Modifier.frosted(shape, strength = 0.42f).background(Color.White.copy(alpha = 0.06f), shape)

    Box(
        modifier.height(height)
            .graphicsLayer { scaleX = sx; scaleY = sy }
            .shadow(if (light) 20.dp else 24.dp, shape, ambientColor = if (light) Color.White.copy(alpha = 0.35f) else Color.Black, spotColor = if (light) Color.White.copy(alpha = 0.35f) else Color.Black)
            .clip(shape)
            .then(body)
            .drawBehind {
                // inner glow along the top, and a faint reflection along the bottom, as in the tab bar's bubble
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (light) 0.55f else 0.16f), 0.4f to Color.Transparent, 0.82f to Color.Transparent, 1f to Color.White.copy(alpha = if (light) 0.25f else 0.06f)))
                if (glow > 0f && touch != Offset.Unspecified) {
                    val r = size.height * 1.6f
                    drawCircle(Brush.radialGradient(listOf((if (light) Color.White else Color.White.copy(alpha = 0.22f)).copy(alpha = (if (light) 0.8f else 0.22f) * glow), Color.Transparent), touch, r), r, touch)
                }
            }
            .border(
                1.2.dp,
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 1f else 0.75f), 0.22f to Color.White.copy(alpha = if (light) 0.35f else 0.12f),
                    0.62f to Color.White.copy(alpha = if (light) 0.15f else 0.06f), 1f to Color.White.copy(alpha = if (light) 0.8f else 0.45f),
                ),
                shape,
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        e.changes.firstOrNull()?.let { if (it.pressed) touch = it.position }
                    }
                }
            }
            .clickable(src, indication = null) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Button.copy(fontSize = 17.sp), color = if (light) Color(0xFF0B0B0D) else Tv.Text, maxLines = 1)
    }
}

// ---------------------------------------------------------------- staggered entrance

/** Fades and lifts content in on first show, [index] steps after the screen appears. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    if (rememberReduceMotion()) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L + index * 45L)
        a.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 260f))
    }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 18.dp.toPx() }
}

// ---------------------------------------------------------------- motion tilt

/**
 * How far the phone is tilted from where it was when this appeared, −1…1 on
 * each axis (x: roll, y: pitch), smoothed. Listens only while the app is
 * resumed and this is on screen; stays at zero when animations are off or the
 * phone has no rotation sensor.
 */
@Composable
fun rememberTilt(): androidx.compose.runtime.State<Offset> {
    val tilt = remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current
    val reduce = rememberReduceMotion()
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner, reduce) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (reduce || sm == null || sensor == null) return@DisposableEffect onDispose {}
        var base: FloatArray? = null
        val rot = FloatArray(9)
        val angles = FloatArray(3)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
                android.hardware.SensorManager.getOrientation(rot, angles)
                val b = base ?: angles.copyOf().also { base = it }
                val range = 0.35f // radians for a full lean
                val x = ((angles[2] - b[2]) / range).coerceIn(-1f, 1f)
                val y = ((angles[1] - b[1]) / range).coerceIn(-1f, 1f)
                val old = tilt.value
                tilt.value = Offset(old.x + (x - old.x) * 0.15f, old.y + (y - old.y) * 0.15f)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { base = null; sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { sm.unregisterListener(listener); tilt.value = Offset.Zero }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); sm.unregisterListener(listener) }
    }
    return tilt
}

// ---------------------------------------------------------------- shared elements

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalScreenScope = staticCompositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/** Marks the card so it flies between Home and Your card instead of fading. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val screen = LocalScreenScope.current ?: return this
    if (rememberReduceMotion()) return this
    return with(shared) {
        this@sharedCard.sharedElement(
            rememberSharedContentState("card"), screen,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 300f) },
        )
    }
}
