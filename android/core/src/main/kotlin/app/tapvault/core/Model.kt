package app.tapvault.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    // ---- contract v0.2 only (an older wallet refuses to parse these) ----

    /** Once the allowlist has an entry, only listed receivers are paid instantly. */
    class AllowReceiver(val receiver: String) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(7).string(receiver) }
        override fun json() = buildJsonObject { put("AllowReceiver", buildJsonObject { put("receiver", receiver) }) }
    }

    class DisallowReceiver(val receiver: String) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(8).string(receiver) }
        override fun json() = buildJsonObject { put("DisallowReceiver", buildJsonObject { put("receiver", receiver) }) }
    }

    /** Replace the wallet's code with the wasm of this SHA-256; waits the recovery delay. */
    class UpgradeCode(val codeHash: ByteArray) : ConfigOp {
        override fun borsh(b: Borsh) { b.u8(9).bytes(codeHash) }
        override fun json() = buildJsonObject { put("UpgradeCode", buildJsonObject { put("code_hash", codeHash.b64()) }) }
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

/** v0.2: a new key's signature over [Domain.possessionMessage] / [Domain.cardPossessionChallenge]. */
sealed interface PossessionProof {
    fun json(): JsonElement

    class P256(val sig: P256Sig) : PossessionProof {
        override fun json() = buildJsonObject { put("P256", sig.json()) }
    }

    class Card(val sig: CardSig) : PossessionProof {
        override fun json() = buildJsonObject { put("Card", sig.json()) }
    }
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

/**
 * Parse an action from its JSON form (`get_proposal` / `list_proposals`
 * views, the same form [Action.json] writes). Used to recompute the intent
 * hash locally before the phone signs (invariant 10, DESIGN gap 6b) and to
 * build the prompt text from the same values. Throws on anything unknown.
 */
object ActionJson {
    fun parse(j: JsonElement): Action {
        val o = j.jsonObject
        o["Transfer"]?.jsonObject?.let { t ->
            return Action.Transfer(asset(t["asset"]!!), BigInteger(t["amount"]!!.jsonPrimitive.content), t["receiver"]!!.jsonPrimitive.content)
        }
        val ops = o["Config"]?.jsonObject?.get("ops")?.jsonArray ?: throw IllegalArgumentException("unknown action")
        return Action.Config(ops.map { op(it) })
    }

    private fun asset(j: JsonElement): Asset = when {
        j is JsonPrimitive && j.content == "Near" -> Asset.Near
        else -> Asset.Ft(j.jsonObject["Ft"]!!.jsonObject["token"]!!.jsonPrimitive.content)
    }

    private fun delays(j: JsonElement) = j.jsonObject.let {
        Delays(it["transfer"]!!.jsonPrimitive.long, it["config"]!!.jsonPrimitive.long, it["recovery"]!!.jsonPrimitive.long)
    }

    fun signerKey(j: JsonElement): SignerKey {
        val o = j.jsonObject
        o["P256"]?.jsonObject?.let { p ->
            val w = p["webauthn"]?.takeIf { it != JsonNull }?.jsonObject
            return SignerKey.P256(
                p["pubkey"]!!.jsonPrimitive.content.unb64(),
                w?.let { WebAuthnConfig(it["rp_id_hash"]!!.jsonPrimitive.content.unb64(), it["require_uv"]!!.jsonPrimitive.boolean) },
            )
        }
        val c = o["EmvCard"]?.jsonObject ?: throw IllegalArgumentException("unknown signer key")
        return SignerKey.EmvCard(
            c["modulus"]!!.jsonPrimitive.content.unb64(), c["exponent"]!!.jsonPrimitive.int,
            c["un_offset"]!!.jsonPrimitive.int, c["un_len"]!!.jsonPrimitive.int, c["terminal_data_len"]!!.jsonPrimitive.int,
        )
    }

    private fun op(j: JsonElement): ConfigOp {
        if (j is JsonPrimitive) {
            require(j.content == "Unfreeze") { "unknown config op ${j.content}" }
            return ConfigOp.Unfreeze
        }
        val (name, v) = j.jsonObject.entries.single()
        return when (name) {
            "AddSigner" -> ConfigOp.AddSigner(signerKey(v))
            "RemoveSigner" -> ConfigOp.RemoveSigner(v.jsonObject["key_hash"]!!.jsonPrimitive.content.unb64())
            "SetThreshold" -> ConfigOp.SetThreshold(v.jsonPrimitive.int)
            "SetLimit" -> v.jsonObject.let {
                ConfigOp.SetLimit(LimitInit(asset(it["asset"]!!), BigInteger(it["instant_max"]!!.jsonPrimitive.content), BigInteger(it["daily_max"]!!.jsonPrimitive.content)))
            }
            "RemoveLimit" -> ConfigOp.RemoveLimit(asset(v.jsonObject["asset"]!!))
            "SetDelays" -> ConfigOp.SetDelays(delays(v))
            "AllowReceiver" -> ConfigOp.AllowReceiver(v.jsonObject["receiver"]!!.jsonPrimitive.content)
            "DisallowReceiver" -> ConfigOp.DisallowReceiver(v.jsonObject["receiver"]!!.jsonPrimitive.content)
            "UpgradeCode" -> ConfigOp.UpgradeCode(v.jsonObject["code_hash"]!!.jsonPrimitive.content.unb64())
            else -> throw IllegalArgumentException("unknown config op $name")
        }
    }
}

/** What the phone's prompt says an action does, built from the action itself (the values that get hashed). */
fun Action.describe(): String = when (this) {
    is Action.Transfer -> {
        val what = when (asset) {
            Asset.Near -> "${Near.format(amount)} NEAR"
            is Asset.Ft -> "$amount of ${asset.token}"
        }
        "Send $what to $receiver"
    }
    is Action.Config -> "Change settings: " + ops.joinToString(", ") { op ->
        when (op) {
            is ConfigOp.AddSigner -> if (op.key is SignerKey.EmvCard) "add a card" else "add a phone key"
            is ConfigOp.RemoveSigner -> "remove signer ${op.keyHash.hex().take(8)}"
            is ConfigOp.SetThreshold -> "approvals needed ${op.threshold}"
            is ConfigOp.SetLimit -> "limit ${Near.format(op.limit.instantMax)} at once, ${Near.format(op.limit.dailyMax)} a day"
            is ConfigOp.RemoveLimit -> "remove a limit"
            is ConfigOp.SetDelays -> "waits ${op.delays.transfer / 60} / ${op.delays.config / 60} / ${op.delays.recovery / 60} min"
            ConfigOp.Unfreeze -> "unfreeze"
            is ConfigOp.AllowReceiver -> "pay ${op.receiver} instantly"
            is ConfigOp.DisallowReceiver -> "stop instant payments to ${op.receiver}"
            is ConfigOp.UpgradeCode -> "REPLACE THE WALLET CODE with build ${Base58.encode(op.codeHash)}"
        }
    }
}
