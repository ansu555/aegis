package app.tapvault

import app.tapvault.core.CardSig
import app.tapvault.core.SignerKey
import app.tapvault.core.hex
import app.tapvault.core.unhex

/**
 * A card read and signed during setup, kept until it has been added on-chain,
 * so a retry after a crash or network error doesn't need another tap. The
 * proof only works for this wallet's setup (it signs the wallet's enrolment
 * challenge), and there is no PAN in it.
 */
class PendingCard(val key: SignerKey.EmvCard, val proof: CardSig, val network: String, val last4: String) {
    fun encode() = listOf(
        key.modulus.hex(), key.exponent, key.unOffset, key.unLen, key.terminalDataLen,
        proof.sdad.hex(), proof.terminalData.hex(), network, last4,
    ).joinToString("|")

    companion object {
        fun decode(s: String?): PendingCard? = s?.split("|")?.takeIf { it.size == 9 }?.let { f ->
            runCatching {
                PendingCard(
                    SignerKey.EmvCard(f[0].unhex(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt()),
                    CardSig(f[5].unhex(), f[6].unhex()),
                    f[7], f[8],
                )
            }.getOrNull()
        }
    }
}
