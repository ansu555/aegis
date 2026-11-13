package app.tapvault.core

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A phone signer: an ECDSA P-256 key that signs SHA-256 over a 32-byte
 * message. On Android this is a Keystore key behind a biometric prompt.
 */
interface P256Signer {
    /** SEC1 compressed public key, 33 bytes. */
    val publicKey: ByteArray

    /** 64-byte `r || s` signature over [message]. */
    fun sign(message: ByteArray): P256Sig

    fun signerKey() = SignerKey.P256(publicKey)
}

object P256 {
    fun compress(pub: ECPublicKey): ByteArray {
        val x = pub.w.affineX.toFixed(32)
        val prefix: Byte = if (pub.w.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix) + x
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` to 64-byte `r || s`. */
    fun derToRaw(der: ByteArray): ByteArray {
        require(der[0] == 0x30.toByte()) { "not a DER sequence" }
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): BigInteger {
            require(der[i] == 0x02.toByte()) { "expected INTEGER" }
            val len = der[i + 1].toInt() and 0xff
            val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + len))
            i += 2 + len
            return v
        }
        val r = int()
        val s = int()
        return r.toFixed(32) + s.toFixed(32)
    }

    /** 64-byte `r || s` to DER. */
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun int(b: ByteArray): ByteArray {
            val v = BigInteger(1, b).toByteArray() // minimal, with a 0x00 if the top bit is set
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private val params by lazy {
        java.security.AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    }

    /** The public key of a compressed SEC1 point, or null if it isn't on the curve. */
    fun decompress(pub33: ByteArray): ECPublicKey? {
        if (!Guardian.isP256Point(pub33)) return null
        val p = (params.curve.field as java.security.spec.ECFieldFp).p
        val x = BigInteger(1, pub33.copyOfRange(1, 33))
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p) // p ≡ 3 (mod 4)
        if (y.testBit(0) != (pub33[0] == 0x03.toByte())) y = p.subtract(y)
        val spec = java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), params)
        return java.security.KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /** ECDSA-P256-SHA256 check of [sig] over [message] (what the contract checks for a plain phone key). */
    fun verify(pub33: ByteArray, message: ByteArray, sig: P256Sig): Boolean = runCatching {
        val key = decompress(pub33) ?: return false
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(rawToDer(sig.signature)) }
    }.getOrDefault(false)

    fun signDer(key: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() }
}

/** In-memory P-256 signer for tests and tooling. */
class SoftP256Signer : P256Signer {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: ByteArray = P256.compress(kp.public as ECPublicKey)
    override fun sign(message: ByteArray) = P256Sig(P256.derToRaw(P256.signDer(kp.private, message)))
}
