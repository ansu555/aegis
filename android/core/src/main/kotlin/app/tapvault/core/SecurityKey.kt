package app.tapvault.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The wallet's security key: something the owner knows, asked by the app
 * before the phone's key signs a payment, an approval or a change of keys.
 *
 * It is enforced by this app, not by the wallet contract: the phone's
 * Keystore key only works inside this app, so on this phone nothing can be
 * signed without passing here. Only a slow, salted hash is kept, and the
 * input is first mixed with [pepper], an HMAC under a key that never leaves
 * the phone's secure hardware, so a copy of the app's storage can't be
 * brute-forced elsewhere.
 */
object SecurityKey {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 64
    const val ITERATIONS = 120_000

    /** Wrong tries allowed before the first pause. */
    const val FREE_TRIES = 5

    class Record(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
        fun encode() = "v1|$iterations|${salt.hex()}|${hash.hex()}"

        companion object {
            fun decode(s: String?): Record? = s?.split("|")?.takeIf { it.size == 4 && it[0] == "v1" }?.let {
                runCatching { Record(it[2].unhex(), it[3].unhex(), it[1].toInt()) }.getOrNull()
            }
        }
    }

    /** Why [key] (typed twice: [confirm]) can't be a security key, or null when it can. */
    fun problem(key: String, confirm: String): String? = when {
        key.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
        key.length > MAX_LENGTH -> "Use at most $MAX_LENGTH characters."
        key.isBlank() || key.toSet().size == 1 -> "Pick something harder to guess."
        key in COMMON -> "That one is too easy to guess. Pick another."
        key != confirm -> "The two entries don't match."
        else -> null
    }

    fun create(key: String, pepper: (ByteArray) -> ByteArray, iterations: Int = ITERATIONS): Record {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Record(salt, derive(key, salt, iterations, pepper), iterations)
    }

    fun verify(record: Record, key: String, pepper: (ByteArray) -> ByteArray): Boolean =
        MessageDigest.isEqual(derive(key, record.salt, record.iterations, pepper), record.hash)

    private fun derive(key: String, salt: ByteArray, iterations: Int, pepper: (ByteArray) -> ByteArray): ByteArray {
        val mixed = pepper(key.toByteArray(Charsets.UTF_8)).hex().toCharArray()
        val spec = PBEKeySpec(mixed, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * After [failures] wrong tries, how long (seconds) the next try must wait:
     * none for the first [FREE_TRIES], then 30 s doubling each time, at most 1 h.
     */
    fun pauseAfter(failures: Int): Long =
        if (failures < FREE_TRIES) 0 else minOf(30L shl minOf(failures - FREE_TRIES, 7), 3600L)

    private val COMMON = setOf("123456", "1234567", "12345678", "123456789", "1234567890", "654321", "111111", "000000", "password", "qwerty", "abcdef", "abc123", "112233", "121212", "123123")
}
