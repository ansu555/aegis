package app.tapvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URI

/**
 * A NEP-141 fungible token. [verified]: on Tapvault's built-in list for the
 * network; anything else (added by contract, found in the wallet) is shown
 * as unverified, because a token's name and symbol are whatever its
 * creator typed.
 */
data class TokenInfo(val contract: String, val symbol: String, val name: String, val decimals: Int, val verified: Boolean)

/** One token in the wallet: its balance and whether the wallet can receive it ([registered], null if unknown). */
data class TokenBalance(val token: TokenInfo, val raw: BigInteger, val registered: Boolean?)

/** Token amounts with their decimals, in [BigInteger] / [BigDecimal] only (never floating point). */
object TokenAmount {
    fun format(raw: BigInteger, decimals: Int, maxFraction: Int = 4): String {
        val v = BigDecimal(raw).movePointLeft(decimals)
        val shown = v.setScale(minOf(maxFraction, decimals), RoundingMode.DOWN)
        // A small non-zero balance shouldn't read as 0.
        if (shown.signum() == 0 && raw.signum() > 0) return "<" + BigDecimal.ONE.movePointLeft(minOf(maxFraction, decimals)).toPlainString()
        return shown.stripTrailingZeros().toPlainString()
    }

    /**
     * "1.5 USDC" for a built-in token (either network: their contracts differ),
     * "12 units of x.near" for one whose decimals the phone doesn't know here.
     */
    fun describe(asset: Asset, raw: BigInteger): String = when (asset) {
        Asset.Near -> "${Near.format(raw)} NEAR"
        is Asset.Ft -> KnownTokens.anywhere(asset.token)?.let { "${format(raw, it.decimals, maxFraction = it.decimals)} ${it.symbol}" }
            ?: "$raw units of ${asset.token}"
    }

    fun symbolOf(asset: Asset): String = when (asset) {
        Asset.Near -> "NEAR"
        is Asset.Ft -> KnownTokens.anywhere(asset.token)?.symbol ?: asset.token
    }

    /** "1.5" → raw units; null if it isn't a positive amount with at most [decimals] fraction digits. */
    fun parse(text: String, decimals: Int): BigInteger? = runCatching {
        val v = BigDecimal(text.trim())
        if (v.signum() <= 0 || v.stripTrailingZeros().scale() > decimals) null else v.movePointRight(decimals).toBigIntegerExact()
    }.getOrNull()
}

/** The tokens Tapvault knows per network (metadata checked on chain on 14 Nov 2026). */
object KnownTokens {
    private val testnet = listOf(
        TokenInfo("usdc.fakes.testnet", "USDC.e", "Bridged USDC (test)", 6, true),
        TokenInfo("usdt.fakes.testnet", "USDT.e", "Tether USD (test)", 6, true),
        TokenInfo("wrap.testnet", "wNEAR", "Wrapped NEAR", 24, true),
        TokenInfo("dai.fakes.testnet", "DAI", "Dai Stablecoin (test)", 18, true),
        TokenInfo("ref.fakes.testnet", "REF", "Ref Finance (test)", 18, true),
    )
    private val mainnet = listOf(
        TokenInfo("17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1", "USDC", "USD Coin", 6, true),
        TokenInfo("usdt.tether-token.near", "USDt", "Tether USD", 6, true),
        TokenInfo("wrap.near", "wNEAR", "Wrapped NEAR", 24, true),
        TokenInfo("meta-pool.near", "stNEAR", "Staked NEAR (Meta Pool)", 24, true),
        TokenInfo("v2-nearx.stader-labs.near", "NearX", "NearX (Stader)", 24, true),
        TokenInfo("token.v2.ref-finance.near", "REF", "Ref Finance", 18, true),
        TokenInfo("aaaaaa20d9e0e2461697782ef11675f668207961.factory.bridge.near", "AURORA", "Aurora", 18, true),
    )

    fun on(n: Network): List<TokenInfo> = if (n.isMainnet) mainnet else testnet

    fun find(n: Network, contract: String): TokenInfo? = on(n).firstOrNull { it.contract == contract }

    /** A built-in token on either network (testnet and mainnet contracts never share a name). */
    fun anywhere(contract: String): TokenInfo? = (testnet + mainnet).firstOrNull { it.contract == contract }
}

/**
 * What the user chose for one wallet's token list: tokens they turned on
 * ([added], in order) and tokens they turned off ([hidden]). Kept on the
 * phone only. A built-in token shows by itself once it has a balance; any
 * other token only after the user adds it, so tokens sent to the wallet
 * unasked (spam) never appear on Home by themselves.
 */
data class TokenPrefs(val added: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
    fun isShown(contract: String, known: Boolean, balance: BigInteger): Boolean =
        contract !in hidden && (contract in added || (known && balance.signum() > 0))

    fun show(contract: String) = TokenPrefs(if (contract in added) added else added + contract, hidden - contract)
    fun hide(contract: String) = TokenPrefs(added - contract, hidden + contract)

    /** Forget a token the user added (back to its default: built-in tokens show again when they have a balance). */
    fun remove(contract: String) = TokenPrefs(added - contract, hidden - contract)

    fun encode() = "a=" + added.joinToString(",") + "\nh=" + hidden.sorted().joinToString(",")

    companion object {
        fun decode(s: String?): TokenPrefs {
            val m = s.orEmpty().lines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 } }.associate { it[0] to it[1] }
            fun list(k: String) = m[k].orEmpty().split(',').filter { Tokens.isAccount(it) }
            return TokenPrefs(list("a").distinct(), list("h").toSet())
        }
    }
}

/** Token reads (views, no signing) and the one write the app makes: registering the wallet on a token. */
class Tokens(private val near: NearClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A token's metadata from its contract (`ft_metadata`), checked enough
     * to display it safely. Built-in tokens come from [KnownTokens] (verified).
     */
    fun metadata(contract: String): TokenInfo {
        KnownTokens.find(near.network, contract)?.let { return it }
        if (!isAccount(contract)) throw TapvaultException("That isn't a token contract account.")
        val m = try {
            near.viewFunction(contract, "ft_metadata").jsonObject
        } catch (e: NearRpcException) {
            throw TapvaultException("$contract isn't a token on ${near.network.displayName} (it has no ft_metadata).")
        }
        val spec = m["spec"]?.jsonPrimitive?.content.orEmpty()
        val decimals = m["decimals"]?.jsonPrimitive?.content?.toIntOrNull()
        if (!spec.startsWith("ft-") || decimals == null || decimals !in 0..36) throw TapvaultException("$contract doesn't look like a fungible token (spec \"$spec\").")
        return TokenInfo(contract, clean(m["symbol"]?.jsonPrimitive?.content, 12).ifEmpty { "?" }, clean(m["name"]?.jsonPrimitive?.content, 40), decimals, verified = false)
    }

    fun balanceOf(contract: String, account: String): BigInteger =
        BigInteger(near.viewFunction(contract, "ft_balance_of", jsonArgs { put("account_id", account) }).jsonPrimitive.content)

    /** Whether [account] can receive [contract] (NEP-145 `storage_balance_of`); null if the token doesn't say. */
    fun isRegistered(contract: String, account: String): Boolean? = try {
        near.viewFunction(contract, "storage_balance_of", jsonArgs { put("account_id", account) }) !is JsonNull
    } catch (e: NearRpcException) {
        if (e.rpcCause == "VIEW_ERROR") null else throw e
    }

    /** The NEAR a registration costs (`storage_balance_bounds.min`). */
    fun registrationCost(contract: String): BigInteger =
        BigInteger(near.viewFunction(contract, "storage_balance_bounds").jsonObject["min"]!!.jsonPrimitive.content)

    /**
     * Register [account] on [contract] so it can receive the token: [payer]
     * (the wallet's fee account) calls `storage_deposit` for it, paying
     * [registrationCost]. Anyone may register any account; nothing of the
     * wallet's is spent and no wallet key signs.
     */
    fun register(contract: String, account: String, payer: String, payerKey: NearKey): TxResult {
        val cost = registrationCost(contract)
        require(cost.signum() >= 0 && cost <= MAX_REGISTRATION) { "this token asks ${Near.format(cost)} NEAR to register, more than Tapvault pays" }
        val r = near.send(payer, payerKey, contract, listOf(
            NearAction.FunctionCall("storage_deposit", jsonArgs { put("account_id", account); put("registration_only", true) }, gasTgas = 30, deposit = cost),
        ))
        if (!r.ok) throw TapvaultException("enabling the token failed: ${r.error}")
        return r
    }

    /**
     * A seed phrase wallet sends a token itself: `ft_transfer` signed with the
     * phrase's key, with the 1 yoctoNEAR the standard asks for.
     */
    fun transferFrom(contract: String, from: String, key: NearKey, to: String, amount: BigInteger): TxResult {
        require(amount.signum() > 0) { "amount must be positive" }
        val r = near.send(from, key, contract, listOf(
            NearAction.FunctionCall("ft_transfer", jsonArgs { put("receiver_id", to); put("amount", amount.toString()) }, gasTgas = 30, deposit = BigInteger.ONE),
        ))
        if (!r.ok) throw TapvaultException("sending the token failed: ${r.error}")
        return r
    }

    /** Token contracts the index says [account] holds, with balances (FastNEAR; may lag a little). Empty if the index can't be reached. */
    fun held(account: String): Map<String, BigInteger> = runCatching {
        val c = URI("${near.indexUrl}/v1/account/$account/ft").toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (c.responseCode !in 200..299) return emptyMap()
            val r = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            r["tokens"]?.jsonArray.orEmpty().mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                val id = o["contract_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val bal = o["balance"]?.jsonPrimitive?.content?.toBigIntegerOrNull() ?: return@mapNotNull null
                if (isAccount(id)) id to bal else null
            }.toMap()
        } finally {
            c.disconnect()
        }
    }.getOrDefault(emptyMap())

    /**
     * Balances of every token the wallet might show: the built-in ones and
     * the ones in [prefs] (read on chain, not from the index). [found]: other
     * tokens the index lists with a balance, for "Found in this wallet".
     */
    class Snapshot(val tokens: List<TokenBalance>, val found: List<TokenBalance>)

    fun load(account: String, prefs: TokenPrefs, metadataCache: MutableMap<String, TokenInfo> = mutableMapOf()): Snapshot {
        val known = KnownTokens.on(near.network)
        val mine = (known.map { it.contract } + prefs.added).distinct()
        fun info(c: String) = metadataCache.getOrPut(c) { metadata(c) }
        val tokens = mine.mapNotNull { c ->
            runCatching {
                val t = info(c)
                val raw = balanceOf(c, account)
                // Holding some proves registration; otherwise the token detail asks when opened.
                TokenBalance(t, raw, if (raw.signum() > 0) true else null)
            }.getOrNull()
        }
        val found = held(account).filter { (c, b) -> c !in mine && b.signum() > 0 }.keys.take(MAX_FOUND).mapNotNull { c ->
            runCatching { TokenBalance(info(c), balanceOf(c, account), true) }.getOrNull()
        }
        return Snapshot(tokens, found)
    }

    companion object {
        /** More than any NEP-145 token asks (0.00125 NEAR is usual): refuse a token that wants more. */
        val MAX_REGISTRATION: BigInteger = Near.parse("0.05")

        /** At most this many unlisted tokens are looked up per refresh. */
        const val MAX_FOUND = 12

        private val ACCOUNT = Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")

        fun isAccount(s: String) = s.length in 2..64 && ACCOUNT.matches(s)

        /** Display text from a contract: printable, one line, at most [max] characters. */
        fun clean(s: String?, max: Int): String =
            s.orEmpty().map { if (it.isISOControl()) ' ' else it }.filter { !Character.isSurrogate(it) }.joinToString("").replace(Regex("\\s+"), " ").trim().take(max)
    }
}
