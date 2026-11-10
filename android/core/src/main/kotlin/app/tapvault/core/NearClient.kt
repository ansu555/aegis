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

    class DeployContract(val code: ByteArray) : NearAction {
        override fun borsh(b: Borsh) { b.u8(1).bytes(code) }
    }

    class FunctionCall(val method: String, val args: JsonElement, val gasTgas: Long = 100, val deposit: BigInteger = BigInteger.ZERO) : NearAction {
        override fun borsh(b: Borsh) {
            b.u8(2).string(method).bytes(args.toString().toByteArray()).u64(gasTgas * 1_000_000_000_000L).u128(deposit)
        }
    }
}

class NearRpcException(message: String, val data: JsonElement? = null) : Exception(message)

/** Outcome of a transaction: success flag, the return value, and logs. */
class TxResult(val hash: String, val ok: Boolean, val error: String?, val returnValue: String?, val logs: List<String>)

/** Minimal NEAR JSON-RPC client (testnet by default). */
class NearClient(
    val rpcUrl: String = "https://rpc.testnet.fastnear.com",
    val faucetUrl: String = "https://helper.nearprotocol.com/account",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun post(url: String, body: String, timeoutMs: Int = 60_000): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20_000
        c.readTimeout = timeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        if (c.responseCode !in 200..299) throw NearRpcException("HTTP ${c.responseCode} from $url: ${text.take(300)}")
        return text
    }

    fun rpc(method: String, params: JsonElement, timeoutMs: Int = 60_000): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", "tv"); put("method", method); put("params", params)
        }
        val r = json.parseToJsonElement(post(rpcUrl, body.toString(), timeoutMs)).jsonObject
        r["error"]?.let { e ->
            val o = e.jsonObject
            val cause = o["cause"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            val msg = o["data"]?.toString() ?: o["message"]?.jsonPrimitive?.content ?: e.toString()
            throw NearRpcException("$method: ${cause ?: ""} $msg".trim(), e)
        }
        return r["result"]!!
    }

    // ---- views ----

    fun viewFunction(contract: String, method: String, args: JsonElement = JsonObject(emptyMap())): JsonElement {
        val r = rpc("query", buildJsonObject {
            put("request_type", "call_function")
            put("finality", "optimistic")
            put("account_id", contract)
            put("method_name", method)
            put("args_base64", args.toString().toByteArray().b64())
        }).jsonObject
        val bytes = r["result"]!!.jsonArray.map { it.jsonPrimitive.content.toInt().toByte() }.toByteArray()
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

    fun accountExists(account: String) = runCatching { balance(account) }.isSuccess

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
        return r["nonce"]!!.jsonPrimitive.long
    }

    private fun latestBlockHash(): ByteArray {
        val r = rpc("block", buildJsonObject { put("finality", "final") }).jsonObject
        return Base58.decode(r["header"]!!.jsonObject["hash"]!!.jsonPrimitive.content)
    }

    // ---- transactions ----

    /** Sign and send, waiting until every receipt has executed. */
    fun send(signer: String, key: NearKey, receiver: String, actions: List<NearAction>): TxResult {
        val tx = Borsh.of {
            string(signer)
            u8(0).fixed(key.publicKey)
            u64(nonce(signer, key) + 1)
            string(receiver)
            fixed(latestBlockHash())
            vec(actions) { it.borsh(this) }
        }
        val hash = sha256(tx)
        val signed = tx + Borsh.of { u8(0).fixed(key.sign(hash)) }
        val r = rpc("send_tx", buildJsonObject {
            put("signed_tx_base64", signed.b64())
            put("wait_until", "EXECUTED_OPTIMISTIC")
        }, timeoutMs = 120_000).jsonObject
        return parseOutcome(Base58.encode(hash), r)
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
        return s.take(300)
    }

    /** Create `<name>.testnet` with [key] via the testnet faucet (10 NEAR). */
    fun createTestnetAccount(account: String, key: NearKey) {
        val body = buildJsonObject { put("newAccountId", account); put("newAccountPublicKey", key.publicKeyString) }
        post(faucetUrl, body.toString(), 120_000)
        repeat(20) {
            if (accountExists(account)) return
            Thread.sleep(1500)
        }
        throw NearRpcException("faucet did not create $account")
    }

    fun explorerTx(hash: String) = "https://testnet.nearblocks.io/txns/$hash"
    fun explorerAccount(account: String) = "https://testnet.nearblocks.io/address/$account"
}

fun jsonArgs(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

fun jsonList(vararg e: JsonElement) = buildJsonArray { e.forEach { add(it) } }
