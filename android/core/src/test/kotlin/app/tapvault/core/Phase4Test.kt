package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Contract v0.2 on the client side: card challenge recomputed from the seed, P-256 verify, possession proofs. */
class Phase4Test {
    private val account = "w.testnet"
    private val action = Action.Transfer(Asset.Near, Near.ONE, "bob.testnet")
    private val intent = Domain.intentHash(account, 3, action.borshBytes())
    private val seed = ByteArray(32) { 9 }
    private val wallet = TapvaultWallet(NearClient(), account, "gas.testnet", NearKey.random())

    private fun proposal(challenge: ByteArray, seed: ByteArray? = this.seed, viewIntent: ByteArray = intent) =
        ProposalInfo(3, viewIntent, 0, 1, 0, null, null, false, action.json(), challenge, seed)

    @Test
    fun theCardChallengeIsRecomputedFromTheSeed() {
        val good = Domain.cardApproveChallenge(intent, seed)
        assertContentEquals(good, wallet.checkedChallenge(proposal(good), good))
        // a view whose challenge isn't the one its seed gives
        val steered = Domain.cardApproveChallenge(Domain.intentHash(account, 4, action.borshBytes()), seed)
        assertFailsWith<TapvaultException> { wallet.checkedChallenge(proposal(steered), steered) }
        // a view that lies about the intent hash too: the phone computes the intent from the action
        val other = Domain.intentHash(account, 3, Action.Transfer(Asset.Near, Near.ONE, "mallory.testnet").borshBytes())
        val c = Domain.cardApproveChallenge(other, seed)
        assertFailsWith<TapvaultException> { wallet.checkedChallenge(proposal(c, viewIntent = other), c) }
        // an older contract (no seed): used as is
        assertContentEquals(steered, wallet.checkedChallenge(proposal(steered, seed = null), steered))
    }

    @Test
    fun walletsOnTheTwelveNovemberBuildStillPassTheWalletCheck() {
        val phone = SoftP256Signer()
        val st = WalletState(false, false, 2, 0, 0, Delays.SHORTEST,
            listOf(SignerInfo(0, false, phone.signerKey().keyHash().hex(), phone.signerKey().json())), emptyList())
        val r = Integrity.evaluate(Integrity.V01_BUILD, emptyList(), st, phone.publicKey, shippedCodeHash = "SomeNewerBuild")
        assertTrue(r.ok, r.toString())
        assertTrue(r.checks[0].status == Integrity.Status.NOTE)
        assertTrue(!Integrity.evaluate("Unknown", emptyList(), st, phone.publicKey, "SomeNewerBuild").ok)
    }

    @Test
    fun p256VerifyMatchesSigning() {
        val k = SoftP256Signer()
        val m = ByteArray(32) { it.toByte() }
        val sig = k.sign(m)
        assertTrue(P256.verify(k.publicKey, m, sig))
        assertTrue(!P256.verify(k.publicKey, m.copyOf().also { it[0] = 1 }, sig))
        assertTrue(!P256.verify(SoftP256Signer().publicKey, m, sig))
        repeat(30) { val s = SoftP256Signer(); assertTrue(P256.verify(s.publicKey, m, s.sign(m)), "decompress both parities") }
    }

    @Test
    fun possessionProofJson() {
        val k = SoftP256Signer()
        val p = PossessionProof.P256(k.sign(Domain.possessionMessage(account, k.signerKey().keyHash())))
        assertTrue(p.json().toString().startsWith("{\"P256\":{\"signature\":"))
        assertTrue(PossessionProof.Card(CardSig(ByteArray(4), ByteArray(4))).json().toString().startsWith("{\"Card\":{\"sdad\":"))
    }

    @Test
    fun allowlistMakesUnlistedReceiversWait() {
        val now = 1_000_000L
        val limit = LimitInfo(Asset.Near.json(), Near.ONE, Near.ONE * java.math.BigInteger.TWO, now, java.math.BigInteger.ZERO)
        val open = WalletState(false, false, 2, 0, 1, Delays.SHORTEST, emptyList(), listOf(limit))
        assertTrue(open.isInstantTo("anyone.testnet", Near.ONE, now), "no allowlist: limit only")
        assertTrue(!open.isInstantTo("anyone.testnet", Near.ONE + java.math.BigInteger.ONE, now), "over the limit")
        val listed = open.copy(allowlist = listOf("bob.testnet"))
        assertTrue(listed.isInstantTo("bob.testnet", Near.ONE, now))
        assertTrue(!listed.isInstantTo("eve.testnet", Near.ONE, now), "unlisted waits")
        assertTrue(!listed.isInstantTo(null, Near.ONE, now), "unknown receiver waits")
        assertTrue(!listed.copy(limits = emptyList()).isInstantTo("bob.testnet", Near.ONE, now), "no limit: never instant")
    }
}
