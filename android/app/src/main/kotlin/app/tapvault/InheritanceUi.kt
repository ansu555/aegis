package app.tapvault

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tapvault.core.Inheritance
import app.tapvault.core.InheritanceInfo
import java.text.DateFormat
import java.util.Date

/*
 * Contract v0.4 inheritance: the owner's sheet (name an heir, check in) and
 * the heir's sheet (look up a wallet, start the claim, release the funds).
 * Everything shown is read from the wallet contract (`get_inheritance`).
 */

private fun at(secs: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(secs * 1000))

/** One line on where inheritance stands, for the Security Center row. */
fun inheritanceSummary(info: InheritanceInfo?, now: Long): String = when (info?.stage(now)) {
    null, Inheritance.Stage.NONE -> "Off: name who receives this wallet if you're gone"
    Inheritance.Stage.WATCHING -> "${info.heir} · claim possible in ${Inheritance.describeLeft(info.secondsToClaimable(now))} without activity"
    Inheritance.Stage.CLAIMABLE -> "No activity for ${Inheritance.describePeriod(info.inactivity)}: check in now"
    Inheritance.Stage.CLAIMED, Inheritance.Stage.RELEASABLE -> "A claim is open: check in to void it"
}

@Composable
fun HeirSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val w = vm.wallet
    val info = vm.inheritance
    val now = System.currentTimeMillis() / 1000
    val choices = Inheritance.choices(vm.network.isTestnet)
    var heir by remember { mutableStateOf(info?.heir ?: "") }
    var period by remember { mutableStateOf(info?.inactivity?.takeIf { it in choices } ?: choices.first { it >= 180 * Inheritance.DAY || it == choices.last() }) }
    val wait = hoursLabel(w?.delays?.config ?: 3600)
    val approve = if (w?.phoneOnly == true) "phone" else "phone + card"
    GlassSheet(onDismiss) {
        Text("Inheritance", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "Name someone to receive this wallet if you're gone. If no key of this wallet (this phone or your cards) signs anything for the period you pick, " +
                "anyone can start a claim; if you still don't check in within $wait, everything can go only to your heir. Any payment, approval, freeze or check-in restarts the period.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        )
        if (info?.hasHeir == true) {
            Group {
                InfoRow("Heir", info.heir!!)
                InfoRow("Period", Inheritance.describePeriod(info.inactivity))
                InfoRow("Last activity", at(info.lastActive))
                when (info.stage(now)) {
                    Inheritance.Stage.WATCHING -> InfoRow("Claim possible", "in ${Inheritance.describeLeft(info.secondsToClaimable(now))}")
                    Inheritance.Stage.CLAIMABLE -> InfoRow("Claim possible", "now", Tv.Warn)
                    Inheritance.Stage.CLAIMED -> InfoRow("Claim open", "release from ${at(info.releasableAt!!)}", Tv.Danger)
                    Inheritance.Stage.RELEASABLE -> InfoRow("Claim open", "can be released now", Tv.Danger)
                    Inheritance.Stage.NONE -> {}
                }
            }
            Spacer(Modifier.height(12.dp))
            TvButton("I'm still here (check in)", Modifier.fillMaxWidth(), icon = Ic.Fingerprint, enabled = vm.busy == null) {
                onDismiss(); vm.checkIn()
            }
            Caption("Signs with this phone. Restarts the period and voids an open claim.", Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp), Tv.Text3, TextAlign.Center)
        }
        TvField(if (info?.hasHeir == true) "Change the heir" else "Heir's NEAR account", heir, { heir = it.trim() }, placeholder = vm.network.example("mum"))
        Spacer(Modifier.height(12.dp))
        SectionHeader("If nothing is signed for")
        Group {
            choices.forEachIndexed { i, c ->
                if (i > 0) GroupDivider()
                Row_(
                    Ic.Hourglass, Inheritance.describePeriod(c), if (c == Inheritance.HOUR) "Testnet only: for trying it out" else null,
                    trailingIcon = if (c == period) Ic.Check else null, tint = if (c == period) Tv.Positive else Tv.Text,
                    onClick = { period = c },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val changed = heir.isNotEmpty() && (heir.lowercase() != info?.heir || period != info.inactivity)
        TvButton(
            if (info?.hasHeir == true) "Save ($approve)" else "Name heir ($approve)", Modifier.fillMaxWidth(), icon = Ic.Key,
            enabled = vm.busy == null && changed && heir.length > 1 && (w?.phoneOnly == true || vm.hasNfc),
        ) { onDismiss(); vm.setHeir(heir, period) }
        if (info?.hasHeir == true) {
            Spacer(Modifier.height(8.dp))
            TvButton("Remove the heir", Modifier.fillMaxWidth(), Btn.Danger, icon = Ic.Trash, enabled = vm.busy == null && (w?.phoneOnly == true || vm.hasNfc)) {
                onDismiss(); vm.removeHeir()
            }
        }
        Caption("A change takes effect after $wait: Activity → Execute. Tell your heir the wallet's account (${w?.let { vm.store.walletAccount } ?: ""}): they claim it from their own Aegis app.", Modifier.fillMaxWidth().padding(top = 8.dp), Tv.Text3, TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        TvButton("Close", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}

@Composable
fun ClaimSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var account by remember { mutableStateOf(vm.claimLookup?.first ?: "") }
    val found = vm.claimLookup?.takeIf { it.first == account.trim().lowercase() }
    val now = System.currentTimeMillis() / 1000
    GlassSheet(onDismiss) {
        Text("Claim an inheritance", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "If someone named you as the heir of their Aegis wallet, enter that wallet's account. A claim can start only after their wallet has been silent for the whole period they chose, " +
                "and the funds go only to the heir they named, whoever presses the button. This phone's fee account pays the network fees.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        )
        TvField("Their wallet account", account, { account = it.trim() }, placeholder = vm.network.example("tv-0123456789"))
        Spacer(Modifier.height(12.dp))
        TvButton("Look up", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Scan, enabled = vm.busy == null && account.length > 1) { vm.lookupInheritance(account) }
        found?.let { (acct, info) ->
            Spacer(Modifier.height(12.dp))
            Group {
                InfoRow("Heir", info.heir!!)
                InfoRow("Period", Inheritance.describePeriod(info.inactivity))
                InfoRow("Last activity", at(info.lastActive))
            }
            Spacer(Modifier.height(12.dp))
            when (info.stage(now)) {
                Inheritance.Stage.WATCHING -> Notice(Ic.Hourglass, "Not yet", "The owner was active recently. A claim can start in ${Inheritance.describeLeft(info.secondsToClaimable(now))} if nothing is signed until then.", Tv.Text2)
                Inheritance.Stage.CLAIMABLE -> TvButton("Start the claim", Modifier.fillMaxWidth(), icon = Ic.Key, enabled = vm.busy == null) { vm.startInheritanceClaim(acct) }
                Inheritance.Stage.CLAIMED -> Notice(Ic.Timer, "Claim open", "If the owner doesn't check in, everything can be released from ${at(info.releasableAt!!)}.", Tv.Warn)
                Inheritance.Stage.RELEASABLE -> TvButton("Release to ${info.heir}", Modifier.fillMaxWidth(), icon = Ic.Receive, enabled = vm.busy == null) { vm.releaseInheritance(acct) }
                Inheritance.Stage.NONE -> {}
            }
        }
        Spacer(Modifier.height(6.dp))
        TvButton("Close", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
    }
}
