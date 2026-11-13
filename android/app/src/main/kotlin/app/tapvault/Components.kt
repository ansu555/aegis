package app.tapvault

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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
        Btn.Secondary -> Tv.Surface2 to Tv.Text
        Btn.Danger -> Tv.DangerSoft to Tv.Danger
        Btn.Ghost -> Color.Transparent to Tv.Text2
    }
    val bgC by animateColorAsState(if (enabled) bg else if (kind == Btn.Primary) Tv.Surface2 else bg, label = "bg")
    val fgC by animateColorAsState(if (enabled) fg else Tv.Text3, label = "fg")
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(bgC)
            .let { if (kind == Btn.Secondary) it.border(1.dp, Tv.Stroke, RoundedCornerShape(height / 2)) else it }
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
        val bg = when { !enabled -> Tv.Surface; primary -> Tv.Accent; else -> Tv.Surface2 }
        val fg = when { !enabled -> Tv.Text3; primary -> Tv.OnAccent; else -> tint ?: Tv.Text }
        Box(
            Modifier.size(58.dp).background(bg, CircleShape).let { if (!primary) it.border(1.dp, Tv.Stroke, CircleShape) else it },
            contentAlignment = Alignment.Center,
        ) { TvIcon(icon, 24.dp, fg) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = Type.CaptionStrong, color = if (enabled) Tv.Text else Tv.Text3)
    }
}

/** Small round icon button (back, close, header actions). */
@Composable
fun RoundIcon(icon: Ic, label: String, size: Dp = 44.dp, tint: Color = Tv.Text, bg: Color = Tv.Surface2, dot: Boolean = false, onClick: () -> Unit) {
    Box {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, Tv.Stroke, CircleShape)
                .pressable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { TvIcon(icon, size * 0.46f, tint) }
        if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).size(10.dp).background(Tv.Warn, CircleShape).border(2.dp, Tv.Bg, CircleShape))
    }
}

/** Small selectable chip ("Half", "Max", presets). */
@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Tv.AccentSoft else Tv.Surface2, label = "chip")
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, if (selected) Tv.Accent.copy(alpha = 0.5f) else Tv.Stroke, RoundedCornerShape(50))
            .pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, style = Type.CaptionStrong, color = if (selected) Tv.Accent else Tv.Text) }
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
fun IconTile(icon: Ic, tint: Color = Tv.Text, bg: Color = Tv.Surface3, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { TvIcon(icon, size * 0.5f, tint) }
}

/** A row in a [Group]: icon, title and subtitle, trailing value and chevron if tappable. */
@Composable
fun Row_(
    icon: Ic,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    tint: Color = Tv.Text,
    iconBg: Color = Tv.Surface3,
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
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Tv.Surface3)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Round avatar with the wallet's initial. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFF2E3A36), Color(0xFF1B2422))), CircleShape).border(1.dp, Tv.Accent.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(name.trim().firstOrNull()?.uppercase() ?: "W", style = Type.Title.copy(fontSize = (size.value * 0.42f).sp), color = Tv.Accent) }
}

/** An amount with the decimals dimmer than the whole part: 6.133 NEAR. */
@Composable
fun Amount(yocto: BigInteger?, decimals: Int = 4, style: TextStyle = Type.Balance, unit: String? = "NEAR", modifier: Modifier = Modifier) {
    val s = yocto?.let { Near.format(it, decimals) } ?: "–"
    val whole = s.substringBefore('.')
    val frac = if ('.' in s) "." + s.substringAfter('.') else ""
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(whole, style = style, color = Tv.Text)
        if (frac.isNotEmpty()) Text(frac, style = style, color = Tv.Text2)
        unit?.let { Text(" $it", style = style.copy(fontSize = style.fontSize * 0.42f, letterSpacing = 0.sp), color = Tv.Text2, modifier = Modifier.padding(bottom = (style.fontSize.value * 0.16f).dp)) }
    }
}

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
) {
    Column(modifier) {
        if (label.isNotEmpty()) { Text(label, style = Type.CaptionStrong, color = Tv.Text2); Spacer(Modifier.height(8.dp)) }
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = Type.Body.copy(color = Tv.Text, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            cursorBrush = SolidColor(Tv.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Ascii, autoCorrectEnabled = false),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).surface(16.dp, Tv.Surface, if (error != null) Tv.Danger.copy(alpha = 0.6f) else Tv.StrokeStrong).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

val navItems = listOf(
    NavItem(Screen.Home, Ic.Wallet, "Wallet"),
    NavItem(Screen.Activity, Ic.History, "Activity"),
    NavItem(Screen.CardCheck, Ic.Card, "Card"),
    NavItem(Screen.Settings, Ic.Settings, "Settings"),
)

/** Solid bottom tab bar: icon and label, the current tab in accent with a soft pill behind its icon. */
@Composable
fun BottomNav(current: Screen, badge: Int, go: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Tv.BgRaised)) {
        HorizontalDivider(thickness = 1.dp, color = Tv.Stroke)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            navItems.forEach { item ->
                val sel = item.screen == current
                val tint by animateColorAsState(if (sel) Tv.Accent else Tv.Text3, label = "nav")
                val pill by animateColorAsState(if (sel) Tv.AccentSoft else Color.Transparent, label = "pill")
                Column(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).pressable(haptic = true) { go(item.screen) }
                        .semantics { contentDescription = item.label; role = Role.Tab },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Box(Modifier.size(52.dp, 30.dp).background(pill, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { TvIcon(item.icon, 22.dp, tint) }
                        if (item.screen == Screen.Activity && badge > 0) Box(
                            Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-3).dp).size(17.dp).background(Tv.Warn, CircleShape).border(2.dp, Tv.BgRaised, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (badge > 9) "9+" else "$badge", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 9.sp), color = Tv.OnAccent) }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, style = Type.Overline.copy(fontSize = 11.sp, letterSpacing = 0.2.sp), color = tint)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- feedback

/**
 * Result banner, pinned at the top. Solid (never see-through), slides in,
 * swipe up or tap × to dismiss; successes leave on their own after a few seconds.
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
    LaunchedEffect(o) { if (o != null && !o.ok) h.performHapticFeedback(HapticFeedbackType.LongPress) }
    AnimatedVisibility(
        o != null, modifier,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
    ) {
        val b = last ?: return@AnimatedVisibility
        val accent = if (b.ok) Tv.Accent else Tv.Danger
        var drag by remember { mutableStateOf(0f) }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .offset(y = drag.coerceAtMost(0f).dp / 3)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragEnd = { if (drag < -60f) dismiss(); drag = 0f }) { _, dy -> drag += dy }
                }
                .shadow(24.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(Tv.Surface2, RoundedCornerShape(20.dp))
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                .padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(36.dp).background(accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                TvIcon(if (b.ok) Ic.Check else Ic.Alert, 20.dp, accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(b.title, style = Type.BodyStrong, color = Tv.Text)
                if (b.detail.isNotBlank()) Text(
                    b.detail, style = Type.Caption, color = Tv.Text2,
                    modifier = Modifier.padding(top = 2.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
                b.link?.let { l ->
                    Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { openLink(l) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("View on explorer", style = Type.CaptionStrong, color = Tv.Accent)
                        Spacer(Modifier.width(4.dp))
                        TvIcon(Ic.External, 14.dp, Tv.Accent)
                    }
                }
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { dismiss() }.semantics { contentDescription = "Dismiss" }, contentAlignment = Alignment.Center) {
                TvIcon(Ic.Close, 18.dp, Tv.Text3)
            }
        }
    }
}

/** Blocks input while something runs and says what. */
@Composable
fun BusyOverlay(text: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(40.dp).shadow(30.dp, RoundedCornerShape(28.dp)).surface(28.dp, Tv.Surface2, Tv.StrokeStrong).padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spinner(44.dp)
            Spacer(Modifier.height(18.dp))
            Text(text, style = Type.BodyStrong, textAlign = TextAlign.Center)
            Text("Keep the app open", style = Type.Caption, color = Tv.Text3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** An arc spinner in the accent colour. */
@Composable
fun Spinner(size: Dp = 28.dp, color: Color = Tv.Accent) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size).rotate(a)) {
        val w = this.size.minDimension * 0.09f
        drawArc(color.copy(alpha = 0.15f), 0f, 360f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w))
        drawArc(color, 0f, 100f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tv.Surface2,
        shape = RoundedCornerShape(28.dp),
        icon = icon?.let { { Box(Modifier.size(48.dp).background((if (danger) Tv.Danger else Tv.Accent).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { TvIcon(it, 24.dp, if (danger) Tv.Danger else Tv.Accent) } } },
        title = { Text(title, style = Type.H2, textAlign = TextAlign.Center) },
        text = { Text(text, style = Type.Body, color = Tv.Text2) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = Type.Button, color = if (danger) Tv.Danger else Tv.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, style = Type.Button, color = Tv.Text2) } },
    )
}

// ---------------------------------------------------------------- the card

/**
 * A bank card in graphite with a mint sheen: chip, contactless mark, last
 * four digits. [frozen] frosts it over in ice blue.
 */
@Composable
fun CardArt(modifier: Modifier = Modifier, last4: String? = null, network: String? = null, frozen: Boolean = false, compact: Boolean = false) {
    val r = if (compact) 14.dp else 22.dp
    Box(
        modifier.aspectRatio(1.586f).shadow(if (compact) 8.dp else 26.dp, RoundedCornerShape(r), spotColor = Color.Black)
            .clip(RoundedCornerShape(r))
            .background(Brush.linearGradient(listOf(Color(0xFF2B313A), Color(0xFF181B21), Color(0xFF0E1013))))
            .drawBehind {
                // mint glow and a diagonal sheen
                drawCircle(Brush.radialGradient(listOf((if (frozen) Tv.Ice else Tv.Accent).copy(alpha = 0.32f), Color.Transparent), center = Offset(size.width * 1.0f, size.height * 1.05f), radius = size.width * 0.75f), radius = size.width * 0.75f, center = Offset(size.width, size.height * 1.05f))
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent), start = Offset(size.width * 0.15f, 0f), end = Offset(size.width * 0.75f, size.height)))
                // fine guilloché-like arcs
                for (i in 1..6) {
                    val rr = size.width * (0.16f + i * 0.09f)
                    drawCircle(Color.White.copy(alpha = 0.025f), rr, Offset(size.width * 1.02f, size.height * 1.05f), style = Stroke(1.2f))
                }
            }
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(r))
            .padding(if (compact) 10.dp else 20.dp),
    ) {
        if (!compact) Text("tapvault", Modifier.align(Alignment.TopStart), style = Type.BodyStrong.copy(letterSpacing = 0.5.sp), color = Tv.Text.copy(alpha = 0.9f))
        TvIcon(Ic.Nfc, if (compact) 14.dp else 22.dp, Tv.Text.copy(alpha = 0.75f), Modifier.align(Alignment.TopEnd))
        // EMV chip
        Box(
            Modifier.align(if (compact) Alignment.CenterStart else Alignment.CenterStart).offset(y = if (compact) 0.dp else (-4).dp)
                .size(if (compact) 18.dp else 42.dp, if (compact) 13.dp else 31.dp)
                .background(Brush.linearGradient(listOf(Color(0xFFEAD9A6), Color(0xFFB99A55), Color(0xFFE2CC8E))), RoundedCornerShape(if (compact) 3.dp else 7.dp))
                .drawBehind {
                    val c = Color(0x55705A26); val w = 1.2f
                    drawLine(c, Offset(size.width * 0.33f, 0f), Offset(size.width * 0.33f, size.height), w)
                    drawLine(c, Offset(size.width * 0.67f, 0f), Offset(size.width * 0.67f, size.height), w)
                    drawLine(c, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), w)
                },
        )
        if (!compact) {
            Text(last4?.let { "••••  $it" } ?: "••••  ••••", Modifier.align(Alignment.BottomStart), style = Type.Mono.copy(fontSize = 16.sp, letterSpacing = 1.5.sp), color = Tv.Text.copy(alpha = 0.92f))
            network?.let { Text(it.uppercase(), Modifier.align(Alignment.BottomEnd), style = Type.Title.copy(fontSize = 15.sp, letterSpacing = 1.sp), color = Tv.Text.copy(alpha = 0.92f)) }
        }
        if (frozen) Box(Modifier.fillMaxSize().background(Tv.Ice.copy(alpha = 0.10f)))
    }
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
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f * appear.value)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { translationY = (1f - appear.value) * 600f }
                .background(Tv.Surface, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .border(1.dp, Tv.Stroke, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(40.dp, 4.dp).background(Tv.Surface3, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(18.dp))
            Text(if (t.reading) "Reading card" else "Ready to scan", style = Type.Title, color = Tv.Text2)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                // NFC waves
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..2) {
                        val p = ((wave + i / 3f) % 1f)
                        drawCircle(Tv.Accent.copy(alpha = (1f - p) * if (t.reading) 0.5f else 0.35f), radius = size.minDimension * (0.25f + 0.25f * p), style = Stroke(2.dp.toPx()))
                    }
                }
                if (t.reading) Spinner(170.dp)
                // phone, seen from the back
                Box(
                    Modifier.size(96.dp, 172.dp).background(Tv.Surface2, RoundedCornerShape(20.dp)).border(1.5.dp, Tv.StrokeStrong, RoundedCornerShape(20.dp)),
                ) {
                    Box(Modifier.padding(10.dp).size(22.dp, 34.dp).background(Tv.Surface3, RoundedCornerShape(8.dp)))
                }
                // card gliding up onto the phone's back
                val y = if (t.reading) 8f else 110f - 102f * FastOutSlowInEasing.transform((glide / 0.7f).coerceAtMost(1f))
                val alpha = if (t.reading) 1f else (1f - ((glide - 0.85f) / 0.15f).coerceIn(0f, 1f))
                CardArt(Modifier.width(150.dp).offset(y = y.dp).graphicsLayer { this.alpha = alpha; rotationZ = if (t.reading) 0f else -6f + 6f * glide }, compact = true)
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

// ---------------------------------------------------------------- QR

/** A QR code drawn as crisp squares on a white tile. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Box(modifier.background(Color.White, RoundedCornerShape(24.dp)).padding(18.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            // Whole-pixel modules, centred: no seams between neighbouring squares.
            val n = m.width
            val cell = kotlin.math.floor(size.minDimension / n)
            val o = kotlin.math.floor((size.minDimension - cell * n) / 2)
            for (y in 0 until n) for (x in 0 until n) if (m.get(x, y)) {
                drawRect(Color(0xFF0A0B0D), Offset(o + x * cell, o + y * cell), Size(cell, cell))
            }
        }
    }
}

// ---------------------------------------------------------------- brand

/** The Tapvault mark: a card with contactless waves, in a rounded tile. Replaceable by a final logo. */
@Composable
fun LogoMark(size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFF7BF0C2), Tv.Accent, Color(0xFF2BBF86))), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) {
            val s = this.size.minDimension
            val st = Stroke(s * 0.09f, cap = StrokeCap.Round)
            drawRoundRect(Tv.OnAccent, Offset(s * 0.04f, s * 0.24f), Size(s * 0.6f, s * 0.52f), CornerRadius(s * 0.1f), style = st)
            drawLine(Tv.OnAccent, Offset(s * 0.04f, s * 0.42f), Offset(s * 0.64f, s * 0.42f), s * 0.09f)
            for (i in 1..2) {
                val r = s * (0.12f + i * 0.11f)
                drawArc(Tv.OnAccent, -55f, 110f, false, Offset(s * 0.62f - r, s * 0.5f - r), Size(r * 2, r * 2), style = st)
            }
        }
    }
}

@Composable
fun Wordmark(size: Dp = 32.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark(size)
        Spacer(Modifier.width(10.dp))
        Text("tapvault", style = Type.Title.copy(fontSize = (size.value * 0.6f).sp, letterSpacing = (-0.3).sp))
    }
}

/** Generic sheet body: drag handle, padded column. */
@Composable
fun SheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), content = content)
}
