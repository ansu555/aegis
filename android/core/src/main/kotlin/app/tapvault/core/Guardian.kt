package app.tapvault.core

import java.math.BigInteger

/**
 * Guardian phone (plan 5.10): a second phone, usually a family member's,
 * whose Keystore P-256 key is added to the wallet as one more signer.
 *
 * What that key can do is exactly what the owner's phone key can: cancel,
 * freeze, approve, and (because a P-256 key counts as user-verified) approve
 * a payment together with ANY one card. So it is a full co-signer, not a
 * read-only watcher, and the app says so before the owner adds one.
 *
 * The guardian phone shows its key as a short text (QR or copy/paste):
 * `tapvault:guardian:1:<wallet>:<33-byte key hex>:<64-byte sig hex>:<check>`.
 * The signature is the guardian key's possession proof over
 * [Domain.possessionMessage] for that wallet (contract v0.2 requires it
 * before the key can be added; older wallets ignore it). check is the first
 * 4 bytes of SHA-256 over everything before it. The wallet name stops a key
 * offered for one wallet from being added to another, and the check catches
 * typing and scanning mistakes.
 */
object Guardian {
    const val PREFIX = "tapvault:guardian:1:"

    const val WARNING =
        "A guardian phone is a full co-signer. Together with any one of your cards it can approve a payment, " +
            "and on its own it can cancel requests and freeze the wallet. Only add a phone you would trust with a card."

    data class Offer(val wallet: String, val key: SignerKey.P256, val possession: P256Sig) {
        val proof get() = PossessionProof.P256(possession)
    }

    /** What the guardian phone signs (with its fingerprint) to make its code. */
    fun messageToSign(wallet: String, pubkey: ByteArray) = Domain.possessionMessage(wallet, SignerKey.P256(pubkey).keyHash())

    fun encode(wallet: String, pubkey: ByteArray, possession: P256Sig): String {
        val body = "$PREFIX$wallet:${pubkey.hex()}:${possession.signature.hex()}"
        return "$body:${check(body)}"
    }

    /** Sign and encode in one step (the guardian phone). */
    fun offer(wallet: String, signer: P256Signer): String = encode(wallet, signer.publicKey, signer.sign(messageToSign(wallet, signer.publicKey)))

    /** Parses and checks an offer; throws [TapvaultException] with a readable reason. */
    fun decode(text: String): Offer {
        val s = text.trim()
        if (!s.startsWith(PREFIX)) throw TapvaultException("That isn't a Tapvault guardian code.")
        val parts = s.removePrefix(PREFIX).split(":")
        if (parts.size != 4) throw TapvaultException("That guardian code is incomplete. Copy or scan it again.")
        val (wallet, keyHex, sigHex, sum) = parts
        if (check("$PREFIX$wallet:$keyHex:$sigHex") != sum.lowercase()) throw TapvaultException("That guardian code has a typo or was cut off. Copy or scan it again.")
        val key = runCatching { keyHex.unhex() }.getOrNull()
        if (key == null || !isP256Point(key)) throw TapvaultException("That guardian code doesn't hold a valid phone key.")
        val sig = runCatching { P256Sig(sigHex.unhex()) }.getOrNull()
        if (sig == null || !P256.verify(key, messageToSign(wallet, key), sig)) {
            throw TapvaultException("That guardian code's signature doesn't check out. Make a new code on the guardian phone.")
        }
        return Offer(wallet, SignerKey.P256(key), sig)
    }

    /** The settings change that adds [offer]'s key to [state] of [account]. */
    fun addAction(account: String, state: WalletState, offer: Offer): Action.Config {
        if (offer.wallet != account) throw TapvaultException("That guardian code is for ${offer.wallet}, not this wallet.")
        if (state.signerByKeyHash(offer.key.keyHash().hex()) != null) throw TapvaultException("That phone is already a signer of this wallet.")
        if (state.signers.size >= Recovery.MAX_SIGNERS) throw TapvaultException("This wallet already has ${Recovery.MAX_SIGNERS} signers, the most it can have.")
        return Action.Config(listOf(ConfigOp.AddSigner(offer.key)))
    }

    private fun check(body: String) = sha256(body.toByteArray()).copyOf(4).hex()

    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /** A compressed SEC1 P-256 point whose x is on the curve (y² = x³ − 3x + b has a root). */
    fun isP256Point(k: ByteArray): Boolean {
        if (k.size != 33 || (k[0] != 0x02.toByte() && k[0] != 0x03.toByte())) return false
        val x = BigInteger(1, k.copyOfRange(1, 33))
        if (x >= P) return false
        val rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        if (rhs.signum() == 0) return true
        return rhs.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE
    }
}
