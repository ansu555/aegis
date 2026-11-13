package app.tapvault

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- welcome

private data class Slide(val title: String, val body: String)

private val slides = listOf(
    Slide("Your bank card is your hardware wallet", "Approve payments with a tap of the contactless card you already carry. No seed phrase, no extra device."),
    Slide("Two keys, every payment", "Each payment needs your fingerprint on this phone and a tap of your card. Neither one alone can move funds."),
    Slide("Limits that have your back", "Small payments go through instantly. Bigger ones wait, so you can cancel them or freeze the wallet."),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Welcome(vm: AppViewModel) {
    val others = vm.walletList.count { !it.active }
    val pager = rememberPagerState { slides.size }
    // Gentle auto-advance until the user swipes.
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress) { delay(5_500); pager.animateScrollToPage((pager.currentPage + 1) % slides.size) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(30.dp)
            Spacer(Modifier.weight(1f))
            StatusPill("Testnet", Tv.Warn)
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { i ->
            // parallax: the art moves further than the text, and fades as it leaves
            val off = (pager.currentPage - i) + pager.currentPageOffsetFraction
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                        translationX = off * size.width * 0.45f
                        alpha = 1f - kotlin.math.abs(off).coerceAtMost(1f) * 0.7f
                        val sc = 1f - kotlin.math.abs(off).coerceAtMost(1f) * 0.12f
                        scaleX = sc; scaleY = sc
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when (i) {
                        0 -> CardHeroArt()
                        1 -> TwoKeysArt()
                        else -> LimitsArt()
                    }
                }
                Column(Modifier.graphicsLayer { translationX = off * size.width * 0.12f }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(slides[i].title, style = Type.Hero.copy(brush = Tv.titanium()), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(slides[i].body, style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(slides.size) { i ->
                val w by animateDpAsState(if (pager.currentPage == i) 22.dp else 7.dp, label = "dot")
                val c by animateColorAsState(if (pager.currentPage == i) Tv.Accent else Tv.GlassStrong, label = "dotc")
                Box(Modifier.padding(horizontal = 3.dp).size(w, 7.dp).background(c, CircleShape))
            }
        }
        Spacer(Modifier.height(22.dp))
        NfcNotice(vm)
        // the actions sit on one glass panel
        Column(Modifier.fillMaxWidth().surface(30.dp).padding(10.dp)) {
            TvButton("Create a new wallet", Modifier.fillMaxWidth(), icon = Ic.Plus) { vm.go(Screen.Limits) }
            Spacer(Modifier.height(8.dp))
            TvButton("Check my card first", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Scan) { vm.go(Screen.CardCheck) }
            if (others > 0) {
                Spacer(Modifier.height(2.dp))
                TvButton("Back to my wallets", Modifier.fillMaxWidth(), Btn.Ghost, height = 48.dp) { vm.cancelNewWallet() }
            }
        }
        Caption("Self-custody on NEAR testnet. Only test NEAR, no real money.", Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), Tv.Text3, TextAlign.Center)
    }
}

/** Shown when this phone can't read a card right now, with a shortcut to the NFC settings. */
@Composable
fun NfcNotice(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    when {
        !vm.hasNfc -> Notice(Ic.Nfc, "This phone has no NFC", "It can't read a bank card. Use an Android phone with NFC.", Tv.Warn, modifier.padding(bottom = 14.dp))
        !vm.nfcEnabled -> Notice(Ic.Nfc, "NFC is off", "Turn it on to tap your card (Connected devices → NFC).", Tv.Warn, modifier.padding(bottom = 14.dp)) {
            TvButton("Open NFC settings", Modifier.fillMaxWidth(), Btn.Secondary, height = 44.dp) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
        }
    }
}

// ---------------------------------------------------------------- limits (step before setup)

@Composable
fun ColumnScope.LimitsScreen(vm: AppViewModel) {
    var instant by remember { mutableStateOf(vm.store.instantNear) }
    var daily by remember { mutableStateOf(vm.store.dailyNear) }
    fun clean(v: String) = v.replace(',', '.').filter { it.isDigit() || it == '.' }.take(12)
    val i = instant.toBigDecimalOrNull()
    val d = daily.toBigDecimalOrNull()
    val err = when {
        i == null || d == null || i.signum() <= 0 || d.signum() <= 0 -> null
        i > d -> "The daily limit must be at least the instant limit."
        else -> null
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
    TopBar("New wallet", onBack = { vm.go(Screen.Welcome) })
    Text("Set your spending limits", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
    Text("Payments within these go out instantly with your fingerprint and card. Anything bigger waits an hour, so you can cancel it.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(22.dp))
    LimitInput("Instant payment limit", "Largest single payment that goes out at once", instant, { instant = clean(it) }, listOf("0.5", "1", "5"))
    Spacer(Modifier.height(14.dp))
    LimitInput("Daily limit", "Most that can go out instantly in 24 hours", daily, { daily = clean(it) }, listOf("2", "5", "20"), err)
    SectionHeader("What you'll need")
    Group {
        Row_(Ic.Card, "Your contactless bank card", "Visa, Mastercard, RuPay and others with a chip")
        GroupDivider()
        Row_(Ic.Fingerprint, "A screen lock on this phone", "Fingerprint, PIN, pattern or password")
        GroupDivider()
        Row_(Ic.Clock, "About a minute", "Three approvals and one card tap")
    }
    Spacer(Modifier.height(22.dp))
    NfcNotice(vm)
    Caption("Limits are enforced by your wallet's contract on NEAR, not by this app.", Modifier.fillMaxWidth().padding(bottom = 16.dp), Tv.Text3, TextAlign.Center)
    }
    TvButton("Continue", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), enabled = vm.hasNfc && err == null && i != null && d != null, icon = Ic.Chevron) { vm.beginSetup(instant, daily) }
}

@Composable
private fun LimitInput(title: String, sub: String, value: String, onChange: (String) -> Unit, presets: List<String>, error: String? = null) {
    Column(Modifier.fillMaxWidth().surface(22.dp).padding(16.dp)) {
        Text(title, style = Type.BodyStrong)
        Text(sub, style = Type.Caption, color = Tv.Text2)
        Spacer(Modifier.height(12.dp))
        TvField("", value, onChange, number = true, placeholder = "0", error = error, trailing = { Text("NEAR", style = Type.BodyStrong, color = Tv.Text2, modifier = Modifier.padding(end = 8.dp)) })
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p -> Chip("$p NEAR", selected = value == p) { onChange(p) } }
        }
    }
}

// ---------------------------------------------------------------- setup progress

@Composable
fun SetupProgress(vm: AppViewModel) {
    var confirmStartOver by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val cardRead = vm.store.pendingCard != null || vm.setupStep >= 3
    val steps = listOf(
        Triple(Ic.Key, "Phone key in secure hardware", vm.phoneKeyExists),
        Triple(Ic.Wallet, "Wallet account on NEAR", vm.setupStep >= 1),
        Triple(Ic.Card, "Card read and signed", cardRead),
        Triple(Ic.Layers, "Wallet contract deployed", vm.setupStep >= 2),
        Triple(Ic.ShieldCheck, "Card added as a signer", vm.setupStep >= 3),
        Triple(Ic.Lock, "Limits set, account locked", vm.setupStep >= 4),
    )
    val done = steps.count { it.third }
    val active = steps.indexOfFirst { !it.third }
    TopBar("Creating ${vm.walletName}")
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        ProgressRing(done, steps.size, vm.busy != null)
    }
    Text(if (vm.busy != null) "Setting up your wallet" else if (done == steps.size) "All set" else if (done == 0) "Ready to set up" else "Paused", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text(
        if (vm.busy != null) "Approve each step with your fingerprint. Keep the app open." else "Each step is saved. Continue where you left off.",
        style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
    )
    Column(Modifier.fillMaxWidth().surface(22.dp).padding(vertical = 8.dp)) {
        steps.forEachIndexed { i, (icon, title, ok) ->
            val running = i == active && vm.busy != null
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StepDot(ok, running)
                    if (i < steps.lastIndex) Box(Modifier.width(2.dp).height(30.dp).background(if (ok) Tv.Positive.copy(alpha = 0.5f) else Tv.GlassStrong, RoundedCornerShape(1.dp)))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(title, style = Type.BodyStrong, color = if (ok || running) Tv.Text else Tv.Text3)
                    if (running) Text(vm.busy ?: "", style = Type.Caption, color = Tv.Accent)
                }
                TvIcon(icon, 18.dp, if (ok) Tv.Positive else Tv.Text3, Modifier.padding(top = 5.dp))
            }
        }
    }
    vm.store.walletAccount?.let { Caption("Account: $it", Modifier.fillMaxWidth().padding(top = 12.dp), Tv.Text3, TextAlign.Center) }
    vm.cardReport?.let { r ->
        Spacer(Modifier.height(12.dp))
        CardReportCard(r)
        val ctx = LocalContext.current
        Spacer(Modifier.height(10.dp))
        TvButton("Share card report", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Share) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.diagnostics()), "Share card report"))
        }
    }
    AnimatedVisibility(vm.busy == null && vm.setupStep < 4, enter = fadeIn(), exit = fadeOut()) {
        Column {
            Spacer(Modifier.height(20.dp))
            TvButton("Continue setup", Modifier.fillMaxWidth(), icon = Ic.Chevron) { vm.continueSetup() }
            Spacer(Modifier.height(8.dp))
            if (vm.walletList.size > 1) {
                TvButton("Switch wallet (this setup stays saved)", Modifier.fillMaxWidth(), Btn.Secondary) { showPicker = true }
                Spacer(Modifier.height(8.dp))
            }
            TvButton("Start over", Modifier.fillMaxWidth(), Btn.Ghost) { confirmStartOver = true }
        }
    }
    if (showPicker) WalletPicker(vm) { showPicker = false }
    if (confirmStartOver) ConfirmDialog(
        "Start over?",
        if (!vm.hasSponsor) "The phone key and this setup's saved state are removed. The accounts made so far stay on testnet with their test NEAR."
        else if (vm.setupStep <= 1) "The accounts made for this setup are deleted (their NEAR goes back to the sponsor) and the phone key is removed."
        else "The half-built wallet is left on testnet with its test NEAR. The fee account is deleted and the phone key removed.",
        "Start over", { confirmStartOver = false; vm.startOver() }, { confirmStartOver = false }, dismiss = "Keep going", danger = true, icon = Ic.Refresh,
    )
}

@Composable
private fun StepDot(done: Boolean, running: Boolean) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        when {
            done -> Box(Modifier.size(28.dp).background(Tv.Positive, CircleShape), contentAlignment = Alignment.Center) { TvIcon(Ic.Check, 16.dp, Tv.OnAccent) }
            running -> { Spinner(28.dp); Box(Modifier.size(8.dp).background(Tv.Accent, CircleShape)) }
            else -> Box(Modifier.size(28.dp).border(2.dp, Tv.GlassStrong, CircleShape))
        }
    }
}

@Composable
private fun ProgressRing(done: Int, total: Int, running: Boolean) {
    val inf = rememberInfiniteTransition(label = "ring")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val frac = done.toFloat() / total
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 8.dp.toPx()
            drawCircle(Tv.GlassStrong, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
            drawArc(Brush.sweepGradient(listOf(Tv.Accent.copy(alpha = 0.5f), Tv.Accent)), -90f, 360f * frac, false, Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        if (running) Canvas(Modifier.fillMaxSize().padding(16.dp).rotate(spin)) {
            drawArc(Tv.Accent.copy(alpha = 0.35f), 0f, 60f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$done/$total", style = Type.H1.copy(fontFeatureSettings = "tnum"))
            Text("steps", style = Type.Caption, color = Tv.Text3)
        }
    }
}

// ---------------------------------------------------------------- illustrations (vector, scale to any screen)

@Composable
private fun CardHeroArt() {
    val inf = rememberInfiniteTransition(label = "hero")
    val float by inf.animateFloat(-6f, 6f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "w")
    val tilt by rememberTilt()
    Box(Modifier.fillMaxWidth().aspectRatio(1.1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Tv.Accent.copy(alpha = 0.18f), Color.Transparent)), radius = size.minDimension * 0.5f)
            for (i in 0..2) {
                val p = (wave + i / 3f) % 1f
                drawCircle(Tv.Accent.copy(alpha = (1f - p) * 0.25f), radius = size.minDimension * (0.28f + 0.22f * p), style = Stroke(1.5.dp.toPx()))
            }
        }
        CardArt(Modifier.fillMaxWidth(0.74f).graphicsLayer { translationY = float * density; rotationZ = -8f; rotationX = 10f }, last4 = "4242", network = "Debit", tilt = tilt)
    }
}

@Composable
private fun TwoKeysArt() {
    val inf = rememberInfiniteTransition(label = "keys")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Box(Modifier.fillMaxWidth().aspectRatio(1.1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Tv.Accent.copy(alpha = 0.14f), Color.Transparent)), radius = size.minDimension * 0.5f)
            // a dashed link between the two keys with a travelling dot
            val y = size.height * 0.5f
            val x0 = size.width * 0.3f; val x1 = size.width * 0.7f
            var x = x0
            while (x < x1) { drawLine(Tv.StrokeStrong, Offset(x, y), Offset((x + 8.dp.toPx()).coerceAtMost(x1), y), 2.dp.toPx(), StrokeCap.Round); x += 16.dp.toPx() }
            drawCircle(Tv.Accent, 5.dp.toPx(), Offset(x0 + (x1 - x0) * pulse, y))
        }
        Row(Modifier.fillMaxWidth(0.86f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            KeyBubble(Ic.Fingerprint, "Phone")
            Box(Modifier.size(64.dp).background(Tv.GlassStrong, CircleShape).glassRim(32.dp, shape = CircleShape), contentAlignment = Alignment.Center) { TvIcon(Ic.ShieldCheck, 30.dp, Tv.Accent) }
            KeyBubble(Ic.Card, "Card")
        }
    }
}

@Composable
private fun KeyBubble(icon: Ic, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).background(Tv.GlassStrong, RoundedCornerShape(26.dp)).glassRim(26.dp), contentAlignment = Alignment.Center) {
            TvIcon(icon, 38.dp, Tv.Text)
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = Type.CaptionStrong, color = Tv.Text2)
    }
}

@Composable
private fun LimitsArt() {
    val inf = rememberInfiniteTransition(label = "lim")
    val f by inf.animateFloat(0.15f, 0.62f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    Box(Modifier.fillMaxWidth().aspectRatio(1.1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(Tv.Accent.copy(alpha = 0.14f), Color.Transparent)), radius = size.minDimension * 0.5f) }
        Column(Modifier.fillMaxWidth(0.82f).surface(26.dp, Tv.GlassStrong).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Ic.Send, Tv.Positive, Tv.PositiveSoft)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("0.5 NEAR", style = Type.Amount); Text("Instant", style = Type.Caption, color = Tv.Positive) }
                TvIcon(Ic.CheckCircle, 20.dp, Tv.Positive)
            }
            Spacer(Modifier.height(14.dp))
            Meter(f)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Ic.Send, Tv.Warn, Tv.WarnSoft)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("25 NEAR", style = Type.Amount); Text("Waits 1 h · cancel any time", style = Type.Caption, color = Tv.Warn) }
                TvIcon(Ic.Timer, 20.dp, Tv.Warn)
            }
        }
    }
}
