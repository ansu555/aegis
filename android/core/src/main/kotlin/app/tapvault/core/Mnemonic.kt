package app.tapvault.core

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer

/**
 * Recovery phrases the way NEAR wallets make them: BIP-39 English words,
 * the BIP-39 seed (no passphrase), then SLIP-10 ed25519 at NEAR's path
 * m/44'/397'/0'. The same phrase gives the same key as `near-seed-phrase`
 * (MyNearWallet, Meteor, near-cli), checked in `MnemonicTest`.
 *
 * Used only by seed-phrase wallets: a plain NEAR account whose full-access
 * key comes from the phrase, until the user protects it with a bank card
 * (that deletes the key from the chain, so the phrase stops working).
 */
object Mnemonic {
    /** NEAR's derivation path, all levels hardened: m/44'/397'/0'. */
    private val NEAR_PATH = intArrayOf(44, 397, 0)

    /** The BIP-39 English list (SHA-256 2f5eed53…dbda, 2048 words). */
    val WORDS: List<String> by lazy {
        val text = Mnemonic::class.java.getResourceAsStream("/bip39-english.txt")?.use { it.readBytes().decodeToString() }
            ?: error("bip39-english.txt missing from resources")
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.also { check(it.size == 2048) { "BIP-39 list has ${it.size} words" } }
    }
    private val INDEX: Map<String, Int> by lazy { WORDS.withIndex().associate { (i, w) -> w to i } }

    /** Lower case, single spaces, no leading or trailing space (what `near-seed-phrase` does). */
    fun normalize(phrase: String): String = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }

    /** A new phrase of [words] words (12 by default, like NEAR wallets). */
    fun generate(words: Int = 12, random: SecureRandom = SecureRandom()): String {
        require(words in setOf(12, 15, 18, 21, 24)) { "words must be 12, 15, 18, 21 or 24" }
        val entropy = ByteArray(words * 4 / 3).also { random.nextBytes(it) }
        return fromEntropy(entropy)
    }

    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size in 16..32 && entropy.size % 4 == 0) { "entropy must be 16..32 bytes, a multiple of 4" }
        val checksumBits = entropy.size * 8 / 32
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val bits = BooleanArray(entropy.size * 8 + checksumBits) { i ->
            val b = if (i < entropy.size * 8) entropy[i / 8] else hash[(i - entropy.size * 8) / 8]
            (b.toInt() shr (7 - i % 8)) and 1 == 1
        }
        return (0 until bits.size / 11).joinToString(" ") { w ->
            WORDS[(0 until 11).fold(0) { acc, j -> (acc shl 1) or (if (bits[w * 11 + j]) 1 else 0) }]
        }
    }

    /** Why [phrase] isn't a valid phrase, in words for the user; null when it is. */
    fun problem(phrase: String): String? {
        val words = normalize(phrase).split(" ").filter { it.isNotEmpty() }
        if (words.size !in setOf(12, 15, 18, 21, 24)) return "A recovery phrase has 12 or 24 words; this has ${words.size}."
        val unknown = words.withIndex().firstOrNull { it.value !in INDEX }
        if (unknown != null) return "Word ${unknown.index + 1} (\"${unknown.value}\") isn't on the recovery word list. Check the spelling."
        val bits = words.flatMap { w -> val n = INDEX[w]!!; (10 downTo 0).map { (n shr it) and 1 == 1 } }
        val entBits = bits.size * 32 / 33
        val entropy = ByteArray(entBits / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) }.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val ok = (0 until bits.size - entBits).all { i -> bits[entBits + i] == ((hash[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) }
        return if (ok) null else "These words don't form a valid recovery phrase: one of them is probably wrong or out of order."
    }

    fun isValid(phrase: String) = problem(phrase) == null

    /** The BIP-39 seed: PBKDF2-HMAC-SHA512, 2048 rounds, salt "mnemonic" + passphrase, NFKD. */
    fun seed(phrase: String, passphrase: String = ""): ByteArray {
        val pw = Normalizer.normalize(normalize(phrase), Normalizer.Form.NFKD).toByteArray()
        val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD).toByteArray()
        val gen = PKCS5S2ParametersGenerator(SHA512Digest()).apply { init(pw, salt, 2048) }
        return (gen.generateDerivedParameters(512) as KeyParameter).key
    }

    /** SLIP-10 ed25519: the 32-byte private seed at [path] (every level hardened). */
    fun slip10Ed25519(seed: ByteArray, path: IntArray): ByteArray {
        var i = hmac512("ed25519 seed".toByteArray(), seed)
        for (index in path) {
            val data = ByteArray(1) + i.copyOfRange(0, 32) + ser32(index.toLong() or 0x80000000L)
            i = hmac512(i.copyOfRange(32, 64), data)
        }
        return i.copyOfRange(0, 32)
    }

    /** The NEAR key of a recovery phrase (the phrase is normalised first; validity isn't checked here). */
    fun nearKey(phrase: String): NearKey = NearKey(slip10Ed25519(seed(phrase), NEAR_PATH))

    private fun hmac512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest()).apply { init(KeyParameter(key)) }
        mac.update(data, 0, data.size)
        return ByteArray(64).also { mac.doFinal(it, 0) }
    }

    private fun ser32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
