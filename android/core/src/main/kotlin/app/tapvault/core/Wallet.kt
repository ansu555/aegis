package app.tapvault.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
    /** Bumped by every executed config change (0 if the view doesn't say). */
    val signerEpoch: Long = 0,
    /** v0.2 receiver allowlist (empty: none, or an older contract). */
    val allowlist: List<String> = emptyList(),
) {
    val nearLimit get() = limits.firstOrNull { it.asset == Asset.Near.json() }

    /**
     * Whether the contract pays [amount] NEAR to [receiver] at once: within the
     * NEAR limit and, on v0.2 wallets with an allowlist, a listed receiver
     * (`execute.rs`: instant check). [receiver] null means "not known yet".
     */
    fun isInstantTo(receiver: String?, amount: BigInteger, nowSecs: Long = System.currentTimeMillis() / 1000): Boolean =
        nearLimit?.isInstant(amount, nowSecs) == true && (allowlist.isEmpty() || receiver in allowlist)

    /** The first card signer (setup's card while no card has been removed). */
    fun card() = signers.firstOrNull { it.isCard }

    /**
     * Contract v0.3: a wallet set up without a card. Its one signer, the
     * phone key, approves alone (threshold 1) until a card is added together
     * with `SetThreshold(2)`.
     */
    val phoneOnly get() = threshold == 1 && cards().isEmpty()

    /** Every card signer. Indices move down when a signer is removed, so look cards up by key hash. */
    fun cards() = signers.filter { it.isCard }

    fun signerByKeyHash(keyHash: String) = signers.firstOrNull { it.keyHash.equals(keyHash, ignoreCase = true) }

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
    /** v0.2: the sealed seed (null before the seal, or from an older contract). */
    val cardSeed: ByteArray? = null,
    /** v0.2: bit i set ⇔ the key added by op i proved possession. */
    val possessed: Int = 0,
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
        return "Change settings: " + configOps().joinToString(", ") {
            if (it is JsonObject) it.keys.first() else it.jsonPrimitive.content
        }
    }

    private fun configOps() = action.jsonObject["Config"]?.jsonObject?.get("ops")?.jsonArray ?: emptyList()

    /** Key hashes (hex) of the cards this config proposal adds. */
    fun addedCardKeyHashes(): List<String> = configOps().mapNotNull { op ->
        val c = (op as? JsonObject)?.get("AddSigner")?.jsonObject?.get("EmvCard")?.jsonObject ?: return@mapNotNull null
        Domain.keyHashEmv(c["modulus"]!!.jsonPrimitive.content.unb64(), c["exponent"]!!.jsonPrimitive.int).hex()
    }

    /** Key hashes (hex) of the signers this config proposal removes. */
    fun removedKeyHashes(): List<String> = configOps().mapNotNull { op ->
        (op as? JsonObject)?.get("RemoveSigner")?.jsonObject?.get("key_hash")?.jsonPrimitive?.content?.unb64()?.hex()
    }

    val isTransfer get() = action.jsonObject.containsKey("Transfer")

    /**
     * The action, parsed, after checking that its intent hash recomputed on
     * the phone equals the one the view reports. The phone signs only what it
     * computed itself (invariant 10); a view that lies about either is refused.
     */
    fun verifiedAction(account: String): Pair<Action, ByteArray> {
        val a = try { ActionJson.parse(action) } catch (e: Exception) {
            throw TapvaultException("This request's action can't be read by this app version, so it won't be signed (${e.message})")
        }
        val intent = Domain.intentHash(account, id, a.borshBytes())
        if (!intent.contentEquals(intentHash)) {
            throw TapvaultException("The network's copy of request #$id doesn't match what it says it does, so the phone won't sign it. Try again later or from another network.")
        }
        return a to intent
    }

    /** A transfer of NEAR (not a token). */
    val isNearTransfer get() = action.jsonObject["Transfer"]?.jsonObject?.get("asset") == Asset.Near.json()

    fun transferReceiver(): String? =
        action.jsonObject["Transfer"]?.jsonObject?.get("receiver")?.jsonPrimitive?.content

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
            signerEpoch = w["signer_epoch"]?.jsonPrimitive?.longOrNull ?: 0,
            allowlist = w["allowlist"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
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
        cardSeed = o["card_seed"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.unhex(),
        possessed = o["possessed"]?.jsonPrimitive?.int ?: 0,
    )

    /** "0.2.0" from a v0.2 wallet; null from older code, which has no such view. */
    /**
     * The contract's `contract_version`, or null when the wallet's code has no
     * such view (v0.1). A network failure is thrown, not taken for v0.1, so a
     * flaky connection never hides v0.2 features or skips a possession proof.
     */
    fun contractVersion(): String? = try {
        near.viewFunction(account, "contract_version").jsonPrimitive.content
    } catch (e: NearRpcException) {
        val m = e.message.orEmpty()
        if (e.rpcCause == "VIEW_ERROR" || e.rpcCause == "CONTRACT_EXECUTION_ERROR" || "MethodNotFound" in m) null else throw e
    }

    /** True when this wallet runs contract v0.2 or later (possession proofs, allowlist, upgrade). */
    fun isV02(): Boolean = contractVersion()?.let { v -> v.split('.').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) > 0 || (it.getOrNull(1)?.toIntOrNull() ?: 0) >= 2 } } == true

    fun proposal(id: Long): ProposalInfo? =
        near.viewFunction(account, "get_proposal", jsonArgs { put("id", id.toString()) })
            .takeIf { it != JsonNull }?.jsonObject?.let { parseProposal(it) }

    fun proposals(): List<ProposalInfo> =
        near.viewFunction(account, "list_proposals", jsonArgs { put("limit", 50) }).jsonArray.map { parseProposal(it.jsonObject) }

    fun balance(): BigInteger = near.balance(account)

    // ---- calls ----

    private fun call(vararg calls: NearAction): TxResult {
        val r = near.send(gasAccount, gasKey, account, calls.toList())
        if (!r.ok) throw TapvaultException(r.error ?: "transaction failed")
        return r
    }

    /**
     * Phone proposes (its signature over the intent is also its approval).
     * [possession]: v0.2 possession proofs for the keys the action adds, by
     * op position; leave it empty for an older contract.
     */
    fun proposeByPhone(action: Action, phone: P256Signer, phoneIndex: Int, possession: Map<Int, PossessionProof> = emptyMap()): Long {
        val id = state().nextProposalId
        val intent = Domain.intentHash(account, id, action.borshBytes())
        val sig = phone.sign(intent)
        val r = call(proposeCall(action, SignerProof.P256(phoneIndex, sig), possession))
        return r.returnValue!!.trim('"').toLong()
    }

    private fun proposeCall(action: Action, proof: SignerProof, possession: Map<Int, PossessionProof>) =
        NearAction.FunctionCall("propose", jsonArgs {
            put("action", action.json()); put("proof", proof.json())
            if (possession.isNotEmpty()) {
                put("possession", buildJsonArray {
                    possession.forEach { (op, p) -> add(buildJsonObject { put("op", op); put("proof", p.json()) }) }
                })
            }
        })

    /** v0.2: send the possession proof of the key that op [op] of proposal [id] adds (anyone may send it). */
    fun provePossession(id: Long, op: Int, proof: PossessionProof) = call(NearAction.FunctionCall("prove_possession", jsonArgs {
        put("id", id.toString()); put("op", op); put("proof", proof.json())
    }))

    /** v0.2: run an approved UpgradeCode proposal with the new wasm (Borsh args: u64 id, then the code). */
    fun executeUpgrade(id: Long, code: ByteArray) =
        call(NearAction.RawFunctionCall("execute_upgrade", Borsh.of { u64(id); bytes(code) }, gasTgas = 300))

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

    /**
     * Card proposes when the phone doesn't know yet which card will be
     * tapped (recovery on a new phone): the challenge depends only on the
     * action and the next proposal id, so [tap] gets it first and returns
     * the signer index of the card it identified together with its signature.
     */
    fun proposeWithCard(action: Action, possession: Map<Int, PossessionProof> = emptyMap(), tap: (challenge: ByteArray) -> Pair<Int, CardSig>): Long {
        val id = state().nextProposalId
        val intent = Domain.intentHash(account, id, action.borshBytes())
        val (cardIndex, sig) = tap(Domain.cardProposeChallenge(intent))
        val r = call(proposeCall(action, SignerProof.Card(cardIndex, sig), possession))
        return r.returnValue!!.trim('"').toLong()
    }

    /** Wait for the contract to seal the card challenge (one block after propose). */
    fun awaitCardChallenge(id: Long, timeoutMs: Long = 20_000): ByteArray {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val p = proposal(id)
            p?.cardChallenge?.let { ch -> return checkedChallenge(p, ch) }
            Thread.sleep(700)
        }
        throw TapvaultException("card challenge for proposal $id was not sealed")
    }

    /**
     * v0.2 (DESIGN gap 6a): with the seed in the view, recompute the card
     * challenge from the intent this phone computes itself (from the parsed
     * action, [ProposalInfo.verifiedAction]) and refuse a view that differs.
     * An older contract doesn't return the seed; its challenge is used as is.
     */
    fun checkedChallenge(p: ProposalInfo, challenge: ByteArray): ByteArray {
        val seed = p.cardSeed ?: return challenge
        val (_, intent) = p.verifiedAction(account)
        val mine = Domain.cardApproveChallenge(intent, seed)
        if (!mine.contentEquals(challenge)) throw TapvaultException("The network's card challenge for request #${p.id} doesn't match its own seed. Nothing was signed; try another connection.")
        return mine
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

    /**
     * Phone approval of proposal [id]: the action is read from the view, its
     * intent hash recomputed here ([ProposalInfo.verifiedAction]), [beforeSign]
     * sees the parsed action (to build the prompt text from it), and the phone
     * signs the hash it computed.
     */
    fun approvePhone(id: Long, phone: P256Signer, phoneIndex: Int, beforeSign: (Action) -> Unit = {}): TxResult {
        val p = proposal(id) ?: throw TapvaultException("no proposal $id")
        val (action, intent) = p.verifiedAction(account)
        beforeSign(action)
        return call(NearAction.FunctionCall("approve_p256", jsonArgs {
            put("id", id.toString()); put("signer_index", phoneIndex); put("sig", phone.sign(intent).json())
        }))
    }

    fun execute(id: Long) = call(NearAction.FunctionCall("execute", jsonArgs { put("id", id.toString()) }, gasTgas = 150))

    /**
     * Phone cancel of proposal [id]. [beforeSign] gets the parsed action when
     * it checks out ([ProposalInfo.verifiedAction]), or null when the app
     * can't read it or it doesn't match: cancelling is still allowed then
     * (the safe direction: at worst a different request is cancelled), over
     * the view's intent hash, and the prompt must say the details are unknown.
     */
    fun cancelByPhone(id: Long, phone: P256Signer, phoneIndex: Int, beforeSign: (Action?) -> Unit = {}): TxResult {
        val p = proposal(id) ?: throw TapvaultException("no proposal $id")
        val checked = runCatching { p.verifiedAction(account) }.getOrNull()
        beforeSign(checked?.first)
        val sig = phone.sign(Domain.cancelMessage(checked?.second ?: p.intentHash))
        return call(NearAction.FunctionCall("cancel", jsonArgs { put("id", id.toString()); put("proof", SignerProof.P256(phoneIndex, sig).json()) }))
    }

    fun freezeByPhone(phone: P256Signer, phoneIndex: Int): TxResult {
        val sig = phone.sign(Domain.freezeMessage(account, state().freezeNonce))
        return call(NearAction.FunctionCall("freeze", jsonArgs { put("proof", SignerProof.P256(phoneIndex, sig).json()) }))
    }

    fun freezeByCard(cardIndex: Int, tap: (challenge: ByteArray) -> CardSig): TxResult =
        freezeByCard(cardIndex, tap(cardFreezeChallenge()))

    /** What a card signs to freeze the wallet now (public: the nonce is in `get_wallet`). */
    fun cardFreezeChallenge(): ByteArray = Domain.cardFreezeChallenge(account, state().freezeNonce)

    fun freezeByCard(cardIndex: Int, sig: CardSig): TxResult =
        call(NearAction.FunctionCall("freeze", jsonArgs { put("proof", SignerProof.Card(cardIndex, sig).json()) }))

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
            /** The contract's storage (~3.4 NEAR) plus about 2 NEAR to send. */
            val WALLET_FUNDING: BigInteger = Near.parse("5.5")
            val GAS_FUNDING: BigInteger = Near.parse("0.5")
        }
    }

    /**
     * The names and keys of a new wallet, picked (and saved by the caller)
     * before anything is created, so a retry after a crash reuses accounts
     * that were already made instead of funding new ones.
     */
    class Plan(val tag: String, val walletKey: NearKey, val gasKey: NearKey) {
        fun encode() = "$tag|${walletKey.secretString()}|${gasKey.secretString()}"

        companion object {
            fun random() = Plan(ByteArray(5).also { java.security.SecureRandom().nextBytes(it) }.hex(), NearKey.random(), NearKey.random())

            fun decode(s: String?): Plan? = s?.split("|")?.takeIf { it.size == 3 }?.let {
                runCatching { Plan(it[0], NearKey.fromSecret(it[1]), NearKey.fromSecret(it[2])) }.getOrNull()
            }
        }
    }

    /**
     * Two accounts (the wallet and its fee account) from the testnet faucet.
     * The faucet is rate-limited per IP; if it refuses and a [sponsor] is
     * configured, accounts are created as sub-accounts of the sponsor and
     * funded by it instead. Accounts from an earlier try of the same [plan]
     * are reused.
     */
    fun createAccounts(
        prefix: String = "tv",
        sponsor: Sponsor? = null,
        plan: Plan = Plan.random(),
        onFallback: (String) -> Unit = {},
    ): Accounts = createAccounts(prefix, sponsor, plan, onFallback, walletToo = true)

    /**
     * Only a fee account, for a phone that joins an existing wallet
     * (recovery, guardian): "$prefix-<tag>-gas" with [Plan.gasKey]; the
     * plan's wallet key is unused. Returns the account name.
     */
    fun createFeeAccount(
        prefix: String = "tv",
        sponsor: Sponsor? = null,
        plan: Plan = Plan.random(),
        onFallback: (String) -> Unit = {},
    ): String = createAccounts(prefix, sponsor, plan, onFallback, walletToo = false).gas

    private fun createAccounts(prefix: String, sponsor: Sponsor?, plan: Plan, onFallback: (String) -> Unit, walletToo: Boolean): Accounts {
        var faucetError: String? = if (sponsor != null && System.getenv("TAPVAULT_FORCE_SPONSOR") == "1") "skipped" else null

        fun ours(account: String, key: NearKey) =
            runCatching { near.accessKeys(account).contains(key.publicKeyString) }.getOrDefault(false)

        // Each account comes from the faucet while it cooperates, otherwise from the sponsor.
        fun create(name: String, key: NearKey, funding: BigInteger): String {
            listOfNotNull("$name.testnet", sponsor?.let { "$name.${it.account}" }).firstOrNull { ours(it, key) }?.let { return it }
            if (faucetError == null) {
                try {
                    near.createTestnetAccount("$name.testnet", key)
                    return "$name.testnet"
                } catch (e: Exception) {
                    faucetError = e.message ?: "faucet failed"
                }
            }
            if (sponsor == null) throw TapvaultException("The testnet faucet refused ($faucetError). Try again later or from another network.")
            onFallback("faucet unavailable ($faucetError); funding $name from ${sponsor.account}")
            val account = "$name.${sponsor.account}"
            near.createSubAccount(sponsor.account, sponsor.key, account, key, funding)
            return account
        }

        val wallet = if (walletToo) create("$prefix-${plan.tag}", plan.walletKey, Sponsor.WALLET_FUNDING) else ""
        val gas = create("$prefix-${plan.tag}-gas", plan.gasKey, Sponsor.GAS_FUNDING)
        return Accounts(wallet, plan.walletKey, gas, plan.gasKey)
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
    fun addCard(a: Accounts, phone: P256Signer, signerCount: Int, enrolment: CardService.Enrolment): TxResult =
        addCard(a, phone, signerCount, enrolment.key, enrolment.proof)

    /** Add a card from a saved enrolment: its key and the proof it signed for this wallet. */
    fun addCard(a: Accounts, phone: P256Signer, signerCount: Int, key: SignerKey.EmvCard, proof: CardSig): TxResult {
        val auth = phone.sign(Domain.setupAddMessage(a.wallet, signerCount, key.keyHash()))
        val r = near.send(a.wallet, a.walletKey, a.wallet, listOf(
            NearAction.FunctionCall("setup_add_signer", jsonArgs {
                put("new_signer", key.json())
                put("authorizer_index", 0)
                put("authorizer_sig", auth.json())
                put("possession", jsonArgs { put("Card", proof.json()) })
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
