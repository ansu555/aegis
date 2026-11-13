package app.tapvault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tapvault.core.Integrity
import app.tapvault.core.Near
import app.tapvault.core.ProposalInfo
import app.tapvault.core.SignerInfo

/**
 * Security Center (plan 4.3): the wallet's keys, protection and the hold
 * timer. Keys, limits and waits come from `get_wallet`, so the screen shows
 * what the contract enforces; only card names (network, last four) and the
 * hold timer are this phone's own.
 */
@Composable
fun SecurityScreen(vm: AppViewModel) {
    val w = vm.wallet
    val idle = vm.busy == null
    var addOpen by remember { mutableStateOf(false) }
    var holdOpen by remember { mutableStateOf(false) }
    var lost by remember { mutableStateOf<SignerInfo?>(null) }
    var guardianOpen by remember { mutableStateOf(false) }
    var strictOpen by remember { mutableStateOf(false) }
    var allowOpen by remember { mutableStateOf(false) }
    var confirmFreeze by remember { mutableStateOf(false) }
    TopBar("Security", onBack = { vm.go(vm.cameFrom) })

    if (w == null) {
        Spacer(Modifier.height(12.dp))
        Shimmer(Modifier.fillMaxWidth().height(180.dp))
        Caption(vm.offline ?: "Reading the wallet contract…", Modifier.padding(top = 12.dp, start = 4.dp))
        return
    }
    val myPub = runCatching { vm.phone.publicKey }.getOrNull()
    val cards = w.cards()
    val report = vm.integrity
    val now = System.currentTimeMillis() / 1000
    val removing = vm.proposals.filter { !it.stale }.flatMap { it.removedKeyHashes() }.map { it.lowercase() }.toSet()

    // Protection score (plan 4.3): one point each.
    val points = listOf(
        cards.size >= 2,
        vm.alertsOn,
        w.nearLimit?.let { l -> vm.balance?.let { b -> b.signum() > 0 && l.instantMax * java.math.BigInteger.TEN <= b } } ?: false,
        report != null && report.ok && now - report.atSecs < 24 * 3600,
        !w.frozen,
    )
    Spacer(Modifier.height(4.dp))
    Group {
        Row_(
            Ic.Shield, "Protection score", listOfNotNull(
                "backup card".takeIf { !points[0] }, "alerts".takeIf { !points[1] },
                "instant limit ≤ 10% of balance".takeIf { !points[2] }, "wallet check in the last 24 h".takeIf { !points[3] },
                "unfreeze".takeIf { !points[4] },
            ).let { if (it.isEmpty()) "Everything on this list is done" else "Missing: " + it.joinToString(", ") },
            value = "●".repeat(points.count { it }) + "○".repeat(points.count { !it }),
            tint = if (points.all { it }) Tv.Positive else Tv.Warn,
            iconBg = if (points.all { it }) Tv.PositiveSoft else Tv.Warn.copy(alpha = 0.13f),
        )
    }
    if (report != null && !report.ok) {
        Spacer(Modifier.height(8.dp))
        Notice(Ic.Alert, "Wallet check failed", report.failed.joinToString("\n") { "${it.name}: ${it.detail}" } + "\n\nIf you didn't change anything, freeze the wallet now.", Tv.Danger) {
            if (!w.frozen) TvButton("Freeze with this phone", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Freeze, height = 48.dp, enabled = idle) { vm.freezeWithPhone() }
        }
    }

    if (w.frozen) {
        Spacer(Modifier.height(8.dp))
        Notice(Ic.Freeze, "Wallet frozen", "No transfers can go out. Unfreezing needs your phone and a card, then waits ${hoursLabel(w.delays.config)}.", Tv.Ice) {
            TvButton("Request unfreeze", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Key, height = 48.dp, enabled = idle) { vm.requestUnfreeze() }
        }
    } else if (cards.size < 2) {
        Spacer(Modifier.height(8.dp))
        Notice(Ic.Warning, "No backup card", "Lose this phone's key or your card and the wallet can't approve anything again. A backup card co-signs with this phone in place of the main card.", Tv.Warn)
    }

    Column(Modifier.enter(1)) {
        SectionHeader("Keys · ${w.threshold} of ${w.signers.size} needed")
        Group {
            w.signers.forEachIndexed { i, s ->
                if (i > 0) GroupDivider()
                if (!s.isCard) {
                    val mine = myPub != null && s.phonePublicKey()?.contentEquals(myPub) == true
                    Row_(
                        Ic.Phone, if (mine) "This phone" else "Another phone key",
                        if (mine) "${vm.phone.hardwareLabel} · " + (if (vm.phone.isStrict) "fingerprint only" else "fingerprint or screen lock") else "Another phone or a guardian",
                        value = "P-256", tint = if (mine) Tv.Positive else Tv.Text, iconBg = if (mine) Tv.PositiveSoft else Tv.GlassStrong,
                    )
                } else {
                    val known = vm.cardBook.find(s.keyHash)
                    val role = if (s.keyHash == cards.first().keyHash) "Main card" else "Backup card"
                    val going = s.keyHash.lowercase() in removing
                    Row_(
                        Ic.Card, vm.cardName(s),
                        if (going) "Being removed · see Activity" else role + (known?.recipe?.let { " · ${it.method.label}" } ?: " · not read on this phone yet"),
                        value = "RSA", chevron = !going, tint = if (going) Tv.Warn else Tv.Text,
                        onClick = if (going) null else ({ if (idle) lost = s }),
                    )
                }
            }
        }
        vm.pendingCardAdds.forEach { p ->
            Spacer(Modifier.height(10.dp))
            Group { PendingAddRow(vm, p) }
        }
        Spacer(Modifier.height(14.dp))
        TvButton(
            "Add backup card", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Plus,
            enabled = idle && vm.hasNfc && w.signers.size < 8,
        ) { addOpen = true }
        Caption(
            "One backup is enough: keep it at home, not with your main card. Every extra card is one more card that can be skimmed.",
            Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), color = Tv.Text3,
        )
        Spacer(Modifier.height(12.dp))
        Group {
            Row_(
                Ic.Phone, "Add a guardian phone", "Someone you trust can cancel and freeze, and co-sign with a card",
                chevron = true, onClick = { if (idle && w.signers.size < 8) guardianOpen = true },
            )
            if (vm.isV02 == true) {
                GroupDivider()
                Row_(
                    Ic.Send, "Instant receivers", if (w.allowlist.isEmpty()) "Off: anyone within your limits" else "Only listed receivers are instant",
                    value = if (w.allowlist.isEmpty()) "Off" else "${w.allowlist.size}", chevron = true,
                    onClick = { if (idle) allowOpen = true },
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                GroupDivider()
                val strict = vm.phone.isStrict
                Row_(
                    Ic.Fingerprint, "Fingerprint only", if (strict) "This phone's key refuses the PIN or pattern" else "Stop the PIN or pattern from approving",
                    value = if (strict) "On" else if (vm.store.pendingAlias != null) "Pending" else "Off",
                    chevron = !strict && vm.store.pendingAlias == null, tint = if (strict) Tv.Positive else Tv.Text,
                    iconBg = if (strict) Tv.PositiveSoft else Tv.GlassStrong,
                    onClick = if (strict || vm.store.pendingAlias != null) null else ({ if (idle) strictOpen = true }),
                )
            }
        }
    }

    Column(Modifier.enter(2)) {
        SectionHeader("Protection")
        Group {
            w.nearLimit?.let { l ->
                Row_(Ic.Send, "Instant limit", "Per payment, goes out at once", value = "${Near.format(l.instantMax)} NEAR")
                GroupDivider()
                Row_(Ic.Wallet, "Daily limit", "Most that goes out at once in a day", value = "${Near.format(l.dailyMax)} NEAR")
                GroupDivider()
            }
            Row_(Ic.Timer, "Big transfer wait", "Over the limits; you can cancel meanwhile", value = hoursLabel(w.delays.transfer))
            GroupDivider()
            Row_(Ic.Settings, "Settings change wait", "Adding a card, unfreezing", value = hoursLabel(w.delays.config))
            GroupDivider()
            Row_(Ic.Key, "Recovery wait", "Changes approved by cards only", value = hoursLabel(w.delays.recovery))
            GroupDivider()
            Row_(
                Ic.Hourglass, "Hold card to sign", "A pause in this app before a card signs",
                value = if (vm.holdSeconds == 0) "Off" else "${vm.holdSeconds} s", chevron = true,
                onClick = { if (idle) holdOpen = true },
            )
            GroupDivider()
            Row_(
                if (w.frozen) Ic.Freeze else Ic.ShieldCheck, "Wallet", if (w.frozen) "Frozen: no transfers go out" else "Active · tap to freeze with this phone",
                tint = if (w.frozen) Tv.Ice else Tv.Positive, iconBg = if (w.frozen) Tv.IceSoft else Tv.PositiveSoft,
                chevron = !w.frozen, onClick = if (w.frozen) null else ({ if (idle) confirmFreeze = true }),
            )
        }
        Caption(
            "Keys, limits and waits are read from the wallet contract on NEAR testnet: this is what the chain enforces. The hold timer is this app's own setting.",
            Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), color = Tv.Text3,
        )
    }

    Column(Modifier.enter(3)) {
        SectionHeader("Checks", action = "Check now".takeIf { idle }, onAction = { vm.checkIntegrity() })
        Group {
            if (report == null) {
                Row_(Ic.Refresh, "Wallet check", "Not run yet on this phone", value = "—")
            } else {
                report.checks.forEachIndexed { i, c ->
                    if (i > 0) GroupDivider()
                    val (ic, col) = when (c.status) {
                        Integrity.Status.OK -> Ic.CheckCircle to Tv.Positive
                        Integrity.Status.NOTE -> Ic.Info to Tv.Warn
                        Integrity.Status.FAIL -> Ic.XCircle to Tv.Danger
                    }
                    Row_(ic, c.name, c.detail, tint = col, iconBg = col.copy(alpha = 0.13f))
                }
            }
            GroupDivider()
            Row_(
                Ic.Alert, "Alerts", if (vm.alertsOn) "About every 15 min: requests you didn't make, freezes, signer changes" else "Off: nothing warns you when you're not in the app",
                value = if (vm.alertsOn) "On" else "Off", chevron = true,
                tint = if (vm.alertsOn) Tv.Positive else Tv.Text, iconBg = if (vm.alertsOn) Tv.PositiveSoft else Tv.GlassStrong,
                onClick = { vm.setAlerts(!vm.alertsOn) },
            )
        }
        Caption(
            (report?.let { "Last check ${formatDuration((now - it.atSecs).coerceAtLeast(0))} ago. " } ?: "") +
                "Alerts go to this phone only: if the phone is what was stolen, they can't warn you. Android may delay background checks.",
            Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), color = Tv.Text3,
        )
    }

    if (confirmFreeze) ConfirmDialog(
        "Freeze the wallet?", "Blocks every transfer at once. Unfreezing takes your phone and a card, then waits ${hoursLabel(w.delays.config)}.",
        "Freeze", { confirmFreeze = false; vm.freezeWithPhone() }, { confirmFreeze = false }, icon = Ic.Freeze,
    )
    lost?.let { LostCardSheet(vm, it) { lost = null } }
    if (guardianOpen) AddGuardianSheet(vm) { guardianOpen = false }
    if (strictOpen) StrictKeySheet(vm) { strictOpen = false }
    if (allowOpen) AllowlistSheet(vm) { allowOpen = false }
    if (addOpen && vm.backup == null) AddBackupIntro(vm, { addOpen = false })
    vm.backup?.let { BackupReview(vm, it) { addOpen = false } }
    if (holdOpen) HoldTimerSheet(vm) { holdOpen = false }
}

@Composable
private fun PendingAddRow(vm: AppViewModel, p: ProposalInfo) {
    val now = System.currentTimeMillis() / 1000
    val w = vm.wallet ?: return
    val name = p.addedCardKeyHashes().firstOrNull()?.let { vm.cardBook.find(it)?.name } ?: "A card"
    val ready = p.readyTs
    val left = ready?.let { (it + w.delays.config - now).coerceAtLeast(0) }
    val status = when {
        ready == null -> "Waiting for a card's approval"
        left!! > 0 -> "Becomes a signer in ${formatDuration(left)}"
        else -> "Ready: tap Execute in Activity"
    }
    Row_(Ic.Hourglass, "Adding $name", "$status · #${p.id}", tint = Tv.Warn, iconBg = Tv.Warn.copy(alpha = 0.13f), chevron = true, onClick = { vm.go(Screen.Activity) })
}

/** Add backup card, step 1: what happens, then hold the new card. */
@Composable
private fun AddBackupIntro(vm: AppViewModel, onDismiss: () -> Unit) {
    val w = vm.wallet
    GlassSheet(onDismiss) {
        Text("Add a backup card", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "A second bank card that can co-sign with this phone. Keep it at home, not in the same wallet as your main card.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
        )
        Group {
            Row_(Ic.Nfc, "Hold the new card", "The phone reads its certificate and makes it sign a test challenge")
            GroupDivider()
            Row_(Ic.Fingerprint, "Approve", "Your fingerprint, then a tap of a card already in this wallet")
            GroupDivider()
            Row_(Ic.Timer, "Wait ${hoursLabel(w?.delays?.config ?: 3600)}", "Then tap Execute in Activity; until then you can cancel")
        }
        Spacer(Modifier.height(14.dp))
        Notice(
            Ic.Info, "Checked on this phone",
            "The wallet contract doesn't check that a card added after setup can sign, so this phone verifies the new card's test signature before anything is proposed.",
            Tv.Text2,
        )
        Spacer(Modifier.height(16.dp))
        NfcNotice(vm)
        TvButton("Hold the new card", Modifier.fillMaxWidth(), icon = Ic.Nfc, enabled = vm.busy == null && vm.hasNfc) {
            onDismiss(); vm.readBackupCard()
        }
        Spacer(Modifier.height(6.dp))
        TvButton("Not now", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}

/** Add backup card, step 2: review the card that checked out, then slide to propose it. */
@Composable
private fun BackupReview(vm: AppViewModel, b: BackupCandidate, onDone: () -> Unit) {
    val w = vm.wallet
    val open = vm.proposals.count { !it.stale }
    val wait = hoursLabel(w?.delays?.config ?: 3600)
    GlassSheet({ vm.discardBackup(); onDone() }) {
        Text("Add backup card", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CardArt(Modifier.width(240.dp), last4 = b.last4, network = b.network)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            InfoRow("Card", b.name)
            InfoRow("Role", "Backup card")
            InfoRow("Takes effect", "in $wait")
            InfoRow("Approvals needed", "Stay ${w?.threshold ?: 2}")
            InfoRow("Test signature", "Verified on this phone", Tv.Positive)
        }
        Spacer(Modifier.height(14.dp))
        if (open > 0) Notice(
            Ic.Warning, "Waiting requests will be voided",
            "When the card is added, the $open request${if (open == 1) "" else "s"} still waiting in Activity become${if (open == 1) "s" else ""} void and must be made again. Any request still open then is voided the same way.",
            Tv.Warn,
        ) else Caption("Any payment still waiting when the card is added becomes void and must be made again.", Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 4.dp)) {
            Text("1", style = Type.CaptionStrong, color = Tv.Text2); Text("Confirm with your fingerprint", style = Type.Body)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 4.dp)) {
            Text("2", style = Type.CaptionStrong, color = Tv.Text2); Text("Tap a card already in this wallet", style = Type.Body)
        }
        Spacer(Modifier.height(20.dp))
        SlideToConfirm("Slide to add") { onDone(); vm.addBackupCard() }
        Spacer(Modifier.height(6.dp))
        TvButton("Use another card", Modifier.fillMaxWidth(), Btn.Ghost) { vm.discardBackup() }
    }
}

/** Hold timer choices; a change needs the phone's fingerprint or screen lock. */
@Composable
private fun HoldTimerSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        Text("Hold card to sign", style = Type.H2)
        Text(
            "After a card is read and recognised, it has to stay on the phone this long before it signs. Lift it and the timer starts over.",
            style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HOLD_CHOICES.forEach { s ->
                Chip(if (s == 0) "Off" else "$s s", selected = vm.holdSeconds == s) {
                    if (s != vm.holdSeconds) { onDismiss(); vm.setHoldTimer(s) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Notice(
            Ic.Info, "A pause in this app",
            "So nothing signs by accident. Your real protection is the phone approval and the limits: a skimmer with its own reader doesn't wait. Changing it asks for your fingerprint. Freezing never waits.",
            Tv.Text2,
        )
        Spacer(Modifier.height(10.dp))
        Caption("Keep it short until you have tried your card: a long hold makes NFC drops more likely.", Modifier.padding(start = 4.dp), color = Tv.Text3)
    }
}

/** Lost or stolen card (plan 5.4): optionally freeze, then propose removing it; another card approves. */
@Composable
private fun LostCardSheet(vm: AppViewModel, card: SignerInfo, onDismiss: () -> Unit) {
    val w = vm.wallet ?: return
    val name = vm.cardName(card)
    val others = w.cards().filter { it.keyHash != card.keyHash }
    val open = vm.proposals.count { !it.stale }
    var freeze by remember { mutableStateOf(!w.frozen) }
    val wait = hoursLabel(w.delays.config)
    GlassSheet(onDismiss) {
        Text("I lost this card", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(name, style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp))
        when {
            others.isEmpty() -> Notice(
                Ic.Warning, "This is the wallet's only card",
                "Removing it needs another card to approve. Freeze the wallet now if the card was stolen, then add a backup card; once it is a signer you can remove this one.", Tv.Warn,
            ) {
                if (!w.frozen) TvButton("Freeze with this phone", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Freeze, height = 48.dp, enabled = vm.busy == null) { onDismiss(); vm.freezeWithPhone() }
            }
            w.signers.size - 1 < w.threshold -> Notice(Ic.Warning, "Can't remove it", "That would leave fewer keys than the ${w.threshold} approvals needed.", Tv.Warn)
            else -> {
                if (!w.frozen) {
                    Group {
                        Row_(
                            Ic.Freeze, "Freeze the wallet first", if (freeze) "Recommended if it was stolen: blocks transfers right now" else "Off: payments keep working while you wait",
                            value = if (freeze) "On" else "Off", chevron = true, tint = if (freeze) Tv.Ice else Tv.Text, iconBg = if (freeze) Tv.IceSoft else Tv.GlassStrong,
                            onClick = { freeze = !freeze },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    InfoRow("Remove", name)
                    InfoRow("Takes effect", "in $wait")
                    InfoRow("Approve with", others.joinToString { vm.cardName(it) })
                    if (w.frozen || freeze) InfoRow("Also", "Unfreezes when it executes", Tv.Ice)
                }
                Spacer(Modifier.height(12.dp))
                Caption(
                    "The lost card can't cancel its own removal. " + if (open > 0) "When it executes, the $open request${if (open == 1) "" else "s"} waiting in Activity become void." else "Requests still waiting when it executes become void.",
                    Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(20.dp))
                SlideToConfirm("Slide to remove") { onDismiss(); vm.removeCard(card.keyHash, freeze && !w.frozen) }
            }
        }
        Spacer(Modifier.height(6.dp))
        TvButton("Not now", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}
