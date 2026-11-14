package app.tapvault.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hardware-wallet plan phase 2: gap 6b (recompute what the phone signs), integrity check, watchtower, receivers. */
class Phase2Test {
    private val account = "w.testnet"
    private val phone = SoftP256Signer()
    private val card = CardService(CapkStore.load().plus(SimCard.TEST_CA)).enrol(SimCard()) { ByteArray(32) }.key

    private val everyAction = listOf(
        Action.Transfer(Asset.Near, Near.parse("1.25"), "bob.testnet"),
        Action.Transfer(Asset.Ft("usdc.testnet"), BigInteger.valueOf(42), "bob.testnet"),
        Action.Config(
            listOf(
                ConfigOp.AddSigner(card),
                ConfigOp.AddSigner(phone.signerKey()),
                ConfigOp.AddSigner(SignerKey.P256(phone.publicKey, WebAuthnConfig(ByteArray(32) { 7 }, true))),
                ConfigOp.RemoveSigner(card.keyHash()),
                ConfigOp.SetThreshold(3),
                ConfigOp.SetLimit(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO)),
                ConfigOp.RemoveLimit(Asset.Ft("usdc.testnet")),
                ConfigOp.SetDelays(Delays(7200, 3600, 86400)),
                ConfigOp.Unfreeze,
            ),
        ),
    )

    private fun proposal(id: Long, action: Action, intent: ByteArray = Domain.intentHash(account, id, action.borshBytes()), approvals: Int = 1, stale: Boolean = false, ready: Long? = null) =
        ProposalInfo(id, intent, 0, approvals, 0, ready, null, stale, action.json(), null)

    private fun state(frozen: Boolean = false, signers: List<SignerKey> = listOf(phone.signerKey(), card), setup: Boolean = false) = WalletState(
        setupMode = setup, frozen = frozen, threshold = 2, freezeNonce = 0, nextProposalId = 0, delays = Delays.SHORTEST,
        signers = signers.mapIndexed { i, k -> SignerInfo(i, k is SignerKey.EmvCard, k.keyHash().hex(), k.json()) }, limits = emptyList(),
    )

    // ---- gap 6b ----

    @Test
    fun everyActionParsesBackToTheSameBytes() {
        for (a in everyAction) {
            val back = ActionJson.parse(a.json())
            assertContentEquals(a.borshBytes(), back.borshBytes(), a.json().toString())
            assertEquals(a.json(), back.json())
        }
        assertTrue(everyAction[0].describe() == "Send 1.25 NEAR to bob.testnet", everyAction[0].describe())
        assertTrue(everyAction[2].describe().contains("add a card"))
    }

    @Test
    fun thePhoneSignsOnlyTheIntentItComputed() {
        val a = everyAction[0]
        val (parsed, intent) = proposal(5, a).verifiedAction(account)
        assertContentEquals(Domain.intentHash(account, 5, a.borshBytes()), intent)
        assertEquals(a.json(), parsed.json())

        // the view reports a different action than the one its intent hash covers
        val other = Action.Transfer(Asset.Near, Near.parse("1.25"), "mallory.testnet")
        val disguised = proposal(5, a, intent = Domain.intentHash(account, 5, other.borshBytes()))
        assertFailsWith<TapvaultException> { disguised.verifiedAction(account) }
        // same action, another proposal id or wallet
        assertFailsWith<TapvaultException> { proposal(5, a, intent = Domain.intentHash(account, 6, a.borshBytes())).verifiedAction(account) }
        assertFailsWith<TapvaultException> { proposal(5, a).verifiedAction("x.testnet") }
        // an action this app can't read is never signed
        val weird = ProposalInfo(5, ByteArray(32), 0, 1, 0, null, null, false, JsonObject(mapOf("Upgrade" to JsonPrimitive(1))), null)
        assertFailsWith<TapvaultException> { weird.verifiedAction(account) }
    }

    // ---- integrity ----

    @Test
    fun shippedWasmHasTheDocumentedCodeHash() {
        val wasm = File(System.getenv("TAPVAULT_WASM") ?: return).readBytes()
        assertEquals("93djY2LQU6NbNHJ93GmCDtKXecdrZhQPVCZTUcWt1Kxy", Integrity.codeHashOf(wasm))
    }

    @Test
    fun integrityCheck() {
        val shipped = "5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf"
        val good = Integrity.evaluate(shipped, emptyList(), state(), phone.publicKey, shipped)
        assertTrue(good.ok, good.toString())
        assertTrue(good.checks.all { it.status == Integrity.Status.OK })

        val v03 = Integrity.evaluate(Integrity.V03_BUILD, emptyList(), state(), phone.publicKey, shipped)
        assertTrue(v03.ok)
        assertEquals(Integrity.Status.NOTE, v03.checks[0].status)

        val old = Integrity.evaluate(Integrity.PREVIOUS_BUILD, emptyList(), state(), phone.publicKey, shipped)
        assertTrue(old.ok)
        assertEquals(Integrity.Status.NOTE, old.checks[0].status)

        assertEquals(listOf("Wallet code"), Integrity.evaluate("1111", emptyList(), state(), phone.publicKey, shipped).failed.map { it.name })
        assertEquals(listOf("Account keys"), Integrity.evaluate(shipped, listOf("ed25519:x"), state(), phone.publicKey, shipped).failed.map { it.name })
        assertEquals(listOf("Phone key"), Integrity.evaluate(shipped, emptyList(), state(), SoftP256Signer().publicKey, shipped).failed.map { it.name })
        assertEquals(listOf("Phone key"), Integrity.evaluate(shipped, emptyList(), state(), null, shipped).failed.map { it.name })
        assertEquals(listOf("Setup"), Integrity.evaluate(shipped, emptyList(), state(setup = true), phone.publicKey, shipped).failed.map { it.name })

        val bad = Integrity.evaluate("1111", listOf("k"), state(), phone.publicKey, shipped, nowSecs = 99)
        val back = Integrity.Report.decode(bad.encode())!!
        assertEquals(bad, back)
        assertNull(Integrity.Report.decode("garbage"))
    }

    /** Live (TAPVAULT_TESTNET=1): a wallet made by TestnetE2ETest on 12 Nov runs a known build and has no account keys. */
    @Test
    fun liveIntegrityOfATestnetWallet() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") return
        val near = NearClient()
        val acct = "tv-e2e-cc807a553f.testnet"
        val code = near.codeHash(acct)
        println("[phase2] $acct code_hash $code")
        assertTrue(code == Integrity.PREVIOUS_BUILD || code == "5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf", code)
        assertEquals(emptyList(), near.accessKeys(acct))
    }

    // ---- watchtower ----

    @Test
    fun watchtowerAlertsOnceOnWhatIsNew() {
        val st = state()
        val mine = proposal(1, everyAction[0], approvals = 1 shl 0)
        val theirs = proposal(2, everyAction[0], approvals = 0, ready = null)

        // first look only records
        val (first, m0) = Watch.check(null, st, listOf(mine), phoneIndex = 0)
        assertTrue(first.isEmpty())

        val (a1, m1) = Watch.check(m0, st, listOf(mine, theirs), phoneIndex = 0)
        assertEquals(listOf(Watch.Kind.UNKNOWN_REQUEST), a1.map { it.kind })
        assertEquals(2L, a1[0].proposalId)
        // seen once: no repeat
        assertTrue(Watch.check(m1, st, listOf(mine, theirs), phoneIndex = 0).first.isEmpty())
        // void proposals don't alert
        assertTrue(Watch.check(m0, st, listOf(proposal(3, everyAction[0], approvals = 0, stale = true)), 0).first.isEmpty())

        val (a2, m2) = Watch.check(m1, state(frozen = true), emptyList(), 0)
        assertEquals(listOf(Watch.Kind.FROZEN), a2.map { it.kind })
        val (a3, m3) = Watch.check(m2, state(frozen = true, signers = listOf(phone.signerKey())), emptyList(), 0)
        assertEquals(listOf(Watch.Kind.SIGNERS_CHANGED), a3.map { it.kind })
        val (a4, m4) = Watch.check(m3, state(frozen = true, signers = listOf(phone.signerKey())), emptyList(), 0, integrityOk = false)
        assertEquals(listOf(Watch.Kind.INTEGRITY), a4.map { it.kind })
        assertEquals(m4, Watch.Memory.decode(m4.encode()))
        assertNull(Watch.Memory.decode("x"))
    }

    // ---- receivers ----

    @Test
    fun receiversAndLookAlikes() {
        assertEquals(1, Receivers.distance("alice.testnet", "a1ice.testnet"))
        assertEquals(2, Receivers.distance("alice.testnet", "alcie.testnet"))
        assertEquals(0, Receivers.distance("x", "x"))
        val r = Receivers.decode(null).add("alice.testnet", 10).add("bob.testnet", 20)
        assertTrue(r.isKnown("alice.testnet"))
        assertEquals("alice.testnet", r.lookAlike("a1ice.testnet"))
        assertNull(r.lookAlike("alice.testnet"), "an exact match isn't a look-alike")
        assertNull(r.lookAlike("carol.testnet"))
        val back = Receivers.decode(r.encode())
        assertEquals(r.known, back.known)
        assertEquals(10L, back.add("alice.testnet", 99).known["alice.testnet"], "first-seen time is kept")
    }

    @Test
    fun walletViewSignerEpochIsOptional() {
        assertEquals(0L, state().signerEpoch)
        assertTrue(everyAction[2].json().jsonObject.containsKey("Config"))
    }
}
