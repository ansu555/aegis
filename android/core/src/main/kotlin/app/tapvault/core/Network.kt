package app.tapvault.core

/**
 * A NEAR network: where its RPCs, account index and explorer are, what its
 * named accounts end in, and what only exists on testnet (the faucet, the
 * sponsor). Every wallet belongs to one network for life; the app stores it
 * with the wallet and never infers it from the account name (an implicit
 * 64-hex account looks the same on both networks).
 */
class Network private constructor(
    /** Stored with each wallet: "testnet" or "mainnet". Never change these. */
    val id: String,
    /** "NEAR testnet", "NEAR mainnet": used in messages ("doesn't exist on NEAR testnet"). */
    val displayName: String,
    val rpcUrls: List<String>,
    /** FastNEAR's account index (accounts per public key). */
    val indexUrl: String,
    /** Account creation for free; null where there is none (mainnet). */
    val faucetUrl: String?,
    private val explorer: String,
    /** What named accounts end in: ".testnet", ".near". */
    val accountSuffix: String,
) {
    val isTestnet get() = this === TESTNET
    val isMainnet get() = this === MAINNET

    /** Free accounts from the faucet, and a sponsor account in the build: testnet only. */
    val hasFaucet get() = faucetUrl != null
    val allowsSponsor get() = isTestnet

    fun explorerTx(hash: String) = "$explorer/txns/$hash"
    fun explorerAccount(account: String) = "$explorer/address/$account"

    /** An example account name for placeholders: "bob.testnet", "bob.near". */
    fun example(name: String) = "$name$accountSuffix"

    /**
     * Why [account] can't be an account on this network, or null. Implicit
     * accounts (64 hex, or 0x + 40 hex) exist on both; a name ending in
     * ".testnet" is a testnet account, and testnet names always end in it.
     */
    fun accountProblem(account: String): String? = when {
        isImplicit(account) -> null
        isTestnet && !account.endsWith(".testnet") -> "That's not a testnet account: testnet names end in .testnet"
        isMainnet && account.endsWith(".testnet") -> "That's a testnet account; this wallet is on NEAR mainnet"
        else -> null
    }

    /** The other network: where a wallet with the same implicit account could also exist. */
    val other: Network get() = if (isTestnet) MAINNET else TESTNET

    override fun toString() = id

    companion object {
        val TESTNET = Network(
            id = "testnet",
            displayName = "NEAR testnet",
            rpcUrls = listOf(
                "https://rpc.testnet.fastnear.com",
                "https://test.rpc.fastnear.com",
                "https://rpc.testnet.near.org",
            ),
            indexUrl = "https://test.api.fastnear.com",
            faucetUrl = "https://helper.nearprotocol.com/account",
            explorer = "https://testnet.nearblocks.io",
            accountSuffix = ".testnet",
        )

        val MAINNET = Network(
            id = "mainnet",
            displayName = "NEAR mainnet",
            rpcUrls = listOf(
                "https://rpc.mainnet.fastnear.com",
                "https://free.rpc.fastnear.com",
                "https://rpc.mainnet.near.org",
            ),
            indexUrl = "https://api.fastnear.com",
            faucetUrl = null,
            explorer = "https://nearblocks.io",
            accountSuffix = ".near",
        )

        val ALL = listOf(MAINNET, TESTNET)

        /** The network stored as [id]; anything else (an entry from before networks existed) is testnet. */
        fun of(id: String?): Network = if (id == MAINNET.id) MAINNET else TESTNET

        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val ETH = Regex("^0x[0-9a-f]{40}$")

        /** An implicit account: the same name can exist on every network. */
        fun isImplicit(account: String) = HEX64.matches(account) || ETH.matches(account)
    }
}
