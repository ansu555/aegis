package app.tapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tapvault.core.AddressBook
import app.tapvault.core.SecurityKey

// ---------------------------------------------------------------- security key

/** The sheet a flow waits on for the security key ([AppViewModel.keyPrompt]). */
@Composable
fun SecurityKeySheet(vm: AppViewModel, p: KeyPrompt) {
    // Fresh, empty fields for each new prompt (a wrong key brings a new prompt with its error).
    var key by remember(p.title, p.error) { mutableStateOf("") }
    var confirm by remember(p.title, p.error) { mutableStateOf("") }
    var local by remember(p.title, p.error) { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(p.title, p.error) { runCatching { focus.requestFocus() } }
    fun submit() {
        if (p.checking) return
        if (p.create) {
            val problem = SecurityKey.problem(key, confirm)
            if (problem != null) { local = problem; return }
        } else if (key.isEmpty()) return
        vm.submitKey(key)
    }
    GlassSheet({ vm.cancelKey() }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Tv.GlassStrong).glassRim(32.dp, shape = CircleShape), contentAlignment = Alignment.Center) {
                TvIcon(Ic.Lock, 28.dp, Tv.Text)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(p.title, style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(p.detail, style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp))
        val shownError = local ?: p.error
        TvField(
            "", key, { key = it.take(SecurityKey.MAX_LENGTH); local = null }, placeholder = if (p.create) "New security key" else "Security key",
            secret = true, focus = focus, error = if (!p.create) shownError else null,
        )
        if (p.create) {
            Spacer(Modifier.height(10.dp))
            TvField("", confirm, { confirm = it.take(SecurityKey.MAX_LENGTH); local = null }, placeholder = "Type it again", secret = true, error = shownError)
            Caption("At least ${SecurityKey.MIN_LENGTH} characters, letters or digits. Write it down somewhere safe: you'll need it for every payment.", Modifier.padding(top = 8.dp, start = 4.dp), Tv.Text3)
        }
        Spacer(Modifier.height(18.dp))
        TvButton(
            if (p.checking) "Checking…" else "Continue", Modifier.fillMaxWidth(), icon = Ic.Check, loading = p.checking,
            enabled = !p.checking && key.isNotEmpty() && (!p.create || confirm.isNotEmpty()),
        ) { submit() }
        if (p.canForget && !p.create) {
            Spacer(Modifier.height(6.dp))
            TvButton("Forgot security key?", Modifier.fillMaxWidth(), Btn.Ghost, enabled = !p.checking) { vm.forgotKey() }
        }
        TvButton("Cancel", Modifier.fillMaxWidth(), Btn.Ghost, enabled = !p.checking) { vm.cancelKey() }
    }
}

/** Settings → Security key: set it, change it, or reset it with a card. */
@Composable
fun SecurityKeySettingsSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val idle = vm.busy == null
    GlassSheet(onDismiss) {
        Text("Security key", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "Typed after your card for every payment and approval, and to add or remove a card. Freezing and cancelling never need it.",
            style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
        )
        Group {
            if (vm.hasSecurityKey) {
                Row_(Ic.Edit, "Change security key", "Type the current one, then a new one", chevron = true, onClick = { if (idle) { onDismiss(); vm.changeSecurityKey() } })
                GroupDivider()
                Row_(Ic.Card, "Forgot it? Reset with your card", "Tap a card of this wallet and use your fingerprint", chevron = true, onClick = { if (idle) { onDismiss(); vm.resetSecurityKey() } })
            } else {
                Row_(Ic.Lock, "Set a security key", "This wallet doesn't have one yet", chevron = true, tint = Tv.Warn, iconBg = Tv.Warn.copy(alpha = 0.13f), onClick = { if (idle) { onDismiss(); vm.setSecurityKeyNow() } })
            }
        }
        Spacer(Modifier.height(12.dp))
        Notice(
            Ic.Info, "Kept on this phone",
            "Only a scrambled check value is stored, tied to this phone's secure hardware. The wallet contract doesn't know the key: it's this app that asks for it before your phone signs.",
            Tv.Text2,
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- address book

/** Settings → Address book: saved receivers by name. */
@Composable
fun AddressBookSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<AddressBook.Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val book = vm.addressBook
    GlassSheet(onDismiss) {
        Text("Address book", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("Save accounts under a name, then pick them when you send.", style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp))
        if (adding || editing != null) {
            ContactForm(vm, editing) { adding = false; editing = null }
        } else {
            if (book.entries.isEmpty()) {
                Caption("No saved accounts yet.", Modifier.fillMaxWidth().padding(bottom = 12.dp), Tv.Text3, TextAlign.Center)
            } else {
                Group {
                    book.search("").forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        Row_(Ic.Wallet, e.name, e.account, chevron = true, onClick = { editing = e })
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            TvButton("Add an account", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Plus) { adding = true }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ContactForm(vm: AppViewModel, entry: AddressBook.Entry?, done: () -> Unit) {
    var name by remember { mutableStateOf(entry?.name ?: "") }
    var account by remember { mutableStateOf(entry?.account ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    TvField("Name", name, { name = it.take(32); error = null }, placeholder = "Alice")
    Spacer(Modifier.height(12.dp))
    TvField("NEAR account", account, { account = it.trim().lowercase(); error = null }, placeholder = "alice.testnet", error = error)
    Spacer(Modifier.height(18.dp))
    TvButton(if (entry == null) "Save" else "Save changes", Modifier.fillMaxWidth(), icon = Ic.Check) {
        if (entry != null && entry.account != account.trim().lowercase()) vm.deleteContact(entry.account)
        error = vm.saveContact(name, account)
        if (error == null) done()
    }
    if (entry != null) {
        Spacer(Modifier.height(6.dp))
        TvButton("Delete", Modifier.fillMaxWidth(), Btn.Danger, icon = Ic.Trash) { vm.deleteContact(entry.account); done() }
    }
    TvButton("Back", Modifier.fillMaxWidth(), Btn.Ghost) { done() }
}

/** Send: saved accounts matching what's typed in To, as chips; tap one to use it. */
@Composable
fun ContactChips(vm: AppViewModel, to: String, pick: (String) -> Unit) {
    val book = vm.addressBook
    if (book.entries.isEmpty()) return
    // Once a saved account is filled in, show whose it is instead of the chips.
    book.nameOf(to)?.let { name ->
        Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(Ic.CheckCircle, 15.dp, Tv.Positive)
            Spacer(Modifier.width(6.dp))
            Text("$name · in your address book", style = Type.CaptionStrong, color = Tv.Positive)
        }
        return
    }
    val matches = book.search(to).take(8)
    if (matches.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        matches.forEach { e ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Tv.GlassStrong).glassRim(18.dp, shape = RoundedCornerShape(50))
                    .pressable { pick(e.account) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(e.name, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(e.name, style = Type.CaptionStrong, color = Tv.Text, maxLines = 1)
            }
        }
    }
}
