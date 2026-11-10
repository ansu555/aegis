package app.tapvault

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapvault.core.Asset
import app.tapvault.core.Near
import app.tapvault.core.ProposalInfo

private val Bg = Color(0xFF0E0F12)
private val Panel = Color(0xFF181A20)
private val Line = Color(0xFF2A2D36)
private val Accent = Color(0xFF7CE0B0)
private val Warn = Color(0xFFFFB86B)
private val Bad = Color(0xFFFF6B7A)
private val Dim = Color(0xFF9AA0AE)

private val scheme = darkColorScheme(
    primary = Accent, onPrimary = Bg, background = Bg, surface = Panel, onSurface = Color(0xFFECEEF2),
    onBackground = Color(0xFFECEEF2), secondary = Accent, error = Bad, outline = Line,
)

@Composable
fun TapvaultApp(vm: AppViewModel) {
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            LaunchedEffect(Unit) { vm.refresh(); vm.startAutoRefresh() }
            BackHandler(enabled = vm.screen !in setOf(Screen.Home, Screen.Welcome, Screen.Setup)) { vm.go(Screen.Home) }
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(vm)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
                    ) {
                        vm.outcome?.let { OutcomeCard(it) { vm.outcome = null } }
                        when (vm.screen) {
                            Screen.Welcome -> Welcome(vm)
                            Screen.Setup -> SetupProgress(vm)
                            Screen.Home -> Home(vm)
                            Screen.Send -> Send(vm)
                            Screen.Activity -> ActivityScreen(vm)
                            Screen.CardCheck -> CardCheck(vm)
                            Screen.Settings -> SettingsScreen(vm)
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }
                vm.tap?.let { TapSheet(it, vm) }
                if (vm.tap == null) vm.busy?.let { BusyOverlay(it) }
            }
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vm.screen in setOf(Screen.Send, Screen.Activity, Screen.CardCheck, Screen.Settings)) {
            IconButton(onClick = { vm.go(Screen.Home) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        } else Spacer(Modifier.width(16.dp))
        Text("Tapvault", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text("testnet", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
        if (vm.screen == Screen.Home) IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 6.dp).background(Panel, RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
    ) { content() }
}

@Composable
private fun Label(s: String) = Text(s.uppercase(), color = Dim, fontSize = 11.sp, letterSpacing = 1.sp)

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, color: Color = Accent, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) {
        Text(text, color = color)
    }

@Composable
private fun OutcomeCard(o: Outcome, dismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    Panel(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (o.ok) Accent else Bad, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(o.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = dismiss) { Text("OK", color = Dim) }
        }
        if (o.detail.isNotBlank()) Text(o.detail, color = Dim, fontSize = 14.sp)
        o.link?.let { link ->
            Text("View on explorer ↗", color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).clickable { uri.openUri(link) })
        }
    }
}

// ---- welcome / setup ----

@Composable
private fun Welcome(vm: AppViewModel) {
    var demo by remember { mutableStateOf(!vm.hasNfc) }
    var instant by remember { mutableStateOf(vm.store.instantNear) }
    var daily by remember { mutableStateOf(vm.store.dailyNear) }
    Spacer(Modifier.height(24.dp))
    Text("Your bank card is\nyour hardware wallet.", fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
    Spacer(Modifier.height(12.dp))
    Text(
        "Every payment needs your fingerprint and a tap of the contactless card you already carry. " +
            "No seed phrase. A stolen phone or a stolen card alone can't move your money.",
        color = Dim, fontSize = 15.sp,
    )
    Spacer(Modifier.height(20.dp))
    Panel {
        Label("Your card")
        ChoiceRow("My contactless bank card", "Visa, Mastercard or RuPay with the contactless symbol", !demo, enabled = vm.hasNfc) { demo = false }
        ChoiceRow("Demo card", "A simulated card, for trying Tapvault without NFC", demo) { demo = true }
        if (!vm.hasNfc) Text("This phone has no NFC, so only the demo card works.", color = Warn, fontSize = 13.sp)
    }
    Panel {
        Label("Spending limits (NEAR)")
        Text("Payments within these go through instantly. Anything bigger waits an hour, so you can cancel it.", color = Dim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("At once", instant, Modifier.weight(1f)) { instant = it }
            NumberField("Per day", daily, Modifier.weight(1f)) { daily = it }
        }
    }
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Create my wallet") { vm.beginSetup(demo, instant, daily) }
    Text("Runs on NEAR testnet. Accounts and test NEAR come from the testnet faucet.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ChoiceRow(title: String, sub: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).alpha(if (enabled) 1f else 0.4f)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).border(2.dp, if (selected) Accent else Dim, CircleShape).padding(4.dp)) {
            if (selected) Box(Modifier.fillMaxSize().background(Accent, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, color = Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, modifier.padding(top = 8.dp),
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )

@Composable
private fun SetupProgress(vm: AppViewModel) {
    val steps = listOf(
        "Phone key in secure hardware",
        "Wallet account on NEAR",
        "Wallet contract deployed",
        "Card added as a signer",
        "Limits set, account locked",
    )
    Spacer(Modifier.height(16.dp))
    Text("Setting up your wallet", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Panel {
        steps.forEachIndexed { i, s ->
            val done = i == 0 && KeystoreSigner.exists() || i > 0 && vm.setupStep >= i
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).background(if (done) Accent else Color.Transparent, CircleShape).border(2.dp, if (done) Accent else Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (done) Text("✓", color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Text(s, color = if (done) Color.White else Dim)
            }
        }
    }
    vm.cardReport?.let { CardReportPanel(it) }
    if (vm.busy == null && vm.setupStep < 4) {
        PrimaryButton("Continue setup") { vm.continueSetup() }
        TextButton(onClick = { vm.resetApp() }) { Text("Start over", color = Dim) }
    }
}

// ---- home ----

@Composable
private fun Home(vm: AppViewModel) {
    val w = vm.wallet
    val clip = LocalClipboardManager.current
    Spacer(Modifier.height(8.dp))
    if (w?.frozen == true) {
        Panel(Modifier.border(1.dp, Bad, RoundedCornerShape(16.dp))) {
            Text("Wallet frozen", color = Bad, fontWeight = FontWeight.Bold)
            Text("Transfers are blocked. Unfreezing needs your phone and card, and waits ${w.delays.config / 3600} h.", color = Dim, fontSize = 14.sp)
            SecondaryButton("Request unfreeze", vm.busy == null) { vm.requestUnfreeze() }
        }
    }
    Panel {
        Label("Balance")
        Text(vm.balance?.let { "${Near.format(it, 3)} NEAR" } ?: "…", fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text(
            vm.store.walletAccount ?: "", color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { vm.store.walletAccount?.let { clip.setText(AnnotatedString(it)) } },
        )
        w?.nearLimit?.let { l ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(10.dp))
            Text("Instant: up to ${Near.format(l.instantMax)} NEAR · ${Near.format(l.availableNow())} available now", color = Dim, fontSize = 13.sp)
            Text("Bigger payments wait ${w.delays.transfer / 3600} h so you can cancel", color = Dim, fontSize = 13.sp)
        }
    }
    PrimaryButton("Send", enabled = vm.busy == null && w?.frozen == false) { vm.go(Screen.Send) }
    val pending = vm.proposals.size
    SecondaryButton(if (pending > 0) "Activity · $pending pending" else "Activity") { vm.go(Screen.Activity) }
    Panel {
        Label("Signers")
        w?.signers?.forEach { s ->
            Text(
                if (s.isCard) "💳  Card ${s.keyHash.take(8)}" else "📱  Phone key ${s.keyHash.take(8)}",
                modifier = Modifier.padding(top = 6.dp), fontSize = 14.sp,
            )
        }
        Text("${w?.threshold ?: 2} of ${w?.signers?.size ?: 2} must approve every payment", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Panel {
        Label("Emergency")
        Text("Anyone holding one of your keys can freeze the wallet instantly.", color = Dim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SecondaryButton("Freeze (phone)", vm.busy == null && w?.frozen == false, Bad) { vm.freezeWithPhone() } }
            Box(Modifier.weight(1f)) { SecondaryButton("Freeze (card)", vm.busy == null && w?.frozen == false, Bad) { vm.freezeWithCard() } }
        }
    }
    SecondaryButton("Check a card", vm.busy == null) { vm.go(Screen.CardCheck) }
}

@Composable
private fun Send(vm: AppViewModel) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val limit = vm.wallet?.nearLimit
    Spacer(Modifier.height(8.dp))
    Text("Send NEAR", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(to, { to = it.trim() }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("To (NEAR account)") }, singleLine = true)
    NumberField("Amount (NEAR)", amount, Modifier.fillMaxWidth()) { amount = it }
    val parsed = runCatching { Near.parse(amount) }.getOrNull()
    if (parsed != null && limit != null) {
        val instant = limit.isInstant(parsed)
        Text(
            if (instant) "Within your limits: goes through as soon as you tap your card."
            else "Over your instant limit: after you approve, it waits ${vm.wallet!!.delays.transfer / 3600} h before it can be sent.",
            color = if (instant) Accent else Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton("Approve with fingerprint, then tap card", enabled = vm.busy == null && to.isNotBlank() && parsed != null) {
        vm.send(to, amount)
    }
    Text("Your phone shows exactly what you're signing. The card proves you're holding it right now.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

// ---- activity ----

@Composable
private fun ActivityScreen(vm: AppViewModel) {
    val w = vm.wallet
    val now = System.currentTimeMillis() / 1000
    Spacer(Modifier.height(8.dp))
    Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    if (vm.proposals.isEmpty()) Text("Nothing pending.", color = Dim, modifier = Modifier.padding(top = 12.dp))
    val cardIdx = w?.card()?.index
    val phoneIdx = runCatching { w?.phoneIndexOf(vm.phone.publicKey) }.getOrNull()
    vm.proposals.forEach { p -> ProposalCard(vm, p, now, cardIdx, phoneIdx) }
    Spacer(Modifier.height(16.dp))
    Label("Log")
    vm.log.take(60).forEach { Text(it, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun ProposalCard(vm: AppViewModel, p: ProposalInfo, now: Long, cardIdx: Int?, phoneIdx: Int?) {
    val w = vm.wallet ?: return
    Panel {
        Text("#${p.id}  ${p.describe()}", fontWeight = FontWeight.Medium)
        val readyTs = p.readyTs
        val status = when {
            p.stale -> "Void: the signers changed after this was proposed"
            readyTs == null -> "Waiting for approvals"
            else -> {
                val delay = if (p.isTransfer) {
                    if (w.nearLimit?.isInstant(p.transferAmount() ?: java.math.BigInteger.ZERO) == true) 0 else w.delays.transfer
                } else if (phoneIdx != null && p.approvedBy(phoneIdx)) w.delays.config else w.delays.recovery
                val left = readyTs + delay - now
                if (left > 0) "Approved · can execute in ${left / 3600} h ${(left % 3600) / 60} min" else "Approved · ready to execute"
            }
        }
        Text(status, color = Dim, fontSize = 13.sp)
        val canCard = cardIdx != null && !p.approvedBy(cardIdx) && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now && !p.stale
        val canPhone = phoneIdx != null && !p.approvedBy(phoneIdx) && !p.stale
        if (canCard) SecondaryButton("Approve with card", vm.busy == null) { vm.approveWithCard(p) }
        if (canPhone) SecondaryButton("Approve with phone", vm.busy == null) { vm.approveWithPhone(p) }
        if (p.readyTs != null && !p.stale) SecondaryButton("Execute", vm.busy == null) { vm.execute(p) }
        if (!p.stale) SecondaryButton("Cancel", vm.busy == null, Bad) { vm.cancel(p) }
        if (p.stale || (p.readyTs == null && now > p.createdTs + 86_400)) SecondaryButton("Clean up", vm.busy == null, Dim) { vm.closeStale(p) }
    }
}

// ---- card check / settings ----

@Composable
private fun CardCheck(vm: AppViewModel) {
    val ctx = LocalContext.current
    Spacer(Modifier.height(8.dp))
    Text("Card check", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Reads a card's payment application and tells you whether it can be a Tapvault signer. Your card number never leaves the phone.", color = Dim, fontSize = 14.sp)
    PrimaryButton("Check a card", vm.busy == null) { vm.checkCard() }
    vm.cardReport?.let { r ->
        CardReportPanel(r)
        SecondaryButton("Share report") {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.text()), "Share card report"))
        }
    }
}

@Composable
private fun CardReportPanel(r: app.tapvault.core.CardReport) = Panel {
    Label("Card")
    Text("${r.network} ****${r.panLast4 ?: "????"}", fontWeight = FontWeight.SemiBold)
    Text(r.text(), color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    var confirmReset by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Panel {
        Label("Wallet")
        vm.store.walletAccount?.let { a -> Text(a, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent, modifier = Modifier.clickable { uri.openUri(vm.near.explorerAccount(a)) }) }
        Label("Fee account (pays gas, can't move funds)")
        vm.store.gasAccount?.let { a -> Text(a, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent, modifier = Modifier.clickable { uri.openUri(vm.near.explorerAccount(a)) }) }
        Spacer(Modifier.height(8.dp))
        Label("Phone key")
        Text(if (KeystoreSigner.exists()) vm.phone.hardwareLabel else "none", fontSize = 14.sp)
        Label("Card mode")
        Text(if (vm.store.demoCard) "Demo card (simulated)" else "Real contactless card over NFC", fontSize = 14.sp)
    }
    SecondaryButton("Forget this wallet on this phone", color = Bad) { confirmReset = true }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetApp() }) { Text("Forget", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep") } },
            title = { Text("Forget this wallet?") },
            text = { Text("This deletes the phone key. Without it, recovering the wallet needs your cards and the recovery delay.") },
        )
    }
}

// ---- overlays ----

@Composable
private fun BusyOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.85f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(16.dp))
            Text(text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TapSheet(t: TapRequest, vm: AppViewModel) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(0.9f, 1.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.94f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(140.dp).scale(s).border(3.dp, Accent, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 84.dp, height = 54.dp).background(Accent, RoundedCornerShape(8.dp)))
            }
            Spacer(Modifier.height(28.dp))
            Text(t.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                if (t.demo) "Demo mode: the simulated card answers like a real one." else "Hold it flat against the back of the phone until it vibrates.",
                color = Dim, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (t.demo) Button(onClick = { vm.demoTapNow() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Tap demo card") }
            TextButton(onClick = { vm.cancelTap() }) { Text("Cancel", color = Dim) }
        }
    }
}
