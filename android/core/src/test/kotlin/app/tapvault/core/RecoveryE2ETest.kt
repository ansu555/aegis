package app.tapvault.core

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 3 against the live testnet (TAPVAULT_TESTNET=1):
 *
 * 1. Setup with a backup card: two cards are added during setup (no delay),
 *    then finalize with threshold 2.
 * 2. Recover on a new phone: a second software key with its own fee
 *    account; card 1 proposes the recovery change (remove the old phone,
 *    add the new one) without the phone knowing beforehand which card is
 *    tapped, then cards 1 and 2 approve after the challenge is sealed.
 *    Executing now is refused: cards alone wait the recovery delay (24 h).
 * 3. The old phone cancels it: the defence against stolen cards.
 * 4. Guardian: the old phone proposes adding a guardian key from its code,
 *    a card approves, and it waits the config delay.
 *
 * Creates three testnet accounts: the wallet and its fee account (faucet or
 * sponsor), and the new phone's fee account (faucet, else a sub-account of
 * the first fee account).
 *
 *   TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*RecoveryE2E*' --rerun
 */
class RecoveryE2ETest {
    private fun step(s: String) = println("[recovery] $s")

    private fun tap(cards: CardService, st: WalletState, card: CardTransceiver, challenge: ByteArray): Pair<Int, CardSig> {
        val held = cards.identify(card, st.cards().map { CardCandidate(it.keyHash, it.cardKey()!!, null) })
        held.stillThere()
        return st.signerByKeyHash(held.card.keyHash)!!.index to held.sign(challenge)
    }

    private fun refusedEarly(r: Result<*>) {
        val msg = r.exceptionOrNull()?.message
        assertTrue(msg?.contains("Delay has not elapsed") == true, "got $msg")
        step("refused as expected: $msg")
    }

    @Test
    fun recoverOnANewPhoneOnTestnet() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") {
            println("skipped: set TAPVAULT_TESTNET=1 to run against testnet")
            return
        }
        val wasm = File(System.getenv("TAPVAULT_WASM") ?: fail("TAPVAULT_WASM not set")).readBytes()
        val near = NearClient()
        val oldPhone = SoftP256Signer()
        val profile = SimCard.Profile.valueOf(System.getenv("TAPVAULT_CARD_PROFILE") ?: "DDA")
        val card1 = SimCard(profile)
        val card2 = SimCard(profile, TestCards.random(), SimCard.PAN2)
        val cards = CardService(CapkStore.load().plus(SimCard.TEST_CA))
        val ob = Onboarding(near)
        val sponsor = System.getenv("TAPVAULT_SPONSOR_ACCOUNT")?.let { acct ->
            System.getenv("TAPVAULT_SPONSOR_KEY")?.let { Onboarding.Sponsor(acct, NearKey.fromSecret(it)) }
        }

        // ---- 1. setup with a backup card ----
        val a = ob.createAccounts("tv-e2e", sponsor) { step(it) }
        step("wallet ${a.wallet}, gas ${a.gas}")
        ob.deployAndInit(a, wasm, oldPhone)
        ob.addCard(a, oldPhone, 1, cards.enrol(card1) { k -> Domain.cardEnrollChallenge(a.wallet, 1, k.keyHash()) })
        ob.addCard(a, oldPhone, 2, cards.enrol(card2) { k -> Domain.cardEnrollChallenge(a.wallet, 2, k.keyHash()) })
        step("setup added two cards with no delay")
        ob.finalize(a, oldPhone, FinalizeArgs(2, Delays.SHORTEST, listOf(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO))))
        assertTrue(near.accessKeys(a.wallet).isEmpty())
        val old = TapvaultWallet(near, a.wallet, a.gas, a.gasKey)
        val st0 = old.state()
        assertEquals(3, st0.signers.size)
        assertEquals(2, st0.cards().size)
        step("finalized: phone + 2 cards, threshold 2, recovery delay ${st0.delays.recovery / 3600} h")

        // ---- 2. recover on a new phone ----
        val newPhone = SoftP256Signer()
        val feePlan = Onboarding.Plan.random()
        // The faucet allows about 10 accounts per IP per 12 h; when it refuses, the old fee account sponsors this one.
        val newGas = ob.createFeeAccount("tv-e2e", sponsor ?: Onboarding.Sponsor(a.gas, a.gasKey), feePlan) { step(it) }
        step("new phone's fee account $newGas")
        val w = TapvaultWallet(near, a.wallet, newGas, feePlan.gasKey)
        val plan = Recovery.plan(w.state(), newPhone.signerKey())
        step("recovery change: ${plan.action.describe()}")
        var proposer = -1
        val id = w.proposeWithCard(plan.action) { ch -> tap(cards, w.state(), card1, ch).also { proposer = it.first } }
        step("card 1 (signer $proposer) proposed #$id from the new phone")
        val p0 = w.proposal(id)!!
        assertEquals(0, p0.approvals, "a card's propose is not an approval")
        assertTrue(Recovery.isRecovery(p0, w.state()))
        val ch = w.awaitCardChallenge(id)
        val st = w.state()
        for ((n, c) in listOf(1 to card1, 2 to card2)) {
            val (idx, sig) = tap(cards, st, c, ch)
            w.approveCard(id, idx, sig)
            step("card $n approved")
        }
        val p = w.proposal(id)!!
        assertTrue(p.readyTs != null, "two card approvals reach the threshold")
        refusedEarly(runCatching { w.execute(id) })

        // ---- 3. the old phone cancels ----
        var prompt: Action? = null
        old.cancelByPhone(id, oldPhone, st0.phoneIndexOf(oldPhone.publicKey)!!) { prompt = it }
        assertTrue(prompt is Action.Config, "the cancel prompt saw the parsed recovery change")
        assertEquals(null, old.proposal(id))
        step("the old phone cancelled the recovery")

        // ---- 4. guardian ----
        val guardian = SoftP256Signer()
        val offer = Guardian.decode(Guardian.encode(a.wallet, guardian.publicKey))
        val add = Guardian.addAction(a.wallet, old.state(), offer)
        val gid = old.proposeByPhone(add, oldPhone, old.state().phoneIndexOf(oldPhone.publicKey)!!)
        val (cidx, csig) = tap(cards, old.state(), card2, old.awaitCardChallenge(gid))
        old.approveCard(gid, cidx, csig)
        step("guardian proposal #$gid approved by phone + card 2")
        refusedEarly(runCatching { old.execute(gid) })
        step("done. wallet: ${near.explorerAccount(a.wallet)}")
    }
}
