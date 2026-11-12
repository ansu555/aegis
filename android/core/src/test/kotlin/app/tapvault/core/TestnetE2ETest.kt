package app.tapvault.core

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole flow against the live NEAR testnet, using the same client code
 * as the app and the real deployed contract code. Test stand-ins: a software
 * P-256 key in place of the phone's Keystore key, and a simulated card speaking
 * real APDUs in place of the NFC card (TAPVAULT_CARD_PROFILE picks how it signs:
 * DDA, VISA_FDDA or MASTERCARD_CDA). Creates fresh testnet accounts.
 *
 *   TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*TestnetE2E*'
 */
class TestnetE2ETest {
    private fun step(s: String) = println("[e2e] $s")

    @Test
    fun walletLifecycleOnTestnet() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") {
            println("skipped: set TAPVAULT_TESTNET=1 to run against testnet")
            return
        }
        val wasm = File(System.getenv("TAPVAULT_WASM") ?: fail("TAPVAULT_WASM not set")).readBytes()
        val near = NearClient()
        val phone = SoftP256Signer()
        val card = SimCard(SimCard.Profile.valueOf(System.getenv("TAPVAULT_CARD_PROFILE") ?: "DDA"))
        val cards = CardService(CapkStore.load().plus(SimCard.TEST_CA))
        val ob = Onboarding(near)

        step("creating faucet accounts")
        val sponsor = System.getenv("TAPVAULT_SPONSOR_ACCOUNT")?.let { acct ->
            System.getenv("TAPVAULT_SPONSOR_KEY")?.let { Onboarding.Sponsor(acct, NearKey.fromSecret(it)) }
        }
        val a = ob.createAccounts("tv-e2e", sponsor) { step(it) }
        step("wallet ${a.wallet}, gas ${a.gas}")

        step("deploy + new (phone signs the create message)")
        near.explorerTx(ob.deployAndInit(a, wasm, phone).hash).let(::step)

        step("enrol the card: read its certificate chain and sign the enrolment challenge in one tap")
        val enrol = cards.enrol(card) { k -> Domain.cardEnrollChallenge(a.wallet, 1, k.keyHash()) }
        enrol.report.text().lines().filter { it.isNotBlank() }.forEach { step("  $it") }
        val recipe = enrol.recipe
        step("card signs with ${recipe.method}; terminal data ${enrol.key.terminalDataLen} bytes, UN at ${enrol.key.unOffset}")
        ob.addCard(a, phone, 1, enrol)

        step("finalize: threshold 2, limits 1 NEAR instant / 2 NEAR per day, shortest delays")
        val args = FinalizeArgs(2, Delays.SHORTEST, listOf(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO)))
        ob.finalize(a, phone, args)
        assertTrue(near.accessKeys(a.wallet).isEmpty(), "wallet account must have no keys after finalize")
        step("wallet account has no access keys: only the contract can move funds")

        val w = TapvaultWallet(near, a.wallet, a.gas, a.gasKey)
        val st = w.state()
        assertEquals(false, st.setupMode)
        val phoneIdx = st.phoneIndexOf(phone.publicKey)!!
        val cardIdx = st.card()!!.index
        val cardKey = st.card()!!.cardKey()!!

        step("pay 0.5 NEAR: phone proposes, card taps, executes in one transaction")
        // A fresh implicit account (64-hex id): it starts empty, so its balance is exactly what it receives.
        val receiver = NearKey.random().publicKey.hex()
        val amount = Near.parse("0.5")
        val id = w.proposeByPhone(Action.Transfer(Asset.Near, amount, receiver), phone, phoneIdx)
        val ch = w.awaitCardChallenge(id)
        val tap = cards.sign(card, cardKey, ch, recipe)
        assertTrue(st.nearLimit!!.isInstant(amount))
        val r = w.approveCardAndExecute(id, cardIdx, tap)
        step("executed: ${near.explorerTx(r.hash)}")
        assertEquals(amount, near.balance(receiver), "receiver balance")
        step("receiver $receiver now holds exactly 0.5 NEAR")

        step("over the limit: 1.5 NEAR is approved but must wait the delay")
        val big = w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("1.5"), receiver), phone, phoneIdx)
        w.approveCard(big, cardIdx, cards.sign(card, cardKey, w.awaitCardChallenge(big), recipe))
        val p = w.proposal(big)!!
        assertTrue(p.readyTs != null)
        val early = runCatching { w.execute(big) }
        assertTrue(early.exceptionOrNull()?.message?.contains("Delay has not elapsed") == true, "got ${early.exceptionOrNull()?.message}")
        step("refused as expected: ${early.exceptionOrNull()?.message}")
        w.cancelByPhone(big, phone, phoneIdx)
        assertEquals(null, w.proposal(big))
        step("phone cancelled it")

        step("a skimmed signature for one proposal can't approve the next")
        val p1 = w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.1"), receiver), phone, phoneIdx)
        val skimmed = cards.sign(card, cardKey, w.awaitCardChallenge(p1), recipe)
        val p2 = w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.1"), receiver), phone, phoneIdx)
        w.awaitCardChallenge(p2)
        val replay = runCatching { w.approveCard(p2, cardIdx, skimmed) }
        assertTrue(replay.exceptionOrNull()?.message?.contains("Card signature verification failed") == true, "got ${replay.exceptionOrNull()?.message}")
        step("refused: ${replay.exceptionOrNull()?.message}")

        step("freeze with a card tap")
        w.freezeByCard(cardIdx) { c -> cards.sign(card, cardKey, c, recipe) }
        assertTrue(w.state().frozen)
        val frozen = runCatching { w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.1"), receiver), phone, phoneIdx) }
        assertTrue(frozen.exceptionOrNull()?.message?.contains("frozen") == true)
        step("frozen: new transfers refused")

        step("done. wallet: ${near.explorerAccount(a.wallet)}")
    }
}
