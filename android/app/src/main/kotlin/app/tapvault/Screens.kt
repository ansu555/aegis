package app.tapvault

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.tapvault.core.Near
import app.tapvault.core.ProposalInfo
import java.math.BigInteger

private val tabScreens = setOf(Screen.Home, Screen.Activity, Screen.CardCheck, Screen.Settings)

@Composable
fun TapvaultApp(vm: AppViewModel) {
    TapvaultTheme {
        NightBackground {
            LaunchedEffect(Unit) { vm.refresh(); vm.startAutoRefresh() }
            BackHandler(enabled = vm.screen !in setOf(Screen.Home, Screen.Welcome, Screen.Setup)) { vm.go(if (vm.store.setupStep >= 4) Screen.Home else Screen.Welcome) }
            val uri = LocalUriHandler.current
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
                    when (vm.screen) {
                        Screen.Welcome -> Welcome(vm)
                        Screen.Setup -> SetupProgress(vm)
                        Screen.Home -> Home(vm)
                        Screen.Send -> Send(vm)
                        Screen.Activity -> ActivityScreen(vm)
                        Screen.CardCheck -> CardCheck(vm)
                        Screen.Settings -> SettingsScreen(vm)
                    }
                    Spacer(Modifier.height(if (vm.screen in tabScreens) 120.dp else 40.dp))
                }
                if (vm.screen in tabScreens && vm.store.setupStep >= 4) {
                    BottomBar(vm.screen, vm.proposals.size, { vm.go(it) }, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                }
                // Results stay pinned on top, wherever the page is scrolled.
                vm.outcome?.let {
                    Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OutcomeToast(it, { l -> uri.openUri(l) }) { vm.outcome = null }
                    }
                }
                vm.tap?.let { TapSheet(it) { vm.cancelTap() } }
                if (vm.tap == null) vm.busy?.let { BusyOverlay(it) }
            }
        }
    }
}

// ---------------------------------------------------------------- welcome

@Composable
private fun Welcome(vm: AppViewModel) {
    var instant by remember { mutableStateOf(vm.store.instantNear) }
    var daily by remember { mutableStateOf(vm.store.dailyNear) }
    Spacer(Modifier.height(28.dp))
    Text("tapvault", fontSize = 18.sp, color = Tv.Dim, letterSpacing = 1.sp)
    Spacer(Modifier.height(20.dp))
    Text("Your bank card\nis your\nhardware wallet.", style = Numeral.copy(fontSize = 44.sp, lineHeight = 48.sp, color = Tv.Text))
    Spacer(Modifier.height(14.dp))
    Caption("Every payment needs your fingerprint and a tap of the card you already carry. No seed phrase, no new device.")
    Spacer(Modifier.height(26.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceTile("My bank card", "Visa · Mastercard · RuPay", "NFC", Glyph.Card, vm.hasNfc, warm = true, enabled = vm.hasNfc, modifier = Modifier.weight(1f)) { }
        ChoiceTile("Check it first", "See if your card can sign", "TEST", Glyph.Shield, false, warm = false, enabled = vm.hasNfc, modifier = Modifier.weight(1f)) { vm.go(Screen.CardCheck) }
    }
    if (!vm.hasNfc) Caption("This phone has no NFC, so it can't read a bank card. Use an Android phone with NFC.", Modifier.padding(top = 10.dp), Tv.Warn)
    else if (!vm.nfcEnabled) Caption("NFC is off. Turn it on in Android settings (Connected devices → NFC).", Modifier.padding(top = 10.dp), Tv.Warn)
    Spacer(Modifier.height(16.dp))
    GlassCard {
        Text("Spending limits", fontSize = 16.sp)
        Caption("Within these, payments go through instantly. Anything bigger waits an hour so you can cancel it.")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassField("At once (NEAR)", instant, { v -> instant = v.replace(',', '.').filter { it.isDigit() || it == '.' } }, Modifier.weight(1f), number = true)
            GlassField("Per day (NEAR)", daily, { v -> daily = v.replace(',', '.').filter { it.isDigit() || it == '.' } }, Modifier.weight(1f), number = true)
        }
    }
    Spacer(Modifier.height(20.dp))
    PillButton("Create my wallet", Modifier.fillMaxWidth(), enabled = vm.hasNfc, glyph = Glyph.Plus) { vm.beginSetup(instant, daily) }
    Caption("NEAR testnet · accounts and test NEAR come from the faucet", Modifier.fillMaxWidth().padding(top = 12.dp), Tv.Faint)
}

@Composable
private fun ChoiceTile(title: String, sub: String, tag: String, glyph: Glyph, selected: Boolean, warm: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.height(196.dp).gradientTile(28.dp, warm).border(2.dp, if (selected) Tv.Lime else Color.Transparent, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp)).alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled, onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(46.dp).background(Tv.GlassStrong, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, 22.dp) }
            Spacer(Modifier.weight(1f))
            Tag(tag)
        }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 19.sp)
        Caption(sub)
        if (selected) Box(Modifier.padding(top = 8.dp).size(22.dp).background(Tv.Lime, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Check, 14.dp, Tv.OnLime) }
    }
}

// ---------------------------------------------------------------- setup

@Composable
private fun SetupProgress(vm: AppViewModel) {
    var confirmStartOver by remember { mutableStateOf(false) }
    val cardRead = vm.store.pendingCard != null || vm.setupStep >= 3
    val steps = listOf(
        Triple(Glyph.Phone, "Phone key in secure hardware", KeystoreSigner.exists()),
        Triple(Glyph.Shield, "Wallet account on NEAR", vm.setupStep >= 1),
        Triple(Glyph.Card, "Card read and signed", cardRead),
        Triple(Glyph.Check, "Wallet contract deployed", vm.setupStep >= 2),
        Triple(Glyph.Card, "Card added as a signer", vm.setupStep >= 3),
        Triple(Glyph.Freeze, "Limits set, account locked", vm.setupStep >= 4),
    )
    Header("Setting up")
    Text("Almost there", style = Numeral.copy(fontSize = 40.sp, color = Tv.Text))
    Caption("Three approvals and one card tap. Each step is saved, so you can continue if anything is interrupted.")
    Spacer(Modifier.height(16.dp))
    steps.forEach { (g, s, done) ->
        ListRow(g, s, if (done) "Done" else "Waiting", subColor = if (done) Tv.Up else Tv.Faint) {
            Box(
                Modifier.size(28.dp).background(if (done) Tv.Lime else Color.Transparent, CircleShape).border(1.dp, if (done) Tv.Lime else Tv.Hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (done) GlyphIcon(Glyph.Check, 16.dp, Tv.OnLime) }
        }
    }
    vm.cardReport?.let { CardReportCard(it) }
    if (vm.busy == null && vm.setupStep < 4) {
        Spacer(Modifier.height(12.dp))
        PillButton("Continue setup", Modifier.fillMaxWidth()) { vm.continueSetup() }
        Spacer(Modifier.height(8.dp))
        GhostButton("Start over", Modifier.fillMaxWidth(), Tv.Dim) { confirmStartOver = true }
    }
    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            containerColor = Tv.Plum,
            confirmButton = { TextButton(onClick = { confirmStartOver = false; vm.startOver() }) { Text("Start over", color = Tv.Down) } },
            dismissButton = { TextButton(onClick = { confirmStartOver = false }) { Text("Keep going", color = Tv.Text) } },
            title = { Text("Start over?") },
            text = {
                Text(
                    if (vm.setupStep <= 1) "The accounts made for this setup are deleted and the phone key is removed."
                    else "The half-built wallet is left on testnet with its test NEAR, and the phone key is removed.",
                    textAlign = TextAlign.Start,
                )
            },
        )
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun Home(vm: AppViewModel) {
    val w = vm.wallet
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showReceive by remember { mutableStateOf(false) }
    var confirmFreeze by remember { mutableStateOf(false) }
    if (confirmFreeze) {
        AlertDialog(
            onDismissRequest = { confirmFreeze = false },
            containerColor = Tv.Plum,
            confirmButton = { TextButton(onClick = { confirmFreeze = false; vm.freezeWithCard() }) { Text("Freeze", color = Tv.Down) } },
            dismissButton = { TextButton(onClick = { confirmFreeze = false }) { Text("Cancel", color = Tv.Text) } },
            title = { Text("Freeze the wallet?") },
            text = { Text("One card tap stops every transfer. Unfreezing needs your phone and card, then waits ${(vm.wallet?.delays?.config ?: 3600) / 3600} h.", textAlign = TextAlign.Start) },
        )
    }
    vm.offline?.let { Caption(it, Modifier.fillMaxWidth().padding(top = 10.dp), Tv.Warn) }

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Caption("Hello,")
            Text("Your vault", fontSize = 22.sp)
        }
        CircleButton(Glyph.History, "Activity", 48.dp, dot = vm.proposals.isNotEmpty()) { vm.go(Screen.Activity) }
    }
    Spacer(Modifier.height(26.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Caption("Wallet (NEAR)")
        Text(vm.balance?.let { Near.format(it, 3) } ?: "…", style = Numeral.copy(fontSize = 64.sp, color = Tv.Text))
        Spacer(Modifier.height(6.dp))
        when {
            w?.frozen == true -> Badge("Frozen", Tv.Down, Tv.Down.copy(alpha = 0.15f))
            w?.nearLimit != null -> {
                val instant = w.nearLimit!!.availableNow().let { a -> vm.balance?.let { a.min(it) } ?: a }
                Badge("${Near.format(instant, 2)} NEAR instant now", Tv.Up)
            }
        }
    }
    Spacer(Modifier.height(26.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircleButton(Glyph.Receive, "Receive", 56.dp) { showReceive = true }
        CircleButton(Glyph.Freeze, "Freeze with card", 56.dp, enabled = w?.frozen == false && vm.busy == null) { confirmFreeze = true }
        CircleButton(Glyph.Card, "Check a card", 56.dp) { vm.go(Screen.CardCheck) }
        PillButton("Send", Modifier.weight(1f), enabled = w?.frozen == false && vm.busy == null, glyph = Glyph.Send) { vm.go(Screen.Send) }
    }

    if (w?.frozen == true) {
        Spacer(Modifier.height(16.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wallet frozen", color = Tv.Down, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Tag("${w.delays.config / 3600} h to unfreeze", Tv.Dim)
            }
            Caption("No transfers can go out. Unfreezing needs your phone and your card, then waits.")
            Spacer(Modifier.height(12.dp))
            GhostButton("Request unfreeze", Modifier.fillMaxWidth(), Tv.Lime, enabled = vm.busy == null) { vm.requestUnfreeze() }
        }
    }

    Spacer(Modifier.height(22.dp))
    Segmented(listOf("Activity", "Signers", "Limits"), tab) { tab = it }
    Spacer(Modifier.height(10.dp))
    when (tab) {
        0 -> {
            if (vm.proposals.isEmpty()) ListRow(Glyph.Check, "All clear", "Nothing waiting for approval")
            vm.proposals.forEach { p ->
                ListRow(if (p.isTransfer) Glyph.Send else Glyph.Settings, "#${p.id} ${shortDescribe(p)}", proposalStatus(vm, p), onClick = { vm.go(Screen.Activity) })
            }
        }
        1 -> {
            w?.signers?.forEach { s ->
                if (s.isCard) ListRow(Glyph.Card, "Bank card", "key ${s.keyHash.take(10)}…", "RSA")
                else ListRow(Glyph.Phone, "This phone", "key ${s.keyHash.take(10)}…", "P-256")
            }
            Caption("${w?.threshold ?: 2} of ${w?.signers?.size ?: 2} must approve every payment", Modifier.padding(top = 6.dp, start = 6.dp))
        }
        2 -> {
            w?.nearLimit?.let { l ->
                ListRow(Glyph.Send, "Up to ${Near.format(l.instantMax)} NEAR at once", "instantly, with phone + card", "NEAR")
                ListRow(Glyph.History, "${Near.format(l.dailyMax)} NEAR per day", "${Near.format(l.availableNow(), 2)} available right now", "Daily")
            }
            w?.let { ListRow(Glyph.Shield, "Bigger payments wait ${it.delays.transfer / 3600} h", "you can cancel or freeze meanwhile", "Delay") }
        }
    }
    vm.storageLocked?.let { Caption("+ ${Near.format(it, 2)} NEAR held for the wallet contract's storage", Modifier.fillMaxWidth().padding(top = 12.dp), Tv.Faint) }

    if (showReceive) {
        val acct = vm.store.walletAccount ?: ""
        AlertDialog(
            onDismissRequest = { showReceive = false },
            containerColor = Tv.Plum,
            title = { Text("Receive NEAR") },
            text = {
                Column {
                    Caption("Send NEAR on testnet to this account:")
                    Spacer(Modifier.height(10.dp))
                    Text(acct, fontSize = 18.sp, color = Tv.Lime)
                }
            },
            confirmButton = {
                TextButton(onClick = { clip.setText(AnnotatedString(acct)); showReceive = false }) { Text("Copy", color = Tv.Lime) }
            },
            dismissButton = {
                TextButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, acct), "Share account"))
                    showReceive = false
                }) { Text("Share", color = Tv.Dim) }
            },
        )
    }
}

private fun shortDescribe(p: ProposalInfo) = p.describe().removePrefix("Change settings: ")

/** Seconds until the contract will execute [p] (0 = now), or null while it still needs approvals. */
private fun secondsToExecute(vm: AppViewModel, p: ProposalInfo): Long? {
    val w = vm.wallet ?: return null
    val ready = p.readyTs ?: return null
    if (p.stale) return null
    val now = System.currentTimeMillis() / 1000
    val phoneIdx = runCatching { w.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    val delay = if (p.isTransfer) {
        if (w.nearLimit?.isInstant(p.transferAmount() ?: BigInteger.ZERO) == true) 0 else w.delays.transfer
    } else if (phoneIdx != null && p.approvedBy(phoneIdx)) w.delays.config else w.delays.recovery
    return (ready + delay - now).coerceAtLeast(0)
}

private fun proposalStatus(vm: AppViewModel, p: ProposalInfo): String {
    val left = secondsToExecute(vm, p)
    return when {
        p.stale -> "Void: signers changed"
        p.readyTs == null -> "Waiting for approvals"
        left == null -> ""
        left > 0 -> "Approved · can execute in ${left / 3600} h ${(left % 3600) / 60} min"
        vm.wallet?.frozen == true && p.isTransfer -> "Approved · wallet is frozen"
        else -> "Approved · ready: tap Execute"
    }
}

// ---------------------------------------------------------------- send

@Composable
private fun Send(vm: AppViewModel) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val w = vm.wallet
    val limit = w?.nearLimit
    val parsed = runCatching { Near.parse(amount).takeIf { it.signum() > 0 } }.getOrNull()
    val spendable = vm.balance

    Header("Send", onBack = { vm.go(Screen.Home) })
    Column {
            // amount card
            Column(Modifier.fillMaxWidth().gradientTile(30.dp, warm = true).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Tv.GlassStrong).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(34.dp).background(Tv.Night, CircleShape), contentAlignment = Alignment.Center) {
                            Text("N", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("NEAR", fontSize = 15.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Tag("Send")
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChipButton("Half") { spendable?.let { amount = Near.format(it.divide(BigInteger.valueOf(2)), 4) } }
                    GlassField("", amount, { v -> amount = v.replace(',', '.').filter { it.isDigit() || it == '.' } }, Modifier.weight(1f), number = true, big = true, placeholder = "0")
                    ChipButton("Max") {
                        // keep a little behind so the wallet can always pay for its own storage
                        val buffer = Near.parse("0.01")
                        val most = listOfNotNull(limit?.availableNow(), spendable?.minus(buffer)).minOrNull()
                        most?.let { amount = Near.format(it.max(BigInteger.ZERO), 4) }
                    }
                }
                Caption("Available: ${spendable?.let { Near.format(it, 4) } ?: "…"} NEAR", Modifier.fillMaxWidth().padding(top = 4.dp), Tv.Text.copy(alpha = 0.8f))
            }
            // lime connector, drawn over the gap between the two cards
            Box(Modifier.fillMaxWidth().height(10.dp).zIndex(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(56.dp).background(Tv.Lime, CircleShape).border(5.dp, Tv.Night, CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Down, 22.dp, Tv.OnLime)
                }
            }
            // receiver card
            Column(Modifier.fillMaxWidth().gradientTile(30.dp).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Tv.GlassStrong, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Receive, 18.dp) }
                    Spacer(Modifier.width(8.dp))
                    Text("To", fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Tag("Receive")
                }
                Spacer(Modifier.height(12.dp))
                GlassField("NEAR account", to, { to = it.trim().lowercase() }, placeholder = "friend.testnet")
            }
    }
    Spacer(Modifier.height(18.dp))
    PillButton("Approve & tap card", Modifier.fillMaxWidth(), enabled = vm.busy == null && to.isNotBlank() && parsed != null, glyph = Glyph.Card) { vm.send(to, amount) }
    Spacer(Modifier.height(14.dp))
    GlassCard(padding = 18.dp) {
        val instant = parsed != null && limit?.isInstant(parsed) == true
        InfoRow("This payment", when {
            parsed == null -> "–"
            instant -> "Instant"
            else -> "Waits ${w?.delays?.transfer?.div(3600) ?: 1} h"
        }, if (parsed == null) Tv.Dim else if (instant) Tv.Up else Tv.Warn)
        limit?.let {
            InfoRow("Instant limit", "${Near.format(it.instantMax)} NEAR")
            InfoRow("Instant allowance left", "${Near.format(it.availableNow(), 2)} NEAR")
        }
        InfoRow("Network fee", "paid by your fee account")
        InfoRow("Approvals", "phone + card")
    }
}

@Composable
private fun InfoRow(k: String, v: String, color: Color = Tv.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Caption(k, Modifier.weight(1f))
        Text(v, fontSize = 14.sp, color = color)
    }
}

// ---------------------------------------------------------------- activity

@Composable
private fun ActivityScreen(vm: AppViewModel) {
    val w = vm.wallet
    val now = System.currentTimeMillis() / 1000
    Header("Activity")
    if (vm.proposals.isEmpty()) ListRow(Glyph.Check, "All clear", "Nothing waiting for approval")
    val cardIdx = w?.card()?.index
    val phoneIdx = runCatching { w?.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    vm.proposals.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).glass(28.dp).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Tv.GlassStrong, CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(if (p.isTransfer) Glyph.Send else Glyph.Settings, 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(shortDescribe(p), fontSize = 16.sp)
                    Caption(proposalStatus(vm, p))
                }
                Tag("#${p.id}")
            }
            val canCard = cardIdx != null && !p.approvedBy(cardIdx) && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now && !p.stale
            val canPhone = phoneIdx != null && !p.approvedBy(phoneIdx) && !p.stale
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canCard) GhostButton("Approve · card", Modifier.weight(1f), Tv.Lime, vm.busy == null) { vm.approveWithCard(p) }
                if (canPhone) GhostButton("Approve · phone", Modifier.weight(1f), Tv.Lime, vm.busy == null) { vm.approveWithPhone(p) }
                if (secondsToExecute(vm, p) == 0L && !(w?.frozen == true && p.isTransfer)) GhostButton("Execute", Modifier.weight(1f), Tv.Lime, vm.busy == null) { vm.execute(p) }
                if (!p.stale) GhostButton("Cancel", Modifier.weight(1f), Tv.Down, vm.busy == null) { vm.cancel(p) }
                if (p.stale || (p.readyTs == null && now > p.createdTs + 86_400)) GhostButton("Clean up", Modifier.weight(1f), Tv.Dim, vm.busy == null) { vm.closeStale(p) }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("Log", fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    GlassCard(padding = 16.dp) {
        if (vm.log.isEmpty()) Caption("Nothing yet.")
        vm.log.take(60).forEach { Caption(it, Modifier.padding(vertical = 2.dp)) }
    }
}

// ---------------------------------------------------------------- card check

@Composable
private fun CardCheck(vm: AppViewModel) {
    val ctx = LocalContext.current
    Header("Card check", onBack = if (vm.store.setupStep < 4) ({ vm.go(Screen.Welcome) }) else null)
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) { CardArt(label = vm.cardReport?.let { "•••• ${it.panLast4}" } ?: "TAPVAULT") }
    Caption(
        "Reads a card's payment chip and tells you whether it can sign for Tapvault. Your card number never leaves this phone.",
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    PillButton("Check a card", Modifier.fillMaxWidth(), enabled = vm.busy == null, glyph = Glyph.Card) { vm.checkCard() }
    vm.cardReport?.let { r ->
        CardReportCard(r)
        GhostButton("Share report", Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.diagnostics()), "Share card report"))
        }
    }
}

@Composable
private fun CardReportCard(r: app.tapvault.core.CardReport) {
    val ok = r.canSign
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.network} •••• ${r.panLast4 ?: "????"}", fontSize = 17.sp)
                Caption(r.label.ifBlank { r.aid })
            }
            Badge(if (ok) "Can sign" else "Can't sign", if (ok) Tv.Up else Tv.Down, (if (ok) Tv.Up else Tv.Down).copy(alpha = 0.15f))
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("Offline auth", listOfNotNull("SDA".takeIf { r.sdaSupported }, "DDA".takeIf { r.ddaSupported }, "CDA".takeIf { r.cdaSupported }).joinToString(" · ").ifEmpty { "none" })
        InfoRow("CA key index", r.caIndex ?: "–")
        InfoRow("Issuer key", r.issuerKeyBits?.let { "$it bit" } ?: "–")
        InfoRow("Card key", r.iccKeyBits?.let { "$it bit, e=${r.iccExponent}" } ?: "–")
        InfoRow("Signs with", r.signsWith ?: "–")
        InfoRow("Challenge format", r.ddol ?: "–")
        r.notes.forEach { Caption("· $it", Modifier.padding(top = 4.dp)) }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    var confirmReset by remember { mutableStateOf(false) }
    Header("Settings")
    vm.store.walletAccount?.let { a -> ListRow(Glyph.Shield, "Wallet account", a, "↗", onClick = { uri.openUri(vm.near.explorerAccount(a)) }) }
    vm.store.gasAccount?.let { a -> ListRow(Glyph.Send, "Fee account", "pays gas, can't move funds", "↗", onClick = { uri.openUri(vm.near.explorerAccount(a)) }) }
    ListRow(Glyph.Phone, "Phone key", if (KeystoreSigner.exists()) vm.phone.hardwareLabel else "none", "P-256")
    ListRow(Glyph.Card, "Card", app.tapvault.core.CardRecipe.decode(vm.store.cardRecipe)?.let { "Bank card over NFC · ${it.method.label}" } ?: "Contactless bank card over NFC", "NFC")
    vm.wallet?.let { w ->
        ListRow(Glyph.History, "Delays", "transfers ${w.delays.transfer / 3600} h · changes ${w.delays.config / 3600} h · recovery ${w.delays.recovery / 3600} h")
    }
    Spacer(Modifier.height(16.dp))
    if (vm.wallet?.frozen == false) {
        GhostButton("Freeze with phone", Modifier.fillMaxWidth(), Tv.Down, vm.busy == null) { vm.freezeWithPhone() }
        Spacer(Modifier.height(8.dp))
    }
    GhostButton("Forget this wallet on this phone", Modifier.fillMaxWidth(), Tv.Down) { confirmReset = true }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Tv.Plum,
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetApp() }) { Text("Forget", color = Tv.Down) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep", color = Tv.Text) } },
            title = { Text("Forget this wallet?") },
            text = { Text("This deletes the phone key. Recovering the wallet would then need your cards and the recovery delay.", textAlign = TextAlign.Start) },
        )
    }
}
