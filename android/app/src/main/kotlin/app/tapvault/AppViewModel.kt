package app.tapvault

import android.app.Application
import android.nfc.Tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tapvault.core.Action
import app.tapvault.core.AlreadySignerException
import app.tapvault.core.Asset
import app.tapvault.core.CapkStore
import app.tapvault.core.CardBook
import app.tapvault.core.CardCandidate
import app.tapvault.core.CardCheckFailed
import app.tapvault.core.CardRecipe
import app.tapvault.core.CardSig
import app.tapvault.core.KnownCard
import app.tapvault.core.SignerInfo
import app.tapvault.core.SignerKey
import app.tapvault.core.WrongCardException
import app.tapvault.core.Integrity
import app.tapvault.core.Receivers
import app.tapvault.core.Watch
import app.tapvault.core.describe
import app.tapvault.core.unhex
import app.tapvault.core.hex
import app.tapvault.core.provesPossession
import app.tapvault.core.sha256
import app.tapvault.core.CardReport
import app.tapvault.core.CardService
import app.tapvault.core.CardTransceiver
import app.tapvault.core.ConfigOp
import app.tapvault.core.Delays
import app.tapvault.core.Domain
import app.tapvault.core.FinalizeArgs
import app.tapvault.core.LimitInit
import app.tapvault.core.Near
import app.tapvault.core.NearClient
import app.tapvault.core.TokenBalance
import app.tapvault.core.TokenInfo
import app.tapvault.core.TokenPrefs
import app.tapvault.core.Tokens
import app.tapvault.core.Network
import app.tapvault.core.NearKey
import app.tapvault.core.NearRpcException
import app.tapvault.core.Onboarding
import app.tapvault.core.ProposalInfo
import app.tapvault.core.PossessionProof
import app.tapvault.core.Recovery
import app.tapvault.core.Guardian
import app.tapvault.core.ActionJson
import app.tapvault.core.TapvaultException
import app.tapvault.core.SecurityKey
import app.tapvault.core.TapvaultWallet
import app.tapvault.core.Mnemonic
import app.tapvault.core.SeedWallet
import app.tapvault.core.WalletState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

enum class Screen { Welcome, Limits, Setup, Home, Send, Activity, CardCheck, Settings, Security, Recover, Guardian, SeedCreate, SeedImport }

/**
 * A card tap the UI is waiting for; [reading] once a card is in the field,
 * [hold] while an identified card counts down before it signs.
 */
data class TapRequest(val title: String, val reading: Boolean = false, val hint: String? = null, val hold: HoldProgress? = null)

/** Hold-to-sign countdown for [card] ("Visa •••• 0093"); [leftMs] of [totalMs] to go, 0 while signing. */
data class HoldProgress(val card: String, val leftMs: Long, val totalMs: Long)

/** The hold timer choices (seconds; 0 = off). */
val HOLD_CHOICES = listOf(0, 3, 5, 10)

/** A card read for "Add backup card" whose signature checked out on the phone, waiting for the user's review. */
class BackupCandidate(
    val key: SignerKey.EmvCard, val recipe: CardRecipe, val network: String, val last4: String,
    /** Contract v0.2: the card's possession proof, sent with the proposal; null for an older wallet. */
    val possession: CardSig? = null,
) {
    val keyHash: String = key.keyHash().hex()
    val name get() = KnownCard(keyHash, recipe, network, last4).name
}

/** A throwaway software key, only to check whether a recovery plan would work before any key is made. */
private object SoftP256Probe {
    val key: SignerKey.P256 by lazy { app.tapvault.core.SoftP256Signer().signerKey() }
}

/**
 * The security key sheet the UI shows while a flow waits for it: [create]
 * asks for a new key twice; otherwise the current key, with [error] after a
 * wrong one. [checking] while the hash is computed.
 */
data class KeyPrompt(val title: String, val detail: String, val create: Boolean, val error: String? = null, val checking: Boolean = false, val canForget: Boolean = true)

/** "Forgot security key?" in the sheet: the flow stops and the card reset starts. */
class ForgotSecurityKey : Exception("Security key forgotten")

/** A finished operation, shown as a result card. */
data class Outcome(val ok: Boolean, val title: String, val detail: String = "", val link: String? = null)

/** One wallet in the switcher. [account] is null until setup has created it. */
data class WalletEntry(val id: String, val name: String, val account: String?, val ready: Boolean, val active: Boolean, val network: Network = Network.TESTNET)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    /** Every wallet on this phone; each has its own accounts, card and phone key. */
    val wallets = WalletIndex(app)

    /** The wallet on screen. Every flow below works on this one only. */
    var activeId by mutableStateOf(wallets.active)
        private set
    var store by mutableStateOf(wallets.store(activeId))
        private set
    var walletList by mutableStateOf<List<WalletEntry>>(emptyList())
        private set
    var walletBalances by mutableStateOf<Map<String, BigInteger>>(emptyMap())
        private set

    /** One client per network; a wallet always uses its own network's ([WalletIndex.networkOf]), whatever the switch says. */
    private val clients = Network.ALL.associateWith { NearClient(it) }

    fun nearFor(n: Network): NearClient = clients.getValue(n)

    /** The active wallet's network (also the network shown: the active wallet is always one of its wallets). */
    var network by mutableStateOf(wallets.networkOf(activeId))
        private set

    /** The active wallet's client. Flows can't outlive a wallet switch (switches wait for [busy]); background work captures its own. */
    val near: NearClient get() = nearFor(network)

    /** Whether this build may show mainnet ([WalletIndex.MAINNET_ENABLED]). */
    val mainnetEnabled get() = WalletIndex.MAINNET_ENABLED
    private var activityRef = WeakReference<MainActivity>(null)
    val phone = KeystoreSigner({ WalletIndex.aliasOf(store, activeId) }) { activityRef.get() }

    /** Whether the active wallet's phone key exists in the Keystore. */
    val phoneKeyExists get() = KeystoreSigner.exists(WalletIndex.aliasOf(store, activeId))
    val walletName get() = wallets.name(activeId)

    var screen by mutableStateOf(startScreen(store))
    var busy by mutableStateOf<String?>(null)
    var outcome by mutableStateOf<Outcome?>(null)
    var tap by mutableStateOf<TapRequest?>(null)

    /** The open welcome menu, "Create wallet" or "I have a wallet" (drawn above the blur source so its glass frosts the screen). */
    var welcomeMenu by mutableStateOf<WelcomeMenu?>(null)

    /** The security key sheet, while a flow waits for the key. */
    var keyPrompt by mutableStateOf<KeyPrompt?>(null)
        private set

    /** Whether the active wallet has a security key yet. */
    var hasSecurityKey by mutableStateOf(store.securityKey != null)
        private set

    /** Saved receivers (shared by the wallets on this phone on the active wallet's network). */
    var addressBook by mutableStateOf(wallets.addressBook(network))
        private set
    var wallet by mutableStateOf<WalletState?>(null)
    var balance by mutableStateOf<BigInteger?>(null)
    var storageLocked by mutableStateOf<BigInteger?>(null)
    var proposals by mutableStateOf<List<ProposalInfo>>(emptyList())
    var cardReport by mutableStateOf<CardReport?>(null)

    /** Why the last refresh failed (e.g. no connection), or null when it worked. */
    var offline by mutableStateOf<String?>(null)
    var setupStep by mutableStateOf(store.setupStep)

    /** The active wallet is a seed-phrase wallet (a plain NEAR account, not protected by a card yet). */
    var seedWallet by mutableStateOf(store.seedWallet)

    /** The active wallet can be used: a finished Tapvault wallet, or a seed-phrase wallet. */
    val ready get() = setupStep >= 4 || (seedWallet && setupStep == 1)
    val log = mutableStateListOf<String>()


    fun attach(a: MainActivity) {
        activityRef = WeakReference(a)
    }

    private val activity get() = activityRef.get()

    val hasNfc get() = activity?.hasNfc == true
    val nfcEnabled get() = activity?.nfcEnabled == true

    private fun capks() = CapkStore.load()

    private fun cards() = CardService(capks())

    /** The active wallet's cards known to this phone (Store.cards, kept here so the UI follows changes). */
    var cardBook by mutableStateOf(store.cards)
        private set

    private fun saveCards(b: CardBook, s: Store = store) {
        s.cards = b
        if (s === store) cardBook = b
    }

    /** Card for display on Home and Your card: the first card of the book that is a signer, else the first known. */
    val mainCard: KnownCard?
        get() = wallet?.cards()?.firstNotNullOfOrNull { cardBook.find(it.keyHash) } ?: cardBook.cards.firstOrNull()

    /** The name of the card signer [s]: from this phone's book, else "Card N" (network and digits aren't on chain). */
    fun cardName(s: SignerInfo): String =
        cardBook.find(s.keyHash)?.name ?: "Card ${(wallet?.cards()?.indexOfFirst { it.keyHash == s.keyHash } ?: 0) + 1}"

    var holdSeconds by mutableStateOf(store.holdSeconds)
        private set

    /** A card read for "Add backup card", awaiting review. */
    var backup by mutableStateOf<BackupCandidate?>(null)

    /** Receivers paid before from this wallet (review warnings). */
    var receivers by mutableStateOf(store.receivers)
        private set

    /** The last integrity check of the active wallet. */
    var integrity by mutableStateOf(Integrity.Report.decode(store.integrity))
        private set

    /** App lock: balances hidden until the user unlocks (an app screen lock, not key security). */
    var locked by mutableStateOf(false)
        private set

    var alertsOn by mutableStateOf(wallets.alertsOn)

    /** Whether the wallet on screen runs contract v0.2 (null until known). */
    var isV02 by mutableStateOf<Boolean?>(null)
        private set

    fun note(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add(0, "$t  $s")
        if (log.size > 200) log.removeAt(log.size - 1)
    }

    /** The tab a pushed screen (Your card) was opened from, so Back returns there. */
    var cameFrom by mutableStateOf(Screen.Home)
        private set

    fun go(s: Screen) {
        outcome = null
        if (screen in setOf(Screen.Home, Screen.Activity, Screen.Settings)) cameFrom = screen
        screen = s
    }

    /** Run [block] on a background thread with a progress label; errors become an [Outcome]. */
    private fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        outcome = null
        busy = label
        viewModelScope.launch {
            var forgot = false
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: ForgotSecurityKey) {
                note("security key forgotten: reset with a card")
                forgot = true
            } catch (e: Throwable) {
                val msg = (e as? java.util.concurrent.ExecutionException)?.cause?.message ?: e.message ?: e.toString()
                note("error: $msg")
                outcome = Outcome(false, "That didn't work", msg)
            } finally {
                busy = null
                tap = null
                keyPrompt = null
                if (forgot) resetSecurityKey()
            }
        }
    }

    private fun progress(s: String) {
        busy = s
        note(s)
    }

    // ---- security key ----
    //
    // Asked by the app before the phone's key signs a payment, an approval or a
    // change of keys (not to freeze or cancel: those only stop things). The
    // contract doesn't know about it; the phone's key only works in this app,
    // so on this phone nothing is signed without it. See core SecurityKey.

    @Volatile private var keyEntry: CompletableFuture<String>? = null

    fun submitKey(key: String) { keyEntry?.complete(key) }

    fun cancelKey() { keyEntry?.completeExceptionally(KeystoreSigner.Cancelled("Cancelled: the security key wasn't entered")) }

    fun forgotKey() { keyEntry?.completeExceptionally(ForgotSecurityKey()) }

    private fun pepper() = SecurityPepper.of(SecurityPepper.aliasFor(activeId))

    /** Show [p] and wait (this background thread) for what the user types. */
    private fun askKey(p: KeyPrompt): String {
        val f = CompletableFuture<String>()
        keyEntry = f
        keyPrompt = p
        try {
            return f.get(5, TimeUnit.MINUTES)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("No security key was entered.")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            keyEntry = null
        }
    }

    /** Seconds until the next try is allowed after wrong keys (0 = now). */
    private fun keyPause(): Long {
        val pause = SecurityKey.pauseAfter(store.keyFailures)
        return (store.keyLastFail + pause - System.currentTimeMillis() / 1000).coerceAtLeast(0)
    }

    /**
     * Ask for the security key and return once it's right. [what] says what
     * it unlocks. A wallet without one (made before this version) sets it now.
     */
    private fun requireSecurityKey(what: String) {
        val rec = SecurityKey.Record.decode(store.securityKey)
        if (rec == null) {
            createSecurityKey("Set a security key", "This wallet doesn't have one yet. From now on you type it for every payment and every change of cards.")
            return
        }
        var error: String? = null
        try {
            while (true) {
                val wait = keyPause()
                if (wait > 0) throw IllegalStateException("Too many wrong security keys. Try again in ${formatWait(wait)}, or reset it with your card: Settings → Security key.")
                val typed = askKey(KeyPrompt("Enter your security key", what, create = false, error = error))
                keyPrompt = keyPrompt?.copy(checking = true, error = null)
                if (SecurityKey.verify(rec, typed, pepper())) {
                    store.keyFailures = 0
                    note("security key accepted")
                    return
                }
                store.keyFailures = store.keyFailures + 1
                store.keyLastFail = System.currentTimeMillis() / 1000
                val left = SecurityKey.FREE_TRIES - store.keyFailures
                note("wrong security key (${store.keyFailures} in a row)")
                error = if (left > 0) "That isn't your security key. $left ${if (left == 1) "try" else "tries"} left before a pause."
                else "That isn't your security key."
            }
        } finally {
            keyPrompt = null
        }
    }

    /** Ask for a new security key (typed twice) and save its hash. */
    private fun createSecurityKey(title: String, detail: String) {
        var error: String? = null
        try {
            while (true) {
                val typed = askKey(KeyPrompt(title, detail, create = true, error = error, canForget = false))
                // The sheet checks both entries match; the strength rules are checked again here.
                val problem = SecurityKey.problem(typed, typed)
                if (problem != null) {
                    error = problem
                    continue
                }
                keyPrompt = keyPrompt?.copy(checking = true, error = null)
                store.securityKey = SecurityKey.create(typed, pepper()).encode()
                store.keyFailures = 0
                hasSecurityKey = true
                note("security key set")
                return
            }
        } finally {
            keyPrompt = null
        }
    }

    private fun formatWait(secs: Long) = if (secs < 60) "$secs s" else "${(secs + 59) / 60} min"

    /** Settings: set a key now (a wallet made before security keys). */
    fun setSecurityKeyNow() = run("Security key") {
        if (store.securityKey != null) throw IllegalStateException("This wallet already has a security key. Change it instead.")
        createSecurityKey("Set a security key", "You'll type it for every payment and every change of cards.")
        outcome = Outcome(true, "Security key set", "You'll type it for every payment and every change of cards.")
    }

    /** Settings: change the key (the current one first). */
    fun changeSecurityKey() = run("Changing security key") {
        if (store.securityKey == null) throw IllegalStateException("This wallet has no security key yet. Set one first.")
        requireSecurityKey("Type your current security key first.")
        createSecurityKey("Choose a new security key", "At least ${SecurityKey.MIN_LENGTH} characters. You'll type it for every payment and every change of cards.")
        outcome = Outcome(true, "Security key changed", "Use the new one from now on.")
    }

    /**
     * Forgot the key: one of the wallet's cards signs a fresh random challenge
     * (checked here against the card key on chain), the phone's key confirms
     * with a fingerprint, then a new key is set. Wrong-try pauses are cleared.
     */
    fun resetSecurityKey() = run("Resetting security key") {
        requireNfc()
        val st = walletClient().state()
        if (st.cards().isEmpty()) throw IllegalStateException("This wallet has no card yet, so a card can't confirm the reset. Add a card in Security first.")
        val challenge = sha256("tapvault/reset-security-key".toByteArray() + ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val t = tapWalletCard("Tap one of this wallet's cards to reset your security key", st, challenge, hold = false)
        if (!provesPossession(t.signer.cardKey()!!, t.sig, challenge)) {
            throw IllegalStateException("The card's signature didn't check out, so the security key wasn't reset. Tap it again.")
        }
        note("${t.name} verified for the security key reset")
        phone.reason = "Reset your Tapvault security key"
        phone.sign(sha256(challenge))
        store.keyFailures = 0
        createSecurityKey("Choose a new security key", "${t.name} and your fingerprint checked out. Pick a new key of at least ${SecurityKey.MIN_LENGTH} characters.")
        outcome = Outcome(true, "Security key reset", "Use the new one from now on.")
    }

    // ---- tokens ----

    /** The active wallet's tokens (built-in and added), with balances; null until first loaded. */
    var tokens by mutableStateOf<List<TokenBalance>?>(null)
        private set

    /** Tokens the index lists in this wallet that it doesn't show (unverified, never on Home by themselves). */
    var foundTokens by mutableStateOf<List<TokenBalance>>(emptyList())
        private set

    var tokenPrefs by mutableStateOf(store.tokenPrefs)
        private set

    /** The tokens Home lists: shown and not hidden ([TokenPrefs.isShown]). */
    val shownTokens: List<TokenBalance>
        get() = tokens.orEmpty().filter { tokenPrefs.isShown(it.token.contract, it.token.verified, it.raw) }

    private val tokenMeta = java.util.concurrent.ConcurrentHashMap<String, TokenInfo>()
    private var tokensAt = 0L
    private val loadingTokens = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Reload token balances: at most once a minute unless [force] (pull to refresh, a change in Manage tokens). */
    fun refreshTokens(force: Boolean = false) {
        val id = activeId
        val st = store
        val nc = near
        val acct = st.walletAccount ?: return
        if (!st.usable()) return
        if (!force && System.currentTimeMillis() - tokensAt < 60_000) return
        if (!loadingTokens.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                // An unfunded implicit account (new mainnet seed wallet) holds nothing yet.
                val snap = withContext(Dispatchers.IO) {
                    if (Network.isImplicit(acct) && !nc.accountExists(acct)) Tokens.Snapshot(emptyList(), emptyList())
                    else Tokens(nc).load(acct, st.tokenPrefs, tokenMeta)
                }
                if (activeId == id) { tokens = snap.tokens; foundTokens = snap.found; tokensAt = System.currentTimeMillis() }
            } catch (e: Exception) {
                note("tokens: ${e.message}")
            } finally {
                loadingTokens.set(false)
            }
        }
    }

    private fun saveTokenPrefs(p: TokenPrefs) {
        store.tokenPrefs = p
        tokenPrefs = p
    }

    fun showToken(contract: String) { saveTokenPrefs(tokenPrefs.show(contract)); refreshTokens(force = true) }
    fun hideToken(contract: String) = saveTokenPrefs(tokenPrefs.hide(contract))
    fun removeToken(contract: String) { saveTokenPrefs(tokenPrefs.remove(contract)); refreshTokens(force = true) }

    /** Manage tokens → Add by contract: look the token up on chain (shown as unverified unless built in). */
    suspend fun lookUpToken(contract: String): TokenInfo = withContext(Dispatchers.IO) {
        val c = contract.trim().lowercase()
        Tokens(near).metadata(c).also { tokenMeta[c] = it }
    }

    /** Whether the active wallet can receive [contract] (null: the token doesn't say). */
    suspend fun tokenRegistered(contract: String): Boolean? = withContext(Dispatchers.IO) {
        Tokens(near).isRegistered(contract, store.walletAccount ?: return@withContext null)
    }

    /**
     * Register the wallet on a token so it can receive it. The fee account
     * pays (about 0.00125 NEAR); a seed phrase wallet without one pays from
     * itself. No card or wallet approval: nothing of the wallet's moves.
     */
    fun enableToken(t: TokenInfo, onDone: () -> Unit = {}) = run("Enabling ${t.symbol}") {
        val acct = store.walletAccount ?: throw IllegalStateException("no wallet yet")
        val (payer, key) = when {
            store.gasAccount != null && store.gasKey != null -> store.gasAccount!! to NearKey.fromSecret(store.gasKey!!)
            store.seedWallet && store.walletKey != null -> acct to NearKey.fromSecret(store.walletKey!!)
            else -> throw IllegalStateException("This wallet has no fee account on this phone to pay for it.")
        }
        val tk = Tokens(near)
        if (tk.isRegistered(t.contract, acct) == true) {
            outcome = Outcome(true, "${t.symbol} is ready", "This wallet can already receive ${t.symbol}.")
        } else {
            val r = tk.register(t.contract, acct, payer, key)
            note("registered on ${t.contract}: ${near.explorerTx(r.hash)}")
            outcome = Outcome(true, "${t.symbol} enabled", "This wallet can now receive ${t.symbol}.", near.explorerTx(r.hash))
        }
        withContext(Dispatchers.Main) { if (t.contract !in tokenPrefs.added) showToken(t.contract); onDone() }
    }

    // ---- address book ----

    /** Save [account] as [name]; returns a readable problem, or null when saved. */
    fun saveContact(name: String, account: String): String? = try {
        val b = addressBook.put(name, account)
        wallets.setAddressBook(network, b)
        addressBook = b
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    fun deleteContact(account: String) {
        val b = addressBook.remove(account)
        wallets.setAddressBook(network, b)
        addressBook = b
    }

    // ---- card taps ----

    /** The tap being waited for; the activity's reader hands every card here. */
    @Volatile private var pendingTag: CompletableFuture<Tag>? = null

    /** A card that arrived while nothing was waiting (put down early, or left on the phone). */
    @Volatile private var earlyTag: Pair<Tag, Long>? = null

    fun onTag(tag: Tag) {
        val p = pendingTag
        if (p != null && !p.isDone) p.complete(tag) else earlyTag = tag to System.currentTimeMillis()
    }

    private fun awaitTag(): CompletableFuture<Tag> {
        val f = CompletableFuture<Tag>()
        val early = earlyTag
        earlyTag = null
        // A card already lying on the phone is used straight away; if it has gone, connecting fails and we wait for a tap.
        if (early != null && System.currentTimeMillis() - early.second < 30_000) f.complete(early.first) else pendingTag = f
        return f
    }

    /**
     * Wait for a real card tap over NFC and run [block] against it while it is
     * held. A card pulled away too early is retried with a fresh tap.
     */
    private fun <T> withCard(title: String, holding: Boolean = false, block: (CardTransceiver) -> T): T {
        if (store.legacyDemoWallet) {
            throw IllegalStateException("This wallet was set up with the old simulated demo card. Forget it in Settings and set up again with your bank card.")
        }
        var hint: String? = null
        var wrongCard = false
        tapCancelled = false
        // With the hold timer a lifted card restarts the countdown, so allow a few more tries.
        repeat(if (holding) 6 else 3) { attempt ->
            val act = activity ?: throw IllegalStateException("app is not in the foreground")
            if (!act.hasNfc) throw IllegalStateException("This phone has no NFC, so it can't read a bank card.")
            if (!act.nfcEnabled) throw IllegalStateException("Turn on NFC in Android settings (Connected devices → NFC), then try again.")
            tap = TapRequest(title, hint = hint)
            try {
                val tag = awaitTag().get(2, TimeUnit.MINUTES)
                tap = TapRequest(title, reading = true)
                return IsoDepTransceiver(tag).use { block(it) }.also { activity?.vibrate(if (holding) 250 else 90) }
            } catch (e: java.util.concurrent.TimeoutException) {
                throw IllegalStateException("No card was tapped. Try again and hold the card flat against the back of the phone.")
            } catch (e: java.util.concurrent.CancellationException) {
                throw IllegalStateException("Card tap cancelled")
            } catch (e: android.nfc.TagLostException) {
                note("card moved away (attempt ${attempt + 1}); hold it still")
                hint = if (holding) "Card moved, hold it again. The timer starts over." else "The card moved away too soon. Tap again and keep it still until this screen closes."
            } catch (e: java.io.IOException) {
                note("card link error: ${e.message}; tap again")
                hint = "The card connection dropped. Tap again and keep it still until this screen closes."
            } catch (e: SecurityException) {
                // The tag object went stale (the reader was re-armed in between).
                note("stale card connection: ${e.message}; tap again")
                hint = "Tap the card again and keep it still until this screen closes."
            } catch (e: WrongCardException) {
                note("a card that isn't one of this wallet's was tapped; it signed nothing")
                hint = "That card isn't one of this wallet's cards. Tap a card you added."
                wrongCard = true
            } catch (e: CardNotUsable) {
                note("card not usable here: ${e.message}")
                hint = e.message
            } finally {
                pendingTag = null
                tap = null
            }
        }
        if (wrongCard) throw IllegalStateException("That card isn't one of this wallet's cards. Use a card you added to this wallet.")
        throw IllegalStateException("Couldn't read the card. Hold it flat against the back of the phone, near the camera, for 3 seconds.")
    }

    @Volatile private var tapCancelled = false

    fun cancelTap() {
        tapCancelled = true
        pendingTag?.cancel(true)
    }

    /** A card of the wallet that can't be used for this tap (e.g. it already approved); the user may tap another. */
    private class CardNotUsable(message: String) : Exception(message)

    /** A wallet card's signature, with the signer it belongs to. */
    private class Tapped(val signer: SignerInfo, val name: String, val sig: CardSig) {
        val index get() = signer.index
    }

    /**
     * One tap of any of the wallet's cards to sign [challenge]: read the card
     * and find which signer it is by key hash (a card that isn't the wallet's
     * is refused before it signs anything), refuse it if [unusable] says so,
     * then, if [hold], wait the hold timer with a presence check about every
     * 500 ms (the timer restarts if the card is lifted), and only then sign.
     * The signer index comes from the tapped card, never a fixed index.
     */
    private fun tapWalletCard(
        title: String,
        st: WalletState,
        challenge: ByteArray,
        hold: Boolean = true,
        unusable: (SignerInfo) -> String? = { null },
    ): Tapped {
        val book = store.cards
        val signers = st.cards()
        if (signers.isEmpty()) throw IllegalStateException("This wallet has no card signer.")
        val candidates = signers.map { CardCandidate(it.keyHash, it.cardKey()!!, book.find(it.keyHash)?.recipe) }
        val svc = cards()
        val secs = if (hold) store.holdSeconds else 0
        return withCard(title, holding = secs > 0) { link ->
            val held = svc.identify(link, candidates)
            val signer = signers.first { it.keyHash.equals(held.card.keyHash, ignoreCase = true) }
            val name = cardName(signer)
            unusable(signer)?.let { throw CardNotUsable(it) }
            note("$name identified (signer ${signer.index})")
            if (secs > 0) holdCard(title, name, secs * 1000L) { held.stillThere() }
            tap = TapRequest(title, reading = true, hold = HoldProgress(name, 0, secs * 1000L).takeIf { secs > 0 })
            Tapped(signer, name, held.sign(challenge))
        }
    }

    /** The hold countdown: [present] about every 500 ms, a haptic tick each second; throws if the card goes or the user cancels. */
    private fun holdCard(title: String, name: String, totalMs: Long, present: () -> Unit) {
        val start = System.currentTimeMillis()
        var lastSecond = (totalMs + 999) / 1000
        while (true) {
            if (tapCancelled) throw java.util.concurrent.CancellationException()
            val left = totalMs - (System.currentTimeMillis() - start)
            if (left <= 0) return
            tap = TapRequest(title, reading = true, hold = HoldProgress(name, left, totalMs))
            val second = (left + 999) / 1000
            if (second != lastSecond) { activity?.vibrate(20); lastSecond = second }
            Thread.sleep(minOf(500L, left))
            present()
        }
    }

    /** Change the hold timer; needs the phone's fingerprint or screen lock (an app-side setting). */
    fun setHoldTimer(seconds: Int) = run("Confirm on your phone") {
        require(seconds in HOLD_CHOICES)
        phone.reason = "Set the card hold timer to ${if (seconds == 0) "off" else "$seconds s"}"
        // A signature over an app-only message: just the user-verification gate, never sent anywhere.
        phone.sign(sha256("tapvault/app/v1/hold-timer".toByteArray(), byteArrayOf(seconds.toByte())))
        store.holdSeconds = seconds
        withContext(Dispatchers.Main) { holdSeconds = seconds }
        note("hold timer set to $seconds s")
    }

    // ---- wallet ----

    fun walletClient(s: Store = store, nc: NearClient = near): TapvaultWallet {
        val acct = s.walletAccount ?: throw IllegalStateException("no wallet yet")
        return TapvaultWallet(nc, acct, s.gasAccount!!, NearKey.fromSecret(s.gasKey!!))
    }

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while a pull-to-refresh the user started is running. */
    var pulling by mutableStateOf(false)
        private set

    /** Pull-to-refresh: reload now and show the indicator until it's done. */
    fun pullRefresh() {
        if (!store.usable() || pulling) return
        pulling = true
        viewModelScope.launch {
            refreshTokens(force = true)
            refresh()
            while (refreshing.get()) delay(100)
            delay(250)
            pulling = false
        }
    }
    private var autoRefresh: kotlinx.coroutines.Job? = null

    /** Reload wallet state; skipped while another refresh is still running. */
    fun refresh() {
        val id = activeId
        val st = store
        val nc = near
        if (!st.usable()) return
        refreshTokens()
        if (!refreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (st.setupStep < 4) withContext(Dispatchers.IO) {
                    // A seed-phrase wallet: a plain account, so just its balance. A mainnet one is an
                    // implicit account that only exists once NEAR has been sent to it: until then, 0.
                    val acct = st.walletAccount!!
                    val (b, locked) = if (Network.isImplicit(acct) && !nc.accountExists(acct)) BigInteger.ZERO to BigInteger.ZERO else nc.spendable(acct)
                    withContext(Dispatchers.Main) {
                        if (activeId == id) { wallet = null; balance = b; storageLocked = locked; proposals = emptyList(); offline = null }
                    }
                } else withContext(Dispatchers.IO) {
                    val w = walletClient(st, nc)
                    val s = w.state()
                    val (b, locked) = nc.spendable(w.account)
                    val ps = w.proposals()
                    // A card stored by an older build has no key hash yet: it's the card setup added.
                    val bound = st.cards.bind(s)?.also { st.cards = it; note("card list migrated") }
                    adoptPendingKey(s, ps, st, id)
                    val v = if (activeId == id && isV02 != null) isV02 else runCatching { w.isV02() }.getOrNull()
                    // Integrity check at most every 6 h; then remember what the user has now seen (no alert for it).
                    val report = Integrity.Report.decode(st.integrity)
                        .let { r -> if (r == null || System.currentTimeMillis() / 1000 - r.atSecs > 6 * 3600) runCatching { integrityNow(w, s, id, st) }.getOrNull() ?: r else r }
                    st.watchMemory = Watch.memoryOf(s, ps, report?.ok ?: true).encode()
                    // The user may have switched wallets meanwhile: never show one wallet's state under another.
                    withContext(Dispatchers.Main) {
                        if (activeId == id) { wallet = s; balance = b; storageLocked = locked; proposals = ps; offline = null; bound?.let { cardBook = it }; integrity = report; isV02 = v }
                    }
                }
            } catch (e: Exception) {
                note("refresh failed: ${e.message}")
                if (activeId == id) offline = "Can't reach ${nc.network.displayName} right now. Showing the last known state; retrying."
            } finally {
                refreshing.set(false)
                // A switch while this ran skipped the new wallet's refresh; do it now.
                if (activeId != id) refresh()
            }
        }
    }

    /** One polling loop for the app's lifetime; it only polls while the app is on screen. */
    fun startAutoRefresh() {
        if (autoRefresh?.isActive == true) return
        autoRefresh = viewModelScope.launch {
            while (isActive) {
                val visible = activity?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true
                if (visible && (screen == Screen.Home || screen == Screen.Activity)) refresh()
                delay(8_000)
            }
        }
    }

    // ---- setup ----

    /** The testnet sponsor in this build; never on mainnet ([Onboarding] refuses one there too). */
    private fun sponsor(): Onboarding.Sponsor? =
        BuildConfig.SPONSOR_ACCOUNT.takeIf { network.allowsSponsor && it.isNotBlank() && BuildConfig.SPONSOR_KEY.isNotBlank() }
            ?.let { acct ->
                runCatching { Onboarding.Sponsor(acct, NearKey.fromSecret(BuildConfig.SPONSOR_KEY)) }
                    .onFailure { note("sponsor key in this build is unreadable; using the faucet only") }
                    .getOrNull()
            }

    /** Whether this build carries a readable sponsor account (no side effects: used by the UI). */
    val hasSponsor get() = network.allowsSponsor && BuildConfig.SPONSOR_ACCOUNT.isNotBlank() && runCatching { NearKey.fromSecret(BuildConfig.SPONSOR_KEY) }.isSuccess

    /** Throws a readable error unless this phone can read a card right now. */
    private fun requireNfc() {
        val act = activity ?: throw IllegalStateException("app is not in the foreground")
        if (!act.hasNfc) throw IllegalStateException("This phone has no NFC, so it can't read a bank card.")
        if (!act.nfcEnabled) throw IllegalStateException("Turn on NFC in Android settings (Connected devices → NFC), then try again.")
    }

    /** "Create wallet" → with my card / without a card for now (phone-only, contract v0.3). */
    fun chooseSetup(withCard: Boolean) {
        store.withCard = withCard
        if (!withCard) { store.wantBackup = false; store.pendingCard2 = null }
        go(Screen.Limits)
    }

    fun beginSetup(limitsOn: Boolean, instant: String, daily: String) {
        val i = runCatching { Near.parse(instant.trim().replace(',', '.')) }.getOrNull()
        val d = runCatching { Near.parse(daily.trim().replace(',', '.')) }.getOrNull()
        val problem = when {
            !limitsOn -> null
            i == null || d == null || i.signum() <= 0 || d.signum() <= 0 -> "Enter both limits as amounts like 1 or 0.5."
            i > d -> "The per-day limit must be at least the at-once limit."
            else -> null
        }
        val nfcProblem = if (!store.withCard) null else when {
            !hasNfc -> "This phone has no NFC, so it can't read a bank card."
            !nfcEnabled -> "Turn on NFC in Android settings (Connected devices → NFC) first."
            else -> null
        }
        (problem ?: nfcProblem)?.let {
            outcome = Outcome(false, "Can't start yet", it)
            return
        }
        store.limitsOn = limitsOn
        if (limitsOn) {
            store.instantNear = instant.trim().replace(',', '.')
            store.dailyNear = daily.trim().replace(',', '.')
        }
        cardReport = null
        go(Screen.Setup)
        continueSetup()
    }

    /** The security key and the phone key, made once per wallet before anything goes on chain. */
    private fun ensureLocalKeys() {
        if (store.securityKey == null) {
            progress("Set your security key")
            createSecurityKey("Set your security key", "You'll type it for every payment and to change your card. At least ${SecurityKey.MIN_LENGTH} characters; it never leaves this phone.")
        }
        if (!phoneKeyExists) {
            progress("Creating your phone key in secure hardware")
            try {
                KeystoreSigner.create(getApplication(), WalletIndex.aliasOf(store, activeId), strict = store.strictKey)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Set up a screen lock (PIN, pattern or password) on this phone first, then continue. (${e.message})")
            }
            note("phone key stored in ${phone.hardwareLabel}")
        }
    }

    private fun shippedWasm(): ByteArray = getApplication<Application>().assets.open("tapvault_contract.wasm").use { it.readBytes() }

    fun continueSetup() = run("Setting up") {
        // Without a card (phone-only wallet), setup never reads one, so it needs no NFC.
        val withCard = store.withCard
        if (withCard && PendingCard.decode(store.pendingCard) == null && store.setupStep < 3) requireNfc()
        val ob = Onboarding(near)
        ensureLocalKeys()
        // Protecting a seed-phrase wallet: check it's safe before a card is read or anything is sent.
        val seed = store.seedWallet
        // A mainnet seed wallet is an implicit account that exists only once NEAR has arrived.
        if (seed && store.setupStep < 2 && !near.accountExists(store.walletAccount!!)) throw IllegalStateException(
            "Add NEAR to this wallet first (Receive). Protecting it needs about ${Near.format(SeedWallet.storageFor(shippedWasm().size) + Onboarding.Sponsor.GAS_FUNDING, 1)} NEAR " +
                "for the contract's storage and the fee account.",
        )
        if (seed && store.setupStep < 2 && near.codeHash(store.walletAccount!!) == NearClient.NO_CODE) {
            progress("Checking your wallet")
            val sw = SeedWallet(near, store.walletAccount!!, NearKey.fromSecret(store.walletKey!!))
            sw.preflight(shippedWasm().size, needsFeeAccount = store.gasAccount == null)
            // The same implicit account protected on both networks would share card signatures.
            sw.checkNotOnOtherNetwork(nearFor(network.other))
        }
        // A wallet imported with its key has no fee account yet: fund one from the wallet itself.
        if (seed && store.gasAccount == null) {
            progress("Creating the fee account")
            val gk = store.gasKey?.let { NearKey.fromSecret(it) } ?: NearKey.random().also { store.gasKey = it.secretString() }
            store.gasAccount = SeedWallet(near, store.walletAccount!!, NearKey.fromSecret(store.walletKey!!)).fundFeeAccount(gk)
            note("fee account ${store.gasAccount}")
        }
        if (store.setupStep < 1) {
            progress("Creating your wallet account on ${network.displayName}")
            // Keys are saved before any account exists, so a retry reuses what was already created.
            val plan = Onboarding.Plan.decode(store.setupPlan) ?: Onboarding.Plan.random().also { store.setupPlan = it.encode() }
            val a = ob.createAccounts(sponsor = sponsor(), plan = plan) { note(it) }
            store.walletAccount = a.wallet; store.walletKey = a.walletKey.secretString()
            store.gasAccount = a.gas; store.gasKey = a.gasKey.secretString()
            setStep(1)
            store.setupPlan = null
            withContext(Dispatchers.Main) { reloadWalletList() }
            note("wallet ${a.wallet}, fee account ${a.gas}")
        }
        val accounts = Onboarding.Accounts(
            store.walletAccount!!, NearKey.fromSecret(store.walletKey!!), store.gasAccount!!, NearKey.fromSecret(store.gasKey!!),
        )
        // Each step checks the chain first, so a retry after a crash or a lost
        // connection continues instead of repeating a step that already landed.
        val onChain = try {
            TapvaultWallet(near, accounts.wallet, accounts.gas, accounts.gasKey).state()
        } catch (e: NearRpcException) {
            // No contract yet is expected; anything else (no connection) must not re-send a step that landed.
            if (e.message?.contains("CodeDoesNotExist") == true || e.message?.contains("MethodNotFound") == true ||
                e.message?.contains("UNKNOWN_ACCOUNT") == true || e.message?.contains("does not exist") == true
            ) null else throw IllegalStateException("Can't reach ${network.displayName} right now (${e.message?.take(120)}). Try Continue setup again.")
        }

        // Read the card before deploying anything: a card that can't sign
        // stops setup while undoing it is still cheap (Start over).
        var pending = PendingCard.decode(store.pendingCard)
        if (withCard && pending == null && store.setupStep < 3 && onChain?.card() == null) {
            progress("Tap your card")
            val svc = cards()
            val enrol = try {
                withCard("Hold your bank card to the back of the phone to add it") { t ->
                    svc.enrol(t) { k -> Domain.cardEnrollChallenge(accounts.wallet, 1, k.keyHash()) }
                }
            } catch (e: CardCheckFailed) {
                cardReport = e.report
                throw IllegalStateException("This card can't sign for Tapvault: ${e.message}. Tap Continue setup to try another contactless card, or Start over.")
            }
            cardReport = enrol.report
            val known = KnownCard(enrol.key.keyHash().hex(), enrol.recipe, enrol.report.network, enrol.report.panLast4)
            withContext(Dispatchers.Main) { saveCards(store.cards.put(known)) }
            pending = PendingCard(enrol.key, enrol.proof, enrol.report.network, enrol.report.panLast4 ?: "????")
            store.pendingCard = pending.encode()
            note("card ${enrol.report.network} ****${enrol.report.panLast4} read (${enrol.report.iccKeyBits}-bit key, ${enrol.recipe.method.label})")
        }
        if (store.setupStep < 2) {
            if (onChain != null) {
                note("wallet contract already deployed")
            } else {
                val wasm = shippedWasm()
                // Any other key on the account (another wallet app the phrase was used in) goes with the deploy.
                val others = near.accessKeys(accounts.wallet).filter { it != accounts.walletKey.publicKeyString }
                if (others.isNotEmpty()) note("removing ${others.size} other key(s) from the account")
                progress(if (seed) "Approve with your fingerprint to protect the wallet" else "Approve with your fingerprint to create the wallet")
                phone.reason = if (seed) "Protect ${accounts.wallet}: from now on your recovery phrase can't move funds" else "Create your Tapvault wallet"
                val r = ob.deployAndInit(accounts, wasm, phone, others)
                note("wallet contract deployed: ${near.explorerTx(r.hash)}")
            }
            setStep(2)
        }
        if (store.setupStep < 3 && !withCard) {
            note("no card: phone-only wallet, the card can be added later from Security")
            setStep(3)
        }
        if (store.setupStep < 3) {
            if (onChain?.card() != null) {
                note("card already added")
            } else {
                val c = pending ?: throw IllegalStateException("The card details from this setup are missing. Start over to set up again.")
                val cardName = c.network.let { if (it.endsWith("card")) it else "$it card" }
                progress("Approve adding card ****${c.last4}")
                phone.reason = "Add $cardName ****${c.last4} as a signer"
                ob.addCard(accounts, phone, 1, c.key, c.proof)
                note("card ****${c.last4} added as a signer")
            }
            setStep(3)
        }
        // Optional backup card (plan phase 3): a second setup_add_signer, no delay, before the wallet is locked.
        if (store.setupStep < 4 && withCard && store.wantBackup && onChain?.setupMode != false) {
            val now = TapvaultWallet(near, accounts.wallet, accounts.gas, accounts.gasKey).state()
            if (now.cards().size < 2) {
                var p2 = PendingCard.decode(store.pendingCard2)
                if (p2 == null) {
                    progress("Tap your backup card")
                    val existing = now.signers.map { it.keyHash.lowercase() }.toSet()
                    val count = now.signers.size
                    val svc = cards()
                    val enrol = try {
                        withCard("Hold your backup card (a different card) to the back of the phone") { t ->
                            svc.enrol(t) { k ->
                                if (k.keyHash().hex() in existing) throw AlreadySignerException("already a signer")
                                Domain.cardEnrollChallenge(accounts.wallet, count, k.keyHash())
                            }
                        }
                    } catch (e: AlreadySignerException) {
                        throw IllegalStateException("That's the card you already added. Tap Continue setup and hold a different card, or skip the backup card.")
                    } catch (e: CardCheckFailed) {
                        cardReport = e.report
                        throw IllegalStateException("This card can't sign for Tapvault: ${e.message}. Tap Continue setup to try another card, or skip the backup card.")
                    }
                    val known = KnownCard(enrol.key.keyHash().hex(), enrol.recipe, enrol.report.network, enrol.report.panLast4)
                    withContext(Dispatchers.Main) { saveCards(store.cards.put(known)) }
                    p2 = PendingCard(enrol.key, enrol.proof, enrol.report.network, enrol.report.panLast4 ?: "????")
                    store.pendingCard2 = p2.encode()
                    note("backup card ${enrol.report.network} ****${enrol.report.panLast4} read")
                }
                progress("Approve adding backup card ****${p2.last4}")
                phone.reason = "Add backup card ****${p2.last4} as a signer"
                ob.addCard(accounts, phone, now.signers.size, p2.key, p2.proof)
                note("backup card ****${p2.last4} added as a signer")
            } else note("backup card already added")
        }
        if (store.setupStep < 4) {
            if (onChain?.setupMode == false) {
                note("wallet already locked")
            } else {
                progress("Approve your limits to lock the wallet")
                // Contract v0.3: a wallet with no card on chain (set up without one) is phone-only, threshold 1,
                // until a card is added. Read from the chain, so a card that already landed always gets 2.
                val cardOnChain = TapvaultWallet(near, accounts.wallet, accounts.gas, accounts.gasKey).state().cards().isNotEmpty()
                val args = FinalizeArgs(
                    threshold = if (cardOnChain) 2 else 1,
                    delays = Delays.SHORTEST,
                    // No limit: the contract treats every transfer as over the limit, so each one waits the transfer delay.
                    limits = if (store.limitsOn) listOf(LimitInit(Asset.Near, Near.parse(store.instantNear), Near.parse(store.dailyNear))) else emptyList(),
                )
                phone.reason = (if (store.limitsOn) "Lock the wallet: up to ${store.instantNear} NEAR at once, ${store.dailyNear} NEAR a day"
                else "Lock the wallet: no instant payments, every payment waits ${Delays.SHORTEST.transfer / 3600} hour") +
                    (if (cardOnChain) "" else "; this phone alone approves until you add a card")
                ob.finalize(accounts, phone, args, near.accessKeys(accounts.wallet).filter { it != accounts.walletKey.publicKeyString })
            }
            val keys = near.accessKeys(accounts.wallet)
            note(if (keys.isEmpty()) "wallet account locked: no keys left" else "WARNING: wallet still has keys: $keys")
            setStep(4)
            store.walletKey = null
            store.pendingCard = null
            store.pendingCard2 = null
            // A protected seed-phrase wallet: the key is gone from the chain, so the phrase is useless now.
            store.seedPhrase = null
            store.seedWallet = false
        }
        withContext(Dispatchers.Main) { seedWallet = false; screen = Screen.Home; reloadWalletList() }
        refresh()
        outcome = if (seed) Outcome(true, "Your wallet is protected", (if (withCard) "Funds now move only with your phone and your card." else "Funds now move only with your phone; add your card in Security.") + " Your recovery phrase no longer works.", near.explorerAccount(accounts.wallet))
        else if (withCard) Outcome(true, "Your wallet is ready", "Funds now move only with your phone and your card.", near.explorerAccount(accounts.wallet))
        else Outcome(true, "Your wallet is ready", "For now your phone key alone approves payments. Add your card in Security so every payment needs both.", near.explorerAccount(accounts.wallet))
    }

    /**
     * Abandon an unfinished setup. With a sponsor configured, accounts that
     * have nothing deployed yet are deleted and their NEAR goes back to it.
     */
    fun startOver() = run("Starting over") {
        // A seed-phrase wallet being protected: never delete the user's account. Before the contract is
        // deployed, stop and go back to the wallet; after it, only finishing is safe (the key is still there).
        if (store.seedWallet) {
            if (store.setupStep > 1) throw IllegalStateException("The wallet contract is already on your account, so protection has to be finished: tap Continue setup. Until then your recovery phrase still works.")
            store.pendingCard = null; store.pendingCard2 = null; store.wantBackup = false
            withContext(Dispatchers.Main) { cardReport = null; screen = Screen.Home }
            refresh()
            return@run
        }
        val sp = sponsor()
        val done = mutableListOf<String>()
        if (sp != null) {
            val wallet = store.walletAccount
            val wk = store.walletKey
            if (store.setupStep <= 1 && wallet != null && wk != null) {
                runCatching { near.deleteAccount(wallet, NearKey.fromSecret(wk), sp.account) }
                    .onSuccess { done += "deleted $wallet" }
                    .onFailure { done += "couldn't delete $wallet (${it.message?.take(80)})" }
            }
            val gas = store.gasAccount
            val gk = store.gasKey
            if (gas != null && gk != null) {
                runCatching { near.deleteAccount(gas, NearKey.fromSecret(gk), sp.account) }
                    .onSuccess { done += "deleted $gas" }
                    .onFailure { done += "couldn't delete $gas (${it.message?.take(80)})" }
            }
        }
        withContext(Dispatchers.Main) {
            resetCurrent()
            if (done.isNotEmpty()) outcome = Outcome(true, "Started over", done.joinToString("; ") + ". Their NEAR went back to ${sp?.account}.")
        }
    }

    /** This phone's signer index in the wallet, or a readable error. */
    private fun phoneIndex(st: WalletState): Int =
        st.phoneIndexOf(phone.publicKey) ?: throw IllegalStateException("This phone's key isn't a signer of this wallet. Forget the wallet in Settings and set up again.")

    private fun setStep(n: Int) {
        store.setupStep = n
        setupStep = n
    }

    // ---- send ----

    fun send(receiver: String, amountNear: String) = run("Sending") {
        val amount = try { Near.parse(amountNear.trim().replace(',', '.')) } catch (e: Exception) { throw IllegalArgumentException("Enter an amount like 0.5") }
        require(amount.signum() > 0) { "Amount must be more than zero" }
        val to = receiver.trim().lowercase()
        require(to.matches(Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")) && to.length in 2..64) { "That isn't a NEAR account id" }

        if (store.seedWallet && store.setupStep == 1) return@run sendFromSeed(to, amount)
        val w = walletClient()
        val st = w.state()
        if (st.frozen) throw IllegalStateException("The wallet is frozen. Request an unfreeze first.")
        val phoneIdx = st.phoneIndexOf(phone.publicKey) ?: throw IllegalStateException("This phone isn't a signer of the wallet")
        if (st.phoneOnly) return@run sendPhoneOnly(w, st, phoneIdx, to, amount)
        requireNfc()
        if (st.cards().isEmpty()) throw IllegalStateException("No card enrolled")
        require(to != w.account) { "That's this wallet's own account" }
        val (available, _) = near.spendable(w.account)
        require(amount <= available) { "The wallet only has ${Near.format(available, 4)} NEAR available to send" }
        // 64-hex implicit accounts come into existence when they receive NEAR.
        require(Network.isImplicit(to) || near.accountExists(to)) { "$to doesn't exist on ${network.displayName}" }

        // An unfinished identical payment (phone approved, card tap failed) is
        // finished instead of creating a second one.
        val now = System.currentTimeMillis() / 1000
        val existing = w.proposals().firstOrNull { p ->
            p.isNearTransfer && p.transferAmount() == amount && p.transferReceiver() == to && p.approvedBy(phoneIdx) &&
                st.cards().none { p.approvedBy(it.index) } && !p.stale && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now + 30
        }
        val id = if (existing != null) {
            note("finishing unfinished payment #${existing.id}")
            existing.id
        } else {
            progress("Approve on your phone")
            phone.reason = "Send ${Near.format(amount)} NEAR to $to"
            w.proposeByPhone(Action.Transfer(Asset.Near, amount, to), phone, phoneIdx).also { note("proposal #$it created") }
        }
        progress("Waiting for the card challenge")
        val ch = w.awaitCardChallenge(id)
        val t = try {
            tapWalletCard("Tap your card to approve sending ${Near.format(amount)} NEAR", st, ch)
        } catch (e: Exception) {
            refresh()
            throw IllegalStateException("${e.message}\n\nPayment #$id is saved and waits 10 minutes for your card: send the same amount again, or approve or cancel it in Activity.")
        }
        try {
            requireSecurityKey("To send ${Near.format(amount)} NEAR to $to.")
        } catch (e: ForgotSecurityKey) {
            throw e
        } catch (e: Exception) {
            refresh()
            throw IllegalStateException("${e.message}\n\nNothing was sent. Payment #$id is saved and waits 10 minutes for your card: send the same amount again, or cancel it in Activity.")
        }
        progress("Submitting")
        rememberReceiver(to)
        val instant = st.isInstantTo(to, amount)
        val sent = if (instant) approveAndMaybeExecute(w, id, t.index, t.sig) else null
        if (sent != null) {
            note("sent: ${near.explorerTx(sent.hash)}")
            outcome = Outcome(true, "Sent ${Near.format(amount)} NEAR", "to $to", near.explorerTx(sent.hash))
        } else if (instant) {
            note("approved #$id; the contract didn't count it as instant, so it waits")
            outcome = Outcome(true, "Approved, waiting", "The wallet didn't treat this as within your limits (the phone's clock may be off). Execute it from Activity when it's ready.")
        } else {
            val r = w.approveCard(id, t.index, t.sig)
            val hours = st.delays.transfer / 3600
            note("approved #$id; over the limit, executes after $hours h")
            outcome = Outcome(
                true, "Approved, waiting $hours h",
                (when {
                    st.nearLimit == null -> "Spending limits are off, so every payment waits."
                    st.nearLimit?.isInstant(amount) == true -> "$to isn't one of your instant receivers."
                    else -> "This is over your instant limit."
                }) + " In $hours h, open Activity and tap Execute. Until then you can cancel it or freeze the wallet.",
                near.explorerTx(r.hash),
            )
        }
        refresh()
    }

    /**
     * A seed-phrase wallet: a plain NEAR transfer signed with the phrase's key,
     * after the security key and the phone's fingerprint prompt (the app's own
     * gate; the chain only sees the account's key).
     */
    private suspend fun sendFromSeed(to: String, amount: java.math.BigInteger) {
        val acct = store.walletAccount!!
        require(to != acct) { "That's this wallet's own account" }
        val (available, _) = near.spendable(acct)
        require(amount <= available) { "The wallet only has ${Near.format(available, 4)} NEAR available to send" }
        require(Network.isImplicit(to) || near.accountExists(to)) { "$to doesn't exist on ${network.displayName}" }
        requireSecurityKey("To send ${Near.format(amount)} NEAR to $to.")
        progress("Approve on your phone")
        phone.reason = "Send ${Near.format(amount)} NEAR to $to"
        phone.sign(sha256("tapvault/app/v1/seed-send".toByteArray(), to.toByteArray(), amount.toByteArray()))
        progress("Sending")
        rememberReceiver(to)
        val r = SeedWallet(near, acct, NearKey.fromSecret(store.walletKey!!)).send(to, amount)
        note("sent: ${near.explorerTx(r.hash)}")
        outcome = Outcome(true, "Sent ${Near.format(amount)} NEAR", "to $to", near.explorerTx(r.hash))
        refresh()
    }

    /**
     * Contract v0.3, a wallet without a card yet: the phone's proposal is its
     * only approval (threshold 1), so a payment within the limits executes
     * right after it; a bigger one waits the transfer delay like any other.
     */
    private suspend fun sendPhoneOnly(w: TapvaultWallet, st: WalletState, phoneIdx: Int, to: String, amount: java.math.BigInteger) {
        require(to != w.account) { "That's this wallet's own account" }
        val (available, _) = near.spendable(w.account)
        require(amount <= available) { "The wallet only has ${Near.format(available, 4)} NEAR available to send" }
        require(Network.isImplicit(to) || near.accountExists(to)) { "$to doesn't exist on ${network.displayName}" }
        requireSecurityKey("To send ${Near.format(amount)} NEAR to $to.")
        progress("Approve on your phone")
        phone.reason = "Send ${Near.format(amount)} NEAR to $to"
        val id = w.proposeByPhone(Action.Transfer(Asset.Near, amount, to), phone, phoneIdx).also { note("proposal #$it created (phone only)") }
        rememberReceiver(to)
        val sent = if (st.isInstantTo(to, amount)) {
            progress("Submitting")
            try {
                w.execute(id)
            } catch (e: app.tapvault.core.TapvaultException) {
                // The contract decides "instant" with its own clock and allowance.
                if ("Delay has not elapsed" in (e.message ?: "")) null else throw e
            }
        } else null
        val hours = st.delays.transfer / 3600
        outcome = if (sent != null) {
            note("sent: ${near.explorerTx(sent.hash)}")
            Outcome(true, "Sent ${Near.format(amount)} NEAR", "to $to", near.explorerTx(sent.hash))
        } else {
            note("proposal #$id waits $hours h")
            Outcome(
                true, "Approved, waiting $hours h",
                (when {
                    st.nearLimit == null -> "Spending limits are off, so every payment waits."
                    st.nearLimit?.isInstant(amount) == true -> "$to isn't one of your instant receivers."
                    else -> "This is over your instant limit."
                }) + " In $hours h, open Activity and tap Execute. Until then you can cancel it or freeze the wallet.",
            )
        }
        refresh()
    }

    // ---- pending proposals ----

    fun approveWithCard(p: ProposalInfo) = run("Approving") {
        val w = walletClient()
        val st = w.state()
        val ch = p.cardChallenge ?: w.awaitCardChallenge(p.id)
        // A NEAR payment the phone already approved can go out in the same transaction if it's within the limits.
        val phoneIdx = phoneIndex(st)
        val canRunNow = !st.frozen && p.isNearTransfer && p.approvedBy(phoneIdx) &&
            st.isInstantTo(p.transferReceiver(), p.transferAmount()!!)
        val t = tapWalletCard("Tap your card to approve: ${p.describe()}", st, ch) { s ->
            if (p.approvedBy(s.index)) "${cardName(s)} already approved this. Tap another card of this wallet." else null
        }
        requireSecurityKey("To approve: ${p.describe()}.")
        val sent = if (canRunNow) approveAndMaybeExecute(w, p.id, t.index, t.sig) else null
        val r = sent ?: w.approveCard(p.id, t.index, t.sig)
        outcome = Outcome(true, if (sent != null) "Approved and sent" else "Approved", p.describe(), near.explorerTx(r.hash))
        refresh()
    }

    /**
     * approve_card + execute in one transaction. If the contract refuses the
     * execute part (it decides "instant" with its own clock and allowance),
     * the whole transaction is undone; the same card signature is then sent
     * as a plain approval and null is returned.
     */
    private fun approveAndMaybeExecute(w: TapvaultWallet, id: Long, cardIndex: Int, sig: app.tapvault.core.CardSig) = try {
        w.approveCardAndExecute(id, cardIndex, sig)
    } catch (e: app.tapvault.core.TapvaultException) {
        val m = e.message ?: ""
        if ("Delay has not elapsed" in m || "Not enough approvals" in m || "frozen" in m) {
            w.approveCard(id, cardIndex, sig)
            null
        } else {
            throw e
        }
    }

    fun approveWithPhone(p: ProposalInfo) = run("Approving") {
        requireSecurityKey("To approve: ${p.describe()}.")
        val w = walletClient()
        val idx = phoneIndex(w.state())
        // The phone recomputes the intent from the action and the prompt names that same action (gap 6b).
        val r = w.approvePhone(p.id, phone, idx) { a -> phone.reason = "Approve: ${a.describe()}" }
        outcome = Outcome(true, "Approved", p.describe(), near.explorerTx(r.hash))
        refresh()
    }

    fun execute(p: ProposalInfo) = run("Executing") {
        val r = walletClient().execute(p.id)
        outcome = Outcome(true, "Done", p.describe(), near.explorerTx(r.hash))
        refresh()
    }

    fun cancel(p: ProposalInfo) = run("Cancelling") {
        val w = walletClient()
        val idx = phoneIndex(w.state())
        val r = w.cancelByPhone(p.id, phone, idx) { a -> phone.reason = a?.let { "Cancel: ${it.describe()}" } ?: "Cancel request #${p.id} (its details can't be checked)" }
        outcome = Outcome(true, "Cancelled", p.describe(), near.explorerTx(r.hash))
        refresh()
    }

    fun closeStale(p: ProposalInfo) = run("Cleaning up") {
        walletClient().closeStale(p.id)
        refresh()
    }

    // ---- freeze ----

    fun freezeWithPhone() = run("Freezing") {
        val w = walletClient()
        val idx = phoneIndex(w.state())
        phone.reason = "FREEZE the wallet (blocks all transfers)"
        val r = w.freezeByPhone(phone, idx)
        outcome = Outcome(true, "Wallet frozen", "No transfers can go out until you unfreeze (a delayed change).", near.explorerTx(r.hash))
        refresh()
    }

    fun freezeWithCard() = run("Freezing") {
        val w = walletClient()
        val st = w.state()
        // No hold timer here: freezing only stops things, and it should be instant.
        val t = tapWalletCard("Tap your card to FREEZE the wallet", st, w.cardFreezeChallenge(), hold = false)
        val r = w.freezeByCard(t.index, t.sig)
        outcome = Outcome(true, "Wallet frozen", "Frozen with a card tap. Unfreezing needs your phone and card, plus a delay.", near.explorerTx(r.hash))
        refresh()
    }

    fun requestUnfreeze() = run("Requesting unfreeze") {
        requireSecurityKey("To unfreeze the wallet.")
        val w = walletClient()
        val st = w.state()
        val idx = phoneIndex(st)
        if (st.phoneOnly) {
            // No card yet: the phone's proposal is the only approval needed; it waits the config delay.
            phone.reason = "Unfreeze the wallet (takes effect after ${st.delays.config / 3600} h)"
            val id = w.proposeByPhone(Action.Config(listOf(ConfigOp.Unfreeze)), phone, idx)
            outcome = Outcome(true, "Unfreeze requested", "Request #$id can execute in ${st.delays.config / 3600} h (Activity tab). Until then you can cancel it.")
            refresh()
            return@run
        }
        requireNfc()
        val now = System.currentTimeMillis() / 1000
        val open = w.proposals().firstOrNull { p ->
            !p.isTransfer && p.describe().contains("Unfreeze") && p.approvedBy(idx) && st.cards().none { p.approvedBy(it.index) } && !p.stale &&
                (p.cardDeadlineTs ?: Long.MAX_VALUE) > now + 30
        }
        val id = open?.id ?: run {
            phone.reason = "Unfreeze the wallet (takes effect after ${st.delays.config / 3600} h)"
            w.proposeByPhone(Action.Config(listOf(ConfigOp.Unfreeze)), phone, idx)
        }
        val ch = w.awaitCardChallenge(id)
        val t = tapWalletCard("Tap your card to approve unfreezing", st, ch)
        val r = w.approveCard(id, t.index, t.sig)
        outcome = Outcome(true, "Unfreeze approved", "It can execute in ${st.delays.config / 3600} h (Activity tab).", near.explorerTx(r.hash))
        refresh()
    }

    // ---- backup card ----

    /** Open config proposals that add a card, not yet void. */
    val pendingCardAdds get() = proposals.filter { !it.stale && it.addedCardKeyHashes().isNotEmpty() }

    /**
     * Add backup card, step 1: read the new card (chain, key), refuse it if
     * it's already a signer, and make it sign this wallet's enrolment-style
     * challenge, verified here. After setup the contract checks no proof of
     * possession for AddSigner (DESIGN gap 8), so this check is the app's.
     */
    fun readBackupCard() = run("Reading the new card") {
        backup = null
        requireNfc()
        requireSecurityKey("To add a card to this wallet.")
        val w = walletClient()
        val st = w.state()
        if (st.signers.size >= 8) throw IllegalStateException("This wallet already has 8 signers, the most it can have.")
        val existing = st.signers.map { it.keyHash.lowercase() }.toSet()
        val count = st.signers.size
        val svc = cards()
        // v0.2 wallets check possession themselves: the card signs the contract's possession challenge.
        val v02 = w.isV02()
        fun challenge(k: SignerKey.EmvCard) =
            if (v02) Domain.cardPossessionChallenge(w.account, k.keyHash()) else Domain.cardEnrollChallenge(w.account, count, k.keyHash())
        val e = try {
            withCard("Hold the new card to the back of the phone") { t ->
                svc.enrol(t) { k ->
                    if (k.keyHash().hex() in existing) throw AlreadySignerException("already a signer")
                    challenge(k)
                }
            }
        } catch (e: AlreadySignerException) {
            throw IllegalStateException("That card is already one of this wallet's cards. Hold a different card to add it as a backup.")
        } catch (e: CardCheckFailed) {
            cardReport = e.report
            throw IllegalStateException("This card can't sign for Tapvault: ${e.message}. Try another contactless card.")
        }
        if (!provesPossession(e.key, e.proof, challenge(e.key))) {
            throw IllegalStateException("The card's test signature didn't check out, so it won't be added. Tap it again, or use another card.")
        }
        val b = BackupCandidate(e.key, e.recipe, e.report.network, e.report.panLast4 ?: "????", e.proof.takeIf { v02 })
        note("backup card ${b.name} read: ${e.report.iccKeyBits}-bit key, ${e.recipe.method.label}; test signature verified")
        withContext(Dispatchers.Main) { backup = b }
    }

    fun discardBackup() { backup = null }

    /**
     * Add backup card, step 2: the phone proposes Config{AddSigner(card)}
     * (fingerprint), a card already in the wallet approves, and it can execute
     * after the config delay. The config change voids other open proposals.
     */
    fun addBackupCard() = run("Adding backup card") {
        val b = backup ?: throw IllegalStateException("Read the new card first.")
        val w = walletClient()
        val st = w.state()
        if (st.signerByKeyHash(b.keyHash) != null) throw IllegalStateException("${b.name} is already a signer of this wallet.")
        val idx = phoneIndex(st)
        val hours = hoursText(st.delays.config)
        if (st.phoneOnly) return@run addFirstCard(w, st, b, idx, hours)
        requireNfc()
        val now = System.currentTimeMillis() / 1000
        // A request for this card that the phone approved but no card has yet is finished instead of made again.
        val open = w.proposals().firstOrNull { p ->
            b.keyHash in p.addedCardKeyHashes() && p.approvedBy(idx) && st.cards().none { p.approvedBy(it.index) } &&
                !p.stale && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now + 30
        }
        // Remember how it signs now, so it can sign as soon as it is a signer.
        withContext(Dispatchers.Main) { saveCards(store.cards.put(KnownCard(b.keyHash, b.recipe, b.network, b.last4))) }
        val id = open?.id ?: run {
            progress("Approve on your phone")
            phone.reason = "Add ${b.name} as a backup card (takes effect after $hours)"
            val poss = b.possession?.let { mapOf(0 to PossessionProof.Card(it)) } ?: emptyMap()
            w.proposeByPhone(Action.Config(listOf(ConfigOp.AddSigner(b.key))), phone, idx, poss).also { note("proposal #$it: add ${b.name}") }
        }
        withContext(Dispatchers.Main) { backup = null }
        progress("Waiting for the card challenge")
        val ch = w.awaitCardChallenge(id)
        val t = try {
            tapWalletCard("Tap a card already in this wallet to approve adding ${b.name}", st, ch)
        } catch (e: Exception) {
            refresh()
            throw IllegalStateException("${e.message}\n\nRequest #$id is saved: approve it with your card in Activity within 10 minutes, or cancel it there.")
        }
        val r = w.approveCard(id, t.index, t.sig)
        note("approved adding ${b.name} with ${t.name}; executes after $hours")
        outcome = Outcome(
            true, "Backup card added in $hours",
            "${b.name} becomes a signer after the $hours wait. Then open Activity and tap Execute. Until then you can cancel it.",
            near.explorerTx(r.hash),
        )
        refresh()
    }

    /**
     * Contract v0.3: the first card of a wallet set up without one. One change
     * adds the card and raises the approvals needed to 2 (the contract refuses
     * a card on a threshold-1 wallet). The phone's proposal is the only
     * approval it needs; it executes after the config delay.
     */
    private suspend fun addFirstCard(w: TapvaultWallet, st: WalletState, b: BackupCandidate, idx: Int, hours: String) {
        val proof = b.possession ?: throw IllegalStateException("This wallet's code can't check the card. Read the card again.")
        val open = w.proposals().firstOrNull { p -> !p.stale && b.keyHash in p.addedCardKeyHashes() && p.approvedBy(idx) }
        withContext(Dispatchers.Main) { saveCards(store.cards.put(KnownCard(b.keyHash, b.recipe, b.network, b.last4))) }
        val id = open?.id ?: run {
            progress("Approve on your phone")
            phone.reason = "Add ${b.name} as this wallet's card; then every payment needs phone + card (takes effect after $hours)"
            val action = Action.Config(listOf(ConfigOp.AddSigner(b.key), ConfigOp.SetThreshold(2)))
            w.proposeByPhone(action, phone, idx, mapOf(0 to PossessionProof.Card(proof))).also { note("proposal #$it: add ${b.name}, approvals 2") }
        }
        withContext(Dispatchers.Main) { backup = null }
        outcome = Outcome(
            true, "${b.name} added in $hours",
            "After the $hours wait, open Activity and tap Execute. From then on every payment needs your phone and ${b.name}. Until then you can cancel it.",
        )
        refresh()
    }

    private fun hoursText(secs: Long) = if (secs % 3600 == 0L) "${secs / 3600} h" else "${secs / 60} min"

    private suspend fun rememberReceiver(to: String) {
        val r = store.receivers.add(to)
        store.receivers = r
        withContext(Dispatchers.Main) { receivers = r }
    }

    // ---- lost card ----

    /**
     * "I lost this card": optionally freeze first (phone), then the phone
     * proposes Config{RemoveSigner(card)} (plus Unfreeze when the wallet is or
     * gets frozen, so one wait covers both), another card of the wallet
     * approves, and it executes after the config delay. The card being
     * removed can't cancel its own removal (contract rule).
     */
    fun removeCard(keyHash: String, freezeFirst: Boolean) = run("Removing the card") {
        requireNfc()
        requireSecurityKey("To remove a card from this wallet.")
        val w = walletClient()
        var st = w.state()
        val gone = st.signerByKeyHash(keyHash) ?: throw IllegalStateException("That card isn't a signer of this wallet any more.")
        val name = cardName(gone)
        if (st.cards().size < 2) throw IllegalStateException("$name is this wallet's only card. Add a backup card first; then you can remove this one.")
        if (st.signers.size - 1 < st.threshold) throw IllegalStateException("Removing $name would leave fewer signers than the ${st.threshold} approvals needed.")
        val idx = phoneIndex(st)
        if (freezeFirst && !st.frozen) {
            progress("Freeze first: approve on your phone")
            phone.reason = "FREEZE the wallet (blocks all transfers)"
            w.freezeByPhone(phone, idx)
            note("frozen before removing $name")
            st = w.state()
        }
        val ops = listOfNotNull(ConfigOp.RemoveSigner(gone.keyHash.unhex()), ConfigOp.Unfreeze.takeIf { st.frozen })
        val hours = hoursText(st.delays.config)
        progress("Approve the removal on your phone")
        phone.reason = "Remove $name from this wallet" + (if (st.frozen) " and unfreeze" else "") + " (takes effect after $hours)"
        val id = w.proposeByPhone(Action.Config(ops), phone, idx).also { note("proposal #$it: remove $name") }
        progress("Waiting for the card challenge")
        val ch = w.awaitCardChallenge(id)
        val t = try {
            tapWalletCard("Tap your other card to approve removing $name", st, ch) { s ->
                if (s.keyHash.equals(keyHash, true)) "That's $name, the card you're removing. Tap another card of this wallet." else null
            }
        } catch (e: Exception) {
            refresh()
            throw IllegalStateException("${e.message}\n\nRequest #$id is saved: approve it with your other card in Activity within 10 minutes, or cancel it there.")
        }
        val r = w.approveCard(id, t.index, t.sig)
        outcome = Outcome(
            true, "$name removed in $hours",
            "After the $hours wait, open Activity and tap Execute." + (if (st.frozen) " The same change unfreezes the wallet." else "") +
                " The removed card can't cancel this.",
            near.explorerTx(r.hash),
        )
        refresh()
    }

    // ---- setup options (phase 3) ----

    fun setSetupOptions(backupCard: Boolean, strict: Boolean) {
        store.wantBackup = backupCard
        store.strictKey = strict
    }

    /** Setup: go on without any card (a phone-only wallet); the card can be added later from Security. */
    fun continueWithoutCard() {
        store.withCard = false
        store.wantBackup = false
        store.pendingCard = null
        store.pendingCard2 = null
        cardReport = null
        continueSetup()
    }

    /** Setup: go on without the backup card (it can be added later from Security Center). */
    fun skipBackupCard() {
        store.wantBackup = false
        store.pendingCard2 = null
        continueSetup()
    }

    // ---- seed-phrase wallets ----

    /** The phrase on the "Create seed phrase wallet" screens (also saved, encrypted, so a restart shows the same words). */
    var seedDraft by mutableStateOf<String?>(null)

    /** "Create wallet" → "Create seed phrase wallet": new words (or the ones already shown for this wallet). */
    fun startSeedWallet() {
        val phrase = store.seedPhrase?.takeIf { store.setupStep == 0 } ?: Mnemonic.generate().also { store.seedPhrase = it }
        seedDraft = phrase
        go(Screen.SeedCreate)
    }

    /**
     * After the words were written down and checked: an account whose only
     * key comes from the phrase. On testnet the faucet (or sponsor) creates a
     * named account; on mainnet it is the phrase key's implicit account,
     * which exists once the user sends NEAR to it. [protectNow] goes on to
     * "Protect with bank card" straight away.
     */
    fun createSeedWallet(protectNow: Boolean) = run("Creating your wallet") {
        val phrase = store.seedPhrase ?: throw IllegalStateException("The recovery phrase is missing. Go back and start again.")
        ensureLocalKeys()
        if (store.setupStep < 1) {
            progress("Creating your wallet account on ${network.displayName}")
            val key = Mnemonic.nearKey(phrase)
            if (!network.hasFaucet) {
                // Nothing to create: the fee account is funded from the wallet when it is protected.
                val acct = NearClient.implicitAccount(key.publicKey)
                store.walletAccount = acct; store.walletKey = key.secretString()
                store.gasAccount = null; store.gasKey = null
                store.seedWallet = true
                setStep(1)
                note("seed phrase wallet $acct (implicit; exists once funded)")
            } else {
                // Saved before any account exists, so a retry reuses what was already created.
                val plan = Onboarding.Plan.decode(store.setupPlan)?.takeIf { it.walletKey.publicKeyString == key.publicKeyString }
                    ?: Onboarding.Plan(Onboarding.Plan.random().tag, key, NearKey.random()).also { store.setupPlan = it.encode() }
                val a = Onboarding(near).createAccounts(sponsor = sponsor(), plan = plan) { note(it) }
                store.walletAccount = a.wallet; store.walletKey = a.walletKey.secretString()
                store.gasAccount = a.gas; store.gasKey = a.gasKey.secretString()
                store.seedWallet = true
                store.setupPlan = null
                setStep(1)
                note("seed phrase wallet ${a.wallet}")
            }
        }
        withContext(Dispatchers.Main) { seedWallet = true; seedDraft = null; screen = Screen.Home; reloadWalletList() }
        refresh()
        if (protectNow) withContext(Dispatchers.Main) { protectWithCard() }
        else outcome = Outcome(
            true, "Your wallet is ready",
            (if (network.hasFaucet) "" else "Send NEAR to it from an exchange or another wallet to start (Receive). ") +
                "Your recovery phrase controls it. Protect it with your bank card any time from Home.",
            near.explorerAccount(store.walletAccount!!),
        )
    }

    /** What the import screen found for the phrase or key the user entered. */
    class ImportFound(val accounts: List<String>, val key: NearKey, val phrase: String?)

    var importFound by mutableStateOf<ImportFound?>(null)

    /** "I have a wallet" → "Import with recovery phrase": find the accounts the phrase or key controls. */
    fun findImport(input: String, account: String) = run("Looking for your wallet") {
        val (key, phrase) = SeedWallet.parseImport(input)
        val typed = account.trim().lowercase()
        val accounts = if (typed.isNotEmpty()) {
            network.accountProblem(typed)?.let { throw IllegalStateException("$it.") }
            if (!near.accountExists(typed)) throw IllegalStateException("$typed doesn't exist on ${network.displayName}.")
            if (key.publicKeyString !in near.fullAccessKeys(typed)) throw IllegalStateException("This phrase or key doesn't control $typed (it isn't one of its full-access keys).")
            listOf(typed)
        } else SeedWallet.findAccounts(near, key)
        if (accounts.isEmpty()) throw IllegalStateException("No ${network.displayName} account uses this phrase or key yet. If you know the account name, type it in the second box.")
        val plain = accounts.filter { near.codeHash(it) == NearClient.NO_CODE }
        if (plain.isEmpty()) throw IllegalStateException("${accounts.first()} runs a smart contract, so it can't be used as a seed phrase wallet here.")
        val already = wallets.idsOn(network).mapNotNull { wallets.store(it).walletAccount }.toSet()
        plain.firstOrNull { it in already }?.let { if (plain.size == 1) throw IllegalStateException("$it is already on this phone.") }
        withContext(Dispatchers.Main) { importFound = ImportFound(plain.filter { it !in already }, key, phrase) }
    }

    /** Add the account found by [findImport] as a seed-phrase wallet; [protectNow] goes on to "Protect with bank card". */
    fun importWallet(account: String, protectNow: Boolean) = run("Adding your wallet") {
        val f = importFound ?: throw IllegalStateException("Look up the wallet first.")
        require(account in f.accounts) { "Pick one of the accounts found." }
        ensureLocalKeys()
        store.walletAccount = account
        store.walletKey = f.key.secretString()
        store.seedPhrase = f.phrase
        store.gasAccount = null; store.gasKey = null
        store.seedWallet = true
        setStep(1)
        note("imported $account")
        withContext(Dispatchers.Main) { seedWallet = true; importFound = null; screen = Screen.Home; reloadWalletList() }
        refresh()
        if (protectNow) withContext(Dispatchers.Main) { protectWithCard() }
        else outcome = Outcome(true, "Wallet added", "$account is on this phone. Protect it with your bank card any time from Home.", near.explorerAccount(account))
    }

    /** "Protect with bank card": the rest of setup (limits, card, contract) on the seed-phrase wallet's account. */
    fun protectWithCard() {
        if (!seedWallet || setupStep != 1) return
        store.withCard = true
        store.wantBackup = false
        store.pendingCard2 = null
        go(Screen.Limits)
    }

    /** The recovery phrase (or imported private key) while it is on screen; null otherwise. */
    var revealed by mutableStateOf<Pair<String, String>?>(null)

    fun revealPhrase() = run("Recovery phrase") {
        val phrase = store.seedPhrase
        val secret = phrase ?: store.walletKey ?: throw IllegalStateException("This wallet has no recovery phrase on this phone.")
        requireSecurityKey(if (phrase != null) "To see your recovery phrase." else "To see your private key.")
        phone.reason = if (phrase != null) "Show your recovery phrase" else "Show your private key"
        phone.sign(sha256("tapvault/app/v1/reveal-secret".toByteArray()))
        withContext(Dispatchers.Main) { revealed = (if (phrase != null) "Recovery phrase" else "Private key") to secret }
    }

    // ---- recover on a new phone (plan 5.5) ----

    /** What "Recover a wallet" found for the account the user typed. */
    data class RecoverLookup(val account: String, val state: WalletState, val problem: String?)

    var recoverLookup by mutableStateOf<RecoverLookup?>(null)

    /** The open recovery request for this phone's key, as last read (Recover screen). */
    var recoveryRequest by mutableStateOf<ProposalInfo?>(null)
    var recoveryState by mutableStateOf<WalletState?>(null)

    private fun viewOnly(account: String) = TapvaultWallet(near, account, account, NearKey.random())

    /**
     * Recover and Be a guardian make this phone a fee account from the
     * faucet. Mainnet has none, and a fee account the user funds isn't built
     * yet, so both are refused there with a reason.
     */
    private fun requireFaucetForJoin() {
        if (!network.hasFaucet) throw IllegalStateException(
            "On ${network.displayName} this phone needs a fee account you fund yourself, and that isn't built yet. For now this works on testnet only.",
        )
    }

    /** Step 1: read the wallet. Nothing is created yet. */
    fun findWalletToRecover(account: String) = run("Looking up the wallet") {
        val a = account.trim().lowercase()
        if (!Regex("^[a-z0-9._-]{2,64}$").matches(a)) throw IllegalArgumentException("Enter the wallet's account, like ${network.example("tv-1a2b3c4d5e")}")
        network.accountProblem(a)?.let { throw IllegalArgumentException(it) }
        val st = try { viewOnly(a).state() } catch (e: Exception) {
            throw IllegalStateException("No Tapvault wallet found at $a. Check the account name (it's on your old phone's Receive screen).")
        }
        // A throwaway key just to check the plan; the real one is made when recovery starts.
        val problem = runCatching { Recovery.plan(st, SoftP256Probe.key) }.exceptionOrNull()?.message
        withContext(Dispatchers.Main) { recoverLookup = RecoverLookup(a, st, problem) }
    }

    /**
     * Step 2: make this phone's key and fee account, then card A proposes
     * the recovery change and [Recovery.Plan.approvalsNeeded] cards approve
     * within 10 minutes of the seal. Any step can be retried: the request
     * this phone's key is in is found again on chain.
     */
    fun startRecovery(lostCards: Set<String>) = run("Recovering") {
        val look = recoverLookup ?: throw IllegalStateException("Look up the wallet first.")
        requireNfc()
        store.joinMode = "recover"
        if (!phoneKeyExists) {
            progress("Creating this phone's key in secure hardware")
            try {
                KeystoreSigner.create(getApplication(), WalletIndex.aliasOf(store, activeId), strict = store.strictKey)
            } catch (e: IllegalStateException) { throw e } catch (e: Exception) {
                throw IllegalStateException("Set up a screen lock on this phone first, then try again. (${e.message})")
            }
        }
        if (store.gasAccount == null) {
            requireFaucetForJoin()
            progress("Creating this phone's fee account")
            val plan = Onboarding.Plan.decode(store.setupPlan) ?: Onboarding.Plan.random().also { store.setupPlan = it.encode() }
            val gas = Onboarding(near).createFeeAccount(sponsor = sponsor(), plan = plan) { note(it) }
            store.walletAccount = look.account; store.gasAccount = gas; store.gasKey = plan.gasKey.secretString()
            store.setupPlan = null
            note("fee account $gas")
        }
        withContext(Dispatchers.Main) { reloadWalletList() }
        val w = walletClient()
        var st = w.state()
        val mine = phone.signerKey()
        val id = myRecovery(w)?.id ?: run {
            val plan = Recovery.plan(st, mine, lostCards = lostCards)
            val addAt = plan.action.ops.indexOfFirst { it is ConfigOp.AddSigner }
            val poss = if (w.isV02()) {
                progress("Prove this phone's key")
                phone.reason = "Join ${look.account} with this phone (recovery)"
                mapOf(addAt to PossessionProof.P256(phone.sign(Domain.possessionMessage(look.account, mine.keyHash()))))
            } else emptyMap()
            progress("Tap card 1 of ${plan.approvalsNeeded}")
            w.proposeWithCard(plan.action, poss) { ch ->
                val t = tapWalletCard("Recovery: hold one of the wallet's cards (1 of ${plan.approvalsNeeded})", st, ch) { si ->
                    if (si.keyHash.lowercase() in lostCards.map { it.lowercase() }) "That card is marked lost. Tap another card." else null
                }
                t.index to t.sig
            }.also { note("recovery request #$it proposed by card") }
        }
        approveRecovery(w, id, lostCards)
    }

    /** Approve the recovery request with cards until it has all its approvals (within the 10-minute window). */
    private suspend fun approveRecovery(w: TapvaultWallet, id: Long, lostCards: Set<String> = emptySet()) {
        val st = w.state()
        progress("Waiting for the card challenge")
        val ch = w.awaitCardChallenge(id)
        while (true) {
            val p = w.proposal(id) ?: throw IllegalStateException("The recovery request is gone: a phone or card of the wallet cancelled it.")
            if (p.readyTs != null) break
            val done = st.cards().count { p.approvedBy(it.index) }
            val t = try {
                tapWalletCard("Recovery: hold card ${done + 1} of ${st.threshold}", st, ch) { si ->
                    when {
                        p.approvedBy(si.index) -> "That card already approved. Tap another of the wallet's cards."
                        si.keyHash.lowercase() in lostCards.map { it.lowercase() } -> "That card is marked lost. Tap another card."
                        else -> null
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("${e.message}\n\nRequest #$id is saved. Approve it with your other card within 10 minutes of starting (Recover screen → Approve with a card).")
            }
            w.approveCard(id, t.index, t.sig)
            note("recovery #$id approved with ${t.name}")
        }
        val p = w.proposal(id)!!
        val at = (p.readyTs ?: 0) + st.delays.recovery
        outcome = Outcome(
            true, "Recovery requested",
            "This phone joins the wallet after ${hoursText(st.delays.recovery)} (${java.text.DateFormat.getDateTimeInstance().format(Date(at * 1000))}). " +
                "If your old phone turns up, it can cancel this. Keep this app installed.",
        )
        val now = w.state()
        val mine = myRecovery(w)
        withContext(Dispatchers.Main) { recoveryState = now; recoveryRequest = mine }
    }

    /** The open request that adds this phone's key, if any. */
    private fun myRecovery(w: TapvaultWallet): ProposalInfo? {
        val mine = runCatching { phone.publicKey }.getOrNull() ?: return null
        return w.proposals().firstOrNull { p ->
            !p.stale && runCatching { (ActionJson.parse(p.action) as? Action.Config)?.ops }.getOrNull()
                ?.any { it is ConfigOp.AddSigner && (it.key as? SignerKey.P256)?.pubkey?.contentEquals(mine) == true } == true
        }
    }

    /** Recover screen: reload the request; when this phone is already a signer, the wallet opens. */
    fun refreshRecovery() = run("Checking") {
        val w = walletClient()
        val st = w.state()
        if (st.phoneIndexOf(phone.publicKey) != null) { joined("Recovered", "This phone is now a signer of the wallet."); return@run }
        val p = myRecovery(w)
        withContext(Dispatchers.Main) { recoveryState = st; recoveryRequest = p }
    }

    fun approveRecoveryAgain() = run("Approving") {
        val w = walletClient()
        val p = myRecovery(w) ?: throw IllegalStateException("There is no open recovery request for this phone. Start again.")
        requireNfc()
        approveRecovery(w, p.id)
    }

    /** After the recovery delay: execute (anyone can), then the wallet opens normally. */
    fun finishRecovery() = run("Finishing recovery") {
        val w = walletClient()
        val p = myRecovery(w) ?: throw IllegalStateException("There is no open recovery request for this phone.")
        w.execute(p.id)
        val st = w.state()
        if (st.phoneIndexOf(phone.publicKey) == null) throw IllegalStateException("The change ran, but this phone isn't a signer. Pull to refresh and check Activity.")
        joined("Wallet recovered", "This phone is now a signer. Your cards work as before; the old phone key is removed.")
    }

    private suspend fun joined(title: String, detail: String) {
        store.joinMode = null
        store.guardianCode = null
        setStep(4)
        withContext(Dispatchers.Main) {
            recoverLookup = null; recoveryRequest = null; recoveryState = null
            screen = Screen.Home
            reloadWalletList()
        }
        refresh()
        outcome = Outcome(true, title, detail, store.walletAccount?.let { near.explorerAccount(it) })
    }

    // ---- guardian phone (plan phase 3) ----

    /** Guardian phone: make a key and fee account for [account] and show the code the owner adds. */
    fun becomeGuardian(account: String) = run("Preparing guardian code") {
        val a = account.trim().lowercase()
        val st = try { viewOnly(a).state() } catch (e: Exception) {
            throw IllegalStateException("No Tapvault wallet found at $a. Ask the owner for the account on their Receive screen.")
        }
        if (st.setupMode) throw IllegalStateException("That wallet hasn't finished setup yet.")
        if (store.gasAccount == null) requireFaucetForJoin()
        store.joinMode = "guardian"
        if (!phoneKeyExists) {
            progress("Creating this phone's key in secure hardware")
            KeystoreSigner.create(getApplication(), WalletIndex.aliasOf(store, activeId))
        }
        if (store.gasAccount == null) {
            progress("Creating this phone's fee account")
            val plan = Onboarding.Plan.decode(store.setupPlan) ?: Onboarding.Plan.random().also { store.setupPlan = it.encode() }
            val gas = Onboarding(near).createFeeAccount(sponsor = sponsor(), plan = plan) { note(it) }
            store.walletAccount = a; store.gasAccount = gas; store.gasKey = plan.gasKey.secretString()
            store.setupPlan = null
        }
        progress("Sign your guardian code")
        phone.reason = "Offer this phone as a guardian of $a"
        store.guardianCode = Guardian.offer(a, phone)
        withContext(Dispatchers.Main) { reloadWalletList() }
        note("guardian code ready for $a")
    }

    var guardianCode by mutableStateOf<String?>(null)
        private set

    fun loadGuardianCode() { guardianCode = store.guardianCode }

    /** Guardian phone: has the owner added this key yet? */
    fun checkGuardianAdded() = run("Checking") {
        val st = walletClient().state()
        if (st.phoneIndexOf(phone.publicKey) != null) joined("You're a guardian", "This phone can now cancel requests and freeze the wallet, and approve with one of the owner's cards.")
        else outcome = Outcome(false, "Not added yet", "The owner adds your code in Security Center; it takes effect after their settings delay, then they tap Execute.")
    }

    /** Owner phone: add a guardian from its code (phone proposes with the guardian's possession proof, a card approves). */
    fun addGuardian(code: String) = run("Adding guardian") {
        val offer = Guardian.decode(code)
        requireNfc()
        requireSecurityKey("To add a guardian phone.")
        val w = walletClient()
        val st = w.state()
        val action = Guardian.addAction(w.account, st, offer)
        val idx = phoneIndex(st)
        val hours = hoursText(st.delays.config)
        val poss = if (w.isV02()) mapOf(0 to offer.proof) else emptyMap()
        progress("Approve on your phone")
        phone.reason = "Add a guardian phone (full co-signer) to this wallet, after $hours"
        val id = w.proposeByPhone(action, phone, idx, poss)
        val ch = w.awaitCardChallenge(id)
        val t = try { tapWalletCard("Tap a card of this wallet to approve the guardian", st, ch) } catch (e: Exception) {
            refresh(); throw IllegalStateException("${e.message}\n\nRequest #$id is saved: approve it with your card in Activity within 10 minutes, or cancel it there.")
        }
        val r = w.approveCard(id, t.index, t.sig)
        outcome = Outcome(true, "Guardian added in $hours", "After the wait, open Activity and tap Execute. Until then you can cancel it.", near.explorerTx(r.hash))
        refresh()
    }

    // ---- receiver allowlist (contract v0.2) ----

    /**
     * Add [receiver] to (or remove it from) the allowlist: one settings change,
     * phone + card, after the settings delay. With any entry, only listed
     * receivers are paid instantly; everyone else waits the transfer delay.
     */
    fun changeAllowlist(receiver: String, add: Boolean) = run(if (add) "Adding receiver" else "Removing receiver") {
        val r = receiver.trim().lowercase()
        if (!Regex("^[a-z0-9._-]{2,64}$").matches(r)) throw IllegalArgumentException("Enter a NEAR account, like ${network.example("bob")}")
        requireNfc()
        requireSecurityKey(if (add) "To add $r to your instant receivers." else "To remove $r from your instant receivers.")
        val w = walletClient()
        if (!w.isV02()) throw IllegalStateException("This wallet runs an older contract without an allowlist. Wallets created with this app version have it.")
        val st = w.state()
        if (add && r in st.allowlist) throw IllegalStateException("$r is already on the list.")
        if (!add && r !in st.allowlist) throw IllegalStateException("$r isn't on the list.")
        if (add && st.allowlist.size >= 16) throw IllegalStateException("The list is full (16 receivers).")
        val idx = phoneIndex(st)
        val hours = hoursText(st.delays.config)
        val op = if (add) ConfigOp.AllowReceiver(r) else ConfigOp.DisallowReceiver(r)
        progress("Approve on your phone")
        phone.reason = (if (add) "Let $r be paid instantly" else "Stop instant payments to $r") + " (after $hours)" +
            (if (add && st.allowlist.isEmpty()) ". From then on, other receivers always wait" else "")
        val id = w.proposeByPhone(Action.Config(listOf(op)), phone, idx)
        val ch = w.awaitCardChallenge(id)
        val t = try { tapWalletCard("Tap a card of this wallet to approve", st, ch) } catch (e: Exception) {
            refresh(); throw IllegalStateException("${e.message}\n\nRequest #$id is saved: approve it with your card in Activity within 10 minutes, or cancel it there.")
        }
        val res = w.approveCard(id, t.index, t.sig)
        outcome = Outcome(true, if (add) "$r added in $hours" else "$r removed in $hours", "After the wait, open Activity and tap Execute.", near.explorerTx(res.hash))
        refresh()
    }

    // ---- strict mode for an existing wallet (plan 5.8) ----

    /**
     * Replace this wallet's phone key with a fingerprint-only one: create it,
     * then one settings change RemoveSigner(old) + AddSigner(new), approved
     * by the old key and a card, which runs after the settings delay. Needs a
     * backup card first (if the fingerprint stops working, cards recover).
     */
    fun switchToStrictKey() = run("Switching to fingerprint only") {
        if (android.os.Build.VERSION.SDK_INT < 30) throw IllegalStateException("Fingerprint only needs Android 11 or newer; on this phone the key is already fingerprint-only.")
        if (phone.isStrict) throw IllegalStateException("This wallet's phone key is already fingerprint only.")
        requireNfc()
        requireSecurityKey("To switch this phone's key to fingerprint only.")
        val w = walletClient()
        val st = w.state()
        if (st.cards().size < 2) throw IllegalStateException("Add a backup card first: if your fingerprint stops working, two cards are how you get back in.")
        val idx = phoneIndex(st)
        val newAlias = WalletIndex.keyAlias(activeId) + "_strict_" + System.currentTimeMillis() / 1000
        KeystoreSigner.create(getApplication(), newAlias, strict = true)
        store.pendingAlias = newAlias
        val strictKey = KeystoreSigner({ newAlias }) { activityRef.get() }
        val oldHash = phone.signerKey().keyHash()
        val action = Action.Config(listOf(ConfigOp.RemoveSigner(oldHash), ConfigOp.AddSigner(strictKey.signerKey())))
        val poss = if (w.isV02()) {
            progress("Approve with your fingerprint (new key)")
            strictKey.reason = "Prove the new fingerprint-only key"
            mapOf(1 to PossessionProof.P256(strictKey.sign(Domain.possessionMessage(w.account, strictKey.signerKey().keyHash()))))
        } else emptyMap()
        val hours = hoursText(st.delays.config)
        progress("Approve on your phone (current key)")
        phone.reason = "Replace this phone's key with a fingerprint-only key, after $hours"
        val id = w.proposeByPhone(action, phone, idx, poss)
        val ch = w.awaitCardChallenge(id)
        val t = try { tapWalletCard("Tap a card of this wallet to approve the key switch", st, ch) } catch (e: Exception) {
            refresh(); throw IllegalStateException("${e.message}\n\nRequest #$id is saved: approve it with your card in Activity within 10 minutes, or cancel it there.")
        }
        w.approveCard(id, t.index, t.sig)
        outcome = Outcome(true, "Key switch in $hours", "After the wait, open Activity and tap Execute. The app then uses the new fingerprint-only key.")
        refresh()
    }

    /** After a refresh: adopt a pending strict key once it is a signer, or drop it if its request is gone. */
    private fun adoptPendingKey(s: WalletState, ps: List<ProposalInfo>, st: Store, id: String) {
        val alias = st.pendingAlias ?: return
        val pub = runCatching { KeystoreSigner({ alias }) { null }.publicKey }.getOrNull() ?: run { st.pendingAlias = null; return }
        if (s.phoneIndexOf(pub) != null) {
            val old = WalletIndex.aliasOf(st, id)
            st.phoneAlias = alias
            st.pendingAlias = null
            if (old != alias) runCatching { KeystoreSigner.delete(old) }
            note("switched to the fingerprint-only phone key")
        } else if (ps.none { p -> runCatching { (ActionJson.parse(p.action) as? Action.Config)?.ops }.getOrNull()?.any { it is ConfigOp.AddSigner && (it.key as? SignerKey.P256)?.pubkey?.contentEquals(pub) == true } == true && !p.stale }) {
            runCatching { KeystoreSigner.delete(alias) }
            st.pendingAlias = null
            note("key switch request is gone; the new key was deleted")
        }
    }

    // ---- integrity ----

    /** Base58 code hash of the wasm this APK ships (the one every new wallet runs). */
    private val shippedCodeHash: String by lazy {
        Integrity.codeHashOf(getApplication<Application>().assets.open("tapvault_contract.wasm").use { it.readBytes() })
    }

    private fun integrityNow(w: TapvaultWallet, s: WalletState, id: String = activeId, st: Store = store): Integrity.Report {
        // That wallet's own phone key, even if the user switched wallets meanwhile.
        val pub = runCatching { KeystoreSigner({ WalletIndex.aliasOf(st, id) }) { null }.publicKey }.getOrNull()
        val r = Integrity.evaluate(w.near.codeHash(w.account), w.near.accessKeys(w.account), s, pub, shippedCodeHash)
        st.integrity = r.encode()
        note("integrity check: " + if (r.ok) "passed" else "FAILED: " + r.failed.joinToString { it.name })
        return r
    }

    /** Security Center → Check now. */
    fun checkIntegrity() = run("Checking the wallet") {
        val w = walletClient()
        val r = integrityNow(w, w.state())
        withContext(Dispatchers.Main) { integrity = r }
        outcome = if (r.ok) Outcome(true, "Wallet check passed", r.checks.joinToString(" · ") { it.name })
        else Outcome(false, "Wallet check failed", r.failed.joinToString("\n") { "${it.name}: ${it.detail}" })
    }

    // ---- app lock ----

    /** Called when the app comes to the foreground: lock if it was away more than 60 s (or just started) and a wallet exists. */
    fun lockIfAway() {
        val away = System.currentTimeMillis() - wallets.backgroundAt
        val anyReady = wallets.ids.any { wallets.store(it).usable() }
        if (anyReady && busy == null && (wallets.backgroundAt == 0L || away > 60_000)) locked = true
    }

    fun wentToBackground() { wallets.backgroundAt = System.currentTimeMillis() }

    fun unlock() {
        val act = activity ?: return
        AppLock.prompt(act) { locked = false }
    }

    // ---- alerts (watchtower) ----

    fun setAlerts(on: Boolean) {
        wallets.alertsOn = on
        alertsOn = on
        val act = activity ?: return
        if (on) {
            act.askNotificationPermission()
            Watchtower.schedule(act)
            note("alerts on: checked about every 15 minutes")
        } else {
            Watchtower.cancel(act)
            note("alerts off")
        }
    }

    /** A notification was tapped: show its wallet and screen. */
    fun openFromAlert(walletId: String?, target: String?) {
        if (walletId != null && walletId != activeId && walletId in wallets.ids && busy == null) {
            // An alert for a wallet on the other network shows that network.
            val n = wallets.networkOf(walletId)
            if (n !== network && (n.isTestnet || mainnetEnabled)) wallets.network = n
            if (n === wallets.network) activate(walletId)
        }
        when (target) {
            "activity" -> go(Screen.Activity)
            "security" -> go(Screen.Security)
        }
    }

    // ---- card check ----

    fun checkCard() = run("Checking card") {
        cardReport = null
        val svc = cards()
        val report = withCard("Hold the card you want to check") { t -> svc.inspect(t) }
        cardReport = report
        note("card check: ${report.network} ${report.signsWith ?: "can't sign"}")
    }

    // ---- wallets ----

    init {
        reloadWalletList()
    }

    private fun startScreen(s: Store) = when {
        s.usable() -> Screen.Home
        s.joinMode == "recover" -> Screen.Recover
        s.joinMode == "guardian" -> Screen.Guardian
        s.setupStep > 0 -> Screen.Setup
        else -> Screen.Welcome
    }

    /** A wallet that can be used: a finished Tapvault wallet, or a seed-phrase wallet (account made, not being protected). */
    private fun Store.usable() = setupStep >= 4 || (seedWallet && setupStep == 1)

    /** Nothing created yet for [id]: no account, no saved plan, no card read, not joining a wallet. */
    private fun isEmpty(id: String): Boolean = wallets.store(id).let {
        it.setupStep == 0 && it.setupPlan == null && it.pendingCard == null && it.joinMode == null && it.walletAccount == null
    }

    fun reloadWalletList() {
        walletList = wallets.idsOn(network).map { id ->
            val s = wallets.store(id)
            WalletEntry(id, wallets.name(id), s.walletAccount, s.usable(), id == activeId, wallets.networkOf(id))
        }
    }

    /** Spendable balance of every finished wallet, for the switcher. */
    fun loadWalletBalances() {
        val ready = walletList.filter { it.ready && it.account != null }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                ready.mapNotNull { e -> runCatching { e.id to nearFor(e.network).spendable(e.account!!).first }.getOrNull() }.toMap()
            }
            walletBalances = walletBalances + found
        }
    }

    /** Show wallet [id]. Refused while something is running, so no flow ever continues on another wallet. */
    fun switchWallet(id: String) {
        if (busy != null || id == activeId || id !in wallets.idsOn(network)) return
        dropIfEmpty(activeId)
        activate(id)
        note("switched to ${wallets.name(id)}")
    }

    /** Start a new wallet next to the existing ones: its own accounts, card and phone key. */
    fun addWallet() {
        if (busy != null) return
        if (isEmpty(activeId)) {
            // The wallet on screen hasn't been started yet: use it instead of adding another blank one.
            go(Screen.Welcome)
            return
        }
        val id = wallets.add()
        activate(id)
        note("new wallet: ${wallets.name(id)}")
    }

    /** Leave a new wallet that was never started and go back to another one. */
    fun cancelNewWallet() {
        val on = wallets.idsOn(network)
        val back = on.firstOrNull { it != activeId && wallets.store(it).usable() }
            ?: on.firstOrNull { it != activeId } ?: return
        switchWallet(back)
    }

    fun renameWallet(name: String) {
        val n = name.trim().take(24)
        if (n.isEmpty()) return
        wallets.rename(activeId, n)
        reloadWalletList()
    }

    /**
     * Settings → Network: show [n]'s wallets (the last one used there, or
     * Welcome if it has none yet). No wallet changes network; refused while
     * something runs, and mainnet only in builds that allow it.
     */
    fun switchNetwork(n: Network) {
        if (busy != null || n === network) return
        if (n.isMainnet && !mainnetEnabled) return
        // An empty wallet left behind stays: it is that network's Welcome when the user comes back.
        wallets.network = n
        activate(wallets.active)
        note("network: ${n.displayName}")
    }

    private fun activate(id: String) {
        wallets.active = id
        activeId = id
        network = wallets.networkOf(id)
        addressBook = wallets.addressBook(network)
        store = wallets.store(id)
        setupStep = store.setupStep
        seedWallet = store.seedWallet
        seedDraft = null; importFound = null; revealed = null
        wallet = null; balance = null; storageLocked = null; proposals = emptyList(); cardReport = null; offline = null
        cardBook = store.cards; holdSeconds = store.holdSeconds; backup = null
        receivers = store.receivers; integrity = Integrity.Report.decode(store.integrity)
        hasSecurityKey = store.securityKey != null
        isV02 = null
        tokens = null; foundTokens = emptyList(); tokenPrefs = store.tokenPrefs; tokensAt = 0
        outcome = null
        screen = startScreen(store)
        reloadWalletList()
        refresh()
    }

    private fun dropIfEmpty(id: String) {
        if (wallets.ids.size > 1 && isEmpty(id)) erase(id)
    }

    /** Delete [id]'s settings and phone key and take it off the list. */
    private fun erase(id: String) {
        val s = wallets.store(id)
        listOfNotNull(WalletIndex.aliasOf(s, id), WalletIndex.keyAlias(id), s.pendingAlias).distinct().forEach { a -> runCatching { KeystoreSigner.delete(a) } }
        SecurityPepper.delete(SecurityPepper.aliasFor(id))
        s.clear()
        wallets.remove(id)
        walletBalances = walletBalances - id
    }

    /** Clear the active wallet's setup and phone key but keep it in the list (Start over). */
    private fun resetCurrent() {
        listOfNotNull(WalletIndex.aliasOf(store, activeId), WalletIndex.keyAlias(activeId), store.pendingAlias).distinct().forEach { a -> runCatching { KeystoreSigner.delete(a) } }
        SecurityPepper.delete(SecurityPepper.aliasFor(activeId))
        store.clear()
        hasSecurityKey = false
        seedWallet = false
        tokens = null; foundTokens = emptyList(); tokenPrefs = store.tokenPrefs; tokensAt = 0
        setStep(0)
        wallet = null; balance = null; storageLocked = null; proposals = emptyList(); cardReport = null
        cardBook = store.cards; holdSeconds = store.holdSeconds; backup = null
        receivers = store.receivers; integrity = null
        screen = Screen.Welcome
        reloadWalletList()
    }

    /** Forget the active wallet on this phone; the other wallets stay as they are. */
    fun forgetWallet() {
        if (busy != null) return
        val gone = walletName
        erase(activeId)
        log.clear()
        val on = wallets.idsOn(network)
        val next = on.firstOrNull { wallets.store(it).usable() } ?: on.first()
        activate(next)
        if (on.size > 1 || store.setupStep > 0) outcome = Outcome(true, "$gone forgotten", "Now showing ${walletName}.")
    }
}
