package app.tapvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

/**
 * One asset NEAR Intents can swap ([OneClick.tokens]). [blockchain] "near"
 * means a NEP-141 token on NEAR ([contract] is its account); any other
 * chain means the token lives there and is reached through a bridge.
 */
data class IntentToken(
    val assetId: String,
    val symbol: String,
    val blockchain: String,
    val decimals: Int,
    /** USD per whole token, as 1Click reports it; null if it doesn't. */
    val price: BigDecimal?,
    val contract: String?,
    /** CoinGecko's id for it, for market data and charts; null when 1Click names none. */
    val coingeckoId: String? = null,
) {
    val onNear get() = blockchain == "near"

    /** The NEAR token contract this asset is ("nep141:<contract>" on NEAR), or null. */
    val nearContract: String? get() = if (onNear && assetId.startsWith("nep141:")) assetId.removePrefix("nep141:").takeIf { Tokens.isAccount(it) } else null
}

/** What the phone asks 1Click for (`POST /v0/quote`, `EXACT_INPUT` only). */
data class QuoteAsk(
    val originAsset: String,
    val destinationAsset: String,
    /** Base units of the origin asset. */
    val amount: BigInteger,
    /** Where the output goes: a NEAR account, or an address on the destination chain. */
    val recipient: String,
    /** Where a refund goes: on the origin chain. */
    val refundTo: String,
    val slippageBps: Int = 100,
    val deadline: Instant,
    val dry: Boolean,
) {
    fun json(): JsonElement = buildJsonObject {
        put("dry", dry)
        put("swapType", "EXACT_INPUT")
        put("slippageTolerance", slippageBps)
        put("originAsset", originAsset)
        put("depositType", "ORIGIN_CHAIN")
        put("destinationAsset", destinationAsset)
        put("amount", amount.toString())
        put("refundTo", refundTo)
        put("refundType", "ORIGIN_CHAIN")
        put("recipient", recipient)
        put("recipientType", "DESTINATION_CHAIN")
        put("deadline", deadline.toString())
    }
}

/**
 * A 1Click quote. [depositAddress] (wet quotes only) is where the origin
 * asset must be sent; [minAmountOut] is what 1Click promises at worst. The
 * phone can't verify that promise, only that the quote is for what it asked
 * ([Swap.check]).
 */
data class SwapQuote(
    val amountIn: BigInteger,
    val amountOut: BigInteger,
    val minAmountOut: BigInteger,
    val amountInUsd: String?,
    val amountOutUsd: String?,
    val timeEstimateSecs: Long,
    val depositAddress: String?,
    val depositMemo: String?,
    val deadline: Instant?,
    /** The request as 1Click echoes it back (checked against what was asked). */
    val echoed: JsonObject,
    val correlationId: String?,
    /** 1Click's signature over the quote: kept with the swap for disputes. */
    val signature: String?,
)

/** Where a swap is ([OneClick.status]). */
enum class SwapState {
    PENDING_DEPOSIT, KNOWN_DEPOSIT_TX, INCOMPLETE_DEPOSIT, PROCESSING, SUCCESS, REFUNDED, FAILED, UNKNOWN;

    val done get() = this == SUCCESS || this == REFUNDED || this == FAILED

    val label get() = when (this) {
        PENDING_DEPOSIT -> "Waiting for the deposit"
        KNOWN_DEPOSIT_TX -> "Deposit seen"
        INCOMPLETE_DEPOSIT -> "Deposit too small: will be refunded"
        PROCESSING -> "Swapping"
        SUCCESS -> "Done"
        REFUNDED -> "Refunded"
        FAILED -> "Failed"
        UNKNOWN -> "Unknown"
    }

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: UNKNOWN
    }
}

data class SwapStatus(val state: SwapState, val amountOut: BigInteger?, val destinationTx: String?, val refundReason: String?)

/**
 * NEAR Intents' 1Click API (https://1click.chaindefuser.com, mainnet only).
 * [jwt] is optional: without one 1Click adds its own fee to each quote.
 * [http] is replaceable so tests run on recorded answers.
 */
class OneClick(
    val baseUrl: String = BASE_URL,
    private val jwt: String? = null,
    private val http: (method: String, url: String, body: String?, headers: Map<String, String>) -> Pair<Int, String> = ::httpCall,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            jwt?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        val (code, text) = try {
            http(method, baseUrl + path, body?.toString(), headers)
        } catch (e: Exception) {
            throw TapvaultException("Can't reach NEAR Intents right now (${e.message ?: e.javaClass.simpleName}). Check your connection and try again.")
        }
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (code !in 200..299) {
            val msg = (parsed as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
            throw TapvaultException("NEAR Intents: " + (msg?.let { readable(it) } ?: "HTTP $code"))
        }
        return parsed ?: throw TapvaultException("NEAR Intents sent an answer Aegis can't read.")
    }

    /** Every asset 1Click swaps, with USD prices. */
    fun tokens(): List<IntentToken> = call("GET", "/v0/tokens").jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("assetId") ?: return@mapNotNull null
        val decimals = o.str("decimals")?.toIntOrNull()?.takeIf { it in 0..36 } ?: return@mapNotNull null
        IntentToken(
            assetId = id,
            symbol = Tokens.clean(o.str("symbol"), 12).ifEmpty { "?" },
            blockchain = o.str("blockchain").orEmpty(),
            decimals = decimals,
            price = o.str("price")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
            contract = o.str("contractAddress"),
            coingeckoId = o.str("coingeckoId")?.takeIf { Regex("^[a-z0-9][a-z0-9-]{0,63}$").matches(it) },
        )
    }

    fun quote(ask: QuoteAsk): SwapQuote {
        val r = call("POST", "/v0/quote", ask.json()).jsonObject
        val q = r["quote"]?.jsonObject ?: throw TapvaultException("NEAR Intents sent a quote without amounts.")
        fun big(k: String) = q.str(k)?.toBigIntegerOrNull() ?: throw TapvaultException("NEAR Intents' quote has no $k.")
        return SwapQuote(
            amountIn = big("amountIn"),
            amountOut = big("amountOut"),
            minAmountOut = big("minAmountOut"),
            amountInUsd = q.str("amountInUsd"),
            amountOutUsd = q.str("amountOutUsd"),
            timeEstimateSecs = q.str("timeEstimate")?.toDoubleOrNull()?.toLong() ?: 0,
            depositAddress = q.str("depositAddress"),
            depositMemo = q.str("depositMemo"),
            deadline = q.str("deadline")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            echoed = r["quoteRequest"]?.jsonObject ?: JsonObject(emptyMap()),
            correlationId = r.str("correlationId"),
            signature = r.str("signature"),
        )
    }

    /** Tell 1Click the deposit went out (optional; it speeds things up). */
    fun submitDeposit(txHash: String, depositAddress: String, nearSender: String?) {
        call("POST", "/v0/deposit/submit", buildJsonObject {
            put("txHash", txHash)
            put("depositAddress", depositAddress)
            nearSender?.let { put("nearSenderAccount", it) }
        })
    }

    fun status(depositAddress: String, memo: String? = null): SwapStatus {
        val q = "depositAddress=" + enc(depositAddress) + (memo?.let { "&depositMemo=" + enc(it) } ?: "")
        val r = call("GET", "/v0/status?$q").jsonObject
        val d = r["swapDetails"] as? JsonObject
        val dest = (d?.get("destinationChainTxHashes") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("hash") }
            ?: (d?.get("nearTxHashes") as? JsonArray)?.lastOrNull()?.let { (it as? JsonPrimitive)?.content }
        return SwapStatus(
            SwapState.of(r.str("status")),
            d?.str("amountOut")?.toBigIntegerOrNull(),
            dest,
            d?.str("refundReason"),
        )
    }

    companion object {
        const val BASE_URL = "https://1click.chaindefuser.com"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** 1Click's messages, said so a user can act on them. */
        fun readable(m: String): String = when {
            "amount is too low" in m.lowercase() || "too small" in m.lowercase() -> "That amount is too small to swap. Try a bigger one."
            "no quote" in m.lowercase() || "failed to get quote" in m.lowercase() -> "No one offers that swap right now. Try another amount or token."
            else -> Tokens.clean(m, 200)
        }

        fun httpCall(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                return code to text
            } finally {
                c.disconnect()
            }
        }
    }
}

/** A string field, or null when missing or JSON null. */
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

/** USD prices of NEAR-side tokens, from 1Click's list. Native NEAR is priced as wNEAR. */
class Prices(private val usd: Map<String, BigDecimal>, val at: Long = System.currentTimeMillis()) {
    /** USD per whole token for [contract], or null when unknown ("price unavailable", never 0). */
    fun of(contract: String?): BigDecimal? = usd[contract ?: NEAR]

    /** The USD value of [raw] units, or null when the price is unknown. */
    fun value(contract: String?, raw: BigInteger, decimals: Int): BigDecimal? =
        of(contract)?.let { BigDecimal(raw).movePointLeft(decimals).multiply(it) }

    companion object {
        /** The key native NEAR is priced under. */
        private const val NEAR = "wrap.near"

        /** Only verified (built-in) tokens are priced: a look-alike token never borrows a real one's price. */
        fun from(list: List<IntentToken>, verified: Set<String>): Prices =
            Prices(list.mapNotNull { t -> t.nearContract?.takeIf { it in verified }?.let { c -> t.price?.let { c to it } } }.toMap())

        fun usd(v: BigDecimal?): String = when {
            v == null -> "—"
            v.signum() > 0 && v < BigDecimal("0.01") -> "<\$0.01"
            else -> "$" + String.format(java.util.Locale.US, "%,.2f", v)
        }
    }
}
