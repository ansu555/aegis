package app.tapvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.net.URLEncoder
import java.util.Locale

/**
 * One coin as the market shows it (CoinGecko `/coins/markets`). Read only:
 * nothing here is ever signed, and none of it values a wallet's balance
 * (that is [Prices], from NEAR Intents, verified tokens only).
 */
data class MarketCoin(
    val id: String,
    val symbol: String,
    val name: String,
    val price: Double?,
    val change24h: Double?,
    val marketCap: Double?,
    val volume24h: Double?,
    val high24h: Double?,
    val low24h: Double?,
    val ath: Double?,
    val rank: Int?,
    /** CoinGecko's logo for it (https, CoinGecko's image host only), or null. */
    val image: String? = null,
    /** About 7 days of hourly prices, oldest first; empty when CoinGecko has none. */
    val sparkline: List<Double>,
)

/** A token trending on NEAR right now (GeckoTerminal `trending_pools`): often new, often a meme. */
data class TrendingToken(
    /** The token's NEAR account. */
    val address: String,
    val symbol: String,
    /** The pool it trends in ("BLACKDRAGON / wNEAR"), for its chart. */
    val pool: String,
    val poolName: String,
    val price: Double?,
    val change1h: Double?,
    val change24h: Double?,
    val volume24h: Double?,
    val liquidity: Double?,
    val marketCap: Double?,
    /** When its pool was created, epoch millis; null when unknown. */
    val createdAt: Long?,
)

/** A price series for a chart: [times] in epoch millis, oldest first, as long as [prices]. */
data class PriceSeries(val times: List<Long>, val prices: List<Double>) {
    val first get() = prices.firstOrNull()
    val last get() = prices.lastOrNull()

    /** Percent change across the series, or null when it is too short or starts at 0. */
    val change: Double? get() {
        val a = first ?: return null
        val b = last ?: return null
        return if (prices.size < 2 || a == 0.0) null else (b - a) / a * 100
    }

    fun takeLast(ms: Long): PriceSeries {
        val end = times.lastOrNull() ?: return this
        val i = times.indexOfFirst { it >= end - ms }.coerceAtLeast(0)
        return PriceSeries(times.drop(i), prices.drop(i))
    }
}

/** Chart ranges the market screens offer. */
enum class ChartRange(val label: String, val days: Int, val ms: Long) {
    H1("1H", 1, 3_600_000L),
    D1("1D", 1, 86_400_000L),
    W1("1W", 7, 7 * 86_400_000L),
    M1("1M", 30, 30 * 86_400_000L),
    Y1("1Y", 365, 365 * 86_400_000L),
}

/**
 * Mainnet market data for the Trade screen: prices, changes, charts and
 * what is trending. Public APIs without keys; every request carries only
 * coin ids, token contracts and pool addresses, never anything about the
 * wallet. It works the same on any network, because these markets only
 * exist on mainnet (testnet shows them as a preview).
 */
class Markets(
    private val coingecko: String = COINGECKO,
    private val geckoTerminal: String = GECKOTERMINAL,
    private val http: (method: String, url: String, body: String?, headers: Map<String, String>) -> Pair<Int, String> = { m, u, b, h -> OneClick.httpCall(m, u, b, h) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun get(url: String, accept: String = "application/json"): JsonElement {
        val (code, text) = try {
            http("GET", url, null, mapOf("Accept" to accept))
        } catch (e: Exception) {
            throw TapvaultException("Can't reach market data right now (${e.message ?: e.javaClass.simpleName}).")
        }
        if (code == 429) throw TapvaultException("Market data is busy. Try again in a minute.")
        if (code !in 200..299) throw TapvaultException("Market data: HTTP $code")
        return runCatching { json.parseToJsonElement(text) }.getOrNull() ?: throw TapvaultException("Market data came back in a form Tapvault can't read.")
    }

    /** Market data for CoinGecko [ids] (at most 250), largest market cap first. */
    fun coins(ids: Collection<String>): List<MarketCoin> {
        val clean = ids.filter { ID.matches(it) }.distinct().take(250)
        if (clean.isEmpty()) return emptyList()
        val url = "$coingecko/coins/markets?vs_currency=usd&per_page=250&sparkline=true&price_change_percentage=24h&ids=" + enc(clean.joinToString(","))
        return get(url).jsonArray.mapNotNull { e -> (e as? JsonObject)?.let(::coin) }
            .sortedWith(compareBy({ it.rank == null }, { it.rank ?: Int.MAX_VALUE }))
    }

    /** The price of CoinGecko coin [id] over [range]. */
    fun chart(id: String, range: ChartRange): PriceSeries {
        require(ID.matches(id)) { "not a coin id" }
        val o = get("$coingecko/coins/${enc(id)}/market_chart?vs_currency=usd&days=${range.days}").jsonObject
        val s = series((o["prices"] as? JsonArray) ?: JsonArray(emptyList()), timeIndex = 0, priceIndex = 1, seconds = false)
        return if (range == ChartRange.H1) s.takeLast(range.ms) else s
    }

    /** The USD price of [token] in [pool] over [range], from candles (for tokens CoinGecko doesn't list). */
    fun poolChart(pool: String, token: String, range: ChartRange): PriceSeries {
        val (frame, agg, limit) = when (range) {
            ChartRange.H1 -> Triple("minute", 5, 12)
            ChartRange.D1 -> Triple("minute", 15, 96)
            ChartRange.W1 -> Triple("hour", 4, 42)
            ChartRange.M1 -> Triple("day", 1, 30)
            ChartRange.Y1 -> Triple("day", 1, 365)
        }
        val url = "$geckoTerminal/networks/near/pools/${enc(pool)}/ohlcv/$frame?aggregate=$agg&limit=$limit&currency=usd&token=${enc(token)}"
        val list = get(url, GT_ACCEPT).jsonObject["data"]?.jsonObject?.get("attributes")?.jsonObject?.get("ohlcv_list") as? JsonArray ?: JsonArray(emptyList())
        // candles come newest first: [time (s), open, high, low, close, volume]
        return series(JsonArray(list.reversed()), timeIndex = 0, priceIndex = 4, seconds = true)
    }

    /** Tokens trending on NEAR (one per token, in GeckoTerminal's order). */
    fun trending(): List<TrendingToken> {
        val data = get("$geckoTerminal/networks/near/trending_pools?include=base_token", GT_ACCEPT).jsonObject
        val symbols = (data["included"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .associate { o -> o.str("id").orEmpty() to (o["attributes"] as? JsonObject)?.str("symbol").orEmpty() }
        val seen = HashSet<String>()
        return (data["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val a = p["attributes"] as? JsonObject ?: return@mapNotNull null
            val baseId = p["relationships"]?.jsonObject?.get("base_token")?.jsonObject?.get("data")?.jsonObject?.str("id") ?: return@mapNotNull null
            val address = baseId.removePrefix("near_")
            if (!Tokens.isAccount(address) || !seen.add(address)) return@mapNotNull null
            val name = Tokens.clean(a.str("name"), 40)
            val change = a["price_change_percentage"] as? JsonObject
            TrendingToken(
                address = address,
                symbol = Tokens.clean(symbols[baseId] ?: name.substringBefore(" /"), 12).ifEmpty { "?" },
                pool = p.str("id").orEmpty().removePrefix("near_"),
                poolName = name,
                price = a.num("base_token_price_usd"),
                change1h = change?.num("h1"),
                change24h = change?.num("h24"),
                volume24h = (a["volume_usd"] as? JsonObject)?.num("h24"),
                liquidity = a.num("reserve_in_usd"),
                marketCap = a.num("market_cap_usd") ?: a.num("fdv_usd"),
                createdAt = a.str("pool_created_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
    }

    private fun coin(o: JsonObject): MarketCoin? {
        val id = o.str("id") ?: return null
        val spark = (o["sparkline_in_7d"] as? JsonObject)?.get("price") as? JsonArray
        return MarketCoin(
            id = id,
            symbol = Tokens.clean(o.str("symbol"), 12).uppercase(Locale.US).ifEmpty { "?" },
            name = Tokens.clean(o.str("name"), 40),
            price = o.num("current_price"),
            change24h = o.num("price_change_percentage_24h"),
            marketCap = o.num("market_cap")?.takeIf { it > 0 },
            volume24h = o.num("total_volume"),
            high24h = o.num("high_24h"),
            low24h = o.num("low_24h"),
            ath = o.num("ath"),
            rank = o.num("market_cap_rank")?.toInt(),
            image = o.str("image")?.takeIf { isIconUrl(it) },
            sparkline = spark.orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull },
        )
    }

    private fun series(rows: JsonArray, timeIndex: Int, priceIndex: Int, seconds: Boolean): PriceSeries {
        val t = ArrayList<Long>(rows.size)
        val p = ArrayList<Double>(rows.size)
        for (r in rows) {
            val a = r as? JsonArray ?: continue
            val time = (a.getOrNull(timeIndex) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: continue
            val price = (a.getOrNull(priceIndex) as? JsonPrimitive)?.doubleOrNull ?: continue
            if (!price.isFinite()) continue
            t += if (seconds) time * 1000 else time
            p += price
        }
        return PriceSeries(t, p)
    }

    companion object {
        const val COINGECKO = "https://api.coingecko.com/api/v3"
        const val GECKOTERMINAL = "https://api.geckoterminal.com/api/v2"
        private const val GT_ACCEPT = "application/json;version=20230302"
        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.takeIf { it.isFinite() }

        private val ICON_HOSTS = setOf("coin-images.coingecko.com", "assets.coingecko.com")

        /** Only CoinGecko's own image hosts, over https: a token's creator never picks what the app downloads. */
        fun isIconUrl(url: String): Boolean = runCatching {
            val u = java.net.URI(url)
            u.scheme == "https" && u.host in ICON_HOSTS && u.userInfo == null && (u.port == -1 || u.port == 443)
        }.getOrDefault(false)

        /** CoinGecko ids of Tapvault's built-in tokens (both networks), for their logos. */
        val ICON_IDS = mapOf(
            "wrap.near" to "wrapped-near",
            "17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1" to "usd-coin",
            "usdt.tether-token.near" to "tether",
            "meta-pool.near" to "staked-near",
            "v2-nearx.stader-labs.near" to "stader-nearx",
            "token.v2.ref-finance.near" to "ref-finance",
            "aaaaaa20d9e0e2461697782ef11675f668207961.factory.bridge.near" to "aurora-near",
            "wrap.testnet" to "wrapped-near",
            "usdc.fakes.testnet" to "usd-coin",
            "usdt.fakes.testnet" to "tether",
            "dai.fakes.testnet" to "dai",
            "ref.fakes.testnet" to "ref-finance",
        )

        /** Coins loaded besides what 1Click lists: NEAR itself (1Click lists wrapped NEAR) and the built-in tokens, for logos. */
        val EXTRA_IDS = setOf("near") + ICON_IDS.values

        /**
         * Coins that are memes: by CoinGecko id, and NEAR tokens made on
         * meme launchpads. Only for grouping on screen; a meme is never
         * trusted or hidden for being one.
         */
        val MEME_IDS = setOf(
            "black-dragon", "shitzu", "nearkat", "nearkat-2", "jambo-2", "noear", "abg", "gnear", "forgive-me-father", "turbo",
            "pepe", "dogwifcoin", "book-of-meme", "official-trump", "melania-meme", "mog-coin", "spx6900", "shiba-inu", "dogecoin",
            "based-brett", "pudgy-penguins", "cash-cat",
        )

        fun isMemeContract(contract: String?): Boolean =
            contract != null && (contract.endsWith(".meme-cooking.near") || contract.endsWith(".tkn.near") || contract.endsWith(".tkn.primitives.near"))

        /** "$1,906.07", "$0.0354", "$0.0₈5421": tiny prices keep four significant digits, with the zeros counted. */
        fun price(v: Double?): String {
            if (v == null || !v.isFinite()) return "—"
            if (v == 0.0) return "$0"
            val a = kotlin.math.abs(v)
            return when {
                a >= 1 -> "$" + String.format(Locale.US, "%,.2f", v)
                a >= 0.0001 -> "$" + BigDecimal(v).round(MathContext(4)).stripTrailingZeros().toPlainString()
                else -> {
                    val plain = BigDecimal(a).round(MathContext(4)).stripTrailingZeros().toPlainString() // 0.000000005421
                    val frac = plain.substringAfter('.')
                    val zeros = frac.takeWhile { it == '0' }.length
                    "$0.0" + zeros.toString().map { SUBSCRIPT[it - '0'] }.joinToString("") + frac.drop(zeros)
                }
            }
        }

        private const val SUBSCRIPT = "₀₁₂₃₄₅₆₇₈₉"

        /** "$63.9M", "$1.24B", "$950K". */
        fun compact(v: Double?): String {
            if (v == null || !v.isFinite()) return "—"
            val a = kotlin.math.abs(v)
            return when {
                a >= 1e12 -> "$" + String.format(Locale.US, "%.2fT", v / 1e12)
                a >= 1e9 -> "$" + String.format(Locale.US, "%.2fB", v / 1e9)
                a >= 1e6 -> "$" + String.format(Locale.US, "%.1fM", v / 1e6)
                a >= 1e3 -> "$" + String.format(Locale.US, "%.1fK", v / 1e3)
                else -> "$" + String.format(Locale.US, "%.0f", v)
            }
        }

        /** "+5.93%", "−0.28%". */
        fun percent(v: Double?): String = when {
            v == null || !v.isFinite() -> "—"
            kotlin.math.abs(v) < 0.005 -> "0.00%"
            v >= 0 -> "+" + String.format(Locale.US, "%.2f%%", v)
            else -> "−" + String.format(Locale.US, "%.2f%%", -v)
        }

        /**
         * A NEAR implicit account nobody can hold the key for (all zeros).
         * Preview quotes name it as the recipient and refund address: a
         * dry quote reserves nothing and gives no deposit address, so
         * nothing can ever be sent to it, and no wallet's account is told
         * to NEAR Intents for a preview.
         */
        const val PREVIEW_ACCOUNT = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
