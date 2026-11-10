package app.tapvault.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger

data class SignerInfo(val index: Int, val isCard: Boolean, val keyHash: String, val key: JsonElement) {
    /** Rebuild the enrolled card key from the view (to check a tapped card locally). */
    fun cardKey(): SignerKey.EmvCard? {
        val c = key.jsonObject["EmvCard"]?.jsonObject ?: return null
        return SignerKey.EmvCard(
            c["modulus"]!!.jsonPrimitive.content.unb64(),
            c["exponent"]!!.jsonPrimitive.int,
            c["un_offset"]!!.jsonPrimitive.int,
            c["un_len"]!!.jsonPrimitive.int,
            c["terminal_data_len"]!!.jsonPrimitive.int,
        )
    }

    fun phonePublicKey(): ByteArray? = key.jsonObject["P256"]?.jsonObject?.get("pubkey")?.jsonPrimitive?.content?.unb64()
}

data class LimitInfo(val asset: JsonElement, val instantMax: BigInteger, val dailyMax: BigInteger, val updatedAt: Long, val used: BigInteger) {
    /** Port of LimitState::is_instant, using the phone's clock. */
    fun isInstant(amount: BigInteger, nowSecs: Long = System.currentTimeMillis() / 1000): Boolean {
        val day = BigInteger.valueOf(86_400)
        val elapsed = (nowSecs - updatedAt).coerceAtLeast(0)
        val usedNow = if (elapsed >= 86_400) BigInteger.ZERO
        else (used - dailyMax * BigInteger.valueOf(elapsed) / day).max(BigInteger.ZERO)
        return amount <= instantMax && usedNow + amount <= dailyMax
    }

    fun availableNow(nowSecs: Long = System.currentTimeMillis() / 1000): BigInteger {
        val elapsed = (nowSecs - updatedAt).coerceAtLeast(0)
        val usedNow = if (elapsed >= 86_400) BigInteger.ZERO
        else (used - dailyMax * BigInteger.valueOf(elapsed) / BigInteger.valueOf(86_400)).max(BigInteger.ZERO)
        return (dailyMax - usedNow).min(instantMax).max(BigInteger.ZERO)
    }
}

data class WalletState(
    val setupMode: Boolean,
    val frozen: Boolean,
    val threshold: Int,
    val freezeNonce: Long,
    val nextProposalId: Long,
    val delays: Delays,
    val signers: List<SignerInfo>,
    val limits: List<LimitInfo>,
) {
    val nearLimit get() = limits.firstOrNull { it.asset == Asset.Near.json() }
    fun card() = signers.firstOrNull { it.isCard }
    fun phoneIndexOf(pub: ByteArray) = signers.firstOrNull { it.phonePublicKey()?.contentEquals(pub) == true }?.index
}

data class ProposalInfo(
    val id: Long,
    val intentHash: ByteArray,
    val createdTs: Long,
    val approvals: Int,
    val proposer: Int,
    val readyTs: Long?,
    val cardDeadlineTs: Long?,
    val stale: Boolean,
    val action: JsonElement,
    val cardChallenge: ByteArray?,
) {
    fun approvedBy(index: Int) = approvals and (1 shl index) != 0

    /** Human-readable summary of the action. */
    fun describe(): String {
        val t = action.jsonObject["Transfer"]?.jsonObject
        if (t != null) {
            val amount = BigInteger(t["amount"]!!.jsonPrimitive.content)
            val asset = t["asset"]!!
            val what = if (asset == Asset.Near.json()) "${Near.format(amount)} NEAR" else "$amount of ${asset.jsonObject["Ft"]!!.jsonObject["token"]!!.jsonPrimitive.content}"
            return "Send $what to ${t["receiver"]!!.jsonPrimitive.content}"
        }
        val ops = action.jsonObject["Config"]!!.jsonObject["ops"]!!.jsonArray
        return "Change settings: " + ops.joinToString(", ") {
            if (it is JsonObject) it.keys.first() else it.jsonPrimitive.content
        }
    }

    val isTransfer get() = action.jsonObject.containsKey("Transfer")

    fun transferAmount(): BigInteger? =
        action.jsonObject["Transfer"]?.jsonObject?.get("amount")?.jsonPrimitive?.content?.let { BigInteger(it) }
}

class TapvaultException(message: String) : Exception(message)

/**
 * Client for one Tapvault wallet. Gas is paid by [gasAccount]; all authority
 * comes from the phone's P-256 key and the card.
 */
class TapvaultWallet(
    val near: NearClient,
    val account: String,
    val gasAccount: String,
    private val gasKey: NearKey,
) {
    // ---- views ----

    fun state(): WalletState {
        val w = near.viewFunction(account, "get_wallet").jsonObject
        val d = w["delays"]!!.jsonObject
        return WalletState(
            setupMode = w["setup_mode"]!!.jsonPrimitive.booleanOrNull!!,
            frozen = w["frozen"]!!.jsonPrimitive.booleanOrNull!!,
            threshold = w["threshold"]!!.jsonPrimitive.int,
            freezeNonce = BigInteger(w["freeze_nonce"]!!.jsonPrimitive.content).toLong(),
            nextProposalId = w["next_proposal_id"]!!.jsonPrimitive.content.toLong(),
            delays = Delays(d["transfer"]!!.jsonPrimitive.longOrNull!!, d["config"]!!.jsonPrimitive.longOrNull!!, d["recovery"]!!.jsonPrimitive.longOrNull!!),
            signers = w["signers"]!!.jsonArray.map {
                val o = it.jsonObject
                val key = o["key"]!!
                SignerInfo(o["index"]!!.jsonPrimitive.int, key.jsonObject.containsKey("EmvCard"), o["key_hash"]!!.jsonPrimitive.content, key)
            },
            limits = w["limits"]!!.jsonArray.map {
                val o = it.jsonObject
                LimitInfo(
                    o["asset"]!!,
                    BigInteger(o["instant_max"]!!.jsonPrimitive.content),
                    BigInteger(o["daily_max"]!!.jsonPrimitive.content),
                    o["updated_at"]!!.jsonPrimitive.longOrNull!!,
                    BigInteger(o["used"]!!.jsonPrimitive.content),
                )
            },
        )
    }

    private fun parseProposal(o: JsonObject) = ProposalInfo(
        id = o["id"]!!.jsonPrimitive.content.toLong(),
        intentHash = o["intent_hash"]!!.jsonPrimitive.content.unhex(),
        createdTs = o["created_ts"]!!.jsonPrimitive.longOrNull!!,
        approvals = o["approvals"]!!.jsonPrimitive.int,
        proposer = o["proposer"]!!.jsonPrimitive.int,
        readyTs = o["ready_ts"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
        cardDeadlineTs = o["card_deadline_ts"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
        stale = o["stale"]!!.jsonPrimitive.booleanOrNull!!,
        action = o["action"]!!,
        cardChallenge = o["card_challenge"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.unhex(),
    )

    fun proposal(id: Long): ProposalInfo? =
        near.viewFunction(account, "get_proposal", jsonArgs { put("id", id.toString()) })
            .takeIf { it != JsonNull }?.jsonObject?.let { parseProposal(it) }

    fun proposals(): List<ProposalInfo> =
        near.viewFunction(account, "list_proposals", jsonArgs { put("limit", 50) }).jsonArray.map { parseProposal(it.jsonObject) }

    fun balance(): BigInteger = near.balance(account)

    // ---- calls ----

    private fun call(vararg calls: NearAction.FunctionCall): TxResult {
        val r = near.send(gasAccount, gasKey, account, calls.toList())
        if (!r.ok) throw TapvaultException(r.error ?: "transaction failed")
        return r
    }

    /** Phone proposes (its signature over the intent is also its approval). */
    fun proposeByPhone(action: Action, phone: P256Signer, phoneIndex: Int): Long {
        val id = state().nextProposalId
        val intent = Domain.intentHash(account, id, action.borshBytes())
        val sig = phone.sign(intent)
        val r = call(NearAction.FunctionCall("propose", jsonArgs {
            put("action", action.json()); put("proof", SignerProof.P256(phoneIndex, sig).json())
        }))
        return r.returnValue!!.trim('"').toLong()
    }

    /** Card proposes (proof of possession only; it still needs approvals). */
    fun proposeByCard(action: Action, cardIndex: Int, tap: (challenge: ByteArray) -> CardSig): Long {
        val id = state().nextProposalId
        val intent = Domain.intentHash(account, id, action.borshBytes())
        val sig = tap(Domain.cardProposeChallenge(intent))
        val r = call(NearAction.FunctionCall("propose", jsonArgs {
            put("action", action.json()); put("proof", SignerProof.Card(cardIndex, sig).json())
        }))
        return r.returnValue!!.trim('"').toLong()
    }

    /** Wait for the contract to seal the card challenge (one block after propose). */
    fun awaitCardChallenge(id: Long, timeoutMs: Long = 20_000): ByteArray {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            proposal(id)?.cardChallenge?.let { return it }
            Thread.sleep(700)
        }
        throw TapvaultException("card challenge for proposal $id was not sealed")
    }

    fun approveCard(id: Long, cardIndex: Int, sig: CardSig) = call(NearAction.FunctionCall("approve_card", jsonArgs {
        put("id", id.toString()); put("signer_index", cardIndex); put("sig", sig.json())
    }))

    /** Approve with the card and execute in one transaction (only when it can execute now). */
    fun approveCardAndExecute(id: Long, cardIndex: Int, sig: CardSig) = call(
        NearAction.FunctionCall("approve_card", jsonArgs {
            put("id", id.toString()); put("signer_index", cardIndex); put("sig", sig.json())
        }),
        NearAction.FunctionCall("execute", jsonArgs { put("id", id.toString()) }, gasTgas = 150),
    )

    fun approvePhone(id: Long, phone: P256Signer, phoneIndex: Int): TxResult {
        val p = proposal(id) ?: throw TapvaultException("no proposal $id")
        return call(NearAction.FunctionCall("approve_p256", jsonArgs {
            put("id", id.toString()); put("signer_index", phoneIndex); put("sig", phone.sign(p.intentHash).json())
        }))
    }

    fun execute(id: Long) = call(NearAction.FunctionCall("execute", jsonArgs { put("id", id.toString()) }, gasTgas = 150))

    fun cancelByPhone(id: Long, phone: P256Signer, phoneIndex: Int): TxResult {
        val p = proposal(id) ?: throw TapvaultException("no proposal $id")
        val sig = phone.sign(Domain.cancelMessage(p.intentHash))
        return call(NearAction.FunctionCall("cancel", jsonArgs { put("id", id.toString()); put("proof", SignerProof.P256(phoneIndex, sig).json()) }))
    }

    fun freezeByPhone(phone: P256Signer, phoneIndex: Int): TxResult {
        val sig = phone.sign(Domain.freezeMessage(account, state().freezeNonce))
        return call(NearAction.FunctionCall("freeze", jsonArgs { put("proof", SignerProof.P256(phoneIndex, sig).json()) }))
    }

    fun freezeByCard(cardIndex: Int, tap: (challenge: ByteArray) -> CardSig): TxResult {
        val sig = tap(Domain.cardFreezeChallenge(account, state().freezeNonce))
        return call(NearAction.FunctionCall("freeze", jsonArgs { put("proof", SignerProof.Card(cardIndex, sig).json()) }))
    }

    fun closeStale(id: Long) = call(NearAction.FunctionCall("close_stale", jsonArgs { put("id", id.toString()) }))
}

/**
 * Creating a wallet: two fresh testnet accounts from the faucet (the wallet
 * itself, and a gas account that pays fees), then deploy + `new` in one
 * transaction signed by the wallet's temporary key. `finalize` deletes that
 * key, after which only the contract can move the wallet's funds.
 */
class Onboarding(private val near: NearClient) {
    class Accounts(val wallet: String, val walletKey: NearKey, val gas: String, val gasKey: NearKey)

    /** A funded testnet account that can create wallets when the faucet refuses. */
    class Sponsor(val account: String, val key: NearKey) {
        companion object {
            /** Enough for the contract's storage (~3.4 NEAR) plus some to send. */
            val WALLET_FUNDING: BigInteger = Near.parse("4.2")
            val GAS_FUNDING: BigInteger = Near.parse("0.3")
        }
    }

    /**
     * Two fresh accounts from the testnet faucet. The faucet is rate-limited
     * per IP; if it refuses and a [sponsor] is configured, the accounts are
     * created as sub-accounts of the sponsor and funded by it instead.
     */
    fun createAccounts(prefix: String = "tv", sponsor: Sponsor? = null, onFallback: (String) -> Unit = {}): Accounts {
        val tag = ByteArray(5).also { java.security.SecureRandom().nextBytes(it) }.hex()
        val wk = NearKey.random()
        val gk = NearKey.random()
        val faucetError = if (sponsor != null && System.getenv("TAPVAULT_FORCE_SPONSOR") == "1") "skipped" else try {
            val wallet = "$prefix-$tag.testnet"
            val gas = "$prefix-$tag-gas.testnet"
            near.createTestnetAccount(wallet, wk)
            near.createTestnetAccount(gas, gk)
            return Accounts(wallet, wk, gas, gk)
        } catch (e: Exception) {
            e.message ?: "faucet failed"
        }
        if (sponsor == null) throw TapvaultException("The testnet faucet refused ($faucetError). Try again later or from another network.")
        onFallback("faucet unavailable ($faucetError); funding from ${sponsor.account}")
        val wallet = "$prefix-$tag.${sponsor.account}"
        val gas = "$prefix-$tag-gas.${sponsor.account}"
        near.createSubAccount(sponsor.account, sponsor.key, wallet, wk, Sponsor.WALLET_FUNDING)
        near.createSubAccount(sponsor.account, sponsor.key, gas, gk, Sponsor.GAS_FUNDING)
        return Accounts(wallet, wk, gas, gk)
    }

    fun deployAndInit(a: Accounts, wasm: ByteArray, phone: P256Signer): TxResult {
        val key = phone.signerKey()
        val sig = phone.sign(Domain.createMessage(a.wallet, key.keyHash()))
        val r = near.send(a.wallet, a.walletKey, a.wallet, listOf(
            NearAction.DeployContract(wasm),
            NearAction.FunctionCall("new", jsonArgs { put("first_signer", key.json()); put("sig", sig.json()) }, gasTgas = 100),
        ))
        if (!r.ok) throw TapvaultException("deploy failed: ${r.error}")
        return r
    }

    /** Signer count before adding is 1 (just the phone). */
    fun addCard(a: Accounts, phone: P256Signer, signerCount: Int, enrolment: CardService.Enrolment): TxResult {
        val auth = phone.sign(Domain.setupAddMessage(a.wallet, signerCount, enrolment.key.keyHash()))
        val r = near.send(a.wallet, a.walletKey, a.wallet, listOf(
            NearAction.FunctionCall("setup_add_signer", jsonArgs {
                put("new_signer", enrolment.key.json())
                put("authorizer_index", 0)
                put("authorizer_sig", auth.json())
                put("possession", jsonArgs { put("Card", enrolment.proof.json()) })
            }),
        ))
        if (!r.ok) throw TapvaultException("adding the card failed: ${r.error}")
        return r
    }

    fun finalize(a: Accounts, phone: P256Signer, args: FinalizeArgs): TxResult {
        val sig = phone.sign(Domain.finalizeMessage(a.wallet, args.borshBytes()))
        val r = near.send(a.wallet, a.walletKey, a.wallet, listOf(
            NearAction.FunctionCall("setup_finalize", jsonArgs {
                put("args", args.json()); put("authorizer_index", 0); put("sig", sig.json())
            }),
        ))
        if (!r.ok) throw TapvaultException("finalize failed: ${r.error}")
        return r
    }
}
