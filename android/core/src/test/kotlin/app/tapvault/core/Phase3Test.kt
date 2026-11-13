package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hardware-wallet plan phase 3: recovery on a new phone, guardian phone. */
class Phase3Test {
    private val account = "w.testnet"
    private val oldPhone = SoftP256Signer()
    private val newPhone = SoftP256Signer()
    private val svc = CardService(CapkStore.load().plus(SimCard.TEST_CA))
    private val card1 = svc.enrol(SimCard()) { ByteArray(32) }.key
    private val card2 = svc.enrol(SimCard(SimCard.Profile.DDA, TestCards.random(), SimCard.PAN2)) { ByteArray(32) }.key

    private fun state(signers: List<SignerKey>, threshold: Int = 2, frozen: Boolean = false) = WalletState(
        setupMode = false, frozen = frozen, threshold = threshold, freezeNonce = 0, nextProposalId = 7, delays = Delays.SHORTEST,
        signers = signers.mapIndexed { i, k -> SignerInfo(i, k is SignerKey.EmvCard, k.keyHash().hex(), k.json()) }, limits = emptyList(),
    )

    @Test
    fun recoveryRemovesTheOldPhoneAndAddsThisOne() {
        val st = state(listOf(oldPhone.signerKey(), card1, card2))
        val plan = Recovery.plan(st, newPhone.signerKey())
        val ops = plan.action.ops
        assertEquals(2, ops.size)
        assertContentEquals(oldPhone.signerKey().keyHash(), (ops[0] as ConfigOp.RemoveSigner).keyHash)
        assertContentEquals(newPhone.publicKey, ((ops[1] as ConfigOp.AddSigner).key as SignerKey.P256).pubkey)
        assertEquals(2, plan.cardsKept.size)
        assertEquals(2, plan.approvalsNeeded)
        assertEquals(Delays.SHORTEST.recovery, plan.delaySecs)
        assertFalse(plan.unfreezes)
        // it round-trips the same bytes the contract hashes (gap 6b parser)
        assertContentEquals(plan.action.borshBytes(), ActionJson.parse(plan.action.json()).borshBytes())
    }

    @Test
    fun recoveryUnfreezesAFrozenWalletAndCanDropALostCard() {
        val three = state(listOf(oldPhone.signerKey(), card1, card2, SimCardKeys.third), frozen = true)
        val plan = Recovery.plan(three, newPhone.signerKey(), lostCards = listOf(SimCardKeys.third.keyHash().hex()))
        assertEquals(listOf("RemoveSigner", "RemoveSigner", "AddSigner", "Unfreeze"), plan.action.ops.map { it::class.simpleName })
        assertTrue(plan.unfreezes)
        assertEquals(Recovery.plan(three, newPhone.signerKey(), lostCards = listOf(SimCardKeys.third.keyHash().hex()), unfreeze = false).action.ops.size, 3)
    }

    @Test
    fun recoveryIsRefusedWhenTheCardsCantApproveIt() {
        // one card, threshold 2: cards alone can never reach the threshold
        val e = assertFailsWith<TapvaultException> { Recovery.plan(state(listOf(oldPhone.signerKey(), card1)), newPhone.signerKey()) }
        assertTrue(e.message!!.contains("needs 2"), e.message)
        // a lost card leaves one
        assertFailsWith<TapvaultException> {
            Recovery.plan(state(listOf(oldPhone.signerKey(), card1, card2)), newPhone.signerKey(), lostCards = listOf(card2.keyHash().hex()))
        }
        // already a signer, unknown signer, wrong kinds
        val st = state(listOf(oldPhone.signerKey(), card1, card2))
        assertFailsWith<TapvaultException> { Recovery.plan(st, oldPhone.signerKey()) }
        assertFailsWith<TapvaultException> { Recovery.plan(st, newPhone.signerKey(), removePhones = listOf("00".repeat(32))) }
        assertFailsWith<TapvaultException> { Recovery.plan(st, newPhone.signerKey(), removePhones = listOf(card1.keyHash().hex())) }
        assertFailsWith<TapvaultException> { Recovery.plan(st, newPhone.signerKey(), lostCards = listOf(oldPhone.signerKey().keyHash().hex())) }
        // too many ops for one Config
        val phones = List(4) { SoftP256Signer().signerKey() }
        assertFailsWith<TapvaultException> { Recovery.plan(state(phones + card1 + card2), newPhone.signerKey()) }
        // still in setup
        assertFailsWith<TapvaultException> { Recovery.plan(st.copy(setupMode = true), newPhone.signerKey()) }
    }

    @Test
    fun aRecoveryRequestIsRecognised() {
        val st = state(listOf(oldPhone.signerKey(), card1, card2))
        val a = Recovery.plan(st, newPhone.signerKey()).action
        fun p(proposer: Int, action: Action) = ProposalInfo(7, Domain.intentHash(account, 7, action.borshBytes()), 0, 0, proposer, null, null, false, action.json(), null)
        assertTrue(Recovery.isRecovery(p(1, a), st))
        assertFalse(Recovery.isRecovery(p(0, a), st), "proposed by a phone: an ordinary settings change")
        assertFalse(Recovery.isRecovery(p(1, Action.Transfer(Asset.Near, Near.ONE, "bob.testnet")), st))
    }

    @Test
    fun guardianCodeRoundTripsAndCatchesMistakes() {
        val g = SoftP256Signer()
        val code = Guardian.encode(account, g.publicKey)
        val offer = Guardian.decode(code)
        assertEquals(account, offer.wallet)
        assertContentEquals(g.publicKey, offer.key.pubkey)
        assertEquals(offer, Guardian.decode("  $code\n").copy(key = offer.key))

        assertFailsWith<TapvaultException> { Guardian.decode("hello") }
        assertFailsWith<TapvaultException> { Guardian.decode(code.dropLast(1)) }
        // one changed key digit fails the check
        val i = Guardian.PREFIX.length + account.length + 10
        val typo = code.substring(0, i) + (if (code[i] == '0') '1' else '0') + code.substring(i + 1)
        assertFailsWith<TapvaultException> { Guardian.decode(typo) }
        // a well-formed code whose x isn't on the curve
        val bad = ByteArray(33).also { it[0] = 2; it[32] = 5 }
        val badCode = Guardian.encode(account, bad)
        if (!Guardian.isP256Point(bad)) assertFailsWith<TapvaultException> { Guardian.decode(badCode) }
        repeat(20) { assertTrue(Guardian.isP256Point(SoftP256Signer().publicKey)) }
    }

    @Test
    fun guardianIsAddedOnlyToTheWalletItWasOfferedFor() {
        val st = state(listOf(oldPhone.signerKey(), card1))
        val g = SoftP256Signer()
        val a = Guardian.addAction(account, st, Guardian.decode(Guardian.encode(account, g.publicKey)))
        assertContentEquals(g.publicKey, ((a.ops.single() as ConfigOp.AddSigner).key as SignerKey.P256).pubkey)
        assertFailsWith<TapvaultException> { Guardian.addAction("other.testnet", st, Guardian.decode(Guardian.encode(account, g.publicKey))) }
        assertFailsWith<TapvaultException> { Guardian.addAction(account, st, Guardian.decode(Guardian.encode(account, oldPhone.publicKey))) }
        val full = state(List(6) { SoftP256Signer().signerKey() } + card1 + card2)
        assertFailsWith<TapvaultException> { Guardian.addAction(account, full, Guardian.decode(Guardian.encode(account, g.publicKey))) }
    }

    private object SimCardKeys {
        val third: SignerKey.EmvCard = CardService(CapkStore.load().plus(SimCard.TEST_CA))
            .enrol(SimCard(SimCard.Profile.DDA, TestCards.random(), SimCard.PAN2)) { ByteArray(32) }.key
    }
}
