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

    fun signDer(key: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() }
}

/** In-memory P-256 signer for tests and tooling. */
class SoftP256Signer : P256Signer {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: ByteArray = P256.compress(kp.public as ECPublicKey)
    override fun sign(message: ByteArray) = P256Sig(P256.derToRaw(P256.signDer(kp.private, message)))
}
