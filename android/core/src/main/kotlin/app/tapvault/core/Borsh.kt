package app.tapvault.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Minimal Borsh encoder: just the shapes the contract and NEAR transactions use. */
class Borsh {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun u32(v: Int) = apply { out.write(u32le(v)) }
    fun u64(v: Long) = apply { out.write(u64le(v)) }
    fun i64(v: Long) = u64(v)

    fun u128(v: BigInteger) = apply {
        require(v.signum() >= 0 && v.bitLength() <= 128) { "u128 out of range" }
        out.write(v.toFixed(16).reversedArray())
    }

    fun fixed(b: ByteArray) = apply { out.write(b) }
    fun bytes(b: ByteArray) = apply { u32(b.size); out.write(b) }
    fun string(s: String) = bytes(s.toByteArray(Charsets.UTF_8))

    fun <T> vec(items: List<T>, each: Borsh.(T) -> Unit) = apply {
        u32(items.size)
        items.forEach { each(it) }
    }

    fun <T> option(v: T?, each: Borsh.(T) -> Unit) = apply {
        if (v == null) u8(0) else { u8(1); each(v) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun of(build: Borsh.() -> Unit): ByteArray = Borsh().apply(build).toByteArray()
    }
}
