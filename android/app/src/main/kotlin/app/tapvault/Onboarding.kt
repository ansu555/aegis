package app.tapvault

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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

private data class Slide(@DrawableRes val art: Int, val title: String, val body: String)

/** Each slide's art is a full colour panel (res/drawable-nodpi/welcome_*.webp, 780×778 px). */
private val slides = listOf(
    Slide(R.drawable.welcome_card, "Your card is your key", "Tap it to approve. No seed phrase."),
    Slide(R.drawable.welcome_two_keys, "Two keys to pay", "Your fingerprint and your card."),
    Slide(R.drawable.welcome_limits, "Limits that protect you", "Small payments are instant. Big ones wait."),
    Slide(R.drawable.welcome_freeze, "Card lost? Freeze it", "One tap. Recover anytime."),
)
private const val ART_RATIO = 780f / 778f
private const val SLIDE_MS = 5_500

/** Mint: the welcome screen's call to action, the one colour that isn't in a slide panel. */
private val Mint = Color(0xFF7CF5C4)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Welcome(vm: AppViewModel) {
    val others = vm.walletList.count { !it.active }
    val pager = rememberPagerState { slides.size }
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    // Story style: the current segment fills up, then the next slide comes in. A swipe restarts the timer.
    val fill = remember { Animatable(0f) }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        fill.snapTo(0f)
        if (pager.isScrollInProgress) return@LaunchedEffect
        fill.animateTo(1f, tween(SLIDE_MS, easing = LinearEasing))
        pager.animateScrollToPage((pager.settledPage + 1) % slides.size)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(slides.size) { i ->
                val f = when { i < pager.settledPage -> 1f; i == pager.settledPage -> fill.value; else -> 0f }
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White, CircleShape))
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp), pageSpacing = 20.dp) { i ->
            // parallax: the text trails the panel a little
            val off = (pager.currentPage - i) + pager.currentPageOffsetFraction
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The panel takes the room the text leaves; it shrinks on short screens instead of pushing the text off.
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(slides[i].art), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(ART_RATIO, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(30.dp))
                            .pointerInput(Unit) {
                                // tap the left third to go back, anywhere else to go on
                                detectTapGestures { p ->
                                    val to = if (p.x < size.width / 3f) (i - 1).coerceAtLeast(0) else (i + 1) % slides.size
                                    scope.launch { pager.animateScrollToPage(to) }
                                }
                            },
                    )
                }
                // Every slide's text is laid out invisibly underneath, so the block is as tall as the longest
                // one: the panel keeps one size across slides and the body sits right under its title.
                Box(Modifier.padding(top = 24.dp).graphicsLayer { translationX = off * size.width * 0.12f }, contentAlignment = Alignment.TopCenter) {
                    slides.forEach { SlideText(it, Modifier.alpha(0f).clearAndSetSemantics {}) }
                    SlideText(slides[i])
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        NfcChip(vm)
        GlassButton(Ic.Plus, "Create wallet", Mint, Mint.copy(alpha = 0.14f)) { vm.go(Screen.Limits) }
        Spacer(Modifier.height(10.dp))
        GlassButton(Ic.Wallet, "I have a wallet") { more = true }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp)).pressable { vm.go(Screen.CardCheck) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Testnet · ", style = Type.Caption, color = Tv.Text3, maxLines = 1)
            Text("Check my card", style = Type.CaptionStrong, color = Mint, maxLines = 1)
        }
    }
    if (more) GlassSheet({ more = false }) {
        Text("I have a wallet", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Group {
            Row_(Ic.Refresh, "Recover a wallet", "Move it to this phone", chevron = true, onClick = { more = false; vm.go(Screen.Recover) })
            GroupDivider()
            Row_(Ic.ShieldCheck, "Be a guardian", "Help a friend recover", chevron = true, onClick = { more = false; vm.go(Screen.Guardian) })
            if (others > 0) {
                GroupDivider()
                Row_(Ic.Wallet, "Back to my wallets", if (others == 1) "1 wallet on this phone" else "$others wallets on this phone", chevron = true, onClick = { more = false; vm.cancelNewWallet() })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SlideText(slide: Slide, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            slide.title, style = Type.Hero.copy(fontSize = 30.sp, lineHeight = 36.sp), color = Tv.Text,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(slide.body, style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** A welcome button on the same glass box as a [Group]: icon tile, label, chevron. */
@Composable
private fun GlassButton(icon: Ic, text: String, tint: Color = Tv.Text, tileBg: Color = Tv.GlassStrong, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).surface(22.dp).clip(RoundedCornerShape(22.dp))
            .pressable(haptic = true, onClick = onClick).semantics { role = Role.Button }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, tileBg)
        Spacer(Modifier.width(14.dp))
        Text(text, style = Type.BodyStrong, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
        TvIcon(Ic.Chevron, 18.dp, Tv.Text3)
    }
}

/** A small amber chip when this phone can't read a card right now; the full notice waits on the limits screen. */
@Composable
private fun NfcChip(vm: AppViewModel) {
    val ctx = LocalContext.current
    val text = when {
        !vm.hasNfc -> "No NFC on this phone"
        !vm.nfcEnabled -> "NFC is off · Turn on"
        else -> return
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Tv.WarnSoft)
                .pressable(enabled = vm.hasNfc) { runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIcon(Ic.Nfc, 15.dp, Tv.Warn)
            Spacer(Modifier.width(6.dp))
            Text(text, style = Type.CaptionStrong, color = Tv.Warn, maxLines = 1)
        }
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
    var limitsOn by remember { mutableStateOf(vm.store.limitsOn) }
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
    val limitsOk = !limitsOn || (err == null && i != null && d != null)
    PinnedTopBar("New wallet", onBack = { vm.go(Screen.Welcome) })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
    Text("Spending limits", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
    Text("Small payments go out instantly. Bigger ones wait 1 hour.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(22.dp))
    Group {
        Row_(
            Ic.Timer, "Spending limits", if (limitsOn) "Small payments are instant" else "Every payment waits 1 hour",
            value = if (limitsOn) "On" else "Off", chevron = true, tint = if (limitsOn) Tv.Positive else Tv.Text,
            iconBg = if (limitsOn) Tv.PositiveSoft else Tv.GlassStrong,
            onClick = { limitsOn = !limitsOn },
        )
    }
    AnimatedVisibility(limitsOn) {
        Column {
            Spacer(Modifier.height(14.dp))
            LimitInput("Instant payment limit", "Largest payment that goes out at once", instant, { instant = clean(it) }, listOf("0.5", "1", "5"))
            Spacer(Modifier.height(14.dp))
            LimitInput("Daily limit", "Most that goes out instantly in a day", daily, { daily = clean(it) }, listOf("2", "5", "20"), err)
        }
    }
    var backup by remember { mutableStateOf(vm.store.wantBackup) }
    var strict by remember { mutableStateOf(vm.store.strictKey) }
    SectionHeader("Extra protection")
    Group {
        Row_(
            Ic.Card, "Backup card", "Add a second card now. Keep it at home.",
            value = if (backup) "On" else "Off", chevron = true, tint = if (backup) Tv.Positive else Tv.Text,
            iconBg = if (backup) Tv.PositiveSoft else Tv.GlassStrong,
            onClick = { backup = !backup; vm.setSetupOptions(backup, strict) },
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            GroupDivider()
            Row_(
                Ic.Fingerprint, "Fingerprint only", "No PIN or pattern for approvals",
                value = if (strict) "On" else "Off", chevron = true, tint = if (strict) Tv.Positive else Tv.Text,
                iconBg = if (strict) Tv.PositiveSoft else Tv.GlassStrong,
                onClick = { strict = !strict; vm.setSetupOptions(backup, strict) },
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    NfcNotice(vm)
    }
    TvButton("Continue", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), enabled = vm.hasNfc && limitsOk, icon = Ic.Chevron) { vm.beginSetup(limitsOn, instant, daily) }
}

@Composable
private fun LimitInput(title: String, sub: String, value: String, onChange: (String) -> Unit, presets: List<String>, error: String? = null) {
    Column(Modifier.fillMaxWidth().surface(22.dp).padding(16.dp)) {
        Text(title, style = Type.BodyStrong)
        Text(sub, style = Type.Caption, color = Tv.Text2)
        Spacer(Modifier.height(12.dp))
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        TvField("", value, onChange, number = true, placeholder = "Amount", error = error, focus = focus, trailing = { Text("NEAR", style = Type.BodyStrong, color = Tv.Text2, modifier = Modifier.padding(end = 8.dp)) })
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p -> Chip(p, selected = value == p) { onChange(p) } }
            // Custom: clear the field and type any amount
            Chip("Custom", selected = value !in presets) { onChange(""); runCatching { focus.requestFocus() } }
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
    ) + listOfNotNull(
        Triple(Ic.Card, "Backup card added", vm.setupStep >= 4).takeIf { vm.store.wantBackup },
    ) + listOf(
        Triple(Ic.Lock, "Limits set, account locked", vm.setupStep >= 4),
    )
    val done = steps.count { it.third }
    val active = steps.indexOfFirst { !it.third }
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
            if (vm.store.wantBackup && vm.setupStep >= 3) {
                TvButton("Skip the backup card", Modifier.fillMaxWidth(), Btn.Secondary) { vm.skipBackupCard() }
                Spacer(Modifier.height(8.dp))
            }
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
