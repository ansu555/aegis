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
import app.tapvault.core.Asset
import app.tapvault.core.CapkStore
import app.tapvault.core.CardCheckFailed
import app.tapvault.core.CardRecipe
import app.tapvault.core.WrongCardException
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

enum class Screen { Welcome, Setup, Home, Send, Activity, CardCheck, Settings }

/** What the "tap your card" sheet shows. */
/** A card tap the UI is waiting for; [reading] once a card is in the field. */
data class TapRequest(val title: String, val reading: Boolean = false, val hint: String? = null)

/** A finished operation, shown as a result card. */
data class Outcome(val ok: Boolean, val title: String, val detail: String = "", val link: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val near = NearClient()
    private var activityRef = WeakReference<MainActivity>(null)
    val phone = KeystoreSigner { activityRef.get() }

    var screen by mutableStateOf(if (store.setupStep >= 4) Screen.Home else if (store.setupStep > 0) Screen.Setup else Screen.Welcome)
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

    private fun recipe() = CardRecipe.decode(store.cardRecipe)
    private fun cards() = CardService(capks())

    fun note(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add(0, "$t  $s")
        if (log.size > 200) log.removeAt(log.size - 1)
    }

    fun go(s: Screen) {
        outcome = null
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
    private fun <T> withCard(title: String, block: (CardTransceiver) -> T): T {
        if (store.legacyDemoWallet) {
            throw IllegalStateException("This wallet was set up with the old simulated demo card. Forget it in Settings and set up again with your bank card.")
        }
        var hint: String? = null
        repeat(3) { attempt ->
            val act = activity ?: throw IllegalStateException("app is not in the foreground")
            if (!act.hasNfc) throw IllegalStateException("This phone has no NFC, so it can't read a bank card.")
            if (!act.nfcEnabled) throw IllegalStateException("Turn on NFC in Android settings (Connected devices → NFC), then try again.")
            tap = TapRequest(title, hint = hint)
            try {
                val tag = awaitTag().get(2, TimeUnit.MINUTES)
                tap = TapRequest(title, reading = true)
                return IsoDepTransceiver(tag).use { block(it) }.also { activity?.vibrate() }
            } catch (e: java.util.concurrent.TimeoutException) {
                throw IllegalStateException("No card was tapped. Try again and hold the card flat against the back of the phone.")
            } catch (e: java.util.concurrent.CancellationException) {
                throw IllegalStateException("Card tap cancelled")
            } catch (e: android.nfc.TagLostException) {
                note("card moved away (attempt ${attempt + 1}); hold it still")
                hint = "The card moved away too soon. Tap again and keep it still until the phone vibrates."
            } catch (e: java.io.IOException) {
                note("card link error: ${e.message}; tap again")
                hint = "The card connection dropped. Tap again and keep it still until the phone vibrates."
            } catch (e: SecurityException) {
                // The tag object went stale (the reader was re-armed in between).
                note("stale card connection: ${e.message}; tap again")
                hint = "Tap the card again and keep it still until the phone vibrates."
            } catch (e: WrongCardException) {
                note("a different card was tapped")
                hint = "That's not the card in this wallet. Tap the card you added."
            } finally {
                pendingTag = null
                tap = null
            }
        }
        throw IllegalStateException("Couldn't read the card. Hold it flat against the back of the phone, near the camera, for 3 seconds.")
    }

    fun cancelTap() {
        pendingTag?.cancel(true)
    }

    // ---- wallet ----

    fun walletClient(): TapvaultWallet {
        val acct = store.walletAccount ?: throw IllegalStateException("no wallet yet")
        return TapvaultWallet(near, acct, store.gasAccount!!, NearKey.fromSecret(store.gasKey!!))
    }

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var autoRefresh: kotlinx.coroutines.Job? = null

    /** Reload wallet state; skipped while another refresh is still running. */
    fun refresh() {
        if (store.setupStep < 4) return
        if (!refreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val w = walletClient()
                    val s = w.state()
                    val (b, locked) = near.spendable(w.account)
                    val ps = w.proposals()
                    withContext(Dispatchers.Main) { wallet = s; balance = b; storageLocked = locked; proposals = ps; offline = null }
                }
            } catch (e: Exception) {
                note("refresh failed: ${e.message}")
                offline = "Can't reach NEAR testnet right now. Showing the last known state; retrying."
            } finally {
                refreshing.set(false)
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
            ?.let { Onboarding.Sponsor(it, NearKey.fromSecret(BuildConfig.SPONSOR_KEY)) }

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
        store.instantNear = Near.format(i!!)
        store.dailyNear = Near.format(d!!)
        go(Screen.Setup)
        continueSetup()
    }

    fun continueSetup() = run("Setting up") {
        requireNfc()
        val ob = Onboarding(near)
        if (!KeystoreSigner.exists()) {
            progress("Creating your phone key in secure hardware")
            try {
                KeystoreSigner.create(getApplication())
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
            store.cardRecipe = enrol.recipe.encode()
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
        withContext(Dispatchers.Main) { screen = Screen.Home }
        refresh()
        outcome = Outcome(true, "Your wallet is ready", "Funds now move only with your phone and your card.", near.explorerAccount(accounts.wallet))
    }

    /**
     * Abandon an unfinished setup. With a sponsor configured, accounts that
     * have nothing deployed yet are deleted and their NEAR goes back to it.
     */
    fun startOver() = run("Starting over") {
        val sp = sponsor()
        if (sp != null) {
            val wallet = store.walletAccount
            val wk = store.walletKey
            if (store.setupStep <= 1 && wallet != null && wk != null) {
                runCatching { near.deleteAccount(wallet, NearKey.fromSecret(wk), sp.account) }
                    .onSuccess { note("deleted $wallet; its NEAR went back to ${sp.account}") }
                    .onFailure { note("couldn't delete $wallet: ${it.message}") }
            }
            val gas = store.gasAccount
            val gk = store.gasKey
            if (gas != null && gk != null) {
                runCatching { near.deleteAccount(gas, NearKey.fromSecret(gk), sp.account) }
                    .onFailure { note("couldn't delete $gas: ${it.message}") }
            }
        }
        withContext(Dispatchers.Main) { resetApp() }
    }

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
        val card = st.card() ?: throw IllegalStateException("No card enrolled")
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
                !p.approvedBy(card.index) && !p.stale && (p.cardDeadlineTs ?: Long.MAX_VALUE) > now + 30
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
        val svc = cards()
        val sig = try {
            withCard("Tap your card to approve sending ${Near.format(amount)} NEAR") { t -> svc.sign(t, card.cardKey()!!, ch, recipe()) }
        } catch (e: Exception) {
            refresh()
            throw IllegalStateException("${e.message}\n\nPayment #$id is saved and waits 10 minutes for your card: send the same amount again, or approve or cancel it in Activity.")
        }
        progress("Submitting")
        val instant = st.nearLimit?.isInstant(amount) == true
        if (instant) {
            val r = w.approveCardAndExecute(id, card.index, sig)
            note("sent: ${near.explorerTx(r.hash)}")
            outcome = Outcome(true, "Sent ${Near.format(amount)} NEAR", "to $to", near.explorerTx(r.hash))
        } else {
            val r = w.approveCard(id, card.index, sig)
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
        val card = st.card()!!
        val ch = p.cardChallenge ?: w.awaitCardChallenge(p.id)
        val svc = cards()
        val sig = withCard("Tap your card to approve: ${p.describe()}") { t -> svc.sign(t, card.cardKey()!!, ch, recipe()) }
        // A NEAR payment the phone already approved can go out in the same transaction if it's within the limits.
        val phoneIdx = st.phoneIndexOf(phone.publicKey)
        val canRunNow = !st.frozen && p.isNearTransfer && phoneIdx != null && p.approvedBy(phoneIdx) &&
            st.nearLimit?.isInstant(p.transferAmount()!!) == true
        val r = if (canRunNow) w.approveCardAndExecute(p.id, card.index, sig) else w.approveCard(p.id, card.index, sig)
        outcome = Outcome(true, if (canRunNow) "Approved and sent" else "Approved", p.describe(), near.explorerTx(r.hash))
        refresh()
    }

    fun approveWithPhone(p: ProposalInfo) = run("Approving") {
        val w = walletClient()
        val idx = w.state().phoneIndexOf(phone.publicKey)!!
        phone.reason = "Approve: ${p.describe()}"
        val r = w.approvePhone(p.id, phone, idx)
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
        val idx = w.state().phoneIndexOf(phone.publicKey)!!
        phone.reason = "Cancel: ${p.describe()}"
        val r = w.cancelByPhone(p.id, phone, idx)
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
        val idx = w.state().phoneIndexOf(phone.publicKey)!!
        phone.reason = "FREEZE the wallet (blocks all transfers)"
        val r = w.freezeByPhone(phone, idx)
        outcome = Outcome(true, "Wallet frozen", "No transfers can go out until you unfreeze (a delayed change).", near.explorerTx(r.hash))
        refresh()
    }

    fun freezeWithCard() = run("Freezing") {
        val w = walletClient()
        val card = w.state().card()!!
        val svc = cards()
        val r = w.freezeByCard(card.index) { ch -> withCard("Tap your card to FREEZE the wallet") { t -> svc.sign(t, card.cardKey()!!, ch, recipe()) } }
        outcome = Outcome(true, "Wallet frozen", "Frozen with a card tap. Unfreezing needs your phone and card, plus a delay.", near.explorerTx(r.hash))
        refresh()
    }

    fun requestUnfreeze() = run("Requesting unfreeze") {
        val w = walletClient()
        val st = w.state()
        val idx = st.phoneIndexOf(phone.publicKey)!!
        val card = st.card()!!
        phone.reason = "Unfreeze the wallet (takes effect after ${st.delays.config / 3600} h)"
        val id = w.proposeByPhone(Action.Config(listOf(ConfigOp.Unfreeze)), phone, idx)
        val ch = w.awaitCardChallenge(id)
        val svc = cards()
        val sig = withCard("Tap your card to approve unfreezing") { t -> svc.sign(t, card.cardKey()!!, ch, recipe()) }
        val r = w.approveCard(id, card.index, sig)
        outcome = Outcome(true, "Unfreeze approved", "It can execute in ${st.delays.config / 3600} h (Activity tab).", near.explorerTx(r.hash))
        refresh()
    }

    // ---- card check ----

    fun checkCard() = run("Checking card") {
        cardReport = null
        val svc = cards()
        val report = withCard("Hold the card you want to check") { t -> svc.inspect(t) }
        cardReport = report
        note("card check: ${report.network} ${report.signsWith ?: "can't sign"}")
    }

    fun resetApp() {
        store.clear()
        runCatching { KeystoreSigner.delete() }
        setStep(0)
        wallet = null; balance = null; proposals = emptyList(); cardReport = null
        log.clear()
        screen = Screen.Welcome
    }
}
