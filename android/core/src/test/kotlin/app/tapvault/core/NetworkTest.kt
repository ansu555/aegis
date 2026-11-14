package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mainnet and testnet in one app: what each network allows, all offline. */
class NetworkTest {
    private val implicit = "a".repeat(64)

    @Test
    fun storedIdsMapBackAndOldEntriesAreTestnet() {
        assertSame(Network.MAINNET, Network.of("mainnet"))
        assertSame(Network.TESTNET, Network.of("testnet"))
        // A wallet saved before networks existed has no id: it is a testnet wallet.
        assertSame(Network.TESTNET, Network.of(null))
        assertSame(Network.TESTNET, Network.of("something-else"))
        assertSame(Network.TESTNET, Network.MAINNET.other)
        assertSame(Network.MAINNET, Network.TESTNET.other)
    }

    @Test
    fun clientsUseTheirNetworksEndpoints() {
        val main = NearClient(Network.MAINNET)
        assertTrue(main.rpcUrls.all { "testnet" !in it && "test." !in it }, "mainnet RPCs: ${main.rpcUrls}")
        assertEquals("https://nearblocks.io/txns/abc", main.explorerTx("abc"))
        assertEquals("https://nearblocks.io/address/bob.near", main.explorerAccount("bob.near"))
        assertEquals("https://api.fastnear.com", main.indexUrl)

        val test = NearClient()
        assertSame(Network.TESTNET, test.network)
        assertEquals(NearClient.TESTNET_RPCS, test.rpcUrls)
        assertEquals("https://testnet.nearblocks.io/txns/abc", test.explorerTx("abc"))
    }

    @Test
    fun onlyTestnetHasTheFaucetAndTheSponsor() {
        assertTrue(Network.TESTNET.hasFaucet && Network.TESTNET.allowsSponsor)
        assertTrue(!Network.MAINNET.hasFaucet && !Network.MAINNET.allowsSponsor)
        assertNull(Network.MAINNET.faucetUrl)
    }

    @Test
    fun onboardingRefusesFaucetAndSponsorOnMainnetBeforeAnyCall() {
        val ob = Onboarding(NearClient(Network.MAINNET, rpcUrls = listOf(UNREACHABLE)))
        val sponsor = Onboarding.Sponsor("sponsor.near", NearKey.random())
        val e1 = assertFailsWith<TapvaultException> { ob.createAccounts() }
        assertTrue("no faucet" in e1.message!!, e1.message)
        assertFailsWith<TapvaultException> { ob.createAccounts(sponsor = sponsor) }
        assertFailsWith<TapvaultException> { ob.createFeeAccount(sponsor = sponsor) }
        assertFailsWith<NearRpcException> { NearClient(Network.MAINNET).createTestnetAccount("x.near", NearKey.random()) }
    }

    @Test
    fun accountsAreCheckedAgainstTheNetwork() {
        assertNull(Network.TESTNET.accountProblem("bob.testnet"))
        assertNull(Network.TESTNET.accountProblem("tv-1a2b3c4d5e.sponsor.testnet"))
        assertNotNull(Network.TESTNET.accountProblem("bob.near"))
        assertNull(Network.MAINNET.accountProblem("bob.near"))
        assertNull(Network.MAINNET.accountProblem("bob.tg"))
        assertNotNull(Network.MAINNET.accountProblem("bob.testnet"))
        // Implicit accounts exist on both networks.
        for (n in Network.ALL) {
            assertNull(n.accountProblem(implicit))
            assertNull(n.accountProblem("0x" + "b".repeat(40)))
        }
        assertEquals("bob.near", Network.MAINNET.example("bob"))
        assertEquals("bob.testnet", Network.TESTNET.example("bob"))
        assertTrue(Network.isImplicit(implicit) && !Network.isImplicit("a".repeat(63)) && !Network.isImplicit("A".repeat(64)))
    }

    @Test
    fun crossNetworkCheckSkipsNamedAccountsAndFailsClosed() {
        val other = NearClient(Network.MAINNET, rpcUrls = listOf(UNREACHABLE))
        // A named account can't exist on both networks: nothing to ask, so no call is made.
        SeedWallet(NearClient(), "tv-1a2b3c4d5e.testnet", NearKey.random()).checkNotOnOtherNetwork(other)
        // An implicit one can: if the other network can't be asked, protecting is refused, not allowed.
        val e = assertFailsWith<TapvaultException> { SeedWallet(NearClient(), implicit, NearKey.random()).checkNotOnOtherNetwork(other) }
        assertTrue("Can't check" in e.message!!, e.message)
        // The other network's client must really be the other network.
        assertFailsWith<IllegalArgumentException> { SeedWallet(NearClient(), implicit, NearKey.random()).checkNotOnOtherNetwork(NearClient()) }
    }

    private companion object {
        /** Nothing listens here, so a call fails at once without leaving the machine. */
        const val UNREACHABLE = "http://127.0.0.1:9"
    }
}
