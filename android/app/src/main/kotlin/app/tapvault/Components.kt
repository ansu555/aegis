package app.tapvault

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick)

/** Round translucent icon button. `lime` fills it with the accent. */
@Composable
fun CircleButton(glyph: Glyph, label: String, size: Dp = 56.dp, lime: Boolean = false, enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        // Fade with colours rather than an alpha layer: toggling the layer
        // when `enabled` changes dropped the background on some devices.
        val fade = if (enabled) 1f else 0.4f
        val bg = if (lime) Tv.Lime else Tv.GlassStrong
        val fg = if (lime) Tv.OnLime else Tv.Text
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(bg.copy(alpha = bg.alpha * fade))
                .border(1.dp, if (lime) Color.Transparent else Tv.Hairline, CircleShape)
                .press(enabled, onClick)
                .semantics { contentDescription = label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(glyph, size * 0.42f, fg.copy(alpha = fade)) }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(9.dp).background(Color(0xFFFF8A5B), CircleShape))
    }
}

/** White pill: the one main action on a screen. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Glyph? = null, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (enabled) Color.White else Color.White.copy(alpha = 0.25f))
            .press(enabled, onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        glyph?.let { GlyphIcon(it, 20.dp, Tv.OnLime); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Tv.OnLime, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Outlined glass pill for secondary actions. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Tv.Text, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Tv.Glass).border(1.dp, Tv.Hairline, RoundedCornerShape(24.dp))
            .alpha(if (enabled) 1f else 0.4f).press(enabled, onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 15.sp) }
}

/** Small outlined tag, like "ETH" in the coin tiles. */
@Composable
fun Tag(text: String, color: Color = Tv.Text, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).border(1.dp, Tv.Hairline, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = 13.sp)
    }
}

/** Filled badge, like "+2.12%" under the balance. */
@Composable
fun Badge(text: String, color: Color = Tv.Text, bg: Color = Tv.GlassStrong) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = 13.sp)
    }
}

/** Small white chip used inside cards ("Half", "Max"). */
@Composable
fun ChipButton(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).press(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text, color = Tv.OnLime, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, radius: Dp = 28.dp, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().glass(radius).padding(padding)) { content() }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Tv.Dim) = Text(text, modifier, color = color, fontSize = 13.sp, lineHeight = 18.sp)

/** Segmented tabs with a lime pill on the selected one. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).border(1.dp, Tv.Hairline, RoundedCornerShape(50)).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(50)).background(if (i == selected) Tv.Lime else Color.Transparent).press { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (i == selected) Tv.OnLime else Tv.Text, fontSize = 15.sp) }
        }
    }
}

/** List row like the coin rows: round icon, title + subtitle, tag on the right. */
@Composable
fun ListRow(glyph: Glyph, title: String, subtitle: String, tag: String? = null, subColor: Color = Tv.Dim, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(26.dp)).background(Tv.Glass)
            .border(1.dp, Tv.Hairline, RoundedCornerShape(26.dp)).let { if (onClick != null) it.press(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(Tv.GlassStrong, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Tv.Text)
            Text(subtitle, fontSize = 13.sp, color = subColor)
        }
        tag?.let { Tag(it) }
        trailing()
    }
}

/** Header: round back button, centred title, optional actions on the right. */
@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        if (onBack != null) Box(Modifier.align(Alignment.CenterStart)) { CircleButton(Glyph.Back, "Back", 48.dp, onClick = onBack) }
        Text(title, Modifier.align(Alignment.Center), fontSize = 18.sp, color = Tv.Text)
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

/** Glass input with a small label. */
@Composable
fun GlassField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, big: Boolean = false, placeholder: String = "") {
    Column(modifier) {
        Caption(label)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = if (big) Numeral.copy(fontSize = 52.sp, color = Tv.Text, textAlign = TextAlign.Center) else TextStyle(fontFamily = Outfit, fontSize = 17.sp, color = Tv.Text),
            cursorBrush = SolidColor(Tv.Lime),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Ascii, autoCorrectEnabled = false),
            decorationBox = { inner ->
                Box(
                    if (big) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().glass(18.dp).padding(horizontal = 16.dp, vertical = 15.dp),
                    contentAlignment = if (big) Alignment.Center else Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(
                        placeholder,
                        style = if (big) Numeral.copy(fontSize = 52.sp, color = Tv.Faint, textAlign = TextAlign.Center) else TextStyle(fontFamily = Outfit, fontSize = 17.sp, color = Tv.Faint),
                    )
                    inner()
                }
            },
        )
    }
}

/** Floating glass bottom bar; the current tab sits in a lime circle. */
@Composable
fun BottomBar(current: Screen, pending: Int, go: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(Screen.Home to Glyph.Home, Screen.Activity to Glyph.History, Screen.CardCheck to Glyph.Card, Screen.Settings to Glyph.Settings)
    Row(
        modifier.clip(RoundedCornerShape(40.dp)).background(Color(0xE61A1724)).border(1.dp, Tv.Hairline, RoundedCornerShape(40.dp)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { (s, g) ->
            CircleButton(g, s.name, 52.dp, lime = s == current, dot = s == Screen.Activity && pending > 0) { go(s) }
        }
    }
}

@Composable
fun OutcomeToast(o: Outcome, openLink: (String) -> Unit, dismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).glass(24.dp, strong = true).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(34.dp).background(if (o.ok) Tv.Lime else Tv.Down, CircleShape), contentAlignment = Alignment.Center) {
            GlyphIcon(if (o.ok) Glyph.Check else Glyph.Plus, 18.dp, Tv.OnLime)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (o.detail.isNotBlank()) Caption(o.detail)
            o.link?.let { l -> Text("View on explorer ↗", color = Tv.Lime, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).clickable { openLink(l) }) }
        }
        Text("OK", color = Tv.Dim, fontSize = 14.sp, modifier = Modifier.clickable { dismiss() }.padding(start = 8.dp))
    }
}

@Composable
fun BusyOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Tv.Night.copy(alpha = 0.82f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
            CircularProgressIndicator(color = Tv.Lime, strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
            Spacer(Modifier.height(20.dp))
            Text(text, textAlign = TextAlign.Center, fontSize = 17.sp, color = Tv.Text)
        }
    }
}

/** A glassy bank card with chip and contactless waves. */
@Composable
fun CardArt(modifier: Modifier = Modifier, label: String = "Tapvault") {
    Box(modifier.size(220.dp, 138.dp).gradientTile(22.dp, warm = true).padding(18.dp)) {
        Box(Modifier.size(36.dp, 26.dp).background(Brush.linearGradient(listOf(Color(0xFFE9D9A8), Color(0xFFB79C5E))), RoundedCornerShape(6.dp)).align(Alignment.CenterStart))
        Canvas(Modifier.size(30.dp).align(Alignment.TopEnd)) {
            val st = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round)
            for (i in 1..3) {
                val r = size.minDimension * (0.18f * i)
                drawArc(Color.White, -50f, 100f, false, Offset(size.width * 0.1f - r + r, size.height / 2 - r), Size(r * 2, r * 2), style = st)
            }
        }
        Text(label, Modifier.align(Alignment.BottomStart), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, letterSpacing = 2.sp)
    }
}

@Composable
fun TapSheet(t: TapRequest, onDemoTap: () -> Unit, onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(0.92f, 1.12f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "s")
    val a by pulse.animateFloat(0.15f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(Tv.Night.copy(alpha = 0.96f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(270.dp).scale(s).border(2.dp, Tv.Lime.copy(alpha = a), CircleShape))
                Box(Modifier.size(230.dp).border(1.dp, Tv.Hairline, CircleShape))
                CardArt()
            }
            Spacer(Modifier.height(36.dp))
            Text(t.title, fontSize = 22.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
            Spacer(Modifier.height(10.dp))
            Caption(
                if (t.demo) "Demo mode: a simulated card answers exactly like a real one." else "Hold it flat against the back of the phone until it vibrates.",
                Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(28.dp))
            if (t.demo) PillButton("Tap demo card", Modifier.fillMaxWidth(), glyph = Glyph.Card) { onDemoTap() }
            Spacer(Modifier.height(10.dp))
            GhostButton("Cancel", Modifier.fillMaxWidth(), Tv.Dim) { onCancel() }
        }
    }
}
