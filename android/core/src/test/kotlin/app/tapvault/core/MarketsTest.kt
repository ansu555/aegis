package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Market data for Trade (and its mainnet preview): answers recorded from CoinGecko and GeckoTerminal on 14 Nov 2026. */
class MarketsTest {
    private fun client(body: String, code: Int = 200, seen: MutableList<String> = mutableListOf()) =
        Markets(http = { m, url, _, h -> seen += "$m $url ${h["Accept"]}"; code to body })

    @Test
    fun coinsParseAndSortByRank() {
        val seen = mutableListOf<String>()
        val body = """[
          {"id":"wrapped-near","symbol":"wnear","name":"Wrapped Near","image":"https://coin-images.coingecko.com/coins/images/18280/large/x.jpg?1","current_price":3.34,"market_cap":63920035,"market_cap_rank":null,
           "total_volume":9409711,"high_24h":3.62,"low_24h":3.3,"price_change_percentage_24h":-5.93682,"ath":20.5,
           "sparkline_in_7d":{"price":[3.5,3.4,3.34]}},
          {"id":"near","symbol":"near","name":"NEAR Protocol","image":"https://evil.example/near.png","current_price":3.35,"market_cap":4200000000,"market_cap_rank":28,
           "total_volume":310000000,"price_change_percentage_24h":1.2,"sparkline_in_7d":{"price":[]}},
          {"symbol":"broken"}
        ]"""
        val coins = client(body, seen = seen).coins(listOf("wrapped-near", "near", "BAD id", "near"))
        assertEquals(listOf("near", "wrapped-near"), coins.map { it.id })
        assertEquals("WNEAR", coins[1].symbol)
        assertEquals(-5.93682, coins[1].change24h)
        assertEquals(listOf(3.5, 3.4, 3.34), coins[1].sparkline)
        assertEquals(28, coins[0].rank)
        assertEquals("https://coin-images.coingecko.com/coins/images/18280/large/x.jpg?1", coins[1].image)
        assertNull(coins[0].image) // not CoinGecko's image host
        assertTrue(!Markets.isIconUrl("http://coin-images.coingecko.com/a.png"))
        assertTrue(!Markets.isIconUrl("https://coin-images.coingecko.com.evil.io/a.png"))
        // only valid ids, once each, and nothing else in the request
        assertTrue(seen.single().contains("ids=wrapped-near%2Cnear "), seen.single())
    }

    @Test
    fun poolCandlesBecomeOldestFirstUsdPrices() {
        val body = """{"data":{"attributes":{"ohlcv_list":[[1794657600,3.33,3.34,3.33,3.34,303.0],[1794654000,3.32,3.33,3.32,3.33,27853.0],[1794650400,3.35,3.35,3.32,3.32,8027.2]]}}}"""
        val seen = mutableListOf<String>()
        val s = client(body, seen = seen).poolChart("nep141:a.near___nep141:wrap.near", "wrap.near", ChartRange.D1)
        assertEquals(listOf(1794650400000L, 1794654000000L, 1794657600000L), s.times)
        assertEquals(listOf(3.32, 3.33, 3.34), s.prices)
        assertEquals((3.34 - 3.32) / 3.32 * 100, s.change!!, 1e-9)
        assertTrue(seen.single().contains("pools/nep141%3Aa.near___nep141%3Awrap.near/ohlcv/minute?aggregate=15"))
        assertTrue(seen.single().endsWith("application/json;version=20230302"))
    }

    @Test
    fun hourRangeKeepsTheLastHour() {
        val now = 1_794_657_600_000L
        val rows = (0..24).joinToString(",") { i -> "[${now - (24 - i) * 300_000L},${1.0 + i}]" } // 5-minute points over 2 h
        val s = client("""{"prices":[$rows]}""").chart("near", ChartRange.H1)
        assertTrue(s.times.first() >= now - 3_600_000L)
        assertEquals(13, s.prices.size)
        assertFailsWith<IllegalArgumentException> { client("{}").chart("../x", ChartRange.D1) }
    }

    @Test
    fun trendingIsOnePerTokenWithItsSymbol() {
        val body = """{"data":[
          {"id":"near_refv1-4179","attributes":{"name":"BLACKDRAGON / wNEAR","base_token_price_usd":"0.0000000054","reserve_in_usd":"120000.5",
            "pool_created_at":"2024-03-01T10:00:00Z","price_change_percentage":{"h1":"0.5","h24":"-12.4"},"volume_usd":{"h24":"45000"}},
           "relationships":{"base_token":{"data":{"id":"near_blackdragon.tkn.near"}}}},
          {"id":"near_refv1-9","attributes":{"name":"BLACKDRAGON / USDC"},"relationships":{"base_token":{"data":{"id":"near_blackdragon.tkn.near"}}}},
          {"id":"near_x","attributes":{"name":"BAD / X"},"relationships":{"base_token":{"data":{"id":"near_NOT AN ACCOUNT"}}}}
        ],"included":[{"id":"near_blackdragon.tkn.near","type":"token","attributes":{"symbol":"BLACKDRAGON"}}]}"""
        val t = client(body).trending()
        assertEquals(1, t.size)
        assertEquals("blackdragon.tkn.near", t[0].address)
        assertEquals("BLACKDRAGON", t[0].symbol)
        assertEquals("refv1-4179", t[0].pool)
        assertEquals(-12.4, t[0].change24h)
        assertEquals(1709287200000L, t[0].createdAt)
        assertTrue(Markets.isMemeContract(t[0].address))
    }

    @Test
    fun errorsAreReadable() {
        assertEquals("Market data is busy. Try again in a minute.", assertFailsWith<TapvaultException> { client("", 429).coins(listOf("near")) }.message)
        assertFailsWith<TapvaultException> { client("not json").coins(listOf("near")) }
        assertEquals(emptyList(), client("[]").coins(emptyList()))
    }

    @Test
    fun formatting() {
        assertEquals("$1,906.07", Markets.price(1906.07))
        assertEquals("$0.03539", Markets.price(0.03539475))
        assertEquals("$0.0₈5421", Markets.price(5.421e-09))
        assertEquals("—", Markets.price(null))
        assertEquals("$63.9M", Markets.compact(63_920_035.0))
        assertEquals("$4.20B", Markets.compact(4.2e9))
        assertEquals("+1.20%", Markets.percent(1.2))
        assertEquals("−5.94%", Markets.percent(-5.93682))
        assertEquals("0.00%", Markets.percent(-0.001))
        assertNull(PriceSeries(listOf(1L), listOf(2.0)).change)
    }

    @Test
    fun previewAccountIsAnImplicitAccountNobodyHolds() {
        assertTrue(Network.isImplicit(Markets.PREVIEW_ACCOUNT))
        assertEquals(setOf('0'), Markets.PREVIEW_ACCOUNT.toSet())
    }

    @Test
    fun oneClickTokensCarryTheirCoinGeckoId() {
        val body = """[{"assetId":"nep141:wrap.near","decimals":24,"blockchain":"near","symbol":"wNEAR","price":3.34,"contractAddress":"wrap.near","coingeckoId":"wrapped-near"},
                       {"assetId":"nep141:x.near","decimals":18,"blockchain":"near","symbol":"X","price":1,"coingeckoId":"custom:x"}]"""
        val t = OneClick(http = { _, _, _, _ -> 200 to body }).tokens()
        assertEquals("wrapped-near", t[0].coingeckoId)
        assertNull(t[1].coingeckoId) // not a CoinGecko id
    }
}
