package app.tapvault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- palette: deep plum night, warm glow, lime accent ----

object Tv {
    val Night = Color(0xFF121019)
    val Plum = Color(0xFF2A2240)
    val Violet = Color(0xFF6A5A9C)
    val Glow = Color(0xFFC58C93)
    val Lime = Color(0xFFE3F27A)
    val OnLime = Color(0xFF15131C)
    val Text = Color(0xFFF4F2F8)
    val Dim = Color(0xFFA8A3B8)
    val Faint = Color(0xFF6E687F)
    val Up = Color(0xFF6FD39A)
    val Down = Color(0xFFFF7A86)
    val Warn = Color(0xFFF5C46B)
    val Glass = Color.White.copy(alpha = 0.07f)
    val GlassStrong = Color.White.copy(alpha = 0.12f)
    val Hairline = Color.White.copy(alpha = 0.10f)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun outfit(w: Int) = Font(R.font.outfit, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Outfit = FontFamily(outfit(200), outfit(300), outfit(400), outfit(500), outfit(600), outfit(700))

private val typography = Typography().let { t ->
    fun TextStyle.o() = copy(fontFamily = Outfit)
    Typography(
        displayLarge = t.displayLarge.o(), displayMedium = t.displayMedium.o(), displaySmall = t.displaySmall.o(),
        headlineLarge = t.headlineLarge.o(), headlineMedium = t.headlineMedium.o(), headlineSmall = t.headlineSmall.o(),
        titleLarge = t.titleLarge.o(), titleMedium = t.titleMedium.o(), titleSmall = t.titleSmall.o(),
        bodyLarge = t.bodyLarge.o(), bodyMedium = t.bodyMedium.o(), bodySmall = t.bodySmall.o(),
        labelLarge = t.labelLarge.o(), labelMedium = t.labelMedium.o(), labelSmall = t.labelSmall.o(),
    )
}

@Composable
fun TapvaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tv.Lime, onPrimary = Tv.OnLime, background = Tv.Night, surface = Tv.Plum,
            onSurface = Tv.Text, onBackground = Tv.Text, secondary = Tv.Lime, error = Tv.Down, outline = Tv.Hairline,
            surfaceVariant = Tv.Plum, onSurfaceVariant = Tv.Dim,
        ),
        typography = typography,
    ) {
        // Text without an explicit colour should be light on the night background.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Tv.Text, content = content)
    }
}

/** Big light numerals, like the balance in the references. */
val Numeral = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)

/** The app background: dark plum with a violet wash and a warm glow top-right. */
@Composable
fun NightBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Tv.Night).drawBehind {
            drawRect(Brush.verticalGradient(0f to Tv.Plum, 0.55f to Tv.Night, 1f to Color(0xFF0C0B11)))
            drawCircle(
                Brush.radialGradient(listOf(Tv.Violet.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.12f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(size.width * 0.15f, size.height * 0.12f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Tv.Glow.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width * 1.0f, size.height * 0.02f), radius = size.width * 0.75f),
                radius = size.width * 0.75f, center = Offset(size.width * 1.0f, size.height * 0.02f),
            )
        },
        content = content,
    )
}

/** Frosted glass surface. */
fun Modifier.glass(radius: Dp = 28.dp, strong: Boolean = false) = this
    .background(if (strong) Tv.GlassStrong else Tv.Glass, RoundedCornerShape(radius))
    .border(1.dp, Tv.Hairline, RoundedCornerShape(radius))

/** A glass tile with a soft violet-to-glow gradient, like the coin tiles. */
fun Modifier.gradientTile(radius: Dp = 28.dp, warm: Boolean = false) = this
    .background(
        Brush.linearGradient(
            if (warm) listOf(Tv.Plum, Tv.Violet.copy(alpha = 0.8f), Tv.Glow.copy(alpha = 0.7f))
            else listOf(Color(0xFF1C1828), Tv.Plum, Tv.Violet.copy(alpha = 0.7f)),
        ),
        RoundedCornerShape(radius),
    )
    .border(1.dp, Tv.Hairline, RoundedCornerShape(radius))

// ---- line icons, drawn so they stay crisp at any size ----

enum class Glyph { Send, Receive, Freeze, History, Home, Card, Settings, Back, Check, Shield, Phone, Plus, Down }

@Composable
fun GlyphIcon(g: Glyph, size: Dp = 22.dp, color: Color = Tv.Text) {
    Canvas(Modifier.size(size)) { drawGlyph(g, color) }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val s = size.minDimension
    val w = s * 0.085f
    val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg pts: Pair<Float, Float>) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
    }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)
    when (g) {
        Glyph.Send -> { line(0.28f, 0.72f, 0.72f, 0.28f); drawPath(p(0.36f to 0.28f, 0.72f to 0.28f, 0.72f to 0.64f), c, style = st) }
        Glyph.Receive -> { line(0.72f, 0.28f, 0.28f, 0.72f); drawPath(p(0.28f to 0.36f, 0.28f to 0.72f, 0.64f to 0.72f), c, style = st) }
        Glyph.Down -> { line(0.5f, 0.22f, 0.5f, 0.78f); drawPath(p(0.28f to 0.56f, 0.5f to 0.78f, 0.72f to 0.56f), c, style = st) }
        Glyph.Freeze -> {
            for (a in 0 until 3) {
                val ang = Math.toRadians(90.0 + a * 60.0)
                val dx = (Math.cos(ang) * 0.32).toFloat(); val dy = (Math.sin(ang) * 0.32).toFloat()
                line(0.5f - dx, 0.5f - dy, 0.5f + dx, 0.5f + dy)
            }
            drawCircle(c, s * 0.06f, Offset(s * 0.5f, s * 0.5f))
        }
        Glyph.History -> {
            drawArc(c, -200f, 300f, false, Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.6f), style = st)
            drawPath(p(0.5f to 0.34f, 0.5f to 0.5f, 0.62f to 0.58f), c, style = st)
        }
        Glyph.Home -> drawPath(p(0.22f to 0.48f, 0.5f to 0.24f, 0.78f to 0.48f, 0.78f to 0.78f, 0.22f to 0.78f, 0.22f to 0.48f), c, style = st)
        Glyph.Card -> {
            drawRoundRect(c, Offset(s * 0.16f, s * 0.28f), Size(s * 0.68f, s * 0.46f), androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = st)
            line(0.16f, 0.42f, 0.84f, 0.42f)
            line(0.26f, 0.6f, 0.4f, 0.6f)
        }
        Glyph.Settings -> { // sliders
            line(0.22f, 0.36f, 0.78f, 0.36f); line(0.22f, 0.64f, 0.78f, 0.64f)
            drawCircle(Color.Transparent, 0f, Offset.Zero)
            drawCircle(c, s * 0.075f, Offset(s * 0.38f, s * 0.36f))
            drawCircle(c, s * 0.075f, Offset(s * 0.62f, s * 0.64f))
        }
        Glyph.Back -> drawPath(p(0.6f to 0.26f, 0.36f to 0.5f, 0.6f to 0.74f), c, style = st)
        Glyph.Check -> drawPath(p(0.26f to 0.52f, 0.43f to 0.68f, 0.76f to 0.32f), c, style = st)
        Glyph.Shield -> {
            drawPath(p(0.5f to 0.2f, 0.76f to 0.3f, 0.74f to 0.56f, 0.5f to 0.8f, 0.26f to 0.56f, 0.24f to 0.3f, 0.5f to 0.2f), c, style = st)
            drawPath(p(0.38f to 0.5f, 0.47f to 0.59f, 0.63f to 0.42f), c, style = st)
        }
        Glyph.Phone -> {
            drawRoundRect(c, Offset(s * 0.3f, s * 0.16f), Size(s * 0.4f, s * 0.68f), androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = st)
            line(0.45f, 0.74f, 0.55f, 0.74f)
        }
        Glyph.Plus -> { line(0.5f, 0.26f, 0.5f, 0.74f); line(0.26f, 0.5f, 0.74f, 0.5f) }
    }
}
