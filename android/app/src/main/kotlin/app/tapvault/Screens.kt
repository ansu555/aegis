package app.tapvault

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.rotate
import app.tapvault.core.Near
import app.tapvault.core.ProposalInfo
import java.math.BigInteger

private val tabScreens = setOf(Screen.Home, Screen.Activity, Screen.CardCheck, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapvaultApp(vm: AppViewModel) {
    TapvaultTheme {
        val frozen = vm.wallet?.frozen == true
        AppBackground(glow = if (frozen && vm.screen == Screen.Home) Tv.Ice else Tv.Accent) {
            LaunchedEffect(Unit) { vm.refresh(); vm.startAutoRefresh() }
            val ready = vm.store.setupStep >= 4
            BackHandler(enabled = vm.screen !in setOf(Screen.Home, Screen.Welcome, Screen.Setup)) {
                vm.go(
                    when {
                        vm.screen == Screen.Limits -> Screen.Welcome
                        ready -> Screen.Home
                        else -> Screen.Welcome
                    },
                )
            }
            val uri = LocalUriHandler.current
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            LaunchedEffect(vm.screen) { focus.clearFocus(force = true) }
            val tabs = vm.screen in tabScreens && ready
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        vm.screen,
                        transitionSpec = { (fadeIn(tween(220, delayMillis = 90)) + slideInVertically(tween(300, delayMillis = 90, easing = FastOutSlowInEasing)) { it / 30 }) togetherWith fadeOut(tween(90)) },
                        label = "screen",
                    ) { s ->
                        val scroll = rememberScrollState()
                        val body: @Composable () -> Unit = {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding()
                                    .let { if (tabs) it else it.navigationBarsPadding() }.imePadding().padding(horizontal = 20.dp),
                            ) {
                                when (s) {
                                    Screen.Welcome -> Welcome(vm)
                                    Screen.Limits -> LimitsScreen(vm)
                                    Screen.Setup -> SetupProgress(vm)
                                    Screen.Home -> Home(vm)
                                    Screen.Send -> Send(vm)
                                    Screen.Activity -> ActivityScreen(vm)
                                    Screen.CardCheck -> CardCheck(vm)
                                    Screen.Settings -> SettingsScreen(vm)
                                }
                                Spacer(Modifier.height(28.dp))
                            }
                        }
                        if (s == Screen.Welcome) {
                            // The welcome pager fills the screen instead of scrolling.
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) { Welcome(vm) }
                        } else if (s == Screen.Limits || s == Screen.Send) {
                            // Form screens: content scrolls, the main button stays pinned above the keyboard.
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                                if (s == Screen.Limits) LimitsScreen(vm) else Send(vm)
                            }
                        } else if ((s == Screen.Home || s == Screen.Activity) && ready) {
                            PullToRefreshBox(vm.pulling, { vm.pullRefresh() }, Modifier.fillMaxSize()) { body() }
                        } else body()
                    }
                }
                AnimatedVisibility(tabs, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    BottomNav(vm.screen, vm.proposals.size, { vm.go(it) })
                }
            }
            // Scrim behind the status bar so scrolled content never runs under the clock.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Tv.Bg, Tv.Bg.copy(alpha = 0.85f)))))
            if (!tabs) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(Tv.Bg.copy(alpha = 0.92f)))
            OutcomeBanner(vm.outcome, { l -> uri.openUri(l) }, { vm.outcome = null }, Modifier.align(Alignment.TopCenter))
            vm.tap?.let { TapSheet(it) { vm.cancelTap() } }
            // Setup shows its progress in place; everything else gets the overlay.
            if (vm.tap == null && vm.screen != Screen.Setup) vm.busy?.let { BusyOverlay(it) }
        }
    }
}

// ---------------------------------------------------------------- proposal helpers

private fun camelWords(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

/** "Send" or the settings change in words ("Unfreeze", "Add signer"). */
private fun proposalTitle(p: ProposalInfo): String =
    if (p.isTransfer) "Send" else p.describe().removePrefix("Change settings: ").split(", ").joinToString(", ") { camelWords(it) }

private fun proposalSubtitle(p: ProposalInfo): String =
    p.transferReceiver()?.let { "To ${shortAccount(it)}" } ?: "Wallet setting"

private fun shortDescribe(p: ProposalInfo) = p.describe().removePrefix("Change settings: ")

/** Seconds until the contract will execute [p] (0 = now), or null while it still needs approvals. */
private fun secondsToExecute(vm: AppViewModel, p: ProposalInfo): Long? {
    vm.wallet ?: return null
    val ready = p.readyTs ?: return null
    if (p.stale) return null
    val now = System.currentTimeMillis() / 1000
    return (ready + delayFor(vm, p) - now).coerceAtLeast(0)
}

/** The delay the contract applies to [p] (transfers within the NEAR limit run at once). */
private fun delayFor(vm: AppViewModel, p: ProposalInfo): Long {
    val w = vm.wallet ?: return 0
    val phoneIdx = runCatching { w.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    return if (p.isTransfer) {
        if (p.isNearTransfer && w.nearLimit?.isInstant(p.transferAmount() ?: BigInteger.ZERO) == true) 0 else w.delays.transfer
    } else if (phoneIdx != null && p.approvedBy(phoneIdx)) w.delays.config else w.delays.recovery
}

/** Past the contract's execution window (7 days after it became executable): only Cancel or Clean up remain. */
private fun expired(vm: AppViewModel, p: ProposalInfo): Boolean {
    val left = secondsToExecute(vm, p) ?: return false
    val ready = p.readyTs ?: return false
    val now = System.currentTimeMillis() / 1000
    return left == 0L && now > ready + EXECUTION_WINDOW_SECS + delayFor(vm, p)
}

/** close_stale's rule: void proposals, unapproved ones after a day, approved ones after the longest delay plus 7 days. */
private fun closable(vm: AppViewModel, p: ProposalInfo): Boolean {
    val now = System.currentTimeMillis() / 1000
    val d = vm.wallet?.delays ?: return p.stale
    val maxDelay = maxOf(d.transfer, d.config, d.recovery)
    return p.stale || p.readyTs?.let { now > it + maxDelay + EXECUTION_WINDOW_SECS } ?: (now > p.createdTs + 86_400)
}

private const val EXECUTION_WINDOW_SECS = 7L * 86_400

private data class Status(val text: String, val color: Color, val icon: Ic)

private fun proposalStatus(vm: AppViewModel, p: ProposalInfo): Status {
    val left = secondsToExecute(vm, p)
    return when {
        p.stale -> Status("Void: signers changed", Tv.Danger, Ic.XCircle)
        p.readyTs == null -> Status("Waiting for approvals", Tv.Warn, Ic.Hourglass)
        expired(vm, p) -> Status("Expired: it can no longer execute; cancel it", Tv.Danger, Ic.XCircle)
        left == null -> Status("", Tv.Text2, Ic.Info)
        left > 0 -> Status("Approved · can execute in ${formatDuration(left)}", Tv.Text2, Ic.Timer)
        vm.wallet?.frozen == true && p.isTransfer -> Status("Approved · wallet is frozen", Tv.Ice, Ic.Freeze)
        else -> Status("Approved · ready: tap Execute", Tv.Accent, Ic.CheckCircle)
    }
}

/** Compact proposal line for Home. */
@Composable
private fun ProposalRow(vm: AppViewModel, p: ProposalInfo, onClick: () -> Unit) {
    val st = proposalStatus(vm, p)
    Row_(
        if (p.isTransfer) Ic.Send else Ic.Settings, "${proposalTitle(p)} · #${p.id}", st.text,
        tint = st.color, iconBg = st.color.copy(alpha = 0.12f), onClick = onClick, chevron = true,
    ) {
        p.transferAmount()?.takeIf { p.isNearTransfer }?.let { Text("−${Near.format(it)} NEAR", style = Type.Amount.copy(fontSize = 15.sp)) }
    }
}


// ---------------------------------------------------------------- home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(vm: AppViewModel) {
    val w = vm.wallet
    var showReceive by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmFreeze by remember { mutableStateOf(false) }
    val frozen = w?.frozen == true
    val idle = vm.busy == null

    // header: wallet switcher + network
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(enabled = idle) { showPicker = true }.padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(vm.walletName, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.walletName, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    TvIcon(Ic.ChevronDown, 18.dp, Tv.Text2)
                }
                Text(vm.store.walletAccount?.let { shortAccount(it, 26) } ?: "", style = Type.Caption, color = Tv.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } }
        Spacer(Modifier.width(12.dp))
        RoundIcon(Ic.Qr, "Receive", 42.dp) { showReceive = true }
    }
    vm.offline?.let { Notice(Ic.Offline, "Offline", it, Tv.Warn, Modifier.padding(top = 14.dp)) }

    // balance
    Column(Modifier.fillMaxWidth().padding(top = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Total balance", style = Type.CaptionStrong, color = Tv.Text2)
        Spacer(Modifier.height(6.dp))
        if (vm.balance == null) Shimmer(Modifier.size(200.dp, 52.dp)) else Amount(vm.balance, 4)
        Spacer(Modifier.height(12.dp))
        when {
            frozen -> StatusPill("Frozen", Tv.Ice, Ic.Freeze)
            w?.nearLimit != null -> {
                val instant = w.nearLimit!!.availableNow().let { a -> vm.balance?.let { a.min(it) } ?: a }
                StatusPill("${Near.format(instant, 2)} NEAR available instantly", Tv.Accent)
            }
            w == null && vm.offline == null -> Shimmer(Modifier.size(170.dp, 26.dp))
        }
    }

    // actions
    Row(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(Ic.Send, "Send", primary = true, enabled = w?.frozen == false && idle) { vm.go(Screen.Send) }
        ActionButton(Ic.Receive, "Receive") { showReceive = true }
        ActionButton(Ic.Freeze, if (frozen) "Frozen" else "Freeze", tint = Tv.Ice, enabled = w?.frozen == false && idle) { confirmFreeze = true }
        ActionButton(Ic.Scan, "Check card") { vm.go(Screen.CardCheck) }
    }

    if (frozen) {
        Spacer(Modifier.height(24.dp))
        Notice(Ic.Freeze, "Wallet frozen", "No transfers can go out. Unfreezing needs your phone and your card, then waits ${hoursLabel(w!!.delays.config)}.", Tv.Ice) {
            TvButton("Request unfreeze", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Key, height = 48.dp, enabled = idle) { vm.requestUnfreeze() }
        }
    }

    // pending
    SectionHeader("Pending approvals", action = if (vm.proposals.isNotEmpty()) "See all" else null) { vm.go(Screen.Activity) }
    Group {
        if (vm.proposals.isEmpty()) Row_(Ic.CheckCircle, "All clear", "Nothing waiting for approval", tint = Tv.Accent, iconBg = Tv.AccentSoft)
        vm.proposals.take(3).forEachIndexed { i, p ->
            if (i > 0) GroupDivider()
            ProposalRow(vm, p) { vm.go(Screen.Activity) }
        }
    }

    // limits
    w?.nearLimit?.let { l ->
        SectionHeader("Spending limits")
        val now = System.currentTimeMillis() / 1000
        val elapsed = (now - l.updatedAt).coerceAtLeast(0)
        val usedNow = if (elapsed >= 86_400) BigInteger.ZERO else (l.used - l.dailyMax * BigInteger.valueOf(elapsed) / BigInteger.valueOf(86_400)).max(BigInteger.ZERO)
        val frac = if (l.dailyMax.signum() == 0) 0f else usedNow.toBigDecimal().divide(l.dailyMax.toBigDecimal(), 4, java.math.RoundingMode.HALF_UP).toFloat()
        Column(Modifier.fillMaxWidth().surface(22.dp).padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Used today", style = Type.Body, color = Tv.Text2, modifier = Modifier.weight(1f))
                Text("${Near.format(usedNow, 2)} / ${Near.format(l.dailyMax, 2)} NEAR", style = Type.Amount.copy(fontSize = 15.sp))
            }
            Spacer(Modifier.height(10.dp))
            Meter(frac, if (frac > 0.85f) Tv.Warn else Tv.Accent)
            Spacer(Modifier.height(14.dp))
            InfoRow("Instant up to", "${Near.format(l.instantMax)} NEAR per payment")
            InfoRow("Bigger payments", "wait ${hoursLabel(w.delays.transfer)}, cancellable", Tv.Text2)
        }
    }

    // security
    SectionHeader("Security")
    Group {
        val card = listOfNotNull(vm.store.cardNetwork, vm.store.cardLast4?.let { "•••• $it" }).joinToString(" ").ifBlank { "Bank card" }
        Row_(Ic.Fingerprint, "This phone", "Fingerprint or screen lock, key in secure hardware", value = "P-256")
        GroupDivider()
        Row_(Ic.Card, card, "Tap to approve every payment", value = "RSA")
        GroupDivider()
        Row_(Ic.ShieldCheck, "${w?.threshold ?: 2} of ${w?.signers?.size ?: 2} approvals required", "Neither key alone can move funds", tint = Tv.Accent, iconBg = Tv.AccentSoft)
    }
    vm.storageLocked?.let { Caption("+ ${Near.format(it, 2)} NEAR reserved for the wallet contract's storage", Modifier.fillMaxWidth().padding(top = 14.dp), Tv.Text3, TextAlign.Center) }

    if (showPicker) WalletPicker(vm) { showPicker = false }
    if (showReceive) ReceiveSheet(vm) { showReceive = false }
    if (confirmFreeze) ConfirmDialog(
        "Freeze the wallet?",
        "One card tap stops every transfer. Unfreezing needs your phone and card, then waits ${hoursLabel(w?.delays?.config ?: 3600)}.",
        "Freeze", { confirmFreeze = false; vm.freezeWithCard() }, { confirmFreeze = false }, icon = Ic.Freeze,
    )
}

/** Placeholder while values load. */
@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "sh")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(modifier.alpha(a).background(Tv.Surface2, RoundedCornerShape(12.dp)))
}

// ---------------------------------------------------------------- receive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val acct = vm.store.walletAccount ?: ""
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Tv.Surface) {
        SheetColumn {
            Text("Receive NEAR", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("Scan, or share your account name", style = Type.Body, color = Tv.Text2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(acct, Modifier.size(232.dp)) }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().surface(18.dp, Tv.Surface2).padding(16.dp)) {
                Text("${vm.walletName} · NEAR testnet", style = Type.CaptionStrong, color = Tv.Text2)
                Text(acct, style = Type.Mono, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(if (copied) "Copied" else "Copy", Modifier.weight(1f), icon = if (copied) Ic.Check else Ic.Copy) { clip.setText(AnnotatedString(acct)); copied = true }
                TvButton("Share", Modifier.weight(1f), Btn.Secondary, icon = Ic.Share) {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, acct), "Share account"))
                }
            }
            Notice(Ic.Info, "Testnet only", "Send only testnet NEAR to this account. Real NEAR sent here can't be recovered.", Tv.Warn, Modifier.padding(top = 16.dp))
        }
    }
}

// ---------------------------------------------------------------- send

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.Send(vm: AppViewModel) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val w = vm.wallet
    val limit = w?.nearLimit
    val parsed = runCatching { Near.parse(amount).takeIf { it.signum() > 0 } }.getOrNull()
    val spendable = vm.balance
    val validTo = to.matches(Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")) && to.length in 2..64
    val toError = if (to.length >= 2 && !validTo) "That isn't a NEAR account name" else if (to == vm.store.walletAccount) "That's this wallet's own account" else null
    val tooMuch = parsed != null && spendable != null && parsed > spendable
    val instant = parsed != null && limit?.isInstant(parsed) == true

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
    TopBar("Send NEAR", onBack = { vm.go(Screen.Home) })
    Spacer(Modifier.height(6.dp))
    TvField("To", to, { to = it.trim().lowercase() }, placeholder = "name.testnet", error = toError, trailing = {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Tv.Surface2).clickable { clip.getText()?.text?.let { to = it.trim().lowercase() } }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { TvIcon(Ic.Paste, 16.dp, Tv.Accent); Spacer(Modifier.width(6.dp)); Text("Paste", style = Type.CaptionStrong, color = Tv.Accent) }
        }
    })
    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth().surface(26.dp).padding(vertical = 26.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AmountField(amount, { v -> amount = v.replace(',', '.').filter { it.isDigit() || it == '.' }.take(14) })
        Spacer(Modifier.height(14.dp))
        Text(
            if (tooMuch) "More than the wallet holds" else "Available: ${spendable?.let { Near.format(it, 4) } ?: "…"} NEAR",
            style = Type.Caption, color = if (tooMuch) Tv.Danger else Tv.Text2,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Half") { spendable?.let { amount = Near.format(it.divide(BigInteger.valueOf(2)), 4) } }
            Chip("Max instant") {
                // keep a little behind so the wallet can always pay for its own storage
                val buffer = Near.parse("0.01")
                val most = listOfNotNull(limit?.availableNow(), spendable?.minus(buffer)).minOrNull()
                most?.let { amount = Near.format(it.max(BigInteger.ZERO), 4) }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().surface(22.dp).padding(horizontal = 18.dp, vertical = 10.dp)) {
        InfoRow("Speed", when { parsed == null -> "–"; instant -> "Instant"; else -> "Waits ${hoursLabel(w?.delays?.transfer ?: 3600)}" }, if (parsed == null) Tv.Text2 else if (instant) Tv.Accent else Tv.Warn)
        limit?.let { InfoRow("Instant allowance left", "${Near.format(it.availableNow(), 2)} NEAR") }
        InfoRow("Network fee", "Paid by your fee account")
        InfoRow("Approve with", "Fingerprint + card tap")
    }
    Spacer(Modifier.height(20.dp))
    }
    TvButton("Review", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), enabled = vm.busy == null && validTo && toError == null && parsed != null && !tooMuch) { review = true }

    if (review && parsed != null) {
        ModalBottomSheet({ review = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Tv.Surface) {
            SheetColumn {
                Text("Review payment", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Amount(parsed, 4, Type.Balance.copy(fontSize = 40.sp)) }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth().surface(20.dp, Tv.Surface2).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    InfoRow("To", to)
                    InfoRow("From", vm.walletName)
                    InfoRow("Speed", if (instant) "Instant" else "Waits ${hoursLabel(w?.delays?.transfer ?: 3600)}, cancellable", if (instant) Tv.Accent else Tv.Warn)
                    InfoRow("Network", "NEAR testnet")
                }
                Spacer(Modifier.height(16.dp))
                Text("Next", style = Type.CaptionStrong, color = Tv.Text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    StepNum(1); Spacer(Modifier.width(10.dp)); Text("Confirm with your fingerprint", style = Type.Body)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    StepNum(2); Spacer(Modifier.width(10.dp)); Text("Tap your bank card on the phone", style = Type.Body)
                }
                Spacer(Modifier.height(22.dp))
                TvButton("Approve and send", Modifier.fillMaxWidth(), icon = Ic.Fingerprint) { review = false; vm.send(to, amount) }
                Spacer(Modifier.height(6.dp))
                TvButton("Edit", Modifier.fillMaxWidth(), Btn.Ghost) { review = false }
            }
        }
    }
}

@Composable
private fun StepNum(n: Int) {
    Box(Modifier.size(26.dp).background(Tv.AccentSoft, CircleShape), contentAlignment = Alignment.Center) { Text("$n", style = Type.CaptionStrong, color = Tv.Accent) }
}

// ---------------------------------------------------------------- activity

@Composable
private fun ActivityScreen(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    var showLog by remember { mutableStateOf(false) }
    LargeTitle("Activity", "Approvals, delays and history")
    if (vm.proposals.isEmpty()) {
        Column(Modifier.fillMaxWidth().surface(26.dp).padding(vertical = 34.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).background(Tv.AccentSoft, CircleShape), contentAlignment = Alignment.Center) { TvIcon(Ic.CheckCircle, 30.dp, Tv.Accent) }
            Spacer(Modifier.height(14.dp))
            Text("You're all caught up", style = Type.Title)
            Text("Payments waiting for an approval or a delay show up here.", style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    } else {
        Text("Pending · ${vm.proposals.size}", style = Type.Title, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        vm.proposals.forEach { p -> ProposalCard(vm, p); Spacer(Modifier.height(12.dp)) }
    }
    SectionHeader("History")
    Group {
        vm.store.walletAccount?.let { a ->
            Row_(Ic.External, "All transactions", "Open this wallet on the NEAR explorer", chevron = true, onClick = { uri.openUri(vm.near.explorerAccount(a)) })
        }
    }
    SectionHeader("Technical log", action = if (showLog) "Hide" else "Show") { showLog = !showLog }
    AnimatedVisibility(showLog, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(Modifier.fillMaxWidth().surface(20.dp).padding(16.dp)) {
            if (vm.log.isEmpty()) Caption("Nothing yet.")
            vm.log.take(60).forEach { Text(it, style = Type.Caption.copy(fontFeatureSettings = "tnum"), color = Tv.Text2, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
private fun ProposalCard(vm: AppViewModel, p: ProposalInfo) {
    val w = vm.wallet
    val now = System.currentTimeMillis() / 1000
    val cardIdx = w?.card()?.index
    val phoneIdx = runCatching { w?.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    val st = proposalStatus(vm, p)
    val idle = vm.busy == null
    Column(Modifier.fillMaxWidth().surface(24.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (p.isTransfer) Ic.Send else Ic.Settings, st.color, st.color.copy(alpha = 0.12f), 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(proposalTitle(p), style = Type.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${proposalSubtitle(p)} · #${p.id}", style = Type.Caption, color = Tv.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            p.transferAmount()?.takeIf { p.isNearTransfer }?.let { Text("−${Near.format(it)} NEAR", style = Type.Amount) }
        }
        Spacer(Modifier.height(14.dp))
        if (st.text.isNotEmpty()) StatusPill(st.text, st.color, st.icon)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApprovalMark("Phone", Ic.Fingerprint, phoneIdx?.let { p.approvedBy(it) } ?: false, Modifier.weight(1f))
            ApprovalMark("Card", Ic.Card, cardIdx?.let { p.approvedBy(it) } ?: false, Modifier.weight(1f))
        }
        val canCard = cardIdx != null && !p.approvedBy(cardIdx) && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now && !p.stale
        val canPhone = phoneIdx != null && !p.approvedBy(phoneIdx) && !p.stale
        val canExecute = secondsToExecute(vm, p) == 0L && !expired(vm, p) && !(w?.frozen == true && p.isTransfer)
        if (canCard || canPhone || canExecute) Spacer(Modifier.height(14.dp))
        if (canCard) { TvButton("Approve · card", Modifier.fillMaxWidth(), icon = Ic.Card, height = 48.dp, enabled = idle) { vm.approveWithCard(p) }; Spacer(Modifier.height(8.dp)) }
        if (canPhone) { TvButton("Approve · phone", Modifier.fillMaxWidth(), if (canCard) Btn.Secondary else Btn.Primary, icon = Ic.Fingerprint, height = 48.dp, enabled = idle) { vm.approveWithPhone(p) }; Spacer(Modifier.height(8.dp)) }
        if (canExecute) { TvButton("Execute", Modifier.fillMaxWidth(), icon = Ic.Check, height = 48.dp, enabled = idle) { vm.execute(p) }; Spacer(Modifier.height(8.dp)) }
        val canClose = closable(vm, p)
        if (!p.stale || canClose) {
            Spacer(Modifier.height(if (canCard || canPhone || canExecute) 0.dp else 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!p.stale) TvButton("Cancel", Modifier.weight(1f), Btn.Danger, height = 44.dp, enabled = idle) { vm.cancel(p) }
                if (canClose) TvButton("Clean up", Modifier.weight(1f), Btn.Secondary, height = 44.dp, enabled = idle) { vm.closeStale(p) }
            }
        }
    }
}

@Composable
private fun ApprovalMark(label: String, icon: Ic, ok: Boolean, modifier: Modifier) {
    Row(
        modifier.background(if (ok) Tv.AccentSoft else Tv.Surface2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIcon(icon, 16.dp, if (ok) Tv.Accent else Tv.Text3)
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.CaptionStrong, color = if (ok) Tv.Text else Tv.Text2, modifier = Modifier.weight(1f))
        TvIcon(if (ok) Ic.CheckCircle else Ic.Hourglass, 16.dp, if (ok) Tv.Accent else Tv.Text3)
    }
}

// ---------------------------------------------------------------- card check

@Composable
private fun CardCheck(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ready = vm.store.setupStep >= 4
    if (ready) LargeTitle("Card check", "See whether a card can sign for Tapvault")
    else {
        TopBar("Card check", onBack = { vm.go(Screen.Welcome) })
        Text("See whether a card can sign for Tapvault", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(bottom = 6.dp))
    }
    val r = vm.cardReport
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        CardArt(Modifier.fillMaxWidth(), last4 = r?.panLast4, network = r?.network)
    }
    if (r == null) {
        Group {
            Row_(Ic.Scan, "Reads the payment chip", "The same contactless link a card terminal uses", tint = Tv.Accent, iconBg = Tv.AccentSoft)
            GroupDivider()
            Row_(Ic.Key, "Checks it can sign", "Looks for an RSA key Tapvault can verify on-chain", tint = Tv.Accent, iconBg = Tv.AccentSoft)
            GroupDivider()
            Row_(Ic.Lock, "Your card number stays here", "Only the last four digits are ever shown", tint = Tv.Accent, iconBg = Tv.AccentSoft)
        }
        Spacer(Modifier.height(18.dp))
    }
    NfcNotice(vm)
    TvButton(if (r == null) "Check a card" else "Check another card", Modifier.fillMaxWidth(), if (r == null) Btn.Primary else Btn.Secondary, icon = Ic.Nfc, enabled = vm.busy == null && vm.hasNfc) { vm.checkCard() }
    r?.let {
        Spacer(Modifier.height(4.dp))
        CardReportCard(it)
        Spacer(Modifier.height(10.dp))
        TvButton("Share report", Modifier.fillMaxWidth(), Btn.Ghost, icon = Ic.Share) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.diagnostics()), "Share card report"))
        }
    }
}

@Composable
fun CardReportCard(r: app.tapvault.core.CardReport) {
    val ok = r.canSign
    var details by remember { mutableStateOf(!ok) }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().surface(24.dp, stroke = (if (ok) Tv.Accent else Tv.Danger).copy(alpha = 0.3f)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background((if (ok) Tv.Accent else Tv.Danger).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                TvIcon(if (ok) Ic.CheckCircle else Ic.XCircle, 26.dp, if (ok) Tv.Accent else Tv.Danger)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ok) "This card can sign" else "This card can't sign", style = Type.Title, color = if (ok) Tv.Text else Tv.Danger)
                Text("${r.network} •••• ${r.panLast4 ?: "????"} · ${r.label.ifBlank { r.aid }}", style = Type.Caption, color = Tv.Text2, maxLines = 2)
            }
        }
        r.notes.forEach { Text("· $it", style = Type.Caption, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { details = !details }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Technical details", style = Type.CaptionStrong, color = Tv.Text2, modifier = Modifier.weight(1f))
            TvIcon(Ic.ChevronDown, 18.dp, Tv.Text3, Modifier.rotate(if (details) 180f else 0f))
        }
        AnimatedVisibility(details, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                InfoRow("Offline auth", listOfNotNull("SDA".takeIf { r.sdaSupported }, "DDA".takeIf { r.ddaSupported }, "CDA".takeIf { r.cdaSupported }).joinToString(" · ").ifEmpty { "none" })
                InfoRow("CA key index", r.caIndex ?: "–")
                InfoRow("Issuer key", r.issuerKeyBits?.let { "$it bit" } ?: "–")
                InfoRow("Card key", r.iccKeyBits?.let { "$it bit, e=${r.iccExponent}" } ?: "–")
                InfoRow("Signs with", r.signsWith ?: "–")
                InfoRow("Challenge format", r.ddol ?: "–")
            }
        }
    }
}


// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    val clip = LocalClipboardManager.current
    var confirmReset by remember { mutableStateOf(false) }
    var confirmFreeze by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val w = vm.wallet
    LargeTitle("Settings")

    // profile
    Row(Modifier.fillMaxWidth().surface(24.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(vm.walletName, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.walletName, style = Type.Title)
            vm.store.walletAccount?.let { a ->
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { clip.setText(AnnotatedString(a)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortAccount(a, 28), style = Type.Caption, color = Tv.Text2)
                    Spacer(Modifier.width(6.dp))
                    TvIcon(Ic.Copy, 14.dp, Tv.Text3)
                }
            }
        }
        RoundIcon(Ic.Edit, "Rename", 40.dp) { renaming = vm.walletName }
    }

    SectionHeader("Wallets")
    Group {
        Row_(Ic.Layers, "My wallets", "${vm.walletList.size} on this phone · switch or add one", chevron = true, onClick = { showPicker = true })
        vm.store.walletAccount?.let { a -> GroupDivider(); Row_(Ic.Wallet, "Wallet account", shortAccount(a, 30), trailingIcon = Ic.External, onClick = { uri.openUri(vm.near.explorerAccount(a)) }) }
        vm.store.gasAccount?.let { a -> GroupDivider(); Row_(Ic.Send, "Fee account", "Pays network fees, can't move funds", trailingIcon = Ic.External, onClick = { uri.openUri(vm.near.explorerAccount(a)) }) }
    }

    SectionHeader("Security")
    Group {
        Row_(Ic.Fingerprint, "Phone key", if (vm.phoneKeyExists) "${vm.phone.hardwareLabel} · this wallet only" else "none", value = "P-256")
        GroupDivider()
        val card = listOfNotNull(vm.store.cardNetwork, vm.store.cardLast4?.let { "•••• $it" }).joinToString(" ")
        Row_(Ic.Card, if (card.isBlank()) "Bank card" else card, app.tapvault.core.CardRecipe.decode(vm.store.cardRecipe)?.let { "Contactless over NFC · ${it.method.label}" } ?: "Contactless bank card over NFC", value = "RSA")
        w?.let {
            GroupDivider()
            Row_(Ic.ShieldCheck, "Approvals", "${it.threshold} of ${it.signers.size} signers for every payment", tint = Tv.Accent, iconBg = Tv.AccentSoft)
            GroupDivider()
            Row_(Ic.Timer, "Delays", "Transfers ${hoursLabel(it.delays.transfer)} · changes ${hoursLabel(it.delays.config)} · recovery ${hoursLabel(it.delays.recovery)}")
        }
    }

    if (w?.frozen == false) {
        SectionHeader("Emergency")
        Group {
            Row_(Ic.Freeze, "Freeze with phone", "Stop all transfers right away, no card needed", tint = Tv.Ice, iconBg = Tv.IceSoft, titleColor = Tv.Ice, chevron = true, onClick = { if (vm.busy == null) confirmFreeze = true })
        }
    }

    SectionHeader("About")
    Group {
        Row_(Ic.Info, "Network", "NEAR testnet", value = "v${BuildConfig.VERSION_NAME}")
    }
    Spacer(Modifier.height(18.dp))
    TvButton("Forget this wallet on this phone", Modifier.fillMaxWidth(), Btn.Danger, icon = Ic.Trash) { confirmReset = true }

    if (showPicker) WalletPicker(vm) { showPicker = false }
    renaming?.let { current ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Tv.Surface2,
            shape = RoundedCornerShape(28.dp),
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.renameWallet(current); renaming = null }, enabled = current.isNotBlank()) { Text("Save", style = Type.Button, color = Tv.Accent) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel", style = Type.Button, color = Tv.Text2) } },
            title = { Text("Wallet name", style = Type.H2) },
            text = { TvField("Shown on this phone only", current, { renaming = it.take(24) }) },
        )
    }
    if (confirmFreeze) ConfirmDialog(
        "Freeze with phone?",
        "Every transfer stops at once. Unfreezing needs your phone and card, then waits ${hoursLabel(w?.delays?.config ?: 3600)}.",
        "Freeze", { confirmFreeze = false; vm.freezeWithPhone() }, { confirmFreeze = false }, icon = Ic.Freeze,
    )
    if (confirmReset) ConfirmDialog(
        "Forget ${vm.walletName}?",
        "This deletes this wallet's phone key. With a single card the wallet can't approve anything without it, so its NEAR stays locked in the wallet for good (on testnet that is only test NEAR). Your other wallets on this phone aren't affected.",
        "Forget", { confirmReset = false; vm.forgetWallet() }, { confirmReset = false }, dismiss = "Keep", danger = true, icon = Ic.Trash,
    )
}

// ---------------------------------------------------------------- wallet switcher

/** Every wallet on this phone; tap one to switch, or add a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletPicker(vm: AppViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.reloadWalletList(); vm.loadWalletBalances() }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Tv.Surface) {
        SheetColumn {
            Text("My wallets", style = Type.H2)
            Text("Each wallet has its own NEAR account, phone key and card.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.walletList.forEach { e ->
                    val sub = when {
                        e.ready -> e.account?.let { shortAccount(it, 28) } ?: ""
                        e.account != null -> "Setup not finished"
                        else -> "Not created yet"
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (e.active) Tv.AccentSoft else Tv.Surface2)
                            .border(1.dp, if (e.active) Tv.Accent.copy(alpha = 0.4f) else Tv.Stroke, RoundedCornerShape(20.dp))
                            .clickable { onDismiss(); vm.switchWallet(e.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(e.name, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = Type.BodyStrong)
                            Text(sub, style = Type.Caption, color = if (e.ready) Tv.Text2 else Tv.Warn, maxLines = 1)
                        }
                        val bal = vm.walletBalances[e.id]
                        if (bal != null) Text("${Near.format(bal, 2)} NEAR", style = Type.Amount.copy(fontSize = 15.sp))
                        else if (e.ready) Shimmer(Modifier.size(60.dp, 16.dp))
                        if (e.active) { Spacer(Modifier.width(10.dp)); TvIcon(Ic.CheckCircle, 20.dp, Tv.Accent) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TvButton("Add a wallet", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Plus, enabled = vm.busy == null) { onDismiss(); vm.addWallet() }
        }
    }
}
