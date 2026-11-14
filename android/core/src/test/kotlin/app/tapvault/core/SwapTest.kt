package app.tapvault.core

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swaps and bridges through NEAR Intents: answers recorded from 1Click on 14 Nov 2026, plus one opt-in live dry quote. */
class SwapTest {
    private val wallet = "a".repeat(64)
    private val usdc = "17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1"
    private val deposit = "f5aefb0ad47ba17a53cdfc32822fb57d5135625e997cd202b871a380170fc551"
    private val now = Instant.parse("2026-11-14T08:23:29Z")

    private fun ask(dry: Boolean = false) = QuoteAsk(
        originAsset = "nep141:wrap.near", destinationAsset = "nep141:$usdc",
        amount = BigInteger("1000000000000000000000000"), recipient = wallet, refundTo = wallet,
        deadline = now.plusSeconds(1800), dry = dry,
    )

    /** A wet quote as 1Click answered it (appFees and timestamps trimmed to what matters). */
    private fun answer(amount: String = "1000000000000000000000000", recipient: String = wallet, depositAddress: String = deposit, deadline: String = "2026-11-17T08:53:27.000Z", memo: String? = null) = """
        {"quote":{"amountIn":"$amount","amountInFormatted":"1.0","amountInUsd":"3.32","minAmountIn":"$amount",
          "amountOut":"3300764","amountOutFormatted":"3.300764","amountOutUsd":"3.29","minAmountOut":"3267756","timeEstimate":22,
          "refundFee":"0","withdrawFee":"4152","deadline":"$deadline","timeWhenInactive":"$deadline","depositAddress":"$depositAddress"${memo?.let { ",\"depositMemo\":\"$it\"" } ?: ""}},
         "quoteRequest":{"dry":false,"depositMode":"SIMPLE","swapType":"EXACT_INPUT","slippageTolerance":100,"originAsset":"nep141:wrap.near",
          "depositType":"ORIGIN_CHAIN","destinationAsset":"nep141:$usdc","amount":"1000000000000000000000000",
          "refundTo":"$wallet","refundType":"ORIGIN_CHAIN","recipient":"$recipient","recipientType":"DESTINATION_CHAIN",
          "deadline":"2026-11-14T08:53:27.000Z"},
         "signature":"ed25519:sig","timestamp":"2026-11-14T08:23:29.551Z","correlationId":"3898d651"}
    """.trimIndent()

    private fun client(code: Int = 200, body: String, seen: MutableList<String> = mutableListOf()) =
        OneClick(http = { m, url, b, h -> seen += "$m $url ${b.orEmpty()} ${h["Authorization"].orEmpty()}"; code to body })

    @Test
    fun aMatchingQuoteBecomesATransferToTheDepositAddress() {
        val seen = mutableListOf<String>()
        val q = client(body = answer(), seen = seen).quote(ask())
        assertTrue(seen.single().startsWith("POST https://1click.chaindefuser.com/v0/quote"), seen.single())
        assertTrue("\"swapType\":\"EXACT_INPUT\"" in seen.single() && "Bearer" !in seen.single())
        assertEquals(BigInteger("3267756"), q.minAmountOut)
        val t = Swap.check(ask(), q, "wrap.near", now)
        assertEquals(Asset.Ft("wrap.near"), t.asset)
        assertEquals(deposit, t.receiver)
        assertEquals(BigInteger("1000000000000000000000000"), t.amount)
        assertEquals("3.300764", Swap.rate(q, 24, 6))
    }

    @Test
    fun aQuoteForSomethingElseIsRefusedBeforeSigning() {
        fun refused(body: String) = assertFailsWith<TapvaultException> { Swap.check(ask(), client(body = body).quote(ask()), "wrap.near", now) }
        refused(answer(recipient = "b".repeat(64)))            // output to someone else
        refused(answer(amount = "2000000000000000000000000"))  // a different amount in
        refused(answer(depositAddress = "0x76b4c56085ED136a8744D52bE956396624a730E8"))  // not a NEAR account
        refused(answer(memo = "123"))                          // a memo a transfer can't carry
        refused(answer(deadline = "2026-11-14T08:30:00.000Z")) // expires too soon
        assertFailsWith<IllegalArgumentException> { Swap.check(ask(), client(body = answer()).quote(ask()), "usdt.tether-token.near", now) }
        assertFailsWith<IllegalArgumentException> { Swap.check(ask(dry = true), client(body = answer()).quote(ask()), "wrap.near", now) }
    }

    @Test
    fun errorsAreReadableAndTheJwtIsSentWhenSet() {
        val e = assertFailsWith<TapvaultException> { client(400, """{"message":"Amount is too low for bridge, try at least 1000000"}""").quote(ask()) }
        assertTrue("too small" in e.message!!, e.message)
        val down = OneClick(http = { _, _, _, _ -> throw java.io.IOException("timeout") })
        assertTrue("Can't reach" in assertFailsWith<TapvaultException> { down.tokens() }.message!!)
        val seen = mutableListOf<String>()
        OneClick(jwt = "j.w.t", http = { m, u, b, h -> seen += h["Authorization"].orEmpty(); 200 to "[]" }).tokens()
        assertEquals("Bearer j.w.t", seen.single())
    }

    @Test
    fun tokensAndPricesOnlyPriceVerifiedTokens() {
        val list = client(body = """[
            {"assetId":"nep141:wrap.near","decimals":24,"blockchain":"near","symbol":"wNEAR","price":3.32,"contractAddress":"wrap.near"},
            {"assetId":"nep141:$usdc","decimals":6,"blockchain":"near","symbol":"USDC","price":0.99959,"contractAddress":"$usdc"},
            {"assetId":"nep141:spam.near","decimals":6,"blockchain":"near","symbol":"USDC","price":1.0},
            {"assetId":"nep141:eth-0xa0b8.omft.near","decimals":6,"blockchain":"eth","symbol":"USDC","price":1.0,"contractAddress":"0xa0b8"},
            {"assetId":"bad","blockchain":"near"}]""").tokens()
        assertEquals(4, list.size)
        assertEquals("wrap.near", list[0].nearContract)
        assertNull(list[3].nearContract, "a token on Ethereum isn't a NEAR contract")
        val p = Prices.from(list, KnownTokens.on(Network.MAINNET).map { it.contract }.toSet())
        assertEquals(BigDecimal("3.32"), p.of(null), "native NEAR is priced as wNEAR")
        assertNull(p.of("spam.near"), "a look-alike never gets a price")
        assertEquals("$3.32", Prices.usd(p.value(null, Near.parse("1"), 24)))
        assertEquals("—", Prices.usd(null))
    }

    @Test
    fun statusIsParsed() {
        val s = client(body = """{"status":"SUCCESS","quoteResponse":{},"updatedAt":"x","swapDetails":{"amountOut":"3300764","nearTxHashes":["a","b"],"destinationChainTxHashes":[]}}""").status(deposit)
        assertEquals(SwapState.SUCCESS, s.state)
        assertEquals("b", s.destinationTx)
        assertEquals(SwapState.UNKNOWN, SwapState.of("NEW_THING"))
        assertTrue(SwapState.REFUNDED.done && !SwapState.PROCESSING.done)
    }

    @Test
    fun otherChainAddressesAreChecked() {
        assertNull(ChainAddress.problem("eth", "0x2527D02599Ba641c19FEa793cD0F167589a0f10D"))
        assertNull(ChainAddress.problem("sol", "13QkxhNMrTPxoCkRdYdJ65tFuwXPhL5gLS2Z5Nr6gjRK"))
        assertNull(ChainAddress.problem("btc", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertTrue(ChainAddress.problem("eth", "bob.near") != null)
        assertTrue(ChainAddress.problem("btc", "0x2527D02599Ba641c19FEa793cD0F167589a0f10D") != null)
        assertTrue(ChainAddress.problem("doge", "D123") != null, "chains without a format check aren't offered")
        assertTrue(ChainAddress.guess("0x2527D02599Ba641c19FEa793cD0F167589a0f10D").any { it.id == "base" })
    }

    @Test
    fun recordsRoundTrip() {
        val r = SwapRecord(SwapRecord.Kind.BRIDGE_OUT, deposit, "USDC", "USDC", "10 USDC", "≥ 9.9 USDC", 1L, "tx", SwapState.PROCESSING, "eth", 2L)
        assertEquals(listOf(r, r.copy(txHash = null, chain = null, deadline = null)), SwapRecord.decodeAll(SwapRecord.encodeAll(listOf(r, r.copy(txHash = null, chain = null, deadline = null)))))
        assertEquals(emptyList(), SwapRecord.decodeAll("junk\u001e"))
        assertEquals("Bridge USDC → eth", r.title)
    }

    @Test
    fun rampLinksCarryTheAccountOnly() {
        val u = Ramp.buyUrl("bob.near", "NEAR", null)
        assertTrue(u.startsWith("https://app.ramp.network/?") && "userAddress=bob.near" in u && "hostApiKey" !in u, u)
        assertTrue("defaultFlow=OFFRAMP" in Ramp.sellUrl("bob.near", "USDC", "pk") && "hostApiKey=pk" in Ramp.sellUrl("bob.near", "USDC", "pk"))
        assertFailsWith<IllegalArgumentException> { Ramp.buyUrl("bob.near&x=1", "NEAR", null) }
    }

    @Test
    fun tokenAmountsAreDescribedWithDecimals() {
        assertEquals("Send 1.5 USDC to bob.near", Action.Transfer(Asset.Ft(usdc), BigInteger.valueOf(1_500_000), "bob.near").describe())
        assertEquals("Send 7 units of x.near to bob.near", Action.Transfer(Asset.Ft("x.near"), BigInteger.valueOf(7), "bob.near").describe())
        val limit = Action.Config(listOf(ConfigOp.SetLimit(LimitInit(Asset.Ft("usdc.fakes.testnet"), BigInteger.valueOf(5_000_000), BigInteger.valueOf(20_000_000)))))
        assertEquals("Change settings: limit 5 USDC.e at once, 20 USDC.e a day", limit.describe())
    }

    /** Live, opt-in: a dry quote from 1Click (mainnet, nothing is sent; dry quotes have no deposit address). */
    @Test
    fun liveDryQuote() {
        if (System.getenv("TAPVAULT_INTENTS") != "1") return println("skipped: set TAPVAULT_INTENTS=1 to ask 1Click for a dry quote")
        val oc = OneClick()
        val tokens = oc.tokens()
        assertTrue(tokens.any { it.nearContract == "wrap.near" })
        val q = oc.quote(ask(dry = true).copy(deadline = Instant.now().plusSeconds(1800)))
        println("[intents] 1 wNEAR -> ${TokenAmount.format(q.amountOut, 6)} USDC (min ${TokenAmount.format(q.minAmountOut, 6)})")
        assertNull(q.depositAddress)
        assertTrue(q.amountOut.signum() > 0)
    }
}
