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
import app.tapvault.core.NearKey
import app.tapvault.core.NearRpcException
import app.tapvault.core.Onboarding
import app.tapvault.core.ProposalInfo
import app.tapvault.core.TapvaultWallet
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

enum class Screen { Welcome, Limits, Setup, Home, Send, Activity, CardCheck, Settings, Security }

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
class BackupCandidate(val key: SignerKey.EmvCard, val recipe: CardRecipe, val network: String, val last4: String) {
    val keyHash: String = key.keyHash().hex()
    val name get() = KnownCard(keyHash, recipe, network, last4).name
}

/** A finished operation, shown as a result card. */
data class Outcome(val ok: Boolean, val title: String, val detail: String = "", val link: String? = null)

/** One wallet in the switcher. [account] is null until setup has created it. */
data class WalletEntry(val id: String, val name: String, val account: String?, val ready: Boolean, val active: Boolean)

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

    val near = NearClient()
    private var activityRef = WeakReference<MainActivity>(null)
    val phone = KeystoreSigner({ WalletIndex.keyAlias(activeId) }) { activityRef.get() }

    /** Whether the active wallet's phone key exists in the Keystore. */
    val phoneKeyExists get() = KeystoreSigner.exists(WalletIndex.keyAlias(activeId))
    val walletName get() = wallets.name(activeId)

    var screen by mutableStateOf(startScreen(store))
    var busy by mutableStateOf<String?>(null)
    var outcome by mutableStateOf<Outcome?>(null)
    var tap by mutableStateOf<TapRequest?>(null)
    var wallet by mutableStateOf<WalletState?>(null)
    var balance by mutableStateOf<BigInteger?>(null)
    var storageLocked by mutableStateOf<BigInteger?>(null)
    var proposals by mutableStateOf<List<ProposalInfo>>(emptyList())
    var cardReport by mutableStateOf<CardReport?>(null)

    /** Why the last refresh failed (e.g. no connection), or null when it worked. */
    var offline by mutableStateOf<String?>(null)
    var setupStep by mutableStateOf(store.setupStep)
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
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Throwable) {
                val msg = (e as? java.util.concurrent.ExecutionException)?.cause?.message ?: e.message ?: e.toString()
                note("error: $msg")
                outcome = Outcome(false, "That didn't work", msg)
            } finally {
                busy = null
                tap = null
            }
        }
    }

    private fun progress(s: String) {
        busy = s
        note(s)
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

    fun walletClient(s: Store = store): TapvaultWallet {
        val acct = s.walletAccount ?: throw IllegalStateException("no wallet yet")
        return TapvaultWallet(near, acct, s.gasAccount!!, NearKey.fromSecret(s.gasKey!!))
    }

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while a pull-to-refresh the user started is running. */
    var pulling by mutableStateOf(false)
        private set

    /** Pull-to-refresh: reload now and show the indicator until it's done. */
    fun pullRefresh() {
        if (store.setupStep < 4 || pulling) return
        pulling = true
        viewModelScope.launch {
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
        if (st.setupStep < 4) return
        if (!refreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val w = walletClient(st)
                    val s = w.state()
                    val (b, locked) = near.spendable(w.account)
                    val ps = w.proposals()
                    // A card stored by an older build has no key hash yet: it's the card setup added.
                    val bound = st.cards.bind(s)?.also { st.cards = it; note("card list migrated") }
                    // Integrity check at most every 6 h; then remember what the user has now seen (no alert for it).
                    val report = Integrity.Report.decode(st.integrity)
                        .let { r -> if (r == null || System.currentTimeMillis() / 1000 - r.atSecs > 6 * 3600) runCatching { integrityNow(w, s, id, st) }.getOrNull() ?: r else r }
                    st.watchMemory = Watch.memoryOf(s, ps, report?.ok ?: true).encode()
                    // The user may have switched wallets meanwhile: never show one wallet's state under another.
                    withContext(Dispatchers.Main) {
                        if (activeId == id) { wallet = s; balance = b; storageLocked = locked; proposals = ps; offline = null; bound?.let { cardBook = it }; integrity = report }
                    }
                }
            } catch (e: Exception) {
                note("refresh failed: ${e.message}")
                if (activeId == id) offline = "Can't reach NEAR testnet right now. Showing the last known state; retrying."
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

    private fun sponsor(): Onboarding.Sponsor? =
        BuildConfig.SPONSOR_ACCOUNT.takeIf { it.isNotBlank() && BuildConfig.SPONSOR_KEY.isNotBlank() }
            ?.let { acct ->
                runCatching { Onboarding.Sponsor(acct, NearKey.fromSecret(BuildConfig.SPONSOR_KEY)) }
                    .onFailure { note("sponsor key in this build is unreadable; using the faucet only") }
                    .getOrNull()
            }

    /** Whether this build carries a readable sponsor account (no side effects: used by the UI). */
    val hasSponsor get() = BuildConfig.SPONSOR_ACCOUNT.isNotBlank() && runCatching { NearKey.fromSecret(BuildConfig.SPONSOR_KEY) }.isSuccess

    /** Throws a readable error unless this phone can read a card right now. */
    private fun requireNfc() {
        val act = activity ?: throw IllegalStateException("app is not in the foreground")
        if (!act.hasNfc) throw IllegalStateException("This phone has no NFC, so it can't read a bank card.")
        if (!act.nfcEnabled) throw IllegalStateException("Turn on NFC in Android settings (Connected devices → NFC), then try again.")
    }

    fun beginSetup(instant: String, daily: String) {
        val i = runCatching { Near.parse(instant.trim().replace(',', '.')) }.getOrNull()
        val d = runCatching { Near.parse(daily.trim().replace(',', '.')) }.getOrNull()
        val problem = when {
            i == null || d == null || i.signum() <= 0 || d.signum() <= 0 -> "Enter both limits as amounts like 1 or 0.5."
            i > d -> "The per-day limit must be at least the at-once limit."
            !hasNfc -> "This phone has no NFC, so it can't read a bank card."
            !nfcEnabled -> "Turn on NFC in Android settings (Connected devices → NFC) first."
            else -> null
        }
        if (problem != null) {
            outcome = Outcome(false, "Can't start yet", problem)
            return
        }
        store.instantNear = instant.trim().replace(',', '.')
        store.dailyNear = daily.trim().replace(',', '.')
        cardReport = null
        go(Screen.Setup)
        continueSetup()
    }

    fun continueSetup() = run("Setting up") {
        if (PendingCard.decode(store.pendingCard) == null && store.setupStep < 3) requireNfc()
        val ob = Onboarding(near)
        if (!phoneKeyExists) {
            progress("Creating your phone key in secure hardware")
            try {
                KeystoreSigner.create(getApplication(), WalletIndex.keyAlias(activeId))
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Set up a screen lock (PIN, pattern or password) on this phone first, then continue. (${e.message})")
            }
            note("phone key stored in ${phone.hardwareLabel}")
        }
        if (store.setupStep < 1) {
            progress("Creating your wallet account on NEAR testnet")
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
            ) null else throw IllegalStateException("Can't reach NEAR testnet right now (${e.message?.take(120)}). Try Continue setup again.")
        }

        // Read the card before deploying anything: a card that can't sign
        // stops setup while undoing it is still cheap (Start over).
        var pending = PendingCard.decode(store.pendingCard)
        if (pending == null && store.setupStep < 3 && onChain?.card() == null) {
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
                progress("Approve with your fingerprint to create the wallet")
                phone.reason = "Create your Tapvault wallet"
                val wasm = getApplication<Application>().assets.open("tapvault_contract.wasm").use { it.readBytes() }
                val r = ob.deployAndInit(accounts, wasm, phone)
                note("wallet contract deployed: ${near.explorerTx(r.hash)}")
            }
            setStep(2)
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
        if (store.setupStep < 4) {
            if (onChain?.setupMode == false) {
                note("wallet already locked")
            } else {
                progress("Approve your limits to lock the wallet")
                val args = FinalizeArgs(
                    threshold = 2,
                    delays = Delays.SHORTEST,
                    limits = listOf(LimitInit(Asset.Near, Near.parse(store.instantNear), Near.parse(store.dailyNear))),
                )
                phone.reason = "Lock the wallet: up to ${store.instantNear} NEAR at once, ${store.dailyNear} NEAR a day"
                ob.finalize(accounts, phone, args)
            }
            val keys = near.accessKeys(accounts.wallet)
            note(if (keys.isEmpty()) "wallet account locked: no keys left" else "WARNING: wallet still has keys: $keys")
            setStep(4)
            store.walletKey = null
            store.pendingCard = null
        }
        withContext(Dispatchers.Main) { screen = Screen.Home; reloadWalletList() }
        refresh()
        outcome = Outcome(true, "Your wallet is ready", "Funds now move only with your phone and your card.", near.explorerAccount(accounts.wallet))
    }

    /**
     * Abandon an unfinished setup. With a sponsor configured, accounts that
     * have nothing deployed yet are deleted and their NEAR goes back to it.
     */
    fun startOver() = run("Starting over") {
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
        requireNfc()

        val w = walletClient()
        val st = w.state()
        if (st.frozen) throw IllegalStateException("The wallet is frozen. Request an unfreeze first.")
        val phoneIdx = st.phoneIndexOf(phone.publicKey) ?: throw IllegalStateException("This phone isn't a signer of the wallet")
        if (st.cards().isEmpty()) throw IllegalStateException("No card enrolled")
        require(to != w.account) { "That's this wallet's own account" }
        val (available, _) = near.spendable(w.account)
        require(amount <= available) { "The wallet only has ${Near.format(available, 4)} NEAR available to send" }
        // 64-hex implicit accounts come into existence when they receive NEAR.
        require(to.matches(Regex("^[0-9a-f]{64}$")) || near.accountExists(to)) { "$to doesn't exist on NEAR testnet" }

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
        progress("Submitting")
        rememberReceiver(to)
        val instant = st.nearLimit?.isInstant(amount) == true
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
                "This is over your instant limit. In $hours h, open Activity and tap Execute. Until then you can cancel it or freeze the wallet.",
                near.explorerTx(r.hash),
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
            st.nearLimit?.isInstant(p.transferAmount()!!) == true
        val t = tapWalletCard("Tap your card to approve: ${p.describe()}", st, ch) { s ->
            if (p.approvedBy(s.index)) "${cardName(s)} already approved this. Tap another card of this wallet." else null
        }
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
        val w = walletClient()
        val st = w.state()
        val idx = phoneIndex(st)
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
        val w = walletClient()
        val st = w.state()
        if (st.signers.size >= 8) throw IllegalStateException("This wallet already has 8 signers, the most it can have.")
        val existing = st.signers.map { it.keyHash.lowercase() }.toSet()
        val count = st.signers.size
        val svc = cards()
        val e = try {
            withCard("Hold the new card to the back of the phone") { t ->
                svc.enrol(t) { k ->
                    if (k.keyHash().hex() in existing) throw AlreadySignerException("already a signer")
                    Domain.cardEnrollChallenge(w.account, count, k.keyHash())
                }
            }
        } catch (e: AlreadySignerException) {
            throw IllegalStateException("That card is already one of this wallet's cards. Hold a different card to add it as a backup.")
        } catch (e: CardCheckFailed) {
            cardReport = e.report
            throw IllegalStateException("This card can't sign for Tapvault: ${e.message}. Try another contactless card.")
        }
        if (!provesPossession(e.key, e.proof, Domain.cardEnrollChallenge(w.account, count, e.key.keyHash()))) {
            throw IllegalStateException("The card's test signature didn't check out, so it won't be added. Tap it again, or use another card.")
        }
        val b = BackupCandidate(e.key, e.recipe, e.report.network, e.report.panLast4 ?: "????")
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
        requireNfc()
        val w = walletClient()
        val st = w.state()
        if (st.signerByKeyHash(b.keyHash) != null) throw IllegalStateException("${b.name} is already a signer of this wallet.")
        val idx = phoneIndex(st)
        val hours = hoursText(st.delays.config)
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
            w.proposeByPhone(Action.Config(listOf(ConfigOp.AddSigner(b.key))), phone, idx).also { note("proposal #$it: add ${b.name}") }
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

    // ---- integrity ----

    /** Base58 code hash of the wasm this APK ships (the one every new wallet runs). */
    private val shippedCodeHash: String by lazy {
        Integrity.codeHashOf(getApplication<Application>().assets.open("tapvault_contract.wasm").use { it.readBytes() })
    }

    private fun integrityNow(w: TapvaultWallet, s: WalletState, id: String = activeId, st: Store = store): Integrity.Report {
        // That wallet's own phone key, even if the user switched wallets meanwhile.
        val pub = runCatching { KeystoreSigner({ WalletIndex.keyAlias(id) }) { null }.publicKey }.getOrNull()
        val r = Integrity.evaluate(near.codeHash(w.account), near.accessKeys(w.account), s, pub, shippedCodeHash)
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
        val anyReady = wallets.ids.any { wallets.store(it).setupStep >= 4 }
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
        if (walletId != null && walletId != activeId && walletId in wallets.ids && busy == null) activate(walletId)
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

    private fun startScreen(s: Store) = if (s.setupStep >= 4) Screen.Home else if (s.setupStep > 0) Screen.Setup else Screen.Welcome

    /** Nothing created yet for [id]: no account, no saved plan, no card read. */
    private fun isEmpty(id: String): Boolean = wallets.store(id).let { it.setupStep == 0 && it.setupPlan == null && it.pendingCard == null }

    fun reloadWalletList() {
        walletList = wallets.ids.map { id ->
            val s = wallets.store(id)
            WalletEntry(id, wallets.name(id), s.walletAccount, s.setupStep >= 4, id == activeId)
        }
    }

    /** Spendable balance of every finished wallet, for the switcher. */
    fun loadWalletBalances() {
        val ready = walletList.filter { it.ready && it.account != null }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                ready.mapNotNull { e -> runCatching { e.id to near.spendable(e.account!!).first }.getOrNull() }.toMap()
            }
            walletBalances = walletBalances + found
        }
    }

    /** Show wallet [id]. Refused while something is running, so no flow ever continues on another wallet. */
    fun switchWallet(id: String) {
        if (busy != null || id == activeId || id !in wallets.ids) return
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
        val back = wallets.ids.firstOrNull { it != activeId && wallets.store(it).setupStep >= 4 }
            ?: wallets.ids.firstOrNull { it != activeId } ?: return
        switchWallet(back)
    }

    fun renameWallet(name: String) {
        val n = name.trim().take(24)
        if (n.isEmpty()) return
        wallets.rename(activeId, n)
        reloadWalletList()
    }

    private fun activate(id: String) {
        wallets.active = id
        activeId = id
        store = wallets.store(id)
        setupStep = store.setupStep
        wallet = null; balance = null; storageLocked = null; proposals = emptyList(); cardReport = null; offline = null
        cardBook = store.cards; holdSeconds = store.holdSeconds; backup = null
        receivers = store.receivers; integrity = Integrity.Report.decode(store.integrity)
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
        wallets.store(id).clear()
        runCatching { KeystoreSigner.delete(WalletIndex.keyAlias(id)) }
        wallets.remove(id)
        walletBalances = walletBalances - id
    }

    /** Clear the active wallet's setup and phone key but keep it in the list (Start over). */
    private fun resetCurrent() {
        store.clear()
        runCatching { KeystoreSigner.delete(WalletIndex.keyAlias(activeId)) }
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
        val next = wallets.ids.firstOrNull { wallets.store(it).setupStep >= 4 } ?: wallets.ids.first()
        activate(next)
        if (wallets.ids.size > 1 || store.setupStep > 0) outcome = Outcome(true, "$gone forgotten", "Now showing ${walletName}.")
    }
}
