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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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

/** Frosted chrome: blurs the screen content behind it (Android 12+); a dense tint on older phones. */
@Composable
fun Modifier.frosted(shape: Shape, tint: Color = Color(0xFF141417), strength: Float = 0.62f): Modifier {
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
    )
}

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
    Screen.CardCheck -> Offset(0.5f, 0.2f) to Offset(0.0f, 0.95f)
    Screen.Welcome -> Offset(0.5f, 0.3f) to Offset(0.9f, 0.9f)
    Screen.Limits, Screen.Setup, Screen.Recover, Screen.Guardian -> Offset(0.2f, 0.1f) to Offset(0.85f, 0.85f)
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

    val itemW = 86.dp
    val barH = 64.dp
    val pad = 6.dp
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
