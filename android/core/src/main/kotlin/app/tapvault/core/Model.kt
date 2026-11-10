package app.tapvault.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger

/**
 * Kotlin mirrors of the contract's argument types (contract/src/types.rs).
 * `borsh()` must match the Rust encoding byte for byte (it is what gets
 * signed); `json()` is the call-argument form. Both are pinned by vectors.json.
 */
sealed interface Asset {
    fun borsh(b: Borsh)
    fun json(): JsonElement

    data object Near : Asset {
        override fun borsh(b: Borsh) { b.u8(0) }
        override fun json() = JsonPrimitive("Near")
    }

    data class Ft(val token: String) : Asset {
        override fun borsh(b: Borsh) { b.u8(1).string(token) }
        override fun json() = buildJsonObject { put("Ft", buildJsonObject { put("token", token) }) }
    }
}

data class WebAuthnConfig(val rpIdHash: ByteArray, val requireUv: Boolean)

sealed interface SignerKey {
    fun borsh(b: Borsh)
    fun json(): JsonElement
    fun keyHash(): ByteArray

    /** Compressed SEC1 P-256 public key (33 bytes). */
    class P256(val pubkey: ByteArray, val webauthn: WebAuthnConfig? = null) : SignerKey {
        override fun borsh(b: Borsh) {
            b.u8(0).bytes(pubkey).option(webauthn) { bytes(it.rpIdHash).bool(it.requireUv) }
        }

        override fun json() = buildJsonObject {
            put("P256", buildJsonObject {
                put("pubkey", pubkey.b64())
                put("webauthn", webauthn?.let {
                    buildJsonObject { put("rp_id_hash", it.rpIdHash.b64()); put("require_uv", it.requireUv) }
                } ?: JsonNull)
            })
        }

        override fun keyHash() = Domain.keyHashP256(pubkey)
    }

    class EmvCard(
        val modulus: ByteArray,
        val exponent: Int,
        val unOffset: Int,
        val unLen: Int,
        val terminalDataLen: Int,
    ) : SignerKey {
        override fun borsh(b: Borsh) {
            b.u8(1).bytes(modulus).u32(exponent).u8(unOffset).u8(unLen).u8(terminalDataLen)
        }

        override fun json() = buildJsonObject {
            put("EmvCard", buildJsonObject {
                put("modulus", modulus.b64())
                put("exponent", exponent)
                put("un_offset", unOffset)
                put("un_len", unLen)
                put("terminal_data_len", terminalDataLen)
            })
        }

        override fun keyHash() = Domain.keyHashEmv(modulus, exponent)
    }
}

data class Delays(val transfer: Long, val config: Long, val recovery: Long) {
    fun borsh(b: Borsh) { b.i64(transfer).i64(config).i64(recovery) }
    fun json() = buildJsonObject { put("transfer", transfer); put("config", config); put("recovery", recovery) }

    companion object {
        /** The shortest delays the contract accepts; handy for demos. */
        val SHORTEST = Delays(transfer = 3_600, config = 3_600, recovery = 86_400)
    }
}

data class LimitInit(val asset: Asset, val instantMax: BigInteger, val dailyMax: BigInteger) {
    fun borsh(b: Borsh) { asset.borsh(b); b.u128(instantMax).u128(dailyMax) }
    fun json() = buildJsonObject {
        put("asset", asset.json())
        put("instant_max", instantMax.toString())
        put("daily_max", dailyMax.toString())
    }
}

sealed interface ConfigOp {
    fun borsh(b: Borsh)
    fun json(): JsonElement

    class AddSigner(val key: SignerKey) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(0); key.borsh(b) }
        override fun json() = buildJsonObject { put("AddSigner", key.json()) }
    }

    class RemoveSigner(val keyHash: ByteArray) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(1).bytes(keyHash) }
        override fun json() = buildJsonObject { put("RemoveSigner", buildJsonObject { put("key_hash", keyHash.b64()) }) }
    }

    class SetThreshold(val threshold: Int) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(2).u8(threshold) }
        override fun json() = buildJsonObject { put("SetThreshold", threshold) }
    }

    class SetLimit(val limit: LimitInit) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(3); limit.borsh(b) }
        override fun json() = buildJsonObject { put("SetLimit", limit.json()) }
    }

    class RemoveLimit(val asset: Asset) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(4); asset.borsh(b) }
        override fun json() = buildJsonObject { put("RemoveLimit", buildJsonObject { put("asset", asset.json()) }) }
    }

    class SetDelays(val delays: Delays) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(5); delays.borsh(b) }
        override fun json() = buildJsonObject { put("SetDelays", delays.json()) }
    }

    data object Unfreeze : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(6) }
        override fun json() = JsonPrimitive("Unfreeze")
    }
}

sealed interface Action {
    fun borsh(b: Borsh)
    fun json(): JsonElement
    fun borshBytes(): ByteArray = Borsh().also { borsh(it) }.toByteArray()

    class Transfer(val asset: Asset, val amount: BigInteger, val receiver: String) : Action {
        override fun borsh(b: Borsh) { b.u8(0); asset.borsh(b); b.u128(amount).string(receiver) }
        override fun json() = buildJsonObject {
            put("Transfer", buildJsonObject {
                put("asset", asset.json())
                put("amount", amount.toString())
                put("receiver", receiver)
            })
        }
    }

    class Config(val ops: List<ConfigOp>) : Action {
        override fun borsh(b: Borsh) { b.u8(1).vec(ops) { it.borsh(this) } }
        override fun json() = buildJsonObject {
            put("Config", buildJsonObject { put("ops", buildJsonArray { ops.forEach { add(it.json()) } }) })
        }
    }
}

data class FinalizeArgs(val threshold: Int, val delays: Delays, val limits: List<LimitInit>) {
    fun borshBytes(): ByteArray = Borsh.of {
        u8(threshold)
        delays.borsh(this)
        vec(limits) { it.borsh(this) }
    }

    fun json() = buildJsonObject {
        put("threshold", threshold)
        put("delays", delays.json())
        put("limits", buildJsonArray { limits.forEach { add(it.json()) } })
    }
}

/** 64-byte `r || s` P-256 signature. */
class P256Sig(val signature: ByteArray) {
    init { require(signature.size == 64) }
    fun json() = buildJsonObject { put("signature", signature.b64()); put("webauthn", JsonNull) }
}

class CardSig(val sdad: ByteArray, val terminalData: ByteArray) {
    fun json() = buildJsonObject { put("sdad", sdad.b64()); put("terminal_data", terminalData.b64()) }
}

sealed interface SignerProof {
    fun json(): JsonElement

    class P256(val signerIndex: Int, val sig: P256Sig) : SignerProof {
        override fun json() = buildJsonObject {
            put("P256", buildJsonObject { put("signer_index", signerIndex); put("sig", sig.json()) })
        }
    }

    class Card(val signerIndex: Int, val sig: CardSig) : SignerProof {
        override fun json() = buildJsonObject {
            put("Card", buildJsonObject { put("signer_index", signerIndex); put("sig", sig.json()) })
        }
    }
}
