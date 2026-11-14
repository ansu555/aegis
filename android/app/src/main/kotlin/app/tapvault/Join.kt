package app.tapvault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tapvault.core.Guardian
import java.text.DateFormat
import java.util.Date

/*
 * Phase 3 screens: joining an existing wallet from this phone.
 *  - Recover: the old phone is lost; two of the wallet's cards add this phone (24 h).
 *  - Guardian: this phone offers its key to someone else's wallet.
 * And the owner-side sheets in Security Center: add a guardian, fingerprint-only key.
 */

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

// ---------------------------------------------------------------- recover

@Composable
fun ColumnScope.RecoverScreen(vm: AppViewModel) {
    val joining = vm.store.joinMode == "recover" && vm.store.walletAccount != null
    LaunchedEffect(joining) { if (joining) vm.refreshRecovery() }
    PinnedTopBar("Recover a wallet", onBack = if (joining) null else ({ vm.recoverLookup = null; vm.go(Screen.Welcome) }))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (joining) RecoverProgress(vm) else RecoverStart(vm)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecoverStart(vm: AppViewModel) {
    var account by remember { mutableStateOf(vm.recoverLookup?.account ?: "") }
    val look = vm.recoverLookup
    val lost = remember { mutableStateOf(setOf<String>()) }
    Text("Lost your phone?", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Two of the wallet's cards can add this phone in place of the old one. It takes effect after the wallet's recovery wait (24 h), and the old phone can cancel it until then.",
        style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
    )
    TvField("Wallet account", account, { account = it.trim(); vm.recoverLookup = null }, placeholder = vm.network.example("tv-1a2b3c4d5e"))
    Spacer(Modifier.height(10.dp))
    if (look == null) {
        TvButton("Find the wallet", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Scan, enabled = vm.busy == null && account.length > 3) { vm.findWalletToRecover(account) }
        Caption("The account is on your old phone's Receive screen and in any explorer link to the wallet.", Modifier.padding(top = 10.dp), Tv.Text3)
        return
    }
    val st = look.state
    SectionHeader("What's on the wallet")
    Group {
        Row_(Ic.Key, "Approvals needed", "Cards that must tap, one after another", value = "${st.threshold}")
        GroupDivider()
        Row_(Ic.Phone, "Phone keys", "Removed by the recovery", value = "${st.signers.count { !it.isCard }}")
        GroupDivider()
        Row_(Ic.Timer, "Recovery wait", "Before this phone joins", value = hoursLabel(st.delays.recovery))
    }
    SectionHeader("Was a card lost too?")
    Group {
        st.cards().forEachIndexed { i, c ->
            if (i > 0) GroupDivider()
            val isLost = c.keyHash in lost.value
            Row_(
                Ic.Card, "Card ${i + 1}", if (isLost) "Lost: removed by the recovery, can't tap" else "I have this card",
                value = if (isLost) "Lost" else "Have it", tint = if (isLost) Tv.Danger else Tv.Text, chevron = true,
                onClick = { lost.value = if (isLost) lost.value - c.keyHash else lost.value + c.keyHash },
            )
        }
    }
    Caption(
        "Cards aren't named on chain, so they show as Card 1, 2…; each is recognised when you tap it. A lost card left on the wallet could cancel the recovery.",
        Modifier.padding(top = 8.dp, start = 4.dp), Tv.Text3,
    )
    val kept = st.cards().count { it.keyHash !in lost.value }
    val problem = look.problem ?: if (kept < st.threshold) "Recovery needs ${st.threshold} cards you still have; you have $kept." else null
    Spacer(Modifier.height(14.dp))
    if (problem != null) {
        Notice(Ic.Alert, "Can't recover with cards alone", problem, Tv.Danger)
    } else {
        Notice(Ic.Timer, "Have ${st.threshold} cards ready", "They must all tap within 10 minutes of the first one.", Tv.Warn)
        Spacer(Modifier.height(14.dp))
        NfcNotice(vm)
        SlideToConfirm("Slide to start recovery", icon = Ic.Card) { vm.startRecovery(lost.value) }
    }
}

@Composable
private fun RecoverProgress(vm: AppViewModel) {
    val p = vm.recoveryRequest
    val st = vm.recoveryState
    val now = System.currentTimeMillis() / 1000
    Text(vm.store.walletAccount ?: "", style = Type.BodyStrong, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(12.dp))
    when {
        p == null -> {
            Notice(Ic.Alert, "No open recovery request", "It was cancelled (the old phone or a card of the wallet can), or it hasn't been made yet. Start it again.", Tv.Warn)
            Spacer(Modifier.height(14.dp))
            TvButton("Start again", Modifier.fillMaxWidth(), icon = Ic.Refresh, enabled = vm.busy == null) {
                vm.store.joinMode = null; vm.recoverLookup = null; vm.go(Screen.Recover)
                vm.findWalletToRecover(vm.store.walletAccount ?: "")
            }
        }
        p.readyTs == null -> {
            val done = st?.cards()?.count { p.approvedBy(it.index) } ?: 0
            Notice(Ic.Card, "Cards approved: $done of ${st?.threshold ?: 2}", "Tap the next card within 10 minutes of the first one. After that, start again.", Tv.Warn)
            Spacer(Modifier.height(14.dp))
            TvButton("Approve with a card", Modifier.fillMaxWidth(), icon = Ic.Nfc, enabled = vm.busy == null && vm.hasNfc) { vm.approveRecoveryAgain() }
        }
        else -> {
            val at = (p.readyTs ?: 0L) + (st?.delays?.recovery ?: 86_400)
            val left = at - now
            Group {
                Row_(Ic.Hourglass, if (left > 0) "Joins in ${formatDuration(left)}" else "Ready to finish", DateFormat.getDateTimeInstance().format(Date(at * 1000)), tint = Tv.Warn)
                GroupDivider()
                Row_(Ic.Shield, "The old phone can cancel this", "That's what stops someone with stolen cards")
            }
            Spacer(Modifier.height(14.dp))
            TvButton("Finish recovery", Modifier.fillMaxWidth(), icon = Ic.Check, enabled = vm.busy == null && left <= 0) { vm.finishRecovery() }
        }
    }
    Spacer(Modifier.height(8.dp))
    TvButton("Check again", Modifier.fillMaxWidth(), Btn.Ghost, icon = Ic.Refresh, enabled = vm.busy == null) { vm.refreshRecovery() }
    Caption("Keep this app installed: this phone's new key lives only here.", Modifier.fillMaxWidth().padding(top = 8.dp), Tv.Text3, TextAlign.Center)
}

// ---------------------------------------------------------------- guardian (this phone offers its key)

@Composable
fun ColumnScope.GuardianScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var account by remember { mutableStateOf("") }
    LaunchedEffect(vm.busy) { vm.loadGuardianCode() }
    val code = vm.guardianCode
    PinnedTopBar("Be a guardian", onBack = if (code != null) null else ({ vm.go(Screen.Welcome) }))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (code == null) {
            Text("Help guard someone's wallet", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
            Text(Guardian.WARNING, style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            TvField("Their wallet account", account, { account = it.trim() }, placeholder = vm.network.example("tv-1a2b3c4d5e"))
            Spacer(Modifier.height(14.dp))
            TvButton("Make my guardian code", Modifier.fillMaxWidth(), icon = Ic.Key, enabled = vm.busy == null && account.length > 3) { vm.becomeGuardian(account) }
        } else {
            Text("Show this to the owner", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
            Text(
                "They add it in Security Center → Add a guardian phone. After their settings wait and Execute, this phone becomes a signer.",
                style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            Column(Modifier.fillMaxWidth().surface(22.dp).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(code, Modifier.size(220.dp))
                Spacer(Modifier.height(12.dp))
                Caption(code.take(40) + "…", color = Tv.Text3, align = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            TvButton("Copy code", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Copy) { copy(ctx, "Guardian code", code) }
            Spacer(Modifier.height(8.dp))
            TvButton("Share code", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Share) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Share guardian code"))
            }
            Spacer(Modifier.height(8.dp))
            TvButton("I've been added", Modifier.fillMaxWidth(), icon = Ic.Check, enabled = vm.busy == null) { vm.checkGuardianAdded() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Security Center sheets (owner)

@Composable
fun AddGuardianSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val parsed = remember(code) { runCatching { Guardian.decode(code) } }
    val ctx = LocalContext.current
    GlassSheet(onDismiss) {
        Text("Add a guardian phone", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "On the guardian's phone: Aegis → Be a guardian → your wallet account. Then paste the code it shows here.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        )
        Notice(Ic.Warning, "A full co-signer", Guardian.WARNING, Tv.Warn)
        Spacer(Modifier.height(12.dp))
        TvField("Guardian code", code, { code = it.trim() }, placeholder = Guardian.PREFIX + "…",
            error = parsed.exceptionOrNull()?.message?.takeIf { code.isNotEmpty() })
        Spacer(Modifier.height(8.dp))
        TvButton("Paste", Modifier.fillMaxWidth(), Btn.Ghost, icon = Ic.Copy, height = 44.dp) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.let { code = it.toString().trim() }
        }
        Spacer(Modifier.height(12.dp))
        if (parsed.isSuccess) {
            SlideToConfirm("Slide to add guardian") { onDismiss(); vm.addGuardian(code) }
        }
        Spacer(Modifier.height(6.dp))
        TvButton("Not now", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}

@Composable
fun StrictKeySheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val w = vm.wallet
    val hasBackup = (w?.cards()?.size ?: 0) >= 2
    GlassSheet(onDismiss) {
        Text("Fingerprint only", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "Approvals stop accepting the screen lock (PIN, pattern, password): only a fingerprint can unlock this phone's key. Someone who watched you type your PIN still can't approve.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        )
        Group {
            Row_(Ic.Key, "A new phone key", "Android fixes how a key unlocks when it's made, so the key is replaced")
            GroupDivider()
            Row_(Ic.Card, "Approve with a card", "Your fingerprint, then a card tap")
            GroupDivider()
            Row_(Ic.Timer, "Wait ${hoursLabel(w?.delays?.config ?: 3600)}", "Then Execute in Activity; the app switches keys")
        }
        Spacer(Modifier.height(12.dp))
        Notice(Ic.Warning, "If your fingerprint stops working", "you'll need two of your cards to get back in (Recover a wallet). A backup card is required first.", Tv.Warn)
        Spacer(Modifier.height(14.dp))
        if (hasBackup) SlideToConfirm("Slide to switch") { onDismiss(); vm.switchToStrictKey() }
        else TvButton("Add a backup card first", Modifier.fillMaxWidth(), enabled = false) {}
        Spacer(Modifier.height(6.dp))
        TvButton("Not now", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}

// ---------------------------------------------------------------- receiver allowlist (contract v0.2)

@Composable
fun AllowlistSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val w = vm.wallet
    val list = w?.allowlist ?: emptyList()
    var receiver by remember { mutableStateOf("") }
    GlassSheet(onDismiss) {
        Text("Instant receivers", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            if (list.isEmpty()) "Off: anyone can be paid instantly within your limits. Add a receiver and, from then on, only listed receivers are instant; everyone else waits ${hoursLabel(w?.delays?.transfer ?: 3600)}, so you can cancel."
            else "Only these receivers are paid instantly within your limits. Everyone else waits ${hoursLabel(w?.delays?.transfer ?: 3600)}.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        )
        if (list.isNotEmpty()) {
            Group {
                list.forEachIndexed { i, r ->
                    if (i > 0) GroupDivider()
                    Row_(Ic.Check, r, "Tap to remove (after the settings wait)", chevron = true, onClick = { onDismiss(); vm.changeAllowlist(r, add = false) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        TvField("Add a receiver", receiver, { receiver = it.trim() }, placeholder = vm.network.example("bob"))
        Spacer(Modifier.height(12.dp))
        TvButton("Add (phone + card)", Modifier.fillMaxWidth(), icon = Ic.Plus, enabled = vm.busy == null && receiver.length > 2 && vm.hasNfc) {
            onDismiss(); vm.changeAllowlist(receiver, add = true)
        }
        Caption("Each change takes effect after ${hoursLabel(w?.delays?.config ?: 3600)}: Activity → Execute.", Modifier.fillMaxWidth().padding(top = 8.dp), Tv.Text3, TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        TvButton("Close", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}
