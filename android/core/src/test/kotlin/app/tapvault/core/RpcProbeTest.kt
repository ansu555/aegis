package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertTrue

/** Live checks of how the client reads RPC errors (TAPVAULT_TESTNET=1). */
class RpcProbeTest {
    @Test
    fun viewErrorsAreReadable() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") return
        val near = NearClient()
        val noCode = runCatching { near.viewFunction("tapvault-skyyy.testnet", "get_wallet") }.exceptionOrNull()
        assertTrue(noCode is NearRpcException && noCode.message!!.contains("CodeDoesNotExist"), "$noCode")
        val noAccount = runCatching { near.viewFunction("tv-nonexistent-zz9q.testnet", "get_wallet") }.exceptionOrNull()
        assertTrue(noAccount is NearRpcException && noAccount.message!!.contains("UNKNOWN_ACCOUNT"), "$noAccount")
        assertTrue(!near.accountExists("tv-nonexistent-zz9q.testnet"))
        assertTrue(near.accountExists("tapvault-skyyy.testnet"))
    }
}
