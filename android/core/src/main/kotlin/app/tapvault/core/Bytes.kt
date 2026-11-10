package app.tapvault.core

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray {
    val s = replace(" ", "")
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun sha1(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-1")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

fun u32le(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

fun u64le(v: Long): ByteArray = ByteArray(8) { (v ushr (8 * it)).toByte() }

/** Unsigned big-endian bytes of [v], left-padded to [len]. */
fun BigInteger.toFixed(len: Int): ByteArray {
    val raw = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    require(raw.size <= len) { "value doesn't fit in $len bytes" }
    return ByteArray(len - raw.size) + raw
}

fun ByteArray.toPositiveBigInteger(): BigInteger = BigInteger(1, this)

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val B58 = BigInteger.valueOf(58)

    fun encode(b: ByteArray): String {
        var n = BigInteger(1, b)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(B58)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(b.takeWhile { it == 0.toByte() }.size) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun decode(s: String): ByteArray {
        var n = BigInteger.ZERO
        for (c in s) {
            val d = ALPHABET.indexOf(c)
            require(d >= 0) { "bad base58 character '$c'" }
            n = n.multiply(B58).add(BigInteger.valueOf(d.toLong()))
        }
        val body = if (n.signum() == 0) ByteArray(0) else n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(s.takeWhile { it == '1' }.length) + body
    }
}

/** Amounts: 1 NEAR = 10^24 yoctoNEAR. */
object Near {
    val ONE: BigInteger = BigInteger.TEN.pow(24)

    fun parse(near: String): BigInteger = java.math.BigDecimal(near).movePointRight(24).toBigIntegerExact()

    fun format(yocto: BigInteger, decimals: Int = 4): String =
        java.math.BigDecimal(yocto).movePointLeft(24).setScale(decimals, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}
