package app.tapvault

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- colour tokens
//
// Titanium monochrome: near-black, silver and platinum, with glass surfaces.
// Colour is kept for meaning only: green for approved/success, amber for
// waiting, coral for danger, ice blue for the frozen state. Dark only: a
// wallet is opened in every light and this keeps balances calm and legible.

object Tv {
    val Bg = Color(0xFF050506)
    val BgRaised = Color(0xFF0B0B0D)
    val Surface = Color(0xFF111113)
    val Surface2 = Color(0xFF18181B)
    val Surface3 = Color(0xFF232327)
    val Stroke = Color.White.copy(alpha = 0.08f)
    val StrokeStrong = Color.White.copy(alpha = 0.14f)

    /** Glass fills: translucent white over the ambient background. */
    val Glass = Color.White.copy(alpha = 0.055f)
    val GlassStrong = Color.White.copy(alpha = 0.10f)
    /** Opaque "dark glass" for system windows (sheets, dialogs) that can't blur what's behind them. */
    val Sheet = Color(0xFF121214)

    val Text = Color(0xFFF5F5F7)
    val Text2 = Color(0xFF9A9AA2)
    val Text3 = Color(0xFF5E5E66)

    /** Platinum: primary buttons, the selected tab, highlights. */
    val Accent = Color(0xFFEDEDF0)
    val OnAccent = Color(0xFF0A0A0B)
    val AccentSoft = Color.White.copy(alpha = 0.10f)

    /** Approved, can sign, done. */
    val Positive = Color(0xFF7ADBA9)
    val PositiveSoft = Positive.copy(alpha = 0.13f)
    val Danger = Color(0xFFFF7A72)
    val DangerSoft = Danger.copy(alpha = 0.13f)
    val Warn = Color(0xFFF2B65A)
    val WarnSoft = Warn.copy(alpha = 0.13f)
    val Ice = Color(0xFF8CCBFF)
    val IceSoft = Ice.copy(alpha = 0.13f)

    /** Brushed titanium, light from the top-left: the balance, the logo, rims. */
    val Titanium = listOf(Color(0xFFFAFAFC), Color(0xFFC9C9CF), Color(0xFF9C9CA4), Color(0xFFE6E6EA))
    fun titanium(start: Offset = Offset.Zero, end: Offset = Offset.Infinite) = Brush.linearGradient(Titanium, start, end)
}

// ---------------------------------------------------------------- type

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Inter Display: tighter spacing for big titles and the balance. */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
)

/** The app's type scale. Numbers use tabular figures so amounts line up and don't jitter while updating. */
object Type {
    val Balance = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, lineHeight = 52.sp, letterSpacing = (-1.2).sp, fontFeatureSettings = "tnum")
    val Hero = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp)
    val H1 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp)
    val H2 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val BodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val CaptionStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val Overline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
    val Mono = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum, zero")
    val Amount = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")
}

private val typography = Typography().let { t ->
    fun TextStyle.i() = copy(fontFamily = Inter)
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = InterDisplay), displayMedium = t.displayMedium.copy(fontFamily = InterDisplay),
        displaySmall = t.displaySmall.copy(fontFamily = InterDisplay), headlineLarge = t.headlineLarge.copy(fontFamily = InterDisplay),
        headlineMedium = t.headlineMedium.copy(fontFamily = InterDisplay), headlineSmall = Type.H2,
        titleLarge = Type.H2, titleMedium = Type.Title, titleSmall = t.titleSmall.i(),
        bodyLarge = Type.Body, bodyMedium = Type.Body, bodySmall = Type.Caption,
        labelLarge = Type.Button, labelMedium = t.labelMedium.i(), labelSmall = t.labelSmall.i(),
    )
}

@Composable
fun TapvaultTheme(content: @Composable () -> Unit) {
    // The app is dark only. Report night mode to everything inside, so system-drawn
    // pieces (bottom-sheet windows, their navigation bar) pick dark styling too.
    val base = androidx.compose.ui.platform.LocalConfiguration.current
    val night = remember(base) {
        android.content.res.Configuration(base).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides night) { ThemeInner(content) }
}

@Composable
private fun ThemeInner(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tv.Accent, onPrimary = Tv.OnAccent, secondary = Tv.Accent, onSecondary = Tv.OnAccent,
            background = Tv.Bg, onBackground = Tv.Text, surface = Tv.Surface, onSurface = Tv.Text,
            surfaceVariant = Tv.Surface2, onSurfaceVariant = Tv.Text2, surfaceContainerLow = Tv.Surface,
            surfaceContainer = Tv.Surface, surfaceContainerHigh = Tv.Surface2, surfaceContainerHighest = Tv.Surface3,
            error = Tv.Danger, outline = Tv.StrokeStrong, outlineVariant = Tv.Stroke, scrim = Color.Black,
        ),
        typography = typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides Tv.Text, content = content)
    }
}

/**
 * A glass surface: translucent fill over the ambient background, a rim that
 * catches light at the top-left, and a faint sheen across the top.
 * [color] overrides the fill (pass an opaque colour for solid panels).
 */
fun Modifier.surface(radius: Dp = 20.dp, color: Color = Tv.Glass, stroke: Color = Tv.Stroke) = this
    .background(color, RoundedCornerShape(radius))
    .glassRim(radius, stroke)

// ---------------------------------------------------------------- icons
//
// Line icons from Lucide (ISC licence), converted to vector drawables in res/drawable.

enum class Ic(@DrawableRes val res: Int) {
    Send(R.drawable.ic_arrow_up), Receive(R.drawable.ic_arrow_down), Freeze(R.drawable.ic_snowflake),
    History(R.drawable.ic_history), Home(R.drawable.ic_house), Card(R.drawable.ic_credit_card), Settings(R.drawable.ic_settings),
    Back(R.drawable.ic_chevron_left), Check(R.drawable.ic_check), ShieldCheck(R.drawable.ic_shield_check), Shield(R.drawable.ic_shield),
    Phone(R.drawable.ic_smartphone), Plus(R.drawable.ic_plus), Chevron(R.drawable.ic_chevron_right), ChevronDown(R.drawable.ic_chevron_down),
    Close(R.drawable.ic_x), Alert(R.drawable.ic_circle_alert), Copy(R.drawable.ic_copy), Share(R.drawable.ic_share_2), Qr(R.drawable.ic_qr_code),
    Lock(R.drawable.ic_lock), Fingerprint(R.drawable.ic_fingerprint), External(R.drawable.ic_external_link), Info(R.drawable.ic_info),
    Wallet(R.drawable.ic_wallet), Clock(R.drawable.ic_clock), Paste(R.drawable.ic_clipboard_paste), Nfc(R.drawable.ic_nfc),
    Trash(R.drawable.ic_trash_2), Refresh(R.drawable.ic_refresh_cw), Warning(R.drawable.ic_triangle_alert), CheckCircle(R.drawable.ic_circle_check),
    XCircle(R.drawable.ic_circle_x), Key(R.drawable.ic_key_round), Timer(R.drawable.ic_timer), Down(R.drawable.ic_arrow_down), Search(R.drawable.ic_search),
    Edit(R.drawable.ic_pencil), Offline(R.drawable.ic_wifi_off), Layers(R.drawable.ic_layers), Scan(R.drawable.ic_scan_line),
    Hourglass(R.drawable.ic_hourglass),
}

@Composable
fun TvIcon(icon: Ic, size: Dp = 22.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Icon(painterResource(icon.res), contentDescription = null, modifier = modifier.size(size), tint = tint)
}
