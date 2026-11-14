package app.tapvault.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tokens: amounts with decimals, what Home shows, and (live, opt-in) reading tokens on testnet. */
class TokensTest {
    @Test
    fun amountsUseTheTokensDecimals() {
        assertEquals("1.5", TokenAmount.format(BigInteger.valueOf(1_500_000), 6))
        assertEquals("0", TokenAmount.format(BigInteger.ZERO, 6))
        assertEquals("<0.0001", TokenAmount.format(BigInteger.ONE, 18))
        assertEquals("12", TokenAmount.format(BigInteger.valueOf(12), 0))
        assertEquals("1.23456789", TokenAmount.format(BigInteger("1234567891"), 9, maxFraction = 8))
        assertEquals(BigInteger.valueOf(1_500_000), TokenAmount.parse("1.5", 6))
        assertNull(TokenAmount.parse("1.0000001", 6), "more fraction digits than the token has")
        assertNull(TokenAmount.parse("0", 6))
        assertNull(TokenAmount.parse("-1", 6))
        assertNull(TokenAmount.parse("abc", 6))
    }

    @Test
    fun builtInTokensShowWithABalanceOthersOnlyWhenAdded() {
        val p = TokenPrefs()
        assertTrue(p.isShown("usdc.fakes.testnet", known = true, balance = BigInteger.ONE))
        assertFalse(p.isShown("usdc.fakes.testnet", known = true, balance = BigInteger.ZERO))
        // Spam sent to the wallet never appears by itself.
        assertFalse(p.isShown("spam.testnet", known = false, balance = BigInteger.TEN))
        val added = p.show("spam.testnet")
        assertTrue(added.isShown("spam.testnet", known = false, balance = BigInteger.ZERO))
        val hidden = added.hide("spam.testnet").hide("usdc.fakes.testnet")
        assertFalse(hidden.isShown("spam.testnet", false, BigInteger.TEN))
        assertFalse(hidden.isShown("usdc.fakes.testnet", true, BigInteger.TEN))
        assertTrue(hidden.remove("usdc.fakes.testnet").isShown("usdc.fakes.testnet", true, BigInteger.TEN))
        // Round trip, and junk in storage is dropped.
        assertEquals(hidden, TokenPrefs.decode(hidden.encode()))
        assertEquals(TokenPrefs(listOf("a.testnet")), TokenPrefs.decode("a=a.testnet,Bad Name,,a.testnet\nh="))
        assertEquals(TokenPrefs(), TokenPrefs.decode(null))
    }

    @Test
    fun builtInListsAreOnTheirNetwork() {
        assertTrue(KnownTokens.on(Network.TESTNET).all { it.contract.endsWith(".testnet") && it.verified })
        assertTrue(KnownTokens.on(Network.MAINNET).none { it.contract.endsWith(".testnet") })
        assertTrue(KnownTokens.on(Network.MAINNET).all { Tokens.isAccount(it.contract) && it.verified })
    }

    @Test
    fun contractTextIsCleaned() {
        assertEquals("USD Coin", Tokens.clean("USD\n\tCoin\u0000", 40))
        assertEquals("abc", Tokens.clean("abcdef", 3))
        assertEquals("", Tokens.clean(null, 3))
    }

    @Test
    fun badContractsAreRefusedBeforeAnyCall() {
        val t = Tokens(NearClient(rpcUrls = listOf("http://127.0.0.1:9")))
        assertFailsWith<TapvaultException> { t.metadata("Not An Account") }
        // A built-in token needs no call at all.
        assertEquals("USDC.e", t.metadata("usdc.fakes.testnet").symbol)
    }

    /** Live: reads real tokens on testnet. Opt-in like the other live tests. */
    @Test
    fun readsTokensOnTestnet() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") return println("skipped: set TAPVAULT_TESTNET=1 to run against testnet")
        val t = Tokens(NearClient())
        val meta = t.metadata("wrap.testnet")
        assertEquals(24, meta.decimals)
        val other = t.metadata("usdc.fakes.testnet")
        assertEquals(6, other.decimals)
        val custom = Tokens(NearClient()).let { k ->
            // Not on the built-in list: comes from ft_metadata and is unverified.
            runCatching { k.metadata("hello2.baideployer.testnet") }.getOrNull()
        }
        custom?.let { assertFalse(it.verified); println("[tokens] custom: ${it.symbol} ${it.decimals}") }
        val holder = "ref-finance-101.testnet"
        val snap = t.load(holder, TokenPrefs())
        println("[tokens] $holder: " + snap.tokens.joinToString { "${it.token.symbol}=${TokenAmount.format(it.raw, it.token.decimals)}" } + "; found ${snap.found.size}")
        assertEquals(KnownTokens.on(Network.TESTNET).size, snap.tokens.size)
        assertTrue(t.registrationCost("usdc.fakes.testnet") <= Tokens.MAX_REGISTRATION)
        assertFalse(t.isRegistered("usdc.fakes.testnet", "a".repeat(64)) ?: true, "a fresh implicit account isn't registered")
    }
}
