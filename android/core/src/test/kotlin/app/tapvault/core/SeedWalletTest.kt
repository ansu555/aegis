package app.tapvault.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recovery phrases (BIP-39 + SLIP-10, NEAR's path) and the seed-phrase wallet's offline parts. */
class SeedWalletTest {
    /** Phrase → key from `near-seed-phrase` 0.2.1 (`parseSeedPhrase`), the library NEAR wallets use. */
    private val nearVectors = listOf(
        Triple(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "ed25519:6j4b6zUaty6fD1awqcGCCU9JYGCWYUgdJhQrzfZhqE25",
            "ed25519:F1kPR175szkGxEL52A9H6Z5ocS2BtaipprK2Hiob9DjGzHTkScrBb1yt44baXPZ3LxyHcsTPdBjHmn6zx147txH",
        ),
        Triple(
            "  Legal WINNER thank year wave sausage worth useful legal winner thank yellow ",
            "ed25519:5shPrRr1NRJL7aTdCMqSSKxfUHvacCTEjCkCEpd26RmJ",
            "ed25519:2S1RFM3ywBX5649qeYqGRXrQxZuCNM5uYqBoAor7tPhDcQ4udnLqeWffQTHFSjomXmbZvFdAg7PCADyfUK7yrYD6",
        ),
        Triple(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
            "ed25519:BYvZNc9zUYYyXvTT6AtnazqzRZA6edJ5igz3j7fZ2YHG",
            "ed25519:4NfydZTHKxQ44DPkjdxL3Q3VMXzRrDgS2AJUkXBoDBTk7aZB8p9dXpXYFnybn5jo8QeEReqdT75ngLHQAYswg7ox",
        ),
    )

    @Test
    fun phrasesGiveTheSameKeysAsNearWallets() {
        for ((phrase, pub, secret) in nearVectors) {
            val k = Mnemonic.nearKey(phrase)
            assertEquals(pub, k.publicKeyString, phrase)
            assertEquals(secret, k.secretString(), phrase)
        }
    }

    @Test
    fun bip39SeedMatchesTheReferenceVector() {
        // BIP-39 reference vector, passphrase "TREZOR" (checked against the `bip39` npm package).
        val seed = Mnemonic.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "TREZOR")
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seed.hex(),
        )
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            Mnemonic.fromEntropy(ByteArray(16) { 0x7f }),
        )
    }

    @Test
    fun slip10MasterKeyMatchesTheSpec() {
        // SLIP-0010 test vector 1 for ed25519, chain m.
        val seed = "000102030405060708090a0b0c0d0e0f".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7", Mnemonic.slip10Ed25519(seed, intArrayOf()).hex())
    }

    @Test
    fun generatedPhrasesAreValidAndDifferent() {
        val a = Mnemonic.generate()
        val b = Mnemonic.generate()
        assertEquals(12, a.split(" ").size)
        assertNull(Mnemonic.problem(a))
        assertTrue(a != b)
        assertEquals(24, Mnemonic.generate(24).split(" ").size)
    }

    @Test
    fun badPhrasesAreExplained() {
        assertNotNull(Mnemonic.problem("abandon abandon abandon"))!!.let { assertTrue("12 or 24" in it, it) }
        assertTrue("isn't on the recovery word list" in Mnemonic.problem("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandonn")!!)
        // Right words, wrong checksum.
        assertTrue("valid recovery phrase" in Mnemonic.problem("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon")!!)
    }

    @Test
    fun importAcceptsPhrasesAndKeys() {
        val (k1, p1) = SeedWallet.parseImport("  Abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon ABOUT\n")
        assertEquals(nearVectors[0].second, k1.publicKeyString)
        assertEquals(nearVectors[0].first, p1)
        val (k2, p2) = SeedWallet.parseImport(nearVectors[1].third)
        assertEquals(nearVectors[1].second, k2.publicKeyString)
        assertNull(p2)
        val (k3, _) = SeedWallet.parseImport(nearVectors[2].third.removePrefix("ed25519:"))
        assertEquals(nearVectors[2].second, k3.publicKeyString)
        assertFailsWith<TapvaultException> { SeedWallet.parseImport("") }
        assertFailsWith<TapvaultException> { SeedWallet.parseImport("secp256k1:abc") }
        assertFailsWith<TapvaultException> { SeedWallet.parseImport("ed25519:notbase58!!") }
        // Halves that don't match: the public half of another key.
        val bad = Base58.decode(nearVectors[0].third.removePrefix("ed25519:")).copyOf(32) + Mnemonic.nearKey(nearVectors[1].first).publicKey
        assertFailsWith<TapvaultException> { SeedWallet.parseImport("ed25519:" + Base58.encode(bad)) }
    }

    @Test
    fun deleteKeyEncodesLikeNear() {
        val k = Mnemonic.nearKey(nearVectors[0].first)
        val b = Borsh.of { NearAction.DeleteKey(k.publicKeyString).borsh(this) }
        assertEquals("0600" + k.publicKey.hex(), b.hex())
        assertFailsWith<IllegalArgumentException> { Borsh.of { NearAction.DeleteKey("rsa:abc").borsh(this) } }
    }

    @Test
    fun implicitAccountIsTheKeyInHex() {
        val k = Mnemonic.nearKey(nearVectors[0].first)
        assertEquals(64, NearClient.implicitAccount(k.publicKey).length)
        assertEquals(k.publicKey.hex(), NearClient.implicitAccount(k.publicKey))
    }

    @Test
    fun storageCoversTheShippedContract() {
        // 352 170 bytes of wasm: about 3.56 NEAR locked for storage.
        val s = SeedWallet.storageFor(352_170)
        assertTrue(s > Near.parse("3.5") && s < Near.parse("3.6"), Near.format(s))
        assertEquals(BigInteger.TEN.pow(19) * BigInteger.valueOf(356_170), s)
    }
}
