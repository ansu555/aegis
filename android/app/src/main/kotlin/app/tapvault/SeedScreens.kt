package app.tapvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapvault.core.Mnemonic

// ---------------------------------------------------------------- create

/**
 * "Create seed phrase wallet": what the words are, the words, then a check
 * of three of them. Ends with the choice to protect the wallet with a bank
 * card now or later. The window is FLAG_SECURE while it shows ([SecureWindow]),
 * so the words stay out of screenshots and the recent-apps preview.
 */
@Composable
fun ColumnScope.SeedCreateScreen(vm: AppViewModel) {
    val phrase = vm.seedDraft
    var stage by remember { mutableStateOf(0) }
    val words = phrase?.split(" ").orEmpty()
    // Three positions to check, picked once per phrase.
    val asked = remember(phrase) { words.indices.shuffled().take(3).sorted() }
    val answers = remember(phrase) { mutableStateOf(List(3) { "" }) }
    val back: () -> Unit = { if (stage > 0) stage -= 1 else vm.go(Screen.Welcome) }
    BackHandler { back() }
    SecureWindow()
    PinnedTopBar("Seed phrase wallet", onBack = back)
    if (phrase == null) {
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) { Caption("The recovery phrase isn't loaded. Go back and choose Create wallet again.") }
        return
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        when (stage) {
            0 -> {
                Text("Your recovery phrase", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
                Text("12 words that are this wallet. Write them on paper, in order, and keep them somewhere safe.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(22.dp))
                Group {
                    Row_(Ic.Key, "The words are the wallet", "Anyone who has them can take your NEAR, from any NEAR wallet app")
                    GroupDivider()
                    Row_(Ic.XCircle, "Nobody can restore them", "Lose the words and this phone, and the wallet is gone. Tapvault can't help")
                    GroupDivider()
                    Row_(Ic.Card, "Your card can replace them", "Later, protect the wallet with your bank card: then the words stop working")
                }
                Notice(Ic.Warning, "Never type them anywhere", "No real app or person needs your recovery phrase. Don't photograph it or save it in notes or the cloud.", Tv.Warn, Modifier.padding(top = 16.dp))
            }
            1 -> {
                Text("Write these down", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
                Text("In this order. You'll be asked for three of them next.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                WordGrid(words)
                if (vm.network.isTestnet) Notice(Ic.Info, "Testnet only", "This wallet is on NEAR testnet. Never send real NEAR to it.", Tv.Text2, Modifier.padding(top = 16.dp))
                else Notice(Ic.Warning, "These words are the wallet", "Anyone with them can take its NEAR until you protect it with your bank card. Write them on paper; never in a photo or a chat.", Tv.Warn, Modifier.padding(top = 16.dp))
            }
            else -> {
                Text("Check your words", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
                Text("Type the words from your paper.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                asked.forEachIndexed { i, pos ->
                    val typed = answers.value[i].trim().lowercase()
                    val wrong = typed.isNotEmpty() && typed.length >= words[pos].length && typed != words[pos]
                    TvField(
                        "Word #${pos + 1}", answers.value[i], { v -> answers.value = answers.value.toMutableList().also { it[i] = v.filter { c -> c.isLetter() }.take(12) } },
                        placeholder = "word ${pos + 1}", error = if (wrong) "That's not word #${pos + 1}" else null,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Notice(
                    Ic.Card, "Protect it with your bank card?",
                    "Then every payment needs your phone and a tap of your card, and these words stop working. Needs NFC and about 4.2 test NEAR, which the new wallet gets. You can also do it later from Home.",
                    Tv.Accent, Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    val idle = vm.busy == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        when (stage) {
            0 -> TvButton("Show my words", Modifier.fillMaxWidth(), icon = Ic.Chevron) { stage = 1 }
            1 -> TvButton("I wrote them down", Modifier.fillMaxWidth(), icon = Ic.Check) { stage = 2 }
            else -> {
                val ok = asked.indices.all { answers.value[it].trim().lowercase() == words[asked[it]] }
                TvButton("Protect with my bank card", Modifier.fillMaxWidth(), icon = Ic.Card, enabled = ok && idle && vm.hasNfc) { vm.createSeedWallet(protectNow = true) }
                Spacer(Modifier.height(8.dp))
                TvButton("Create wallet, protect later", Modifier.fillMaxWidth(), Btn.Secondary, enabled = ok && idle) { vm.createSeedWallet(protectNow = false) }
            }
        }
    }
}

/** FLAG_SECURE on the activity's window while this is composed (phrases and keys on screen). */
@Composable
private fun SecureWindow() {
    val window = (LocalContext.current as? android.app.Activity)?.window ?: return
    DisposableEffect(window) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val had = window.attributes.flags and flag != 0
        window.addFlags(flag)
        onDispose { if (!had) window.clearFlags(flag) }
    }
}

/** Numbered words in two columns, read top to bottom on the left, then the right. */
@Composable
private fun WordGrid(words: List<String>) {
    val half = (words.size + 1) / 2
    Row(Modifier.fillMaxWidth().surface(22.dp).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0 until half, half until words.size).forEach { range ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                range.forEach { i ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tv.GlassStrong).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", style = Type.Caption, color = Tv.Text3, modifier = Modifier.width(24.dp))
                        Text(words[i], style = Type.BodyStrong, color = Tv.Text)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- import

/** "I have a wallet" → "Import with recovery phrase": phrase or private key, find the account, add it. */
@Composable
fun ColumnScope.SeedImportScreen(vm: AppViewModel) {
    var input by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<String?>(null) }
    val found = vm.importFound
    val idle = vm.busy == null
    val back: () -> Unit = { if (found != null) vm.importFound = null else vm.go(Screen.Welcome) }
    BackHandler { back() }
    SecureWindow()
    PinnedTopBar("Import wallet", onBack = back)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (found == null) {
            Text("Import a wallet", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
            Text("Enter the 12 or 24 words of a NEAR wallet, or its private key.", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            Text("Recovery phrase or private key", style = Type.CaptionStrong, color = Tv.Text2)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).surface(18.dp).padding(14.dp)) {
                if (input.isEmpty()) Text("word1 word2 word3 …  or  ed25519:…", style = Type.Body, color = Tv.Text3)
                BasicTextField(
                    input, { input = it.take(400) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Recovery phrase or private key" },
                    textStyle = Type.Body.copy(color = Tv.Text, fontWeight = FontWeight.Medium, fontSize = 16.sp),
                    cursorBrush = SolidColor(Tv.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
            val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size > 1) Caption(
                "${words.size} words" + (if (words.size in setOf(12, 24) && Mnemonic.isValid(input)) " · valid phrase" else ""),
                Modifier.padding(top = 8.dp, start = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            TvField("Account name (optional)", account, { account = it.trim().lowercase().take(64) }, placeholder = vm.network.example("name"))
            Caption("Leave it empty and Tapvault looks the account up.", Modifier.padding(top = 6.dp, start = 4.dp), color = Tv.Text3)
            if (vm.network.isTestnet) Notice(
                Ic.Warning, "Testnet only",
                "Don't import a phrase or key that holds real NEAR: the same words control the mainnet account too, and this app keeps them on the phone.",
                Tv.Warn, Modifier.padding(top = 18.dp),
            ) else Notice(
                Ic.Warning, "Real NEAR",
                "Until you protect it with your bank card, the phrase alone controls this wallet, and this app keeps it on the phone (encrypted).",
                Tv.Warn, Modifier.padding(top = 18.dp),
            )
        } else {
            val chosen = pick?.takeIf { it in found.accounts } ?: found.accounts.firstOrNull()
            Text("Wallet found", style = Type.H1, modifier = Modifier.padding(top = 8.dp))
            Text(if (found.phrase != null) "This recovery phrase controls:" else "This private key controls:", style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(18.dp))
            Group {
                found.accounts.forEachIndexed { i, a ->
                    if (i > 0) GroupDivider()
                    Row_(
                        Ic.Wallet, shortAccount(a, 30), "${vm.network.displayName} account",
                        tint = if (a == chosen) Tv.Positive else Tv.Text, iconBg = if (a == chosen) Tv.PositiveSoft else Tv.GlassStrong,
                        trailingIcon = if (a == chosen) Ic.Check else null, onClick = { pick = a },
                    )
                }
            }
            Notice(
                Ic.Card, "Protect it with your bank card?",
                "Every payment then needs your phone and a tap of your card. The phrase or key stops working, and any other app signed in to this account is signed out. Needs about 4.2 free test NEAR.",
                Tv.Accent, Modifier.padding(top = 16.dp),
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (found == null) {
            TvButton("Find my wallet", Modifier.fillMaxWidth(), icon = Ic.Chevron, enabled = idle && input.isNotBlank()) { vm.findImport(input, account) }
        } else {
            val chosen = pick?.takeIf { it in found.accounts } ?: found.accounts.firstOrNull()
            TvButton("Protect with my bank card", Modifier.fillMaxWidth(), icon = Ic.Card, enabled = idle && chosen != null && vm.hasNfc) { vm.importWallet(chosen!!, protectNow = true) }
            Spacer(Modifier.height(8.dp))
            TvButton("Add wallet, protect later", Modifier.fillMaxWidth(), Btn.Secondary, enabled = idle && chosen != null) { vm.importWallet(chosen!!, protectNow = false) }
        }
    }
}

// ---------------------------------------------------------------- home, security, settings

/** Home for a seed-phrase wallet: balance, send and receive, and the invitation to protect it. */
@Composable
fun SeedHome(vm: AppViewModel, ui: HomeUi) {
    val idle = vm.busy == null
    vm.offline?.let { Notice(Ic.Offline, "Offline", it, Tv.Warn, Modifier.padding(top = 14.dp)) }
    CardArt(
        Modifier.fillMaxWidth().padding(top = 24.dp, start = 6.dp, end = 6.dp).sharedCard()
            .pressable(haptic = true) { vm.go(Screen.Security) }.semantics { contentDescription = "Wallet security" },
        showBalance = true, balance = vm.balance,
    )
    Column(Modifier.fillMaxWidth().padding(top = 22.dp).enter(2), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusPill(
            "Recovery phrase only · protect it", Tv.Warn, Ic.Key,
            Modifier.clip(RoundedCornerShape(50)).clickable(enabled = idle) { vm.go(Screen.Security) },
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 28.dp).enter(3), horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally)) {
        ActionButton(Ic.Send, "Send", primary = true, enabled = idle) { vm.go(Screen.Send) }
        ActionButton(Ic.Receive, "Receive") { ui.receive = true }
        ActionButton(Ic.Card, "Protect", tint = Tv.Accent, enabled = idle) { vm.protectWithCard() }
    }
    Spacer(Modifier.height(24.dp))
    Notice(
        Ic.ShieldCheck, "Protect with your bank card",
        "Right now your recovery phrase alone controls this wallet. Add your contactless bank card: every payment then needs your phone and a tap, and the phrase stops working.",
        Tv.Accent, Modifier.enter(4),
    ) {
        TvButton("Protect with bank card", Modifier.fillMaxWidth(), Btn.Secondary, icon = Ic.Card, height = 48.dp, enabled = idle) { vm.protectWithCard() }
    }
    TokensSection(vm, Modifier.enter(5))
}

/** Security for a seed-phrase wallet: what controls it, the phrase, and the way to protect it. */
@Composable
fun SeedSecurity(vm: AppViewModel) {
    val idle = vm.busy == null
    Spacer(Modifier.height(8.dp))
    Notice(
        Ic.Warning, "Not protected yet",
        "Whoever has your recovery phrase (or this unlocked phone) can move everything. Protect the wallet with your bank card to need your phone and the card for every payment.",
        Tv.Warn,
    )
    SectionHeader("What controls this wallet")
    Group {
        Row_(
            Ic.Key, if (vm.store.seedPhrase != null) "Recovery phrase" else "Private key", "Show it after your security key and fingerprint",
            chevron = true, onClick = if (idle) ({ vm.revealPhrase() }) else null,
        )
        GroupDivider()
        Row_(Ic.Phone, "This phone", "Keeps the key, encrypted in the Android Keystore")
    }
    SectionHeader("Protection")
    Group {
        Row_(Ic.Card, "Bank card", "Not added · payments need only the phrase's key", value = "Add", chevron = true, onClick = if (idle) ({ vm.protectWithCard() }) else null)
    }
    Spacer(Modifier.height(14.dp))
    TvButton("Protect with bank card", Modifier.fillMaxWidth(), icon = Ic.Card, enabled = idle && vm.hasNfc) { vm.protectWithCard() }
    if (!vm.hasNfc) Caption("This phone has no NFC, so it can't read a bank card.", Modifier.padding(top = 8.dp, start = 4.dp))
    vm.revealed?.let { (label, secret) -> RevealSheet(label, secret) { vm.revealed = null } }
}

/** The settings section a seed-phrase wallet shows instead of its cards. */
@Composable
fun SeedSettingsSection(vm: AppViewModel) {
    val idle = vm.busy == null
    Column(Modifier.enter(2)) {
        SectionHeader("Recovery")
        Group {
            Row_(
                Ic.Key, if (vm.store.seedPhrase != null) "Recovery phrase" else "Private key", "Show it after your security key and fingerprint",
                chevron = true, onClick = if (idle) ({ vm.revealPhrase() }) else null,
            )
            GroupDivider()
            Row_(Ic.Card, "Protect with bank card", "Replace the phrase with your phone and card", chevron = true, onClick = if (idle) ({ vm.protectWithCard() }) else null)
        }
    }
    vm.revealed?.let { (label, secret) -> RevealSheet(label, secret) { vm.revealed = null } }
}

/** The phrase or key on screen until closed (no copy button: it shouldn't reach the clipboard). */
@Composable
private fun RevealSheet(label: String, secret: String, onDismiss: () -> Unit) {
    GlassSheet(onDismiss, secure = true) {
        Text(label, style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("Make sure nobody can see your screen.", style = Type.Body, color = Tv.Text2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        val words = secret.split(" ")
        if (words.size > 1) WordGrid(words)
        else Text(secret, style = Type.Mono, modifier = Modifier.fillMaxWidth().surface(18.dp).border(1.dp, Tv.Stroke, RoundedCornerShape(18.dp)).padding(16.dp))
        Spacer(Modifier.height(16.dp))
        TvButton("Done", Modifier.fillMaxWidth()) { onDismiss() }
    }
}
