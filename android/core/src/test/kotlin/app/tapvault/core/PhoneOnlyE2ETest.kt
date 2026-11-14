package app.tapvault.core

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract v0.3 against the live NEAR testnet: a wallet set up without a
 * card (threshold 1) pays with the phone alone, then the card is added with
 * `SetThreshold(2)` in the same change. Creates two fresh testnet accounts.
 *
 *   TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*PhoneOnlyE2E*' --rerun
 *
 * The config delay is at least 1 h, so by default the run stops after the
 * add-card request is refused early. With TAPVAULT_WAIT_DELAYS=1 it waits,
 * executes it, and checks the phone alone can no longer pay.
 */
class PhoneOnlyE2ETest {
    private fun step(s: String) = println("[e2e] $s")

    @Test
    fun phoneOnlyWalletThenAddTheCard() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") {
            println("skipped: set TAPVAULT_TESTNET=1 to run against testnet")
            return
        }
        val wasm = File(System.getenv("TAPVAULT_WASM") ?: fail("TAPVAULT_WASM not set")).readBytes()
        val near = NearClient()
        val phone = SoftP256Signer()
        val ob = Onboarding(near)
        val sponsor = System.getenv("TAPVAULT_SPONSOR_ACCOUNT")?.let { acct ->
            System.getenv("TAPVAULT_SPONSOR_KEY")?.let { Onboarding.Sponsor(acct, NearKey.fromSecret(it)) }
        }
        val a = ob.createAccounts("tv-e2e", sponsor) { step(it) }
        step("wallet ${a.wallet}, gas ${a.gas}")
        near.explorerTx(ob.deployAndInit(a, wasm, phone).hash).let(::step)

        step("finalize without a card: threshold 1, limits 1 NEAR instant / 2 NEAR per day")
        ob.finalize(a, phone, FinalizeArgs(1, Delays.SHORTEST, listOf(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO))))
        assertTrue(near.accessKeys(a.wallet).isEmpty(), "wallet account must have no keys after finalize")
        val w = TapvaultWallet(near, a.wallet, a.gas, a.gasKey)
        val st = w.state()
        assertTrue(st.phoneOnly, "phone-only: $st")
        assertEquals("0.4.0", w.contractVersion())
        val phoneIdx = st.phoneIndexOf(phone.publicKey)!!

        step("pay 0.5 NEAR with the phone alone: propose, then execute")
        val receiver = NearKey.random().publicKey.hex()
        val amount = Near.parse("0.5")
        val id = w.proposeByPhone(Action.Transfer(Asset.Near, amount, receiver), phone, phoneIdx)
        step("executed: ${near.explorerTx(w.execute(id).hash)}")
        assertEquals(amount, near.balance(receiver), "receiver balance")

        step("add the card: AddSigner(card) + SetThreshold(2), possession proved in the proposal")
        val card = SimCard(SimCard.Profile.valueOf(System.getenv("TAPVAULT_CARD_PROFILE") ?: "DDA"))
        val cards = CardService(CapkStore.load().plus(SimCard.TEST_CA))
        val e = cards.enrol(card) { k -> Domain.cardPossessionChallenge(a.wallet, k.keyHash()) }
        val add = Action.Config(listOf(ConfigOp.AddSigner(e.key), ConfigOp.SetThreshold(2)))
        val cid = w.proposeByPhone(add, phone, phoneIdx, mapOf(0 to PossessionProof.Card(e.proof)))
        val p = w.proposal(cid)!!
        assertTrue(p.readyTs != null, "the phone's proposal is the only approval needed")
        val early = runCatching { w.execute(cid) }
        assertTrue(early.exceptionOrNull()?.message?.contains("Delay has not elapsed") == true, "got ${early.exceptionOrNull()?.message}")
        step("refused before the config delay, as expected")

        if (System.getenv("TAPVAULT_WAIT_DELAYS") == "1") {
            while (true) {
                val left = p.readyTs!! + st.delays.config + 20 - System.currentTimeMillis() / 1000
                if (left <= 0) break
                step("  waiting ${left / 60} min for #$cid")
                Thread.sleep(minOf(left, 600) * 1000)
            }
            step("card added: ${near.explorerTx(w.execute(cid).hash)}")
            val after = w.state()
            assertEquals(2, after.threshold)
            assertTrue(!after.phoneOnly)
            val pay = w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.1"), receiver), phone, after.phoneIndexOf(phone.publicKey)!!)
            val alone = runCatching { w.execute(pay) }
            assertTrue(alone.exceptionOrNull()?.message?.contains("Not enough approvals") == true, "got ${alone.exceptionOrNull()?.message}")
            step("the phone alone can't pay any more")
        }
        step("done. wallet: ${near.explorerAccount(a.wallet)}")
    }
}
