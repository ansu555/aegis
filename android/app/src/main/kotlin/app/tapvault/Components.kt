package app.tapvault

import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapvault.core.Near
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger

// ---------------------------------------------------------------- helpers

/** Long account ids (64-hex implicit accounts) shortened in the middle: 2834ff4c…5428a8. */
fun shortAccount(a: String, keep: Int = 22): String =
    if (a.length <= keep) a else a.take(8) + "…" + a.takeLast(6)

/** "1 h 5 min", "12 min", "40 s". */
fun formatDuration(secs: Long): String = when {
    secs >= 3600 -> "${secs / 3600} h" + ((secs % 3600) / 60).let { if (it > 0) " $it min" else "" }
    secs >= 60 -> "${secs / 60} min"
    else -> "$secs s"
}

fun hoursLabel(secs: Long): String = if (secs % 3600 == 0L) "${secs / 3600} h" else formatDuration(secs)

/** A pressable with a soft scale-down while held, ripple-free (the scale is the feedback). */
@Composable
fun Modifier.pressable(enabled: Boolean = true, haptic: Boolean = false, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.965f else 1f, spring(stiffness = 900f), label = "press")
    val h = LocalHapticFeedback.current
    return this.graphicsLayer { scaleX = s; scaleY = s }
        .clickable(src, indication = null, enabled = enabled) { if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

// ---------------------------------------------------------------- buttons

enum class Btn { Primary, Secondary, Danger, Ghost }

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: Btn = Btn.Primary,
    icon: Ic? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 56.dp,
    onClick: () -> Unit,
) {
    val (bg, fg) = when (kind) {
        Btn.Primary -> Tv.Accent to Tv.OnAccent
        Btn.Secondary -> Tv.GlassStrong to Tv.Text
        Btn.Danger -> Tv.DangerSoft to Tv.Danger
        Btn.Ghost -> Color.Transparent to Tv.Text2
    }
    val bgC by animateColorAsState(if (enabled) bg else if (kind == Btn.Primary) Tv.Glass else bg, label = "bg")
    val fgC by animateColorAsState(if (enabled) fg else Tv.Text3, label = "fg")
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier.height(height).clip(shape).background(bgC)
            .let {
                when {
                    // platinum: a brushed-metal sheen over the fill, brightest at the top
                    kind == Btn.Primary && enabled -> it.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent, Color.Black.copy(alpha = 0.10f))), shape)
                    kind == Btn.Secondary || (kind == Btn.Primary && !enabled) -> it.glassRim(height / 2, shape = shape)
                    else -> it
                }
            }
            .pressable(enabled && !loading, haptic = kind == Btn.Primary, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fgC, strokeWidth = 2.dp, strokeCap = StrokeCap.Round)
            Spacer(Modifier.width(10.dp))
        } else icon?.let { TvIcon(it, 20.dp, fgC); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Button, color = fgC, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon with a label underneath: the wallet's main actions (Send, Receive, …). */
@Composable
fun ActionButton(icon: Ic, label: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    Column(modifier.pressable(enabled, haptic = true, onClick = onClick).semantics { contentDescription = label; role = Role.Button }, horizontalAlignment = Alignment.CenterHorizontally) {
        val fg = when { !enabled -> Tv.Text3; primary -> Tv.OnAccent; else -> tint ?: Tv.Text }
        Box(
            Modifier.size(62.dp)
                .let {
                    if (primary && enabled) it.shadow(18.dp, CircleShape, ambientColor = Color.White.copy(alpha = 0.25f), spotColor = Color.White.copy(alpha = 0.25f))
                        .background(Tv.titanium(), CircleShape)
                        .border(1.dp, Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.2f))), CircleShape)
                    else it.background(if (enabled) Tv.GlassStrong else Tv.Glass, CircleShape).glassRim(31.dp, shape = CircleShape)
                },
            contentAlignment = Alignment.Center,
        ) { TvIcon(icon, 24.dp, fg) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = Type.CaptionStrong, color = if (enabled) Tv.Text else Tv.Text3)
    }
}

/** Small round icon button (back, close, header actions). */
@Composable
fun RoundIcon(icon: Ic, label: String, size: Dp = 44.dp, tint: Color = Tv.Text, bg: Color = Tv.GlassStrong, dot: Boolean = false, onClick: () -> Unit) {
    Box {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).glassRim(size / 2, shape = CircleShape)
                .pressable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { TvIcon(icon, size * 0.46f, tint) }
        if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).size(10.dp).background(Tv.Warn, CircleShape).border(2.dp, Tv.Bg, CircleShape))
    }
}

/** Small selectable chip ("Half", "Max", presets): clear glass, and the tab bar's liquid bubble when [selected]. */
@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val fg by animateColorAsState(if (selected) Tv.Text else Tv.Text2, label = "chipFg")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.let { if (selected) it.liquidBubble() else it.clip(shape).background(Tv.GlassStrong).glassRim(18.dp, shape = shape) }
            .clip(shape).pressable(haptic = true, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, style = Type.CaptionStrong, color = fg) }
}

/** Status pill: tinted background, coloured text, optional leading icon. */
@Composable
fun StatusPill(text: String, color: Color, icon: Ic? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { TvIcon(icon, 14.dp, color); Spacer(Modifier.width(5.dp)) }
        else { Box(Modifier.size(6.dp).background(color, CircleShape)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Type.CaptionStrong, color = color, maxLines = 1)
    }
}

// ---------------------------------------------------------------- layout pieces

/** Screen header: back button, centred title, optional trailing actions. */
@Composable
fun TopBar(title: String = "", onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(60.dp)) {
        if (onBack != null) Box(Modifier.align(Alignment.CenterStart)) { RoundIcon(Ic.Back, "Back", onClick = onBack) }
        Text(title, Modifier.align(Alignment.Center), style = Type.Title)
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** A [TopBar] above a screen's scrolling content, so it stays put while the content scrolls. */
@Composable
fun PinnedTopBar(title: String = "", onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { TopBar(title, onBack, actions) }
}

/** Big left-aligned page title, as on the tab screens. */
@Composable
fun LargeTitle(text: String, sub: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = Type.H1)
            sub?.let { Text(it, style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 4.dp)) }
        }
        trailing()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.Title, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = Type.CaptionStrong, color = Tv.Accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(4.dp))
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Tv.Text2, align: TextAlign? = null) =
    Text(text, modifier, style = Type.Caption, color = color, textAlign = align)

/** A grouped list: rows on one surface, separated by inset hairlines. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().surface(22.dp).clip(RoundedCornerShape(22.dp)), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 68.dp), thickness = 1.dp, color = Tv.Stroke)

/** Icon in a tinted rounded square, used at the start of rows. */
@Composable
fun IconTile(icon: Ic, tint: Color = Tv.Text, bg: Color = Tv.GlassStrong, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(bg, RoundedCornerShape(13.dp)).let { if (bg == Tv.GlassStrong) it.glassRim(13.dp) else it }, contentAlignment = Alignment.Center) { TvIcon(icon, size * 0.5f, tint) }
}

/** A row in a [Group]: icon, title and subtitle, trailing value and chevron if tappable. */
@Composable
fun Row_(
    icon: Ic,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    tint: Color = Tv.Text,
    iconBg: Color = Tv.GlassStrong,
    titleColor: Color = Tv.Text,
    chevron: Boolean = false,
    trailingIcon: Ic? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, iconBg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.BodyStrong, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Type.Caption, color = Tv.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        value?.let { Spacer(Modifier.width(10.dp)); Text(it, style = Type.Body, color = Tv.Text2, maxLines = 1) }
        trailing()
        trailingIcon?.let { Spacer(Modifier.width(8.dp)); TvIcon(it, 18.dp, Tv.Text3) }
        if (chevron) { Spacer(Modifier.width(6.dp)); TvIcon(Ic.Chevron, 18.dp, Tv.Text3) }
    }
}

@Composable
fun InfoRow(k: String, v: String, color: Color = Tv.Text, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(end = 16.dp))
        Text(v, style = Type.BodyStrong, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}

/** A tinted notice: warnings, frozen state, NFC hints. */
@Composable
fun Notice(icon: Ic, title: String, body: String? = null, color: Color = Tv.Warn, modifier: Modifier = Modifier, action: @Composable (ColumnScope.() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(color.copy(alpha = 0.09f), RoundedCornerShape(20.dp)).border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(20.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            TvIcon(icon, 20.dp, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.BodyStrong, color = color)
                body?.let { Text(it, style = Type.Caption, color = Tv.Text2, modifier = Modifier.padding(top = 3.dp)) }
            }
        }
        action?.let { Spacer(Modifier.height(14.dp)); it() }
    }
}

/** A thin progress track. */
@Composable
fun Meter(fraction: Float, color: Color = Tv.Accent, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "meter")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Tv.GlassStrong)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (color == Tv.Accent) Tv.titanium() else androidx.compose.ui.graphics.SolidColor(color)))
    }
}

/** Round avatar with the wallet's initial. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(Tv.titanium(), CircleShape).border(1.dp, Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.15f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(name.trim().firstOrNull()?.uppercase() ?: "W", style = Type.Title.copy(fontSize = (size.value * 0.42f).sp), color = Tv.OnAccent) }
}

/**
 * An amount with the decimals dimmer than the whole part: 6.133 NEAR. [metal] paints the whole part in titanium.
 * [fracScale] below 1 also draws the decimals smaller, like cents on a price tag (one text, so the baselines line up).
 */
@Composable
fun Amount(yocto: BigInteger?, decimals: Int = 4, style: TextStyle = Type.Balance, unit: String? = "NEAR", modifier: Modifier = Modifier, metal: Boolean = false, fracScale: Float = 1f) {
    val s = yocto?.let { Near.format(it, decimals) } ?: "–"
    val whole = s.substringBefore('.')
    val frac = if ('.' in s) "." + s.substringAfter('.') else ""
    if (fracScale != 1f) {
        val text = androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(if (metal) androidx.compose.ui.text.SpanStyle(brush = Tv.titanium()) else androidx.compose.ui.text.SpanStyle(color = Tv.Text))
            append(whole); pop()
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Tv.Text2, fontSize = style.fontSize * fracScale, letterSpacing = 0.sp))
            append(frac); pop()
            unit?.let {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Tv.Text2, fontSize = style.fontSize * 0.38f, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium))
                append("  $it"); pop()
            }
        }
        Text(text, modifier, style = style, maxLines = 1, softWrap = false)
        return
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(whole, style = if (metal) style.copy(brush = Tv.titanium()) else style, color = Tv.Text)
        if (frac.isNotEmpty()) Text(frac, style = style, color = Tv.Text2)
        unit?.let { Text(" $it", style = style.copy(fontSize = style.fontSize * 0.42f, letterSpacing = 0.sp), color = Tv.Text2, modifier = Modifier.padding(bottom = (style.fontSize.value * 0.16f).dp)) }
    }
}

/** [Amount] that counts from its previous value to a new one, so a balance change is seen, not just swapped. */
@Composable
fun CountingAmount(yocto: BigInteger, decimals: Int = 4, style: TextStyle = Type.Balance, modifier: Modifier = Modifier, metal: Boolean = false, fracScale: Float = 1f) {
    var from by remember { mutableStateOf(BigInteger.ZERO) }
    var to by remember { mutableStateOf(BigInteger.ZERO) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(yocto) {
        if (yocto == to) return@LaunchedEffect
        from = if (p.value < 1f) lerp(from, to, p.value) else to
        to = yocto
        p.snapTo(0f)
        p.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    Amount(lerp(from, to, p.value), decimals, style, modifier = modifier, metal = metal, fracScale = fracScale)
}

private fun lerp(a: BigInteger, b: BigInteger, t: Float): BigInteger =
    a + (b - a) * BigInteger.valueOf((t.coerceIn(0f, 1f) * 1_000_000).toLong()) / BigInteger.valueOf(1_000_000)

// ---------------------------------------------------------------- inputs

@Composable
fun TvField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    number: Boolean = false,
    error: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    /** Hidden input (dots), password keyboard, no suggestions: for the security key. */
    secret: Boolean = false,
    /** A pill as round as the tab bar, with this icon in front (search boxes). */
    leading: Ic? = null,
) {
    Column(modifier) {
        if (label.isNotEmpty()) { Text(label, style = Type.CaptionStrong, color = Tv.Text2); Spacer(Modifier.height(8.dp)) }
        BasicTextField(
            value, onChange, singleLine = true,
            modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
            textStyle = Type.Body.copy(color = Tv.Text, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            cursorBrush = SolidColor(Tv.Accent),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when { secret -> KeyboardType.Password; number -> KeyboardType.Decimal; else -> KeyboardType.Ascii },
                autoCorrectEnabled = false,
            ),
            decorationBox = { inner ->
                val stroke = if (error != null) Tv.Danger.copy(alpha = 0.6f) else Tv.Stroke
                val pill = RoundedCornerShape(50)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .let { if (leading != null) it.background(Tv.Glass, pill).glassRim(28.dp, stroke, shape = pill).padding(start = 20.dp, end = 12.dp) else it.surface(18.dp, Tv.Glass, stroke).padding(start = 16.dp, end = 8.dp) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.let { TvIcon(it, 20.dp, Tv.Text3); Spacer(Modifier.width(12.dp)) }
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(fontSize = 16.sp), color = Tv.Text3)
                        inner()
                    }
                    trailing?.invoke(this)
                }
            },
        )
        error?.let { Text(it, style = Type.Caption, color = Tv.Danger, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
    }
}

/** The big centred amount input on Send. */
@Composable
fun AmountField(value: String, onChange: (String) -> Unit, unit: String = "NEAR") {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = Type.Balance.copy(color = Tv.Text, textAlign = TextAlign.Center, fontSize = 52.sp),
        cursorBrush = SolidColor(Tv.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text("0", style = Type.Balance.copy(fontSize = 52.sp, textAlign = TextAlign.Center), color = Tv.Text3, modifier = Modifier.fillMaxWidth())
                    inner()
                }
                Text(unit, style = Type.BodyStrong, color = Tv.Text2)
            }
        },
    )
}

// ---------------------------------------------------------------- navigation

data class NavItem(val screen: Screen, val icon: Ic, val label: String)

/** The tab bar: everyday screens only. Card check lives under Settings › Your card. */
val navItems = listOf(
    NavItem(Screen.Home, Ic.Wallet, "Wallet"),
    NavItem(Screen.Trade, Ic.Refresh, "Trade"),
    NavItem(Screen.Activity, Ic.History, "Activity"),
    NavItem(Screen.Settings, Ic.Settings, "Settings"),
)

// ---------------------------------------------------------------- feedback

/**
 * Result banner, pinned at the top. Frosted glass, slides in, swipe up or
 * tap × to dismiss; successes draw their check and leave on their own after a few seconds.
 */
@Composable
fun OutcomeBanner(o: Outcome?, openLink: (String) -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keep showing the last outcome while the exit animation runs.
    var last by remember { mutableStateOf(o) }
    if (o != null) last = o
    LaunchedEffect(o) {
        if (o != null && o.ok) { delay(if (o.link != null) 7_000 else 5_000); dismiss() }
    }
    val h = LocalHapticFeedback.current
    LaunchedEffect(o) { if (o != null) h.performHapticFeedback(if (o.ok) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress) }
    AnimatedVisibility(
        o != null, modifier,
        enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = 480f)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
    ) {
        val b = last ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(24.dp)
        var drag by remember { mutableStateOf(0f) }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .offset(y = drag.coerceAtMost(0f).dp / 3)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragEnd = { if (drag < -60f) dismiss(); drag = 0f }) { _, dy -> drag += dy }
                }
                .shadow(26.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .frosted(shape, strength = 0.72f)
                .glassRim(24.dp, if (b.ok) Tv.Stroke else Tv.Danger.copy(alpha = 0.35f))
                .padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (b.ok) AnimatedCheck(38.dp)
            else Box(Modifier.size(38.dp).background(Tv.DangerSoft, CircleShape), contentAlignment = Alignment.Center) { TvIcon(Ic.Alert, 20.dp, Tv.Danger) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(b.title, style = Type.BodyStrong, color = Tv.Text)
                if (b.detail.isNotBlank()) Text(
                    b.detail, style = Type.Caption, color = Tv.Text2,
                    modifier = Modifier.padding(top = 2.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
                b.link?.let { l ->
                    Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { openLink(l) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("View on explorer", style = Type.CaptionStrong, color = Tv.Text)
                        Spacer(Modifier.width(4.dp))
                        TvIcon(Ic.External, 14.dp, Tv.Text2)
                    }
                }
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { dismiss() }.semantics { contentDescription = "Dismiss" }, contentAlignment = Alignment.Center) {
                TvIcon(Ic.Close, 18.dp, Tv.Text3)
            }
        }
    }
}

/** A check that draws itself inside a soft disc that pops in: success, seen rather than read. */
@Composable
fun AnimatedCheck(size: Dp, color: Color = Tv.Positive) {
    val ring = remember { Animatable(0f) }
    val stroke = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { ring.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f)) }
        delay(140)
        stroke.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(color.copy(alpha = 0.16f), radius = s / 2 * ring.value)
        val path = Path().apply { moveTo(s * 0.29f, s * 0.53f); lineTo(s * 0.44f, s * 0.67f); lineTo(s * 0.72f, s * 0.37f) }
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * stroke.value, seg, true)
        drawPath(seg, color, style = Stroke(s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Blocks input while something runs and says what. */
@Composable
fun BusyOverlay(text: String) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f)) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * appear.value)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(30.dp)
        Column(
            Modifier.padding(40.dp).graphicsLayer { val s = 0.9f + 0.1f * appear.value; scaleX = s; scaleY = s; alpha = appear.value.coerceIn(0f, 1f) }
                .shadow(30.dp, shape).frosted(shape, strength = 0.7f).glassRim(30.dp).padding(horizontal = 30.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spinner(44.dp)
            Spacer(Modifier.height(18.dp))
            Text(text, style = Type.BodyStrong, textAlign = TextAlign.Center)
            Text("Keep the app open", style = Type.Caption, color = Tv.Text3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** An arc spinner: a bright head on a faint track. */
@Composable
fun Spinner(size: Dp = 28.dp, color: Color = Tv.Accent) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size).rotate(a)) {
        val w = this.size.minDimension * 0.09f
        drawArc(color.copy(alpha = 0.12f), 0f, 360f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w))
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, color)), 0f, 110f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A centred dialog in the pop-up glass ([PopupGlass]), frosting the screen behind it. */
@Composable
fun GlassDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = Overlay(center = true, onDismiss, content)

/** Confirmation dialog in the app's style. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    danger: Boolean = false,
    icon: Ic? = null,
) {
    GlassDialog(onDismiss) {
        icon?.let {
            val c = if (danger) Tv.Danger else if (it == Ic.Freeze) Tv.Ice else Tv.Text
            Box(Modifier.size(58.dp).background(c.copy(alpha = 0.13f), CircleShape).glassRim(29.dp, shape = CircleShape), contentAlignment = Alignment.Center) { TvIcon(it, 26.dp, c) }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = Type.H2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        TvButton(confirm, Modifier.fillMaxWidth(), if (danger) Btn.Danger else Btn.Primary, height = 52.dp, onClick = onConfirm)
        Spacer(Modifier.height(6.dp))
        TvButton(dismiss, Modifier.fillMaxWidth(), Btn.Ghost, height = 46.dp, onClick = onDismiss)
    }
}

/**
 * A bottom sheet in the pop-up glass ([PopupGlass]): a floating frosted card that blurs the screen
 * behind it, like the welcome menus. Drag the handle down, tap outside or go back to close it.
 * [secure] is kept for the callers that show secrets: the sheet is drawn in the activity's own
 * window, which is FLAG_SECURE (MainActivity), so it never appears in screenshots or recordings.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun GlassSheet(onDismiss: () -> Unit, secure: Boolean = false, content: @Composable ColumnScope.() -> Unit) = Overlay(center = false, onDismiss, content)

// ---------------------------------------------------------------- the card

/**
 * The card, in brushed dark titanium: chip, contactless mark, last four digits.
 * [tilt] (−1…1 on each axis, from the phone's motion) leans it and slides the
 * sheen across; [frozen] frosts it over in ice. With [showBalance] the chip and
 * contactless mark move up beside each other and the balance sits mid-card.
 */
@Composable
fun CardArt(
    modifier: Modifier = Modifier, last4: String? = null, network: String? = null, frozen: Boolean = false, compact: Boolean = false, tilt: Offset = Offset.Zero,
    /** Show the wallet balance on the card (Home); null [balance] shimmers while it loads. */
    showBalance: Boolean = false, balance: BigInteger? = null,
) {
    val r = if (compact) 14.dp else 24.dp
    val shape = RoundedCornerShape(r)
    val grain = rememberGrain()
    val brushed = rememberBrushed()
    val frost by animateFloatAsState(if (frozen) 1f else 0f, tween(800), label = "frost")
    Box(
        modifier.aspectRatio(1.586f)
            .graphicsLayer { rotationX = -tilt.y * 8f; rotationY = tilt.x * 8f; cameraDistance = 16f * density }
            .shadow(if (compact) 10.dp else 32.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3E3E44), Color(0xFF202024), Color(0xFF2C2C31), Color(0xFF141417))))
            .drawBehind {
                // brushed metal: fine horizontal grain, lighter and darker than the base
                drawImage(brushed, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = 0.22f, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay)
                // light pool at the top-left and a sheen that follows the tilt
                val lp = Offset(size.width * (0.18f + tilt.x * 0.12f), -size.height * 0.25f)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), lp, size.width * 0.75f), size.width * 0.75f, lp)
                val sx = size.width * (0.4f + tilt.x * 0.35f)
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.02f), Color.Transparent), start = Offset(sx - size.width * 0.45f, 0f), end = Offset(sx + size.width * 0.3f, size.height)))
                // fine guilloché arcs
                for (i in 1..7) drawCircle(Color.White.copy(alpha = 0.03f), size.width * (0.14f + i * 0.08f), Offset(size.width * 1.02f, size.height * 1.08f), style = Stroke(1.2f))
            }
            .drawWithContent {
                drawContent()
                if (frost > 0f) {
                    drawRect(Brush.verticalGradient(listOf(Tv.Ice.copy(alpha = 0.30f * frost), Color.White.copy(alpha = 0.08f * frost), Tv.Ice.copy(alpha = 0.20f * frost))))
                    drawRect(ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)), alpha = 0.10f * frost)
                }
            }
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))), shape)
            .padding(if (compact) 10.dp else 22.dp),
    ) {
        if (showBalance && !compact) {
            CardBalanceFace(last4, network, frozen, frost, balance)
            return@Box
        }
        if (!compact) CardBrand(Modifier.align(Alignment.TopStart))
        TvIcon(Ic.Nfc, if (compact) 14.dp else 22.dp, Tv.Text.copy(alpha = 0.7f), Modifier.align(Alignment.TopEnd))
        // EMV chip
        Box(
            Modifier.align(Alignment.CenterStart).offset(y = if (compact) 0.dp else (-4).dp)
                .size(if (compact) 18.dp else 44.dp, if (compact) 13.dp else 33.dp)
                .background(Brush.linearGradient(listOf(Color(0xFFEFE2BC), Color(0xFFB9A169), Color(0xFFE6D3A0), Color(0xFFA8905A))), RoundedCornerShape(if (compact) 3.dp else 8.dp))
                .drawBehind {
                    val c = Color(0x66705A26); val w = 1.2f
                    drawLine(c, Offset(size.width * 0.33f, 0f), Offset(size.width * 0.33f, size.height), w)
                    drawLine(c, Offset(size.width * 0.67f, 0f), Offset(size.width * 0.67f, size.height), w)
                    drawLine(c, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), w)
                },
        )
        if (!compact) {
            Text(last4?.let { "••••  $it" } ?: "••••  ••••", Modifier.align(Alignment.BottomStart), style = Type.Mono.copy(fontSize = 17.sp, letterSpacing = 1.6.sp), color = Tv.Text.copy(alpha = 0.92f))
            network?.let { Text(it.uppercase(), Modifier.align(Alignment.BottomEnd), style = Type.Title.copy(fontSize = 15.sp, letterSpacing = 1.4.sp, brush = Tv.titanium())) }
        }
        if (frozen && !compact) TvIcon(Ic.Freeze, 22.dp, Tv.Ice.copy(alpha = frost), Modifier.align(Alignment.TopEnd).offset(x = (-34).dp))
    }
}

/** Top-left of the card art: the mark and the lowercase wordmark. */
@Composable
private fun CardBrand(modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AegisMark(11.dp, Tv.Text.copy(alpha = 0.85f))
        Spacer(Modifier.width(7.dp))
        Text("aegis", style = Type.BodyStrong.copy(letterSpacing = 0.6.sp, brush = Tv.titanium()))
    }
}

/** The EMV chip: gold contacts with their dividing lines. */
@Composable
private fun CardChip(modifier: Modifier, w: Dp, h: Dp, r: Dp) {
    Box(
        modifier.size(w, h)
            .background(Brush.linearGradient(listOf(Color(0xFFEFE2BC), Color(0xFFB9A169), Color(0xFFE6D3A0), Color(0xFFA8905A))), RoundedCornerShape(r))
            .drawBehind {
                val c = Color(0x66705A26); val sw = 1.2f
                drawLine(c, Offset(size.width * 0.33f, 0f), Offset(size.width * 0.33f, size.height), sw)
                drawLine(c, Offset(size.width * 0.67f, 0f), Offset(size.width * 0.67f, size.height), sw)
                drawLine(c, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), sw)
            },
    )
}

/** [CardArt]'s face on Home: wordmark and contactless mark on top, the balance mid-card with the chip to its right, number and network. */
@Composable
private fun BoxScope.CardBalanceFace(last4: String?, network: String?, frozen: Boolean, frost: Float, balance: BigInteger?) {
    CardBrand(Modifier.align(Alignment.TopStart))
    TvIcon(Ic.Nfc, 22.dp, Tv.Text.copy(alpha = 0.7f), Modifier.align(Alignment.TopEnd))
    if (frozen) TvIcon(Ic.Freeze, 22.dp, Tv.Ice.copy(alpha = frost), Modifier.align(Alignment.TopEnd).offset(x = (-34).dp))
    CardChip(Modifier.align(Alignment.CenterEnd).offset(y = 6.dp), 44.dp, 33.dp, 8.dp)
    Column(Modifier.align(Alignment.CenterStart).offset(y = 6.dp).padding(end = 58.dp)) { // clear of the chip
        Text(if (frozen) "BALANCE · FROZEN" else "BALANCE", style = Type.Overline.copy(fontSize = 11.sp, letterSpacing = 1.6.sp), color = if (frozen) Tv.Ice else Tv.Text3)
        Spacer(Modifier.height(4.dp))
        if (balance == null) Shimmer(Modifier.size(170.dp, 36.dp))
        else CountingAmount(balance, 4, Type.Balance.copy(fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1).sp), metal = true, fracScale = 0.58f)
    }
    Text(last4?.let { "••••  $it" } ?: "••••  ••••", Modifier.align(Alignment.BottomStart), style = Type.Mono.copy(fontSize = 15.sp, letterSpacing = 1.6.sp), color = Tv.Text.copy(alpha = 0.85f))
    network?.let { Text(it.uppercase(), Modifier.align(Alignment.BottomEnd), style = Type.Title.copy(fontSize = 14.sp, letterSpacing = 1.4.sp, brush = Tv.titanium())) }
}

// ---------------------------------------------------------------- card tap sheet

/**
 * Shown while a card tap is awaited: a phone with the card gliding onto its
 * back, NFC waves, and what to do. Turns into a progress ring while reading.
 */
@Composable
fun TapSheet(t: TapRequest, onCancel: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "tap")
    val glide by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "glide")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f)) }
    val shape = RoundedCornerShape(PopupGlass.Radius)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = PopupGlass.SCRIM * appear.value.coerceIn(0f, 1f))).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(PopupGlass.Margin).fillMaxWidth().offset { IntOffset(0, ((1f - appear.value) * 700f).toInt()) }
                .popupGlass(shape)
                .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(40.dp, 4.dp).background(Tv.StrokeStrong, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(18.dp))
            if (t.hold != null) {
                HoldPanel(t.hold, onCancel)
                return@Column
            }
            Text(if (t.reading) "Reading card" else "Ready to scan", style = Type.CaptionStrong.copy(letterSpacing = 0.4.sp), color = Tv.Text2)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                // NFC waves
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent)), radius = size.minDimension * 0.5f)
                    for (i in 0..2) {
                        val p = ((wave + i / 3f) % 1f)
                        drawCircle(Color.White.copy(alpha = (1f - p) * if (t.reading) 0.45f else 0.28f), radius = size.minDimension * (0.25f + 0.25f * p), style = Stroke(1.5.dp.toPx()))
                    }
                }
                if (t.reading) Spinner(176.dp)
                // phone, seen from the back: titanium frame, glass back, camera island
                Box(
                    Modifier.size(98.dp, 176.dp).shadow(18.dp, RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2E2E33), Color(0xFF151518))), RoundedCornerShape(22.dp))
                        .border(1.5.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.25f))), RoundedCornerShape(22.dp)),
                ) {
                    Box(Modifier.padding(10.dp).size(24.dp, 38.dp).background(Color(0xFF0C0C0E), RoundedCornerShape(9.dp)).border(1.dp, Tv.Stroke, RoundedCornerShape(9.dp)))
                }
                // card gliding up onto the phone's back
                val y = if (t.reading) 8f else 112f - 104f * FastOutSlowInEasing.transform((glide / 0.7f).coerceAtMost(1f))
                val alpha = if (t.reading) 1f else (1f - ((glide - 0.85f) / 0.15f).coerceIn(0f, 1f))
                CardArt(Modifier.width(152.dp).offset(y = y.dp).graphicsLayer { this.alpha = alpha; rotationZ = if (t.reading) 0f else -6f + 6f * glide }, compact = true)
            }
            Spacer(Modifier.height(18.dp))
            Text(if (t.reading) "Keep holding the card…" else t.title, style = Type.H2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val hint = when {
                t.reading -> "Reading the chip and asking it to sign. Don't move the card until this closes."
                t.hint != null -> t.hint
                else -> "Hold the card flat against the back of the phone, near the camera, for 2–3 seconds."
            }
            Text(hint, style = Type.Body, color = if (t.hint != null && !t.reading) Tv.Warn else Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(22.dp))
            AnimatedVisibility(!t.reading, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                TvButton("Cancel", Modifier.fillMaxWidth(), Btn.Secondary) { onCancel() }
            }
        }
    }
}

/**
 * Hold-to-sign: the card was read and is one of the wallet's; it signs when
 * the ring is full. Lifting the card restarts the count (the view model
 * handles that). An app-side pause, and the text says so.
 */
@Composable
private fun ColumnScope.HoldPanel(h: HoldProgress, onCancel: () -> Unit) {
    val signing = h.leftMs <= 0
    val target = if (h.totalMs <= 0) 1f else (1f - h.leftMs.toFloat() / h.totalMs).coerceIn(0f, 1f)
    val p by animateFloatAsState(target, tween(if (signing) 200 else 480, easing = LinearEasing), label = "hold")
    Text(if (signing) "Signing" else "Hold your card", style = Type.CaptionStrong.copy(letterSpacing = 0.4.sp), color = Tv.Text2)
    Spacer(Modifier.height(18.dp))
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 7.dp.toPx()
            val inset = Offset(sw / 2, sw / 2)
            val box = Size(size.width - sw, size.height - sw)
            drawArc(Color.White.copy(alpha = 0.09f), -90f, 360f, false, inset, box, style = Stroke(sw))
            drawArc(Brush.sweepGradient(listOf(Tv.Text2, Tv.Positive, Tv.Positive)), -90f, 360f * p, false, inset, box, style = Stroke(sw, cap = StrokeCap.Round))
        }
        if (signing) Spinner(64.dp)
        else Text("${(h.leftMs + 999) / 1000}", style = Type.Balance.copy(fontSize = 64.sp, brush = Tv.titanium()), modifier = Modifier.semantics { contentDescription = "${(h.leftMs + 999) / 1000} seconds left" })
    }
    Spacer(Modifier.height(18.dp))
    Text("${h.card} found", style = Type.H2, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(
        if (signing) "Signing now. Don't move the card." else "Keep the card on the phone. Card moved? The timer starts over.",
        style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Hold timer: a pause in this app so nothing signs by accident. Your real protection is the phone approval and the limits.",
        style = Type.Caption, color = Tv.Text3, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
    )
    Spacer(Modifier.height(18.dp))
    if (!signing) TvButton("Cancel", Modifier.fillMaxWidth(), Btn.Secondary) { onCancel() }
}

// ---------------------------------------------------------------- QR

/**
 * A QR code drawn as crisp squares on a white tile. With [reveal] it makes an entrance: the tile
 * flips in on a spring, the squares burst out from the centre in a wave of colour and settle to
 * black, a light sweeps down the code, and rings ripple out behind it. Still when animations are off.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, reveal: Boolean = false) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    val animate = reveal && !rememberReduceMotion()
    // build: 0…1 the squares land (each in its own slot, centre first), 1…1.5 their colour drains to black
    val build = remember(text, animate) { Animatable(if (animate) 0f else 1.5f) }
    val flip = remember(text, animate) { Animatable(if (animate) 0f else 1f) }
    val scan = remember(text, animate) { Animatable(if (animate) 0f else 1f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(text, animate) {
        if (!animate) return@LaunchedEffect
        launch { flip.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 150f)) }
        build.animateTo(1.5f, tween(1900, easing = LinearEasing))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scan.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
    }
    val modules = remember(m) { qrModules(m) }
    val ink = Color(0xFF0A0B0D)
    Box(
        modifier
            .drawBehind {
                // ripples behind the tile while it arrives
                if (build.value < 1.5f) for (i in 0..2) {
                    val t = ((build.value / 1.2f) - i * 0.18f).coerceIn(0f, 1f)
                    if (t <= 0f || t >= 1f) continue
                    val r = size.minDimension * (0.45f + 0.32f * t)
                    drawCircle(Brush.sweepGradient(listOf(Color(0xFF7CF5C4), Color(0xFF8AB4FF), Color(0xFFC59BFF), Color(0xFF7CF5C4)), center), r, style = Stroke((3f - 2f * t) * density), alpha = (1f - t) * 0.7f)
                }
            }
            .graphicsLayer {
                val f = flip.value
                rotationY = (1f - f) * -75f
                rotationZ = (1f - f) * 8f
                val sc = 0.55f + 0.45f * f
                scaleX = sc; scaleY = sc
                alpha = (f * 1.6f).coerceIn(0f, 1f)
                cameraDistance = 14f * density
            }
            .shadow(if (animate && scan.value < 1f) 28.dp else 0.dp, RoundedCornerShape(24.dp), ambientColor = Color(0xFF7CF5C4), spotColor = Color(0xFF8AB4FF))
            .background(Color.White, RoundedCornerShape(24.dp)).padding(18.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Whole-pixel modules, centred: no seams between neighbouring squares.
            val n = m.width
            val cell = kotlin.math.floor(size.minDimension / n)
            val o = kotlin.math.floor((size.minDimension - cell * n) / 2)
            val b = build.value
            for (q in modules) {
                val local = ((b - q.start) / 0.28f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val x = o + q.x * cell
                val y = o + q.y * cell
                if (local >= 1f && b >= q.start + 0.78f) { drawRect(ink, Offset(x, y), Size(cell, cell)); continue }
                // easeOutBack: a pop past full size, then back
                val k = local - 1f
                val sc = 1f + 2.70158f * k * k * k + 1.70158f * k * k
                val drain = ((b - q.start - 0.3f) / 0.48f).coerceIn(0f, 1f)
                val c = androidx.compose.ui.graphics.lerp(q.color, ink, drain * drain)
                val w = cell * sc
                drawRect(c, Offset(x + (cell - w) / 2, y + (cell - w) / 2), Size(w, w))
            }
            // a light sweeps down once the code is built
            val sv = scan.value
            if (sv > 0f && sv < 1f) {
                val yy = size.height * sv
                val h = size.height * 0.22f
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF7CF5C4).copy(alpha = 0.55f), Color.Transparent), startY = yy - h, endY = yy + h * 0.25f),
                    Offset(0f, yy - h), Size(size.width, h * 1.25f),
                )
                drawLine(Color(0xFF7CF5C4).copy(alpha = 0.9f * (1f - sv)), Offset(0f, yy), Offset(size.width, yy), 2.dp.toPx())
            }
        }
    }
}

private class QrModule(val x: Int, val y: Int, val start: Float, val color: Color)

/** The dark modules of [m], each with when it lands (centre first, a little jitter) and its colour by angle round the centre. */
private fun qrModules(m: com.google.zxing.common.BitMatrix): List<QrModule> {
    val n = m.width
    val c = (n - 1) / 2f
    val maxD = kotlin.math.sqrt(2f) * c
    val rnd = java.util.Random(n.toLong() * 31 + m.height)
    val out = ArrayList<QrModule>(n * n / 2)
    for (y in 0 until n) for (x in 0 until n) if (m.get(x, y)) {
        val dx = x - c; val dy = y - c
        val d = (kotlin.math.sqrt(dx * dx + dy * dy) / maxD).coerceIn(0f, 1f)
        val hue = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat() + 360f + d * 120f) % 360f)
        out += QrModule(x, y, d * 0.6f + rnd.nextFloat() * 0.12f, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 1f))))
    }
    return out
}

// ---------------------------------------------------------------- brand

/** The Aegis mark alone (two hands and a spark, `R.drawable.aegis_mark`), [height] tall and about 3× as wide. */
@Composable
fun AegisMark(height: Dp, color: Color = Color(0xFFEFE7DE), modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.aegis_mark), contentDescription = null,
        modifier = modifier.height(height).width(height * (856f / 280f)),
        colorFilter = ColorFilter.tint(color),
    )
}

/** The Aegis mark in a rounded near-black tile, like the launcher icon. */
@Composable
fun LogoMark(size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(Color(0xFF060608), RoundedCornerShape(size * 0.3f)).border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f))), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        AegisMark(size * 0.26f)
    }
}

@Composable
fun Wordmark(size: Dp = 32.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark(size)
        Spacer(Modifier.width(10.dp))
        Text("aegis", style = Type.Title.copy(fontSize = (size.value * 0.6f).sp, letterSpacing = (-0.3).sp))
    }
}

// ---------------------------------------------------------------- slide to confirm

/**
 * A deliberate confirm: drag the platinum knob to the end of the track. Let go
 * early and it springs back. Accessibility services get a plain click action.
 */
@Composable
fun SlideToConfirm(label: String, modifier: Modifier = Modifier, icon: Ic = Ic.Fingerprint, onConfirm: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val knob = 54.dp
    val pad = 5.dp
    var width by remember { mutableStateOf(0f) }
    val max = with(density) { (width - knob.toPx() - pad.toPx() * 2).coerceAtLeast(1f) }
    val x = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    val shim by rememberInfiniteTransition(label = "shim").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "s")
    val shape = RoundedCornerShape(50)
    Box(
        modifier.fillMaxWidth().height(64.dp).onSizeChanged { width = it.width.toFloat() }
            .clip(shape).background(Tv.GlassStrong).glassRim(32.dp, shape = shape)
            .semantics {
                role = Role.Button; contentDescription = label
                onClick(label) { if (!done) { done = true; onConfirm() }; true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // the trail behind the knob
        Box(Modifier.fillMaxHeight().width(with(density) { (x.value + knob.toPx() + pad.toPx() * 2).toDp() }).background(Color.White.copy(alpha = 0.07f), shape))
        Text(
            label, Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - (x.value / max).coerceIn(0f, 1f) },
            style = Type.Button.copy(brush = Brush.linearGradient(listOf(Tv.Text3, Tv.Text, Tv.Text3), start = Offset(width * (shim * 1.6f - 0.6f), 0f), end = Offset(width * (shim * 1.6f - 0.6f) + 260f, 0f))),
        )
        Box(
            Modifier.padding(pad).offset { IntOffset(x.value.roundToInt(), 0) }.size(knob)
                .shadow(12.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(Tv.titanium(), CircleShape).border(1.dp, Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.2f))), CircleShape)
                .pointerInput(max) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (x.value > max * 0.82f && !done) {
                                    x.animateTo(max, spring(stiffness = 800f))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    done = true
                                    onConfirm()
                                } else x.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 420f))
                            }
                        },
                    ) { change, dx -> change.consume(); if (!done) scope.launch { x.snapTo((x.value + dx).coerceIn(0f, max)) } }
                },
            contentAlignment = Alignment.Center,
        ) { TvIcon(if (done) Ic.Check else icon, 24.dp, Tv.OnAccent) }
    }
}
