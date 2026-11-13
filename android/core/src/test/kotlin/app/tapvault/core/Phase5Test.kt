package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract v0.3 on the client side: wallets set up without a card (phone-only, threshold 1). */
class Phase5Test {
    private val phone = SoftP256Signer()
    private val card = CardService(CapkStore.load().plus(SimCard.TEST_CA)).enrol(SimCard()) { ByteArray(32) }.key

    private fun state(threshold: Int, vararg keys: SignerKey) = WalletState(
        false, false, threshold, 0, 0, Delays.SHORTEST,
        keys.mapIndexed { i, k -> SignerInfo(i, k is SignerKey.EmvCard, k.keyHash().hex(), k.json()) }, emptyList(),
    )

    @Test
    fun onlyAThresholdOneWalletWithoutCardsIsPhoneOnly() {
        assertTrue(state(1, phone.signerKey()).phoneOnly)
        assertFalse(state(2, phone.signerKey(), card).phoneOnly)
        // a wallet with a card is never treated as phone-only, whatever the threshold says
        assertFalse(state(1, phone.signerKey(), card).phoneOnly)
    }

    @Test
    fun addingTheFirstCardRaisesTheThresholdInTheSameChange() {
        val add = Action.Config(listOf(ConfigOp.AddSigner(card), ConfigOp.SetThreshold(2)))
        // the phone signs the bytes it shows: parsing the RPC's JSON gives the same Borsh bytes
        assertContentEquals(add.borshBytes(), ActionJson.parse(add.json()).borshBytes())
        assertTrue(add.describe().contains("approvals needed 2"), add.describe())
    }

    @Test
    fun aPhoneOnlyWalletPassesTheWalletCheck() {
        val r = Integrity.evaluate("Shipped", emptyList(), state(1, phone.signerKey()), phone.publicKey, shippedCodeHash = "Shipped")
        assertTrue(r.ok, r.toString())
    }
}
