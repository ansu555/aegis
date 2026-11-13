package app.tapvault

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapvault.core.Near
import app.tapvault.core.ProposalInfo
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.math.BigInteger

private val tabScreens = setOf(Screen.Home, Screen.Activity, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun TapvaultApp(vm: AppViewModel) {
    TapvaultTheme {
        val frozen = vm.wallet?.frozen == true
        val haze = remember { HazeState() }
        AppBackground(vm.screen, frozen && vm.screen == Screen.Home) {
            LaunchedEffect(Unit) { vm.refresh(); vm.startAutoRefresh() }
            val ready = vm.store.setupStep >= 4
            BackHandler(enabled = vm.screen !in setOf(Screen.Home, Screen.Welcome, Screen.Setup)) {
                vm.go(
                    when {
                        vm.screen == Screen.Limits -> Screen.Welcome
                        vm.screen == Screen.Recover || vm.screen == Screen.Guardian -> if (vm.store.joinMode != null) vm.screen else Screen.Welcome
                        (vm.screen == Screen.CardCheck || vm.screen == Screen.Security) && ready -> vm.cameFrom
                        ready -> Screen.Home
                        else -> Screen.Welcome
                    },
                )
            }
            val uri = LocalUriHandler.current
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            LaunchedEffect(vm.screen) { focus.clearFocus(force = true) }
            val tabs = vm.screen in tabScreens && ready
            CompositionLocalProvider(LocalHaze provides haze) {
                // Everything on screen is the blur source for the floating glass (tab bar, banners, sheets).
                Box(Modifier.fillMaxSize().hazeSource(haze)) { androidx.compose.animation.SharedTransitionLayout { CompositionLocalProvider(LocalSharedScope provides this) {
                    AnimatedContent(
                        vm.screen,
                        transitionSpec = {
                            (fadeIn(tween(240, delayMillis = 70)) + slideInVertically(spring(dampingRatio = 0.86f, stiffness = 340f)) { it / 24 } + scaleIn(tween(320, 70), initialScale = 0.985f)) togetherWith
                                fadeOut(tween(90))
                        },
                        label = "screen",
                    ) { s -> CompositionLocalProvider(LocalScreenScope provides this) {
                        val scroll = rememberScrollState()
                        val body: @Composable () -> Unit = {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().navigationBarsPadding()
                                    .imePadding().padding(horizontal = 20.dp),
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
                                    Screen.Security -> SecurityScreen(vm)
                                    Screen.Recover, Screen.Guardian -> {} // pinned-form layout below
                                }
                                // room for the floating tab bar
                                Spacer(Modifier.height(if (s in tabScreens && ready) 104.dp else 28.dp))
                            }
                        }
                        if (s == Screen.Welcome) {
                            // The welcome pager fills the screen instead of scrolling.
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) { Welcome(vm) }
                        } else if (s == Screen.Limits || s == Screen.Send || s == Screen.Recover || s == Screen.Guardian) {
                            // Form screens: content scrolls, the main button stays pinned above the keyboard.
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                                when (s) {
                                    Screen.Limits -> LimitsScreen(vm)
                                    Screen.Recover -> RecoverScreen(vm)
                                    Screen.Guardian -> GuardianScreen(vm)
                                    else -> Send(vm)
                                }
                            }
                        } else if ((s == Screen.Home || s == Screen.Activity) && ready) {
                            PullToRefreshBox(vm.pulling, { vm.pullRefresh() }, Modifier.fillMaxSize()) { body() }
                        } else body()
                    } }
                } } }
                // Frosted strip behind the status bar so scrolled content never runs under the clock.
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).frosted(RectangleShape, strength = 0.6f))
                AnimatedVisibility(
                    tabs, Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
                ) {
                    GlassTabBar(navItems, vm.screen, vm.proposals.size, { vm.go(it) })
                }
                OutcomeBanner(vm.outcome, { l -> uri.openUri(l) }, { vm.outcome = null }, Modifier.align(Alignment.TopCenter))
                vm.tap?.let { TapSheet(it) { vm.cancelTap() } }
                // Setup shows its progress in place; everything else gets the overlay.
                if (vm.tap == null && vm.screen != Screen.Setup) vm.busy?.let { BusyOverlay(it) }
                if (vm.locked) LockOverlay(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- proposal helpers

private fun camelWords(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

/** "Send" or the settings change in words ("Unfreeze", "Add signer"). */
private fun proposalTitle(p: ProposalInfo): String = when {
    p.isTransfer -> "Send"
    p.addedCardKeyHashes().isNotEmpty() && p.removedKeyHashes().isEmpty() -> "Add backup card"
    else -> p.describe().removePrefix("Change settings: ").split(", ").joinToString(", ") { camelWords(it) }
}

private fun proposalSubtitle(vm: AppViewModel, p: ProposalInfo): String =
    p.transferReceiver()?.let { "To ${shortAccount(it)}" }
        ?: p.addedCardKeyHashes().firstOrNull()?.let { h -> vm.cardBook.find(h)?.name ?: "A card not known on this phone" }
        ?: "Wallet setting"

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
        if (p.isNearTransfer && w.isInstantTo(p.transferReceiver(), p.transferAmount() ?: BigInteger.ZERO)) 0 else w.delays.transfer
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
        else -> Status("Approved · ready: tap Execute", Tv.Positive, Ic.CheckCircle)
    }
}


/** Compact proposal line for Home. */
@Composable
private fun ProposalRow(vm: AppViewModel, p: ProposalInfo, onClick: () -> Unit) {
    val st = proposalStatus(vm, p)
    Row_(
        if (p.isTransfer) Ic.Send else Ic.Settings, "${proposalTitle(p)} · #${p.id}", st.text,
        tint = st.color, iconBg = st.color.copy(alpha = 0.13f), onClick = onClick, chevron = true,
    ) {
        p.transferAmount()?.takeIf { p.isNearTransfer }?.let { Text("−${Near.format(it)} NEAR", style = Type.Amount.copy(fontSize = 15.sp)) }
    }
}

// ---------------------------------------------------------------- home

/**
 * The everyday screen: the card, the balance, three actions, and only what
 * needs attention. Security details live in Settings, card checks under Your card.
 */
@Composable
private fun Home(vm: AppViewModel) {
    val w = vm.wallet
    var showReceive by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmFreeze by remember { mutableStateOf(false) }
    val frozen = w?.frozen == true
    val idle = vm.busy == null
    val tilt by rememberTilt()

    // header: wallet switcher + receive
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).enter(0), verticalAlignment = Alignment.CenterVertically) {
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
        RoundIcon(Ic.Qr, "Receive", 44.dp) { showReceive = true }
    }
    vm.offline?.let { Notice(Ic.Offline, "Offline", it, Tv.Warn, Modifier.padding(top = 14.dp)) }

    // the card: leans with the phone; tap it for card details
    CardArt(
        Modifier.fillMaxWidth().padding(top = 24.dp, start = 6.dp, end = 6.dp).sharedCard()
            .pressable(haptic = true) { vm.go(Screen.CardCheck) }.semantics { contentDescription = "Your card" },
        last4 = vm.mainCard?.last4, network = vm.mainCard?.network, frozen = frozen, tilt = tilt,
    )

    // balance
    Column(Modifier.fillMaxWidth().padding(top = 30.dp).enter(2), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Total balance", style = Type.CaptionStrong, color = Tv.Text2)
        Spacer(Modifier.height(6.dp))
        val bal = vm.balance
        if (bal == null) Shimmer(Modifier.size(200.dp, 52.dp)) else CountingAmount(bal, 4, metal = true)
        Spacer(Modifier.height(12.dp))
        when {
            frozen -> StatusPill("Frozen", Tv.Ice, Ic.Freeze)
            w?.nearLimit != null -> {
                val instant = w.nearLimit!!.availableNow().let { a -> vm.balance?.let { a.min(it) } ?: a }
                StatusPill("${Near.format(instant, 2)} NEAR available instantly", Tv.Text2)
            }
            w == null && vm.offline == null -> Shimmer(Modifier.size(170.dp, 26.dp))
        }
        securityPill(vm)?.let { (text, color, target) ->
            Spacer(Modifier.height(8.dp))
            StatusPill(text, color, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = idle) { vm.go(target) })
        }
    }

    // actions
    Row(Modifier.fillMaxWidth().padding(top = 28.dp).enter(3), horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally)) {
        ActionButton(Ic.Send, "Send", primary = true, enabled = w?.frozen == false && idle) { vm.go(Screen.Send) }
        ActionButton(Ic.Receive, "Receive") { showReceive = true }
        ActionButton(Ic.Freeze, if (frozen) "Frozen" else "Freeze", tint = Tv.Ice, enabled = w?.frozen == false && idle) { confirmFreeze = true }
    }

    if (frozen) {
        Spacer(Modifier.height(24.dp))
        Notice(Ic.Freeze, "Wallet frozen", "No transfers can go out. Unfreezing needs your phone and your card, then waits ${hoursLabel(w!!.delays.config)}.", Tv.Ice) {
            TvButton("Request unfreeze", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Key, height = 48.dp, enabled = idle) { vm.requestUnfreeze() }
        }
    }

    // pending: only when something waits
    if (vm.proposals.isNotEmpty()) {
        Column(Modifier.enter(4)) {
            SectionHeader("Pending", action = "See all") { vm.go(Screen.Activity) }
            Group {
                vm.proposals.take(2).forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    ProposalRow(vm, p) { vm.go(Screen.Activity) }
                }
            }
        }
    }

    // today's spending
    w?.nearLimit?.let { l ->
        val now = System.currentTimeMillis() / 1000
        val elapsed = (now - l.updatedAt).coerceAtLeast(0)
        val usedNow = if (elapsed >= 86_400) BigInteger.ZERO else (l.used - l.dailyMax * BigInteger.valueOf(elapsed) / BigInteger.valueOf(86_400)).max(BigInteger.ZERO)
        val frac = if (l.dailyMax.signum() == 0) 0f else usedNow.toBigDecimal().divide(l.dailyMax.toBigDecimal(), 4, java.math.RoundingMode.HALF_UP).toFloat()
        Column(Modifier.fillMaxWidth().padding(top = 26.dp).enter(5).surface(24.dp).padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Spent today", style = Type.Body, color = Tv.Text2, modifier = Modifier.weight(1f))
                Text("${Near.format(usedNow, 2)} / ${Near.format(l.dailyMax, 2)} NEAR", style = Type.Amount.copy(fontSize = 15.sp))
            }
            Spacer(Modifier.height(12.dp))
            Meter(frac, if (frac > 0.85f) Tv.Warn else Tv.Accent)
            Spacer(Modifier.height(10.dp))
            Caption("Up to ${Near.format(l.instantMax)} NEAR per payment goes out instantly", color = Tv.Text3)
        }
    }

    if (showPicker) WalletPicker(vm) { showPicker = false }
    if (showReceive) ReceiveSheet(vm) { showReceive = false }
    if (confirmFreeze) ConfirmDialog(
        "Freeze the wallet?",
        "One card tap stops every transfer. Unfreezing needs your phone and card, then waits ${hoursLabel(w?.delays?.config ?: 3600)}.",
        "Freeze", { confirmFreeze = false; vm.freezeWithCard() }, { confirmFreeze = false }, icon = Ic.Freeze,
    )
}

/**
 * Home's at-a-glance security status (plan 4.2); the worst state wins.
 */
private fun securityPill(vm: AppViewModel): Triple<String, Color, Screen>? {
    val w = vm.wallet ?: return null
    val phoneIdx = runCatching { w.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    val cards = w.cards().size
    return when {
        w.frozen -> Triple("Frozen · tap to unfreeze", Tv.Ice, Screen.Security)
        vm.proposals.any { !it.stale && (phoneIdx == null || !it.approvedBy(phoneIdx)) } -> Triple("Unknown request · review", Tv.Danger, Screen.Activity)
        vm.integrity?.ok == false -> Triple("Wallet check failed", Tv.Danger, Screen.Security)
        cards < 2 -> Triple("No backup card · add one", Tv.Warn, Screen.Security)
        else -> Triple("Protected · $cards cards · phone", Tv.Positive, Screen.Security)
    }
}

/** Placeholder while values load: a soft light sweeping across glass. */
@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "sh")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Tv.Glass)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent), startX = x * 400f - 200f, endX = x * 400f + 200f)),
    )
}

// ---------------------------------------------------------------- receive

@Composable
private fun ReceiveSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val acct = vm.store.walletAccount ?: ""
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    GlassSheet(onDismiss) {
        Text("Receive NEAR", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("Scan, or share your account name", style = Type.Body, color = Tv.Text2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(acct, Modifier.size(232.dp)) }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().surface(20.dp).padding(16.dp)) {
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

// ---------------------------------------------------------------- send

@Composable
private fun ColumnScope.Send(vm: AppViewModel) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(false) }
    // The receiver came from the clipboard (Paste button, or several characters inserted at once).
    var pasted by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val w = vm.wallet
    val limit = w?.nearLimit
    val parsed = runCatching { Near.parse(amount).takeIf { it.signum() > 0 } }.getOrNull()
    val spendable = vm.balance
    val validTo = to.matches(Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")) && to.length in 2..64
    val toError = if (to.length >= 2 && !validTo) "That isn't a NEAR account name" else if (to == vm.store.walletAccount) "That's this wallet's own account" else null
    val tooMuch = parsed != null && spendable != null && parsed > spendable
    val instant = parsed != null && w?.isInstantTo(to, parsed) == true
    fun press(k: Char) {
        focus.clearFocus()
        amount = when (k) {
            '<' -> amount.dropLast(1)
            '.' -> if ('.' in amount) amount else if (amount.isEmpty()) "0." else "$amount."
            else -> if (amount == "0") "$k" else "$amount$k"
        }.take(14)
    }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        TopBar("Send NEAR", onBack = { vm.go(Screen.Home) })
        Spacer(Modifier.height(6.dp))
        TvField("To", to, { v ->
            val nv = v.trim().lowercase()
            pasted = when { nv.isEmpty() -> false; nv.length - to.length > 1 -> true; else -> pasted }
            to = nv
        }, placeholder = "name.testnet", error = toError, trailing = {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Tv.GlassStrong).clickable { clip.getText()?.text?.let { to = it.trim().lowercase(); pasted = true } }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { TvIcon(Ic.Paste, 16.dp, Tv.Text); Spacer(Modifier.width(6.dp)); Text("Paste", style = Type.CaptionStrong, color = Tv.Text) }
            }
        })
        // the amount, typed on the keypad below
        Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = amount.ifEmpty { "0" }
            val target = when { shown.length <= 6 -> 60f; shown.length <= 9 -> 46f; else -> 36f }
            val fs by animateFloatAsState(target, spring(dampingRatio = 0.7f, stiffness = 500f), label = "fs")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    shown, maxLines = 1,
                    style = Type.Balance.copy(fontSize = fs.sp, lineHeight = (fs * 1.12f).sp, brush = if (amount.isEmpty()) null else Tv.titanium()),
                    color = if (amount.isEmpty()) Tv.Text3 else Tv.Text,
                    modifier = Modifier.semantics { contentDescription = "Amount ${amount.ifEmpty { "0" }} NEAR" },
                )
                Text(" NEAR", style = Type.BodyStrong, color = Tv.Text2, modifier = Modifier.padding(bottom = (fs * 0.2f).dp))
            }
            Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(14.dp))
            when {
                parsed == null -> Caption("You'll approve with your fingerprint and a card tap", color = Tv.Text3)
                instant -> StatusPill("Goes out instantly", Tv.Positive, Ic.CheckCircle)
                else -> StatusPill("Waits ${hoursLabel(w?.delays?.transfer ?: 3600)} · you can cancel it", Tv.Warn, Ic.Timer)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    Keypad(Modifier.padding(horizontal = 20.dp), ::press)
    TvButton("Review", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), enabled = vm.busy == null && validTo && toError == null && parsed != null && !tooMuch) { focus.clearFocus(); review = true }

    if (review && parsed != null) {
        GlassSheet({ review = false }) {
            Text("Review payment", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Amount(parsed, 4, Type.Balance.copy(fontSize = 40.sp), metal = true) }
            Spacer(Modifier.height(18.dp))
            // The receiver in full, never cut, the last 6 characters bold: what an address swap changes is easy to see.
            Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("To", style = Type.Caption, color = Tv.Text2)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        val cut = (to.length - 6).coerceAtLeast(0)
                        append(to.substring(0, cut))
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Tv.Text))
                        append(to.substring(cut))
                        pop()
                    },
                    style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 2.dp),
                )
            }
            val warnings = buildList {
                val r = vm.receivers
                val alike = r.lookAlike(to)
                if (alike != null) add("Looks like $alike, which you paid before, but isn't the same account. Check every letter.")
                else if (!r.isKnown(to)) add("First payment to this receiver from this phone.")
                if (pasted) add("Pasted · check it: clipboard malware swaps addresses.")
                if (spendable != null && spendable.signum() > 0 && parsed * BigInteger.TWO > spendable) add("More than half of this wallet's balance.")
            }
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Notice(Ic.Warning, if (warnings.size == 1) "Check this" else "Check these", warnings.joinToString("\n") { "• $it" }, Tv.Warn)
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                InfoRow("From", vm.walletName)
                InfoRow("Arrives", if (instant) "Now (within your limit)" else "In ${hoursLabel(w?.delays?.transfer ?: 3600)} · you can cancel", if (instant) Tv.Positive else Tv.Warn)
                limit?.let { InfoRow("Instant left today after", "${Near.format((if (instant) it.availableNow() - parsed else it.availableNow()).max(BigInteger.ZERO), 2)} NEAR") }
                InfoRow("Network fee", "Paid by your fee account")
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
            SlideToConfirm("Slide to approve") { review = false; vm.send(to, amount) }
            Spacer(Modifier.height(6.dp))
            TvButton("Edit", Modifier.fillMaxWidth(), Btn.Ghost) { review = false }
        }
    }
}

/** A glass number pad: digits, the decimal point, and delete (hold to clear). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(modifier: Modifier = Modifier, onKey: (Char) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val rows = listOf("123", "456", "789", ".0<")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { k ->
                    val src = remember { MutableInteractionSource() }
                    val pressed by src.collectIsPressedAsState()
                    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = 900f), label = "k")
                    val bg by androidx.compose.animation.animateColorAsState(if (pressed) Tv.GlassStrong else Color.Transparent, label = "kb")
                    Box(
                        Modifier.weight(1f).height(52.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(18.dp)).background(bg)
                            .combinedClickable(
                                src, indication = null,
                                onLongClick = if (k == '<') ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); repeat(14) { onKey('<') } }) else null,
                            ) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(k) }
                            .semantics { contentDescription = if (k == '<') "Delete" else if (k == '.') "Decimal point" else "$k"; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == '<') TvIcon(Ic.Back, 24.dp, Tv.Text2) else Text("$k", style = Type.H2.copy(fontSize = 26.sp), color = Tv.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepNum(n: Int) {
    Box(Modifier.size(26.dp).background(Tv.GlassStrong, CircleShape).glassRim(13.dp, shape = CircleShape), contentAlignment = Alignment.Center) { Text("$n", style = Type.CaptionStrong, color = Tv.Text) }
}

// ---------------------------------------------------------------- activity

@Composable
private fun ActivityScreen(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    // ticks every second so countdowns move
    val tick by produceState(0L) { while (true) { kotlinx.coroutines.delay(1000); value++ } }
    LargeTitle("Activity", "Approvals, delays and history")
    if (vm.proposals.isEmpty()) {
        Column(Modifier.fillMaxWidth().enter(1).surface(28.dp).padding(vertical = 34.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedCheck(68.dp)
            Spacer(Modifier.height(14.dp))
            Text("You're all caught up", style = Type.Title)
            Text("Payments waiting for an approval or a delay show up here.", style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    } else {
        Text("Pending · ${vm.proposals.size}", style = Type.Title, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        vm.proposals.forEachIndexed { i, p -> key(p.id) { ProposalCard(vm, p, tick, Modifier.enter(i + 1)); Spacer(Modifier.height(12.dp)) } }
    }
    SectionHeader("History")
    Group {
        vm.store.walletAccount?.let { a ->
            Row_(Ic.External, "All transactions", "Open this wallet on the NEAR explorer", chevron = true, onClick = { uri.openUri(vm.near.explorerAccount(a)) })
        }
    }
}

private enum class Node { Done, Waiting, Running, Failed }

@Composable
private fun ProposalCard(vm: AppViewModel, p: ProposalInfo, @Suppress("UNUSED_PARAMETER") tick: Long, modifier: Modifier = Modifier) {
    val w = vm.wallet
    val now = System.currentTimeMillis() / 1000
    val cardIdxs = w?.cards()?.map { it.index }.orEmpty()
    val phoneIdx = runCatching { w?.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    val st = proposalStatus(vm, p)
    val idle = vm.busy == null
    val phoneOk = phoneIdx?.let { p.approvedBy(it) } ?: false
    // Any one of the wallet's cards counts.
    val cardOk = cardIdxs.any { p.approvedBy(it) }
    val left = secondsToExecute(vm, p)
    val delay = delayFor(vm, p)
    val (runNode, runLabel, runProgress) = when {
        p.stale || expired(vm, p) -> Triple(Node.Failed, if (p.stale) "Void" else "Expired", null)
        left == null -> Triple(Node.Waiting, "Delay", null)
        left > 0 -> Triple(Node.Running, formatDuration(left), if (delay > 0) 1f - left.toFloat() / delay else null)
        else -> Triple(Node.Done, "Ready", null)
    }
    Column(modifier.fillMaxWidth().surface(26.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (p.isTransfer) Ic.Send else Ic.Settings, st.color, st.color.copy(alpha = 0.13f), 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(proposalTitle(p), style = Type.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${proposalSubtitle(vm, p)} · #${p.id}", style = Type.Caption, color = Tv.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            p.transferAmount()?.takeIf { p.isNearTransfer }?.let { Text("−${Near.format(it)} NEAR", style = Type.Amount) }
        }
        // approval tracker: phone → card → delay/ready
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            TrackNode(Ic.Fingerprint, "Phone", if (phoneOk) Node.Done else Node.Waiting)
            TrackLine(phoneOk, Modifier.weight(1f))
            TrackNode(Ic.Card, "Card", if (cardOk) Node.Done else Node.Waiting)
            TrackLine(phoneOk && cardOk, Modifier.weight(1f))
            TrackNode(if (runNode == Node.Done) Ic.Check else if (runNode == Node.Failed) Ic.XCircle else Ic.Timer, runLabel, runNode, runProgress)
        }
        if (st.text.isNotEmpty()) StatusPill(st.text, st.color, st.icon, Modifier.padding(top = 10.dp))
        val canCard = p.readyTs == null && cardIdxs.any { !p.approvedBy(it) } && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now && !p.stale
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
private fun TrackNode(icon: Ic, label: String, state: Node, progress: Float? = null) {
    val color = when (state) { Node.Done -> Tv.Positive; Node.Failed -> Tv.Danger; Node.Running -> Tv.Text; Node.Waiting -> Tv.Text3 }
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).background(if (state == Node.Waiting) Tv.Glass else color.copy(alpha = 0.14f), CircleShape)
                    .let { if (state == Node.Waiting) it.glassRim(20.dp, shape = CircleShape) else it },
            )
            if (progress != null) {
                val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
                Canvas(Modifier.size(40.dp)) {
                    val sw = 2.5.dp.toPx()
                    drawArc(Brush.sweepGradient(listOf(Tv.Text2, Tv.Text)), -90f, 360f * p, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            TvIcon(if (state == Node.Done && icon != Ic.Check) Ic.Check else icon, 18.dp, color)
        }
        Text(label, style = Type.Caption.copy(fontFeatureSettings = "tnum"), color = if (state == Node.Waiting) Tv.Text3 else Tv.Text2, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@Composable
private fun TrackLine(done: Boolean, modifier: Modifier) {
    val f by animateFloatAsState(if (done) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "line")
    Box(modifier.padding(top = 19.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Tv.GlassStrong)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Tv.Positive.copy(alpha = 0.7f)))
    }
}

// ---------------------------------------------------------------- your card / card check

@Composable
private fun CardCheck(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ready = vm.store.setupStep >= 4
    val tilt by rememberTilt()
    TopBar(if (ready) "Your card" else "Card check", onBack = { vm.go(if (ready) vm.cameFrom else Screen.Welcome) })
    val r = vm.cardReport
    CardArt(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp).sharedCard(),
        last4 = r?.panLast4 ?: vm.mainCard?.last4.takeIf { ready }, network = r?.network ?: vm.mainCard?.network.takeIf { ready },
        frozen = ready && vm.wallet?.frozen == true, tilt = tilt,
    )
    if (ready && r == null) {
        Group(Modifier.enter(1)) {
            val signers = vm.wallet?.cards()
            if (signers.isNullOrEmpty()) {
                val c = vm.mainCard
                Row_(Ic.Card, c?.name ?: "Bank card", c?.recipe?.let { "Contactless · ${it.method.label}" } ?: "Contactless bank card over NFC", value = "RSA")
            } else signers.forEachIndexed { i, sgn ->
                if (i > 0) GroupDivider()
                val c = vm.cardBook.find(sgn.keyHash)
                Row_(Ic.Card, vm.cardName(sgn), (if (i == 0) "Main card" else "Backup card") + (c?.recipe?.let { " · ${it.method.label}" } ?: ""), value = "RSA")
            }
            GroupDivider()
            Row_(Ic.ShieldCheck, "Co-signs every payment", "Any one of these cards together with this phone's key; no card alone moves funds", tint = Tv.Positive, iconBg = Tv.PositiveSoft)
        }
        SectionHeader("Check a card")
        Caption("See whether this or any other card can sign for Tapvault. Nothing is signed or stored.", Modifier.padding(start = 4.dp, bottom = 14.dp))
    } else if (r == null) {
        Text("See whether a card can sign for Tapvault", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(bottom = 14.dp))
        Group(Modifier.enter(1)) {
            Row_(Ic.Scan, "Reads the payment chip", "The same contactless link a card terminal uses")
            GroupDivider()
            Row_(Ic.Key, "Checks it can sign", "Looks for an RSA key Tapvault can verify on-chain")
            GroupDivider()
            Row_(Ic.Lock, "Your card number stays here", "Only the last four digits are ever shown")
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
    Column(Modifier.fillMaxWidth().surface(26.dp, stroke = (if (ok) Tv.Positive else Tv.Danger).copy(alpha = 0.3f)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ok) AnimatedCheck(48.dp)
            else Box(Modifier.size(48.dp).background(Tv.DangerSoft, CircleShape), contentAlignment = Alignment.Center) { TvIcon(Ic.XCircle, 26.dp, Tv.Danger) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ok) "This card can sign" else "This card can't sign", style = Type.Title, color = if (ok) Tv.Text else Tv.Danger)
                Text("${r.network} •••• ${r.panLast4 ?: "????"} · ${r.label.ifBlank { r.aid }}", style = Type.Caption, color = Tv.Text2, maxLines = 2)
            }
        }
        r.notes.forEach { Text("· $it", style = Type.Caption, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { details = !details }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Technical details", style = Type.CaptionStrong, color = Tv.Text2, modifier = Modifier.weight(1f))
            val rot by animateFloatAsState(if (details) 180f else 0f, label = "rot")
            TvIcon(Ic.ChevronDown, 18.dp, Tv.Text3, Modifier.rotate(rot))
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
    var showLog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val w = vm.wallet
    LargeTitle("Settings")

    // profile
    Row(Modifier.fillMaxWidth().enter(0).surface(26.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
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

    Column(Modifier.enter(1)) {
        SectionHeader("Wallets")
        Group {
            Row_(Ic.Layers, "My wallets", "${vm.walletList.size} on this phone · switch or add one", chevron = true, onClick = { showPicker = true })
        }
    }

    Column(Modifier.enter(2)) {
        SectionHeader(if ((w?.cards()?.size ?: 1) > 1) "Your cards" else "Your card")
        Group {
            val c = vm.mainCard
            val more = ((w?.cards()?.size ?: 1) - 1).coerceAtLeast(0)
            Row_(
                Ic.Card, c?.name ?: "Bank card",
                (c?.recipe?.let { "Contactless · ${it.method.label}" } ?: "Contactless bank card over NFC") + if (more > 0) " · +$more backup" else "",
                chevron = true, onClick = { vm.go(Screen.CardCheck) },
            )
            GroupDivider()
            Row_(Ic.Scan, "Check a card", "See whether a card can sign for Tapvault", chevron = true, onClick = { vm.go(Screen.CardCheck) })
        }
    }

    Column(Modifier.enter(3)) {
        SectionHeader("Security")
        Group {
            Row_(Ic.Shield, "Security Center", "Keys, backup cards, limits, delays and the hold timer", chevron = true, onClick = { vm.go(Screen.Security) })
            GroupDivider()
            Row_(Ic.Fingerprint, "Phone key", if (vm.phoneKeyExists) "${vm.phone.hardwareLabel} · this wallet only" else "none", value = "P-256")
            w?.let {
                GroupDivider()
                Row_(Ic.ShieldCheck, "Approvals", "${it.threshold} of ${it.signers.size} signers for every payment; neither key alone can move funds", tint = Tv.Positive, iconBg = Tv.PositiveSoft)
                GroupDivider()
                Row_(Ic.Timer, "Delays", "Transfers ${hoursLabel(it.delays.transfer)} · changes ${hoursLabel(it.delays.config)} · recovery ${hoursLabel(it.delays.recovery)}")
                it.nearLimit?.let { l ->
                    GroupDivider()
                    Row_(Ic.Send, "Limits", "Instant up to ${Near.format(l.instantMax)} NEAR per payment · ${Near.format(l.dailyMax)} NEAR a day")
                }
            }
        }
    }

    if (w?.frozen == false) {
        Column(Modifier.enter(4)) {
            SectionHeader("Emergency")
            Group {
                Row_(Ic.Freeze, "Freeze with phone", "Stop all transfers right away, no card needed", tint = Tv.Ice, iconBg = Tv.IceSoft, titleColor = Tv.Ice, chevron = true, onClick = { if (vm.busy == null) confirmFreeze = true })
            }
        }
    }

    Column(Modifier.enter(5)) {
        SectionHeader("Advanced")
        Group {
            vm.store.walletAccount?.let { a -> Row_(Ic.Wallet, "Wallet account", shortAccount(a, 30), trailingIcon = Ic.External, onClick = { uri.openUri(vm.near.explorerAccount(a)) }); GroupDivider() }
            vm.store.gasAccount?.let { a -> Row_(Ic.Send, "Fee account", "Pays network fees, can't move funds", trailingIcon = Ic.External, onClick = { uri.openUri(vm.near.explorerAccount(a)) }); GroupDivider() }
            vm.storageLocked?.let { Row_(Ic.Lock, "Contract storage", "Reserved by the wallet contract, not spendable", value = "${Near.format(it, 2)} NEAR"); GroupDivider() }
            Row_(Ic.History, "Technical log", "What the app did recently, for troubleshooting", chevron = true, onClick = { showLog = true })
        }
    }

    Column(Modifier.enter(6)) {
        SectionHeader("About")
        Group {
            Row_(Ic.Info, "Network", "NEAR testnet", value = "v${BuildConfig.VERSION_NAME}")
        }
        Spacer(Modifier.height(18.dp))
        TvButton("Forget this wallet on this phone", Modifier.fillMaxWidth(), Btn.Danger, icon = Ic.Trash) { confirmReset = true }
    }

    if (showPicker) WalletPicker(vm) { showPicker = false }
    if (showLog) GlassSheet({ showLog = false }) {
        Text("Technical log", style = Type.H2)
        Caption("Last 60 lines. Shown on this phone only.", Modifier.padding(top = 4.dp, bottom = 12.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).surface(20.dp).verticalScroll(rememberScrollState()).padding(14.dp)) {
            if (vm.log.isEmpty()) Caption("Nothing yet.")
            vm.log.take(60).forEach { Text(it, style = Type.Caption.copy(fontFeatureSettings = "tnum"), color = Tv.Text2, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
    renaming?.let { current ->
        GlassDialog({ renaming = null }) {
            Text("Wallet name", style = Type.H2)
            Spacer(Modifier.height(14.dp))
            TvField("Shown on this phone only", current, { renaming = it.take(24) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            TvButton("Save", Modifier.fillMaxWidth(), height = 52.dp, enabled = current.isNotBlank()) { vm.renameWallet(current); renaming = null }
            Spacer(Modifier.height(6.dp))
            TvButton("Cancel", Modifier.fillMaxWidth(), Btn.Ghost, height = 46.dp) { renaming = null }
        }
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
@Composable
fun WalletPicker(vm: AppViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.reloadWalletList(); vm.loadWalletBalances() }
    GlassSheet(onDismiss) {
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (e.active) Tv.GlassStrong else Tv.Glass)
                        .glassRim(22.dp, if (e.active) Color.White.copy(alpha = 0.3f) else Tv.Stroke)
                        .pressable { onDismiss(); vm.switchWallet(e.id) }.padding(14.dp),
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
                    if (e.active) { Spacer(Modifier.width(10.dp)); TvIcon(Ic.CheckCircle, 20.dp, Tv.Positive) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TvButton("Add a wallet", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Plus, enabled = vm.busy == null) { onDismiss(); vm.addWallet() }
    }
}

/**
 * App lock: shown after the app was away more than a minute (or on start).
 * Hides balances and history until fingerprint or screen lock; it's a
 * screen lock, not what protects the funds (the card and phone key do).
 */
@Composable
private fun LockOverlay(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.unlock() }
    BackHandler(true) {}
    Box(Modifier.fillMaxSize().background(Tv.Bg).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            TvIcon(Ic.Lock, 40.dp, Tv.Text)
            Spacer(Modifier.height(16.dp))
            Text("Tapvault is locked", style = Type.H2)
            Text(
                "Unlock with your fingerprint or screen lock to see balances and activity.",
                style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
            )
            TvButton("Unlock", Modifier.fillMaxWidth(), icon = Ic.Fingerprint) { vm.unlock() }
        }
    }
}
