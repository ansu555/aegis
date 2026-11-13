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

enum class Screen { Welcome, Limits, Setup, Home, Send, Activity, CardCheck, Settings }

/** What the "tap your card" sheet shows. */
/** A card tap the UI is waiting for; [reading] once a card is in the field. */
data class TapRequest(val title: String, val reading: Boolean = false, val hint: String? = null)

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

    private fun recipe() = CardRecipe.decode(store.cardRecipe)
    private fun cards() = CardService(capks())

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
    private fun <T> withCard(title: String, block: (CardTransceiver) -> T): T {
        if (store.legacyDemoWallet) {
            throw IllegalStateException("This wallet was set up with the old simulated demo card. Forget it in Settings and set up again with your bank card.")
        }
        var hint: String? = null
        var wrongCard = false
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
                hint = "The card moved away too soon. Tap again and keep it still until this screen closes."
            } catch (e: java.io.IOException) {
                note("card link error: ${e.message}; tap again")
                hint = "The card connection dropped. Tap again and keep it still until this screen closes."
            } catch (e: SecurityException) {
                // The tag object went stale (the reader was re-armed in between).
                note("stale card connection: ${e.message}; tap again")
                hint = "Tap the card again and keep it still until this screen closes."
            } catch (e: WrongCardException) {
                note("a different card was tapped")
                hint = "That's not the card in this wallet. Tap the card you added."
                wrongCard = true
            } finally {
                pendingTag = null
                tap = null
            }
        }
        if (wrongCard) throw IllegalStateException("That's not the card in this wallet. Use the card you added during setup.")
        throw IllegalStateException("Couldn't read the card. Hold it flat against the back of the phone, near the camera, for 3 seconds.")
    }

    fun cancelTap() {
        pendingTag?.cancel(true)
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
                    // The user may have switched wallets meanwhile: never show one wallet's state under another.
                    withContext(Dispatchers.Main) {
                        if (activeId == id) { wallet = s; balance = b; storageLocked = locked; proposals = ps; offline = null }
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
            store.cardRecipe = enrol.recipe.encode()
            store.cardNetwork = enrol.report.network
            store.cardLast4 = enrol.report.panLast4
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
        val sent = if (instant) approveAndMaybeExecute(w, id, card.index, sig) else null
        if (sent != null) {
            note("sent: ${near.explorerTx(sent.hash)}")
            outcome = Outcome(true, "Sent ${Near.format(amount)} NEAR", "to $to", near.explorerTx(sent.hash))
        } else if (instant) {
            note("approved #$id; the contract didn't count it as instant, so it waits")
            outcome = Outcome(true, "Approved, waiting", "The wallet didn't treat this as within your limits (the phone's clock may be off). Execute it from Activity when it's ready.")
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
        // A NEAR payment the phone already approved can go out in the same transaction if it's within the limits.
        val phoneIdx = phoneIndex(st)
        val canRunNow = !st.frozen && p.isNearTransfer && p.approvedBy(phoneIdx) &&
            st.nearLimit?.isInstant(p.transferAmount()!!) == true
        val sig = withCard("Tap your card to approve: ${p.describe()}") { t -> svc.sign(t, card.cardKey()!!, ch, recipe()) }
        val sent = if (canRunNow) approveAndMaybeExecute(w, p.id, card.index, sig) else null
        val r = sent ?: w.approveCard(p.id, card.index, sig)
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
        val idx = phoneIndex(w.state())
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
        val idx = phoneIndex(w.state())
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
        val idx = phoneIndex(st)
        val card = st.card()!!
        requireNfc()
        val now = System.currentTimeMillis() / 1000
        val open = w.proposals().firstOrNull { p ->
            !p.isTransfer && p.describe().contains("Unfreeze") && p.approvedBy(idx) && !p.approvedBy(card.index) && !p.stale &&
                (p.cardDeadlineTs ?: Long.MAX_VALUE) > now + 30
        }
        val id = open?.id ?: run {
            phone.reason = "Unfreeze the wallet (takes effect after ${st.delays.config / 3600} h)"
            w.proposeByPhone(Action.Config(listOf(ConfigOp.Unfreeze)), phone, idx)
        }
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
