package app.tapvault

import android.app.Application
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
import app.tapvault.core.Onboarding
import app.tapvault.core.ProposalInfo
import app.tapvault.core.SimCard
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
data class TapRequest(val title: String, val demo: Boolean)

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
    var proposals by mutableStateOf<List<ProposalInfo>>(emptyList())
    var cardReport by mutableStateOf<CardReport?>(null)
    var setupStep by mutableStateOf(store.setupStep)
    val log = mutableStateListOf<String>()

    private var demoTap: CompletableFuture<Unit>? = null
    private val simCard by lazy { SimCard() }

    fun attach(a: MainActivity) {
        activityRef = WeakReference(a)
    }

    private val activity get() = activityRef.get()

    val hasNfc get() = activity?.hasNfc == true
    val nfcEnabled get() = activity?.nfcEnabled == true

    private fun capks() = CapkStore.load().let { if (store.demoCard) it.plus(SimCard.DEMO_CA) else it }
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

    /**
     * Wait for a card tap and run [block] against it while it is held. The
     * demo card answers the same commands without hardware.
     */
    private fun <T> withCard(title: String, block: (CardTransceiver) -> T): T {
        repeat(3) { attempt ->
            tap = TapRequest(title, store.demoCard)
            try {
                return if (store.demoCard) {
                    val f = CompletableFuture<Unit>().also { demoTap = it }
                    f.get(2, TimeUnit.MINUTES)
                    block(simCard)
                } else {
                    val act = activity ?: throw IllegalStateException("app is not in the foreground")
                    if (!act.hasNfc) throw IllegalStateException("This phone has no NFC. Use the demo card (Settings).")
                    if (!act.nfcEnabled) throw IllegalStateException("Turn on NFC in Android settings, then try again.")
                    val tag = act.awaitTag().get(2, TimeUnit.MINUTES)
                    IsoDepTransceiver(tag).use { block(it) }
                }
            } catch (e: android.nfc.TagLostException) {
                note("card moved away (attempt ${attempt + 1}); hold it still")
            } catch (e: java.io.IOException) {
                note("card link error: ${e.message}; tap again")
            } finally {
                tap = null
            }
        }
        throw IllegalStateException("Couldn't read the card. Hold it flat against the back of the phone for two seconds.")
    }

    fun demoTapNow() {
        demoTap?.complete(Unit)
    }

    fun cancelTap() {
        demoTap?.completeExceptionally(IllegalStateException("Tap cancelled"))
        activity?.cancelTag()
    }

    // ---- wallet ----

    fun walletClient(): TapvaultWallet {
        val acct = store.walletAccount ?: throw IllegalStateException("no wallet yet")
        return TapvaultWallet(near, acct, store.gasAccount!!, NearKey.fromSecret(store.gasKey!!))
    }

    fun refresh() {
        if (store.setupStep < 4) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val w = walletClient()
                    val s = w.state()
                    val b = w.balance()
                    val ps = w.proposals()
                    withContext(Dispatchers.Main) { wallet = s; balance = b; proposals = ps }
                }
            } catch (e: Exception) {
                note("refresh failed: ${e.message}")
            }
        }
    }

    fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                if (screen == Screen.Home || screen == Screen.Activity) refresh()
                delay(8_000)
            }
        }
    }

    // ---- setup ----

    fun beginSetup(demo: Boolean, instant: String, daily: String) {
        store.demoCard = demo
        store.instantNear = instant
        store.dailyNear = daily
        go(Screen.Setup)
        continueSetup()
    }

    fun continueSetup() = run("Setting up") {
        val ob = Onboarding(near)
        if (!KeystoreSigner.exists()) {
            progress("Creating your phone key in secure hardware")
            KeystoreSigner.create()
            note("phone key stored in ${phone.hardwareLabel}")
        }
        if (store.setupStep < 1) {
            progress("Creating your wallet account on NEAR testnet")
            val a = ob.createAccounts()
            store.walletAccount = a.wallet; store.walletKey = a.walletKey.secretString()
            store.gasAccount = a.gas; store.gasKey = a.gasKey.secretString()
            setStep(1)
            note("wallet ${a.wallet}, fee account ${a.gas}")
        }
        val accounts = Onboarding.Accounts(
            store.walletAccount!!, NearKey.fromSecret(store.walletKey!!), store.gasAccount!!, NearKey.fromSecret(store.gasKey!!),
        )
        if (store.setupStep < 2) {
            progress("Approve with your fingerprint to create the wallet")
            phone.reason = "Create your Tapvault wallet"
            val wasm = getApplication<Application>().assets.open("tapvault_contract.wasm").use { it.readBytes() }
            val r = ob.deployAndInit(accounts, wasm, phone)
            setStep(2)
            note("wallet contract deployed: ${near.explorerTx(r.hash)}")
        }
        if (store.setupStep < 3) {
            progress("Add your card")
            val svc = cards()
            val enrol = withCard("Hold your bank card to the back of the phone to add it") { t ->
                svc.enrol(t) { k -> Domain.cardEnrollChallenge(accounts.wallet, 1, k.keyHash()) }
            }
            cardReport = enrol.report
            note("card ${enrol.report.network} ****${enrol.report.panLast4} enrolled (${enrol.report.iccKeyBits}-bit key)")
            progress("Approve adding card ****${enrol.report.panLast4}")
            phone.reason = "Add ${enrol.report.network} card ****${enrol.report.panLast4} as a signer"
            ob.addCard(accounts, phone, 1, enrol)
            setStep(3)
        }
        if (store.setupStep < 4) {
            progress("Approve your limits to lock the wallet")
            val args = FinalizeArgs(
                threshold = 2,
                delays = Delays.SHORTEST,
                limits = listOf(LimitInit(Asset.Near, Near.parse(store.instantNear), Near.parse(store.dailyNear))),
            )
            phone.reason = "Lock the wallet: up to ${store.instantNear} NEAR at once, ${store.dailyNear} NEAR a day"
            ob.finalize(accounts, phone, args)
            val keys = near.accessKeys(accounts.wallet)
            note(if (keys.isEmpty()) "wallet account locked: no keys left" else "WARNING: wallet still has keys: $keys")
            store.walletKey = null
            setStep(4)
        }
        withContext(Dispatchers.Main) { screen = Screen.Home }
        refresh()
        outcome = Outcome(true, "Your wallet is ready", "Funds now move only with your phone and your card.", near.explorerAccount(accounts.wallet))
    }

    private fun setStep(n: Int) {
        store.setupStep = n
        setupStep = n
    }

    // ---- send ----

    fun send(receiver: String, amountNear: String) = run("Sending") {
        val amount = try { Near.parse(amountNear.trim()) } catch (e: Exception) { throw IllegalArgumentException("Enter an amount like 0.5") }
        require(amount.signum() > 0) { "Amount must be more than zero" }
        val to = receiver.trim().lowercase()
        require(to.matches(Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")) && to.length in 2..64) { "That isn't a NEAR account id" }

        val w = walletClient()
        val st = w.state()
        if (st.frozen) throw IllegalStateException("The wallet is frozen. Request an unfreeze first.")
        val phoneIdx = st.phoneIndexOf(phone.publicKey) ?: throw IllegalStateException("This phone isn't a signer of the wallet")
        val card = st.card() ?: throw IllegalStateException("No card enrolled")

        progress("Approve on your phone")
        phone.reason = "Send ${Near.format(amount)} NEAR to $to"
        val id = w.proposeByPhone(Action.Transfer(Asset.Near, amount, to), phone, phoneIdx)
        note("proposal #$id created")
        progress("Waiting for the card challenge")
        val ch = w.awaitCardChallenge(id)
        val svc = cards()
        val sig = withCard("Tap your card to approve sending ${Near.format(amount)} NEAR") { t -> svc.sign(t, card.cardKey()!!, ch) }
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
                "This is over your instant limit, so it can execute in $hours h. Until then you can cancel it or freeze the wallet.",
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
        val sig = withCard("Tap your card to approve: ${p.describe()}") { t -> svc.sign(t, card.cardKey()!!, ch) }
        val r = w.approveCard(p.id, card.index, sig)
        outcome = Outcome(true, "Approved", p.describe(), near.explorerTx(r.hash))
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
        val r = w.freezeByCard(card.index) { ch -> withCard("Tap your card to FREEZE the wallet") { t -> svc.sign(t, card.cardKey()!!, ch) } }
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
        val sig = withCard("Tap your card to approve unfreezing") { t -> svc.sign(t, card.cardKey()!!, ch) }
        val r = w.approveCard(id, card.index, sig)
        outcome = Outcome(true, "Unfreeze approved", "It can execute in ${st.delays.config / 3600} h (Activity tab).", near.explorerTx(r.hash))
        refresh()
    }

    // ---- card check ----

    fun checkCard() = run("Checking card") {
        cardReport = null
        val svc = cards()
        val report = withCard("Hold the card you want to check") { t ->
            try { svc.inspect(t) } catch (e: CardCheckFailed) { e.report }
        }
        cardReport = report
        note("card check: ${report.network} DDA=${report.ddaSupported}")
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
