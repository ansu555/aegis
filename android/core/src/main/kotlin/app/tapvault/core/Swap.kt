package app.tapvault.core

import java.math.BigInteger
import java.time.Instant

/**
 * Swaps and bridges through NEAR Intents, as the wallet can do them: every
 * one is a plain `Transfer` of a NEAR token to the deposit address 1Click
 * gives (contract section 1 of docs/WALLET-FEATURES-PLAN.md). Native NEAR
 * can't be the origin (1Click takes wNEAR, and the contract can't wrap), so
 * swaps start from a token.
 */
object Swap {
    /** A quote must stay usable at least this long after the phone signs. */
    const val MIN_DEADLINE_SECS = 15 * 60L

    /** How long the phone asks a quote to stay open. */
    const val ASK_DEADLINE_SECS = 30 * 60L

    fun assetOf(contract: String) = "nep141:$contract"

    /**
     * Checks a wet quote against what the phone asked before anything is
     * signed: same assets, amount, recipient and refund address, an exact
     * input swap, a NEAR deposit address without a memo, and enough time
     * left. Returns the transfer to sign. What 1Click can't prove (that it
     * will pay [SwapQuote.minAmountOut]) is said on the review instead.
     */
    fun check(ask: QuoteAsk, q: SwapQuote, originContract: String, now: Instant = Instant.now()): Action.Transfer {
        require(!ask.dry) { "a dry quote has no deposit address" }
        require(ask.originAsset == assetOf(originContract)) { "the origin asset isn't the token being sent" }
        val e = q.echoed
        fun same(k: String, v: String) {
            if (e.str(k) != v) throw TapvaultException("NEAR Intents answered for a different swap ($k), so nothing was sent.")
        }
        same("originAsset", ask.originAsset)
        same("destinationAsset", ask.destinationAsset)
        same("amount", ask.amount.toString())
        same("recipient", ask.recipient)
        same("refundTo", ask.refundTo)
        same("swapType", "EXACT_INPUT")
        same("depositType", "ORIGIN_CHAIN")
        same("recipientType", "DESTINATION_CHAIN")
        same("refundType", "ORIGIN_CHAIN")
        if (q.amountIn != ask.amount) throw TapvaultException("NEAR Intents asked for a different amount than you entered, so nothing was sent.")
        val to = q.depositAddress ?: throw TapvaultException("NEAR Intents sent no deposit address.")
        if (!Tokens.isAccount(to)) throw TapvaultException("NEAR Intents' deposit address isn't a NEAR account, so nothing was sent.")
        if (!q.depositMemo.isNullOrEmpty()) throw TapvaultException("This swap needs a memo, which a Aegis transfer can't carry.")
        val deadline = q.deadline ?: throw TapvaultException("NEAR Intents sent no deadline for this quote.")
        if (deadline.isBefore(now.plusSeconds(MIN_DEADLINE_SECS))) throw TapvaultException("This quote expires too soon. Try again.")
        if (q.minAmountOut.signum() <= 0 || q.minAmountOut > q.amountOut) throw TapvaultException("NEAR Intents' quote doesn't add up, so nothing was sent.")
        return Action.Transfer(Asset.Ft(originContract), q.amountIn, to)
    }

    /** Worst rate the quote allows, as "1 FROM = x TO" (null if it can't be said). */
    fun rate(q: SwapQuote, fromDecimals: Int, toDecimals: Int): String? {
        if (q.amountIn.signum() <= 0) return null
        val inn = java.math.BigDecimal(q.amountIn).movePointLeft(fromDecimals)
        val out = java.math.BigDecimal(q.amountOut).movePointLeft(toDecimals)
        return out.divide(inn, 6, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    }
}

/**
 * Addresses on other chains, for bridging out. Only chains whose address
 * format can be checked are offered; a wrong-format address is refused
 * before any quote, so nothing goes to an address the chain can't use.
 */
object ChainAddress {
    data class Chain(val id: String, val name: String, val example: String, val pattern: Regex)

    private val EVM = Regex("^0x[0-9a-fA-F]{40}$")
    private val BASE58_SOL = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    val CHAINS = listOf(
        Chain("eth", "Ethereum", "0x…", EVM),
        Chain("base", "Base", "0x…", EVM),
        Chain("arb", "Arbitrum", "0x…", EVM),
        Chain("op", "Optimism", "0x…", EVM),
        Chain("pol", "Polygon", "0x…", EVM),
        Chain("bsc", "BNB Chain", "0x…", EVM),
        Chain("avax", "Avalanche", "0x…", EVM),
        Chain("sol", "Solana", "base58 address", BASE58_SOL),
        Chain("btc", "Bitcoin", "bc1…", Regex("^(bc1[02-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$")),
        Chain("tron", "Tron", "T…", Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$")),
    )

    fun chain(id: String) = CHAINS.firstOrNull { it.id == id }

    /** Null if [address] fits [chainId]'s format, else what's wrong. */
    fun problem(chainId: String, address: String): String? {
        val c = chain(chainId) ?: return "Aegis doesn't bridge to that chain."
        return if (c.pattern.matches(address.trim())) null else "That isn't a ${c.name} address (${c.example})."
    }

    /** Chains an address could belong to (for a hint in Send). */
    fun guess(address: String): List<Chain> = CHAINS.filter { it.pattern.matches(address.trim()) }
}

/** What a swap, bridge or sale was, kept on the phone so Trade can show and follow it. */
data class SwapRecord(
    val kind: Kind,
    val depositAddress: String,
    val from: String,
    val to: String,
    /** "10 USDC" */
    val amountIn: String,
    /** "≥ 3.2 wNEAR" */
    val amountOut: String,
    val createdAt: Long,
    val txHash: String? = null,
    val state: SwapState = SwapState.PENDING_DEPOSIT,
    /** For bridge-in: the chain the user deposits on. */
    val chain: String? = null,
    val deadline: Long? = null,
) {
    enum class Kind { SWAP, BRIDGE_OUT, BRIDGE_IN }

    val title get() = when (kind) {
        Kind.SWAP -> "Swap $from → $to"
        Kind.BRIDGE_OUT -> "Bridge $from → ${chain ?: "another chain"}"
        Kind.BRIDGE_IN -> "Receive $to from ${chain ?: "another chain"}"
    }

    fun encode(): String = listOf(kind.name, depositAddress, from, to, amountIn, amountOut, createdAt.toString(), txHash.orEmpty(), state.name, chain.orEmpty(), deadline?.toString().orEmpty())
        .joinToString("\u001f")

    companion object {
        fun decode(s: String): SwapRecord? = runCatching {
            val p = s.split("\u001f")
            if (p.size < 11) return null
            SwapRecord(
                Kind.valueOf(p[0]), p[1], p[2], p[3], p[4], p[5], p[6].toLong(), p[7].ifEmpty { null }, SwapState.of(p[8]),
                p[9].ifEmpty { null }, p[10].toLongOrNull(),
            )
        }.getOrNull()

        fun encodeAll(l: List<SwapRecord>) = l.joinToString("\u001e") { it.encode() }
        fun decodeAll(s: String?): List<SwapRecord> = s.orEmpty().split("\u001e").filter { it.isNotBlank() }.mapNotNull { decode(it) }

        /** Keep this many records per wallet. */
        const val KEEP = 30
    }
}

/**
 * Buy and sell for cash through Ramp Network's hosted page, opened in the
 * browser with the wallet's account. Ramp does KYC and payments; Aegis
 * never sees card or bank details. [hostKey] (from `tapvault.ramp.key`) is
 * Ramp's public partner key; without one the page still opens, unattributed.
 * Asset codes follow Ramp's CHAIN_SYMBOL form; check them against Ramp's
 * asset list before turning mainnet on (not verified yet).
 */
object Ramp {
    const val PROVIDER = "Ramp Network"
    private const val BASE = "https://app.ramp.network/"

    val ASSETS = listOf("NEAR" to "NEAR_NEAR", "USDC" to "NEAR_USDC")

    fun buyUrl(account: String, asset: String, hostKey: String?): String = url(account, asset, hostKey, "ONRAMP")

    fun sellUrl(account: String, asset: String, hostKey: String?): String = url(account, asset, hostKey, "OFFRAMP")

    private fun url(account: String, asset: String, hostKey: String?, flow: String): String {
        require(Tokens.isAccount(account)) { "not a NEAR account" }
        val code = ASSETS.firstOrNull { it.first == asset }?.second ?: throw IllegalArgumentException("unknown asset $asset")
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val params = buildList {
            hostKey?.takeIf { it.isNotBlank() }?.let { add("hostApiKey=" + e(it)) }
            add("hostAppName=Aegis")
            add("defaultFlow=$flow")
            add("enabledCryptoAssets=" + e(code))
            add((if (flow == "ONRAMP") "swapAsset=" else "offrampAsset=") + e(code))
            if (flow == "ONRAMP") add("userAddress=" + e(account))
        }
        return BASE + "?" + params.joinToString("&")
    }
}
