package app.tapvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom

/** An ed25519 NEAR access key. Pays gas; it never authorises Tapvault actions. */
class NearKey(private val seed: ByteArray) {
    private val priv = Ed25519PrivateKeyParameters(seed, 0)
    val publicKey: ByteArray = priv.generatePublicKey().encoded
    val publicKeyString = "ed25519:" + Base58.encode(publicKey)

    fun sign(msg: ByteArray): ByteArray = Ed25519Signer().run { init(true, priv); update(msg, 0, msg.size); generateSignature() }

    /** `ed25519:<base58(seed || public key)>`, the format NEAR tools use. */
    fun secretString() = "ed25519:" + Base58.encode(seed + publicKey)

    companion object {
        fun random() = NearKey(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun fromSecret(s: String) = NearKey(Base58.decode(s.removePrefix("ed25519:")).copyOf(32))
    }
}

sealed interface NearAction {
    fun borsh(b: Borsh)

    data object CreateAccount : NearAction {
        override fun borsh(b: Borsh) { b.u8(0) }
    }

    class Transfer(val yocto: BigInteger) : NearAction {
        override fun borsh(b: Borsh) { b.u8(3).u128(yocto) }
    }

    class AddFullAccessKey(val publicKey: ByteArray) : NearAction {
        override fun borsh(b: Borsh) { b.u8(5).u8(0).fixed(publicKey).u64(0).u8(1) }
    }

    class DeployContract(val code: ByteArray) : NearAction {
        override fun borsh(b: Borsh) { b.u8(1).bytes(code) }
    }

    /**
     * Delete one access key of the signer's account, given as NEAR writes it
     * (`ed25519:<base58>` or `secp256k1:<base58>`).
     */
    class DeleteKey(val publicKey: String) : NearAction {
        override fun borsh(b: Borsh) {
            val (type, raw) = when {
                publicKey.startsWith("ed25519:") -> 0 to Base58.decode(publicKey.removePrefix("ed25519:")).also { require(it.size == 32) { "bad ed25519 key" } }
                publicKey.startsWith("secp256k1:") -> 1 to Base58.decode(publicKey.removePrefix("secp256k1:")).also { require(it.size == 64) { "bad secp256k1 key" } }
                else -> throw IllegalArgumentException("unknown key type: ${publicKey.substringBefore(':')}")
            }
            b.u8(6).u8(type).fixed(raw)
        }
    }

    /** Delete the signer's account and send everything it holds to [beneficiary]. */
    class DeleteAccount(val beneficiary: String) : NearAction {
        override fun borsh(b: Borsh) { b.u8(7).string(beneficiary) }
    }

    /** A call whose arguments are raw bytes (Borsh), e.g. the v0.2 `execute_upgrade(id, code)`. */
    class RawFunctionCall(val method: String, val args: ByteArray, val gasTgas: Long = 100) : NearAction {
        override fun borsh(b: Borsh) {
            b.u8(2).string(method).bytes(args).u64(gasTgas * 1_000_000_000_000L).u128(BigInteger.ZERO)
        }
    }

    class FunctionCall(val method: String, val args: JsonElement, val gasTgas: Long = 100, val deposit: BigInteger = BigInteger.ZERO) : NearAction {
        override fun borsh(b: Borsh) {
            b.u8(2).string(method).bytes(args.toString().toByteArray()).u64(gasTgas * 1_000_000_000_000L).u128(deposit)
        }
    }
}

/**
 * An RPC or chain error. [rpcCause] is the RPC's error cause name (e.g.
 * TIMEOUT_ERROR) when it gave one; [maybeDelivered] means no RPC gave a
 * definite answer but at least one may have received the request.
 */
class NearRpcException(
    message: String,
    val data: JsonElement? = null,
    val rpcCause: String? = null,
    val maybeDelivered: Boolean = false,
) : Exception(message)

/** Outcome of a transaction: success flag, the return value, and logs. */
class TxResult(val hash: String, val ok: Boolean, val error: String?, val returnValue: String?, val logs: List<String>)

/**
 * Minimal NEAR JSON-RPC client for one [network] (testnet by default).
 * Requests go to the first RPC in [rpcUrls]; when one is down or
 * rate-limits us, the next one is used.
 */
class NearClient(
    val network: Network = Network.TESTNET,
    val rpcUrls: List<String> = network.rpcUrls,
    val indexUrl: String = network.indexUrl,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Index of the RPC that answered last; later calls start there. */
    @Volatile private var preferred = 0

    /** Last nonce used per access key, so back-to-back transactions never reuse one. */
    private val lastNonce = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** HTTP failure that is worth retrying on another RPC (or later). */
    private class Transient(message: String) : Exception(message)

    private fun post(url: String, body: String, timeoutMs: Int = 60_000): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            // A 408 from a NEAR RPC carries a JSON-RPC error body (TIMEOUT_ERROR); rpc() reads it.
            if (code == 408 && text.trimStart().startsWith("{")) return text
            if (code == 408 || code == 429 || code in 500..599) throw Transient("HTTP $code from $url: ${text.take(200)}")
            if (code !in 200..299) throw NearRpcException("HTTP $code from $url: ${text.take(300)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    /**
     * One JSON-RPC call. Network errors, HTTP 408/429/5xx and transient RPC
     * errors move on to the next RPC (RPC-side timeouts only when
     * [retryTimeouts]); a real error from the chain (bad transaction, unknown
     * account, contract panic) is thrown at once.
     */
    fun rpc(
        method: String,
        params: JsonElement,
        timeoutMs: Int = 60_000,
        retry: Boolean = true,
        retryTimeouts: Boolean = true,
    ): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", "tv"); put("method", method); put("params", params)
        }.toString()
        var lastError: NearRpcException? = null
        var maybeDelivered = false
        val attempts = if (retry) rpcUrls.size * 2 else 1
        for (attempt in 0 until attempts) {
            val i = (preferred + attempt) % rpcUrls.size
            val text = try {
                post(rpcUrls[i], body, timeoutMs)
            } catch (e: Transient) {
                maybeDelivered = true
                lastError = NearRpcException(e.message ?: "RPC unavailable", maybeDelivered = true)
                backoff(attempt); continue
            } catch (e: java.io.IOException) {
                val neverConnected = e is java.net.ConnectException || e is java.net.UnknownHostException ||
                    e is java.net.NoRouteToHostException
                if (!neverConnected) maybeDelivered = true
                lastError = NearRpcException("can't reach ${URI(rpcUrls[i]).host}: ${e.message}", maybeDelivered = maybeDelivered)
                backoff(attempt); continue
            }
            val r = json.parseToJsonElement(text).jsonObject
            val e = r["error"]
            if (e != null) {
                val o = e.jsonObject
                val cause = o["cause"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                val msg = o["data"]?.toString() ?: o["message"]?.jsonPrimitive?.content ?: e.toString()
                val err = NearRpcException("$method: ${cause ?: ""} $msg".trim(), e, cause)
                val transient = cause in RETRYABLE_CAUSES && (retryTimeouts || cause != "TIMEOUT_ERROR")
                if (retry && transient) {
                    maybeDelivered = true
                    lastError = err; backoff(attempt); continue
                }
                throw err
            }
            preferred = i
            return r["result"]!!
        }
        val e = lastError ?: NearRpcException("$method: no RPC answered")
        throw NearRpcException(e.message ?: "", e.data, e.rpcCause, maybeDelivered)
    }

    private fun backoff(attempt: Int) = Thread.sleep(minOf(400L shl attempt, 3_000L))

    // ---- views ----

    fun viewFunction(contract: String, method: String, args: JsonElement = JsonObject(emptyMap())): JsonElement {
        val r = rpc("query", buildJsonObject {
            put("request_type", "call_function")
            put("finality", "optimistic")
            put("account_id", contract)
            put("method_name", method)
            put("args_base64", args.toString().toByteArray().b64())
        }).jsonObject
        // A failed view (no contract, a panic) comes back as result.error, not as an RPC error.
        r["error"]?.let { throw NearRpcException("$method on $contract: ${it.jsonPrimitive.content}", rpcCause = "VIEW_ERROR") }
        val bytes = r["result"]?.jsonArray?.map { it.jsonPrimitive.content.toInt().toByte() }?.toByteArray()
            ?: throw NearRpcException("$method on $contract: no result")
        return json.parseToJsonElement(bytes.decodeToString())
    }

    fun balance(account: String): BigInteger {
        val r = rpc("query", buildJsonObject {
            put("request_type", "view_account"); put("finality", "optimistic"); put("account_id", account)
        }).jsonObject
        return BigInteger(r["amount"]!!.jsonPrimitive.content)
    }

    /**
     * What the account can actually send: its balance minus the part locked
     * to pay for its storage (contract code and state, 10^19 yocto per byte).
     */
    fun spendable(account: String): Pair<BigInteger, BigInteger> {
        val r = rpc("query", buildJsonObject {
            put("request_type", "view_account"); put("finality", "optimistic"); put("account_id", account)
        }).jsonObject
        val amount = BigInteger(r["amount"]!!.jsonPrimitive.content)
        val locked = BigInteger(r["storage_usage"]!!.jsonPrimitive.content) * BigInteger.TEN.pow(19)
        return (amount - locked).max(BigInteger.ZERO) to locked
    }

    /** True if [account] exists; false only when the RPC says it doesn't (network errors are thrown). */
    fun accountExists(account: String): Boolean = try {
        balance(account)
        true
    } catch (e: NearRpcException) {
        if (e.rpcCause == "UNKNOWN_ACCOUNT" || e.message?.contains("does not exist") == true) false else throw e
    }

    /** The base58 SHA-256 of the account's contract code (`11111111111111111111111111111111` if none). */
    /**
     * Accounts that list [publicKey] as a full-access key, from FastNEAR's
     * index for [network] (no NEAR RPC answers this). The index can lag a little:
     * callers check each answer on chain ([accessKeys]) before using it.
     */
    fun accountsForKey(publicKey: String): List<String> {
        val c = URI("$indexUrl/v0/public_key/$publicKey").toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            if (c.responseCode !in 200..299) throw NearRpcException("account lookup: HTTP ${c.responseCode}")
            val r = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            return r["account_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        } catch (e: java.io.IOException) {
            throw NearRpcException("account lookup: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    fun codeHash(account: String): String {
        val r = rpc("query", buildJsonObject {
            put("request_type", "view_account"); put("finality", "final"); put("account_id", account)
        }).jsonObject
        return r["code_hash"]!!.jsonPrimitive.content
    }

    /** The account's full-access keys only (function-call keys left out). */
    fun fullAccessKeys(account: String): List<String> {
        val r = rpc("query", buildJsonObject {
            put("request_type", "view_access_key_list"); put("finality", "optimistic"); put("account_id", account)
        }).jsonObject
        return r["keys"]!!.jsonArray.map { it.jsonObject }
            .filter { it["access_key"]?.jsonObject?.get("permission")?.let { p -> p is kotlinx.serialization.json.JsonPrimitive && p.content == "FullAccess" } == true }
            .map { it["public_key"]!!.jsonPrimitive.content }
    }

    fun accessKeys(account: String): List<String> {
        val r = rpc("query", buildJsonObject {
            put("request_type", "view_access_key_list"); put("finality", "optimistic"); put("account_id", account)
        }).jsonObject
        return r["keys"]!!.jsonArray.map { it.jsonObject["public_key"]!!.jsonPrimitive.content }
    }

    private fun nonce(account: String, key: NearKey): Long {
        val r = rpc("query", buildJsonObject {
            put("request_type", "view_access_key"); put("finality", "optimistic")
            put("account_id", account); put("public_key", key.publicKeyString)
        }).jsonObject
        // A missing key comes back as result.error on some RPCs.
        r["error"]?.let { throw NearRpcException("$account has no access key ${key.publicKeyString}: ${it.jsonPrimitive.content}", rpcCause = "UNKNOWN_ACCESS_KEY") }
        return r["nonce"]?.jsonPrimitive?.long ?: throw NearRpcException("no nonce for $account")
    }

    private fun latestBlockHash(): ByteArray {
        val r = rpc("block", buildJsonObject { put("finality", "final") }).jsonObject
        return Base58.decode(r["header"]!!.jsonObject["hash"]!!.jsonPrimitive.content)
    }

    // ---- transactions ----

    /**
     * Sign and send, waiting until every receipt has executed.
     *
     * A signed transaction is only ever re-sent byte for byte (NEAR runs it at
     * most once), and it is re-signed with a fresh nonce only when the chain
     * rejected it before running it (InvalidNonce, and our hash is unknown).
     * If the RPC times out, we look the transaction up by hash instead of
     * sending a second one.
     */
    fun send(signer: String, key: NearKey, receiver: String, actions: List<NearAction>): TxResult {
        val nonceKey = "$signer/${key.publicKeyString}"
        var lastError: NearRpcException? = null
        repeat(3) {
            val nonce = maxOf(nonce(signer, key), lastNonce[nonceKey] ?: 0L) + 1
            val tx = Borsh.of {
                string(signer)
                u8(0).fixed(key.publicKey)
                u64(nonce)
                string(receiver)
                fixed(latestBlockHash())
                vec(actions) { it.borsh(this) }
            }
            val hash = sha256(tx)
            val hash58 = Base58.encode(hash)
            val signed = tx + Borsh.of { u8(0).fixed(key.sign(hash)) }
            lastNonce[nonceKey] = nonce
            val r = try {
                sendSigned(signed)
            } catch (e: NearRpcException) {
                when {
                    e.rpcCause == "TIMEOUT_ERROR" || e.maybeDelivered ->
                        // Accepted (or maybe accepted) but not finished in time: never send a second one.
                        return awaitOutcome(hash58, signer)
                    e.message?.contains("InvalidNonce") == true -> {
                        runCatching { txStatus(hash58, signer) }.getOrNull()?.let { return parseOutcome(hash58, it) }
                        Regex("\"ak_nonce\":(\\d+)").find(e.message!!)?.groupValues?.get(1)?.toLong()?.let { lastNonce[nonceKey] = it }
                        lastError = e
                        null
                    }
                    e.rpcCause == "INVALID_TRANSACTION" ->
                        throw NearRpcException("The network refused the transaction: ${readableError(e.data ?: kotlinx.serialization.json.JsonNull)}", e.data, e.rpcCause)
                    else -> throw e
                }
            } ?: return@repeat
            return parseOutcome(hash58, r)
        }
        throw lastError ?: NearRpcException("transaction could not be sent")
    }

    /** send_tx with the same signed bytes on each RPC in turn (safe: a transaction runs at most once). */
    private fun sendSigned(signed: ByteArray): JsonObject {
        val params = buildJsonObject {
            put("signed_tx_base64", signed.b64())
            put("wait_until", "EXECUTED_OPTIMISTIC")
        }
        return rpc("send_tx", params, timeoutMs = 90_000, retry = true, retryTimeouts = false).jsonObject
    }

    /**
     * The outcome of a transaction we already sent once it has executed, or
     * null if no RPC knows it yet or it is still running. Doesn't wait.
     */
    private fun txStatus(hash58: String, signer: String): JsonObject? = try {
        val r = rpc("tx", buildJsonObject {
            put("tx_hash", hash58); put("sender_account_id", signer); put("wait_until", "NONE")
        }, timeoutMs = 20_000, retryTimeouts = false).jsonObject
        val status = r["final_execution_status"]?.jsonPrimitive?.content
        r.takeIf { status in setOf("EXECUTED_OPTIMISTIC", "EXECUTED", "FINAL") && it["receipts_outcome"] != null }
    } catch (e: NearRpcException) {
        if (e.rpcCause == "UNKNOWN_TRANSACTION" || e.rpcCause == "TIMEOUT_ERROR" || e.maybeDelivered) null else throw e
    }

    /** Poll for a sent transaction's outcome for up to about two minutes. */
    private fun awaitOutcome(hash58: String, signer: String): TxResult {
        val until = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < until) {
            val r = runCatching { txStatus(hash58, signer) }.getOrNull()
            if (r != null) return parseOutcome(hash58, r)
            Thread.sleep(2_000)
        }
        throw NearRpcException(
            "The network didn't confirm transaction $hash58 in time. It may still go through: " +
                "check Activity (or ${explorerTx(hash58)}) before trying again.",
        )
    }

    private fun parseOutcome(hash: String, r: JsonObject): TxResult {
        val logs = r["receipts_outcome"]?.jsonArray?.flatMap { ro ->
            ro.jsonObject["outcome"]!!.jsonObject["logs"]!!.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList()
        // Any failed receipt (except refunds) means the call failed.
        val failure = r["receipts_outcome"]?.jsonArray?.firstNotNullOfOrNull { ro ->
            ro.jsonObject["outcome"]!!.jsonObject["status"]!!.jsonObject["Failure"]
        } ?: r["status"]?.jsonObject?.get("Failure")
        val value = r["status"]?.jsonObject?.get("SuccessValue")?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) "" else it.unb64().decodeToString()
        }
        return TxResult(hash, failure == null, failure?.let { readableError(it) }, value, logs)
    }

    private fun readableError(f: JsonElement): String {
        val s = f.toString()
        Regex("\"ExecutionError\":\"(.*?)\"").find(s)?.let { m ->
            return m.groupValues[1].replace("Smart contract panicked: ", "")
        }
        return when {
            "LackBalanceForState" in s -> "Not enough NEAR: the account must keep enough to pay for its storage"
            "NotEnoughBalance" in s || "BalanceExceeded" in s -> "Not enough NEAR in the account"
            "AccountDoesNotExist" in s -> "That account doesn't exist on ${network.displayName}"
            "AccountAlreadyExists" in s -> "That account already exists"
            "AccessKeyNotFound" in s || "InvalidAccessKeyError" in s -> "This key can't sign for the account any more"
            else -> s.take(300)
        }
    }

    /** Create `<name>.testnet` with [key] via the testnet faucet (10 NEAR). Refused on a network without one. */
    fun createTestnetAccount(account: String, key: NearKey) {
        val faucet = network.faucetUrl ?: throw NearRpcException("${network.displayName} has no faucet")
        require(account.endsWith(network.accountSuffix)) { "$account isn't a ${network.displayName} account" }
        val body = buildJsonObject { put("newAccountId", account); put("newAccountPublicKey", key.publicKeyString) }
        post(faucet, body.toString(), 120_000)
        repeat(20) {
            if (runCatching { accountExists(account) }.getOrDefault(false)) return
            Thread.sleep(1500)
        }
        throw NearRpcException("faucet did not create $account")
    }

    /** Create `account` (a sub-account of [sponsor]) with [key] and [amount] from the sponsor. */
    fun createSubAccount(sponsor: String, sponsorKey: NearKey, account: String, key: NearKey, amount: BigInteger): TxResult {
        require(account.endsWith(".$sponsor")) { "$account isn't a sub-account of $sponsor" }
        val r = send(sponsor, sponsorKey, account, listOf(NearAction.CreateAccount, NearAction.Transfer(amount), NearAction.AddFullAccessKey(key.publicKey)))
        if (!r.ok) throw NearRpcException("creating $account failed: ${r.error}")
        return r
    }

    /** Delete [account] (signed with its own full-access [key]); its NEAR goes to [beneficiary]. */
    fun deleteAccount(account: String, key: NearKey, beneficiary: String): TxResult {
        val r = send(account, key, account, listOf(NearAction.DeleteAccount(beneficiary)))
        if (!r.ok) throw NearRpcException("deleting $account failed: ${r.error}")
        return r
    }

    fun explorerTx(hash: String) = network.explorerTx(hash)
    fun explorerAccount(account: String) = network.explorerAccount(account)

    companion object {
        /** The `code_hash` of an account with no contract. */
        const val NO_CODE = "11111111111111111111111111111111"

        /** An implicit account's name: the hex of its ed25519 public key. */
        fun implicitAccount(publicKey: ByteArray): String = publicKey.hex()

        /** Public testnet RPCs, tried in this order ([Network.TESTNET]). */
        val TESTNET_RPCS get() = Network.TESTNET.rpcUrls

        /** RPC error causes that say "try again", not "this is wrong". */
        private val RETRYABLE_CAUSES = setOf(
            "TIMEOUT_ERROR", "NO_SYNCED_BLOCKS", "NOT_SYNCED_YET", "INTERNAL_ERROR", "UNKNOWN_BLOCK", "UNAVAILABLE_SHARD",
        )
    }
}

fun jsonArgs(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

fun jsonList(vararg e: JsonElement) = buildJsonArray { e.forEach { add(it) } }
