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
 *
 * A second simulated card (its own key and card number) is added as a backup
 * card through a config proposal. The config delay is at least 1 h (the
 * contract's minimum), so by default the run stops after checking the
 * proposal is approved and refused early. With TAPVAULT_WAIT_DELAYS=1 it
 * waits out the delays (about 2 h 10 min): the card becomes a signer, a
 * payment goes out with phone + card 2, and card 1 is removed.
 */
class TestnetE2ETest {
    private fun step(s: String) = println("[e2e] $s")

    /** Tap [card] like the app does: identify it among the wallet's card signers by key hash, then sign. */
    private fun tap(cards: CardService, w: TapvaultWallet, card: CardTransceiver, challenge: ByteArray, recipes: Map<String, CardRecipe>): Pair<Int, CardSig> {
        val st = w.state()
        val held = cards.identify(card, st.cards().map { CardCandidate(it.keyHash, it.cardKey()!!, recipes[it.keyHash]) })
        repeat(2) { held.stillThere() } // the app's hold timer: presence checks before signing
        return st.signerByKeyHash(held.card.keyHash)!!.index to held.sign(challenge)
    }

    /** Wait until [id] can execute (its ready time plus [delay]), then a little more for block time. */
    private fun waitUntilExecutable(w: TapvaultWallet, id: Long, delay: Long) {
        val ready = w.proposal(id)!!.readyTs!!
        while (true) {
            val left = ready + delay + 20 - System.currentTimeMillis() / 1000
            if (left <= 0) return
            step("  waiting ${left / 60} min ${left % 60} s for proposal #$id")
            Thread.sleep(minOf(left, 600) * 1000)
        }
    }

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

        // ---- backup card (plan 5.3) ----
        val profile = SimCard.Profile.valueOf(System.getenv("TAPVAULT_CARD_PROFILE") ?: "DDA")
        val card2 = SimCard(profile, TestCards.random(), SimCard.PAN2)
        val recipes = mutableMapOf(cardKey.keyHash().hex() to recipe)
        step("backup card: card 1 is refused because it's already a signer")
        val signerHashes = w.state().signers.map { it.keyHash }.toSet()
        val count = w.state().signers.size
        val enrolFor = { c: CardTransceiver ->
            cards.enrol(c) { k ->
                if (k.keyHash().hex() in signerHashes) throw AlreadySignerException("already a signer")
                Domain.cardEnrollChallenge(a.wallet, count, k.keyHash())
            }
        }
        assertTrue(runCatching { enrolFor(card) }.exceptionOrNull() is AlreadySignerException)
        step("backup card: card 2 signs a test challenge, verified on the phone (the contract checks none for AddSigner)")
        val e2 = enrolFor(card2)
        assertTrue(provesPossession(e2.key, e2.proof, Domain.cardEnrollChallenge(a.wallet, count, e2.key.keyHash())))
        val hash2 = e2.key.keyHash().hex()
        recipes[hash2] = e2.recipe
        step("  card 2 ****${e2.report.panLast4}, ${e2.recipe.method}, key hash ${hash2.take(12)}…")
        val add = w.proposeByPhone(Action.Config(listOf(ConfigOp.AddSigner(e2.key))), phone, phoneIdx)
        step("phone proposed AddSigner(card 2) as #$add")
        val addCh = w.awaitCardChallenge(add)
        val notYet = runCatching { tap(cards, w, card2, addCh, recipes) }.exceptionOrNull()
        assertTrue(notYet is WrongCardException, "card 2 isn't a signer yet, so it must be refused before signing; got $notYet")
        step("card 2 refused before signing: not a signer yet")
        val (idx1, sig1) = tap(cards, w, card, addCh, recipes)
        assertEquals(cardIdx, idx1)
        w.approveCard(add, idx1, sig1)
        assertTrue(w.proposal(add)!!.readyTs != null)
        val tooEarly = runCatching { w.execute(add) }.exceptionOrNull()?.message
        assertTrue(tooEarly?.contains("Delay has not elapsed") == true, "got $tooEarly")
        step("card 1 approved it; executing now is refused: $tooEarly")

        var freezeCard: CardTransceiver = card
        if (System.getenv("TAPVAULT_WAIT_DELAYS") == "1") {
            val delay = w.state().delays.config
            waitUntilExecutable(w, add, delay)
            near.explorerTx(w.execute(add).hash).let { step("AddSigner executed: $it") }
            val after = w.state()
            val s2 = after.signerByKeyHash(hash2)
            assertTrue(s2 != null && s2.isCard, "card 2 is a signer")
            assertEquals(3, after.signers.size)
            assertTrue(w.proposal(p2)?.stale == true, "the config change voided the open payment")
            step("card 2 is signer ${s2!!.index}; open proposal #$p2 is now void")

            step("pay 0.2 NEAR with phone + card 2")
            val before = near.balance(receiver)
            val pay = w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.2"), receiver), phone, phoneIdx)
            val (i2, sig2) = tap(cards, w, card2, w.awaitCardChallenge(pay), recipes)
            assertEquals(s2.index, i2, "signer index from card 2's key hash")
            near.explorerTx(w.approveCardAndExecute(pay, i2, sig2).hash).let { step("paid with card 2: $it") }
            assertEquals(before + Near.parse("0.2"), near.balance(receiver))

            step("remove card 1: phone proposes, card 2 approves")
            val rm = w.proposeByPhone(Action.Config(listOf(ConfigOp.RemoveSigner(cardKey.keyHash()))), phone, phoneIdx)
            val (ri, rsig) = tap(cards, w, card2, w.awaitCardChallenge(rm), recipes)
            w.approveCard(rm, ri, rsig)
            waitUntilExecutable(w, rm, delay)
            near.explorerTx(w.execute(rm).hash).let { step("RemoveSigner executed: $it") }
            val last = w.state()
            assertEquals(null, last.signerByKeyHash(cardKey.keyHash().hex()), "card 1 is gone")
            assertEquals(1, last.signerByKeyHash(hash2)!!.index, "card 2 moved down to index 1")
            step("card 1 removed; card 2 is now signer 1")

            step("card 1 can no longer sign; card 2 pays with its new index")
            val pay2 = w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.1"), receiver), phone, phoneIdx)
            val ch2 = w.awaitCardChallenge(pay2)
            assertTrue(runCatching { tap(cards, w, card, ch2, recipes) }.exceptionOrNull() is WrongCardException)
            val (j, s) = tap(cards, w, card2, ch2, recipes)
            assertEquals(1, j)
            w.approveCardAndExecute(pay2, j, s)
            freezeCard = card2
        } else {
            step("set TAPVAULT_WAIT_DELAYS=1 to wait out the 1 h delays: pay with card 2, then remove card 1")
        }

        step("freeze with a card tap")
        val (fi, fsig) = tap(cards, w, freezeCard, w.cardFreezeChallenge(), recipes)
        w.freezeByCard(fi, fsig)
        assertTrue(w.state().frozen)
        val frozen = runCatching { w.proposeByPhone(Action.Transfer(Asset.Near, Near.parse("0.1"), receiver), phone, phoneIdx) }
        assertTrue(frozen.exceptionOrNull()?.message?.contains("frozen") == true)
        step("frozen: new transfers refused")

        step("done. wallet: ${near.explorerAccount(a.wallet)}")
    }
}
