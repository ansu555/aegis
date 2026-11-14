package app.tapvault.core

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A seed-phrase wallet against the live NEAR testnet, then "Protect with
 * bank card": the phrase's account pays on its own, gets a second key (as if
 * the phrase had been imported into another wallet), and is turned into a
 * Aegis wallet. Afterwards no key is left, so the phrase can't move funds.
 *
 *   TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*SeedWalletE2E*' --rerun
 */
class SeedWalletE2ETest {
    private fun step(s: String) = println("[e2e] $s")

    @Test
    fun seedPhraseWalletThenProtectWithCard() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") {
            println("skipped: set TAPVAULT_TESTNET=1 to run against testnet")
            return
        }
        val wasm = File(System.getenv("TAPVAULT_WASM") ?: fail("TAPVAULT_WASM not set")).readBytes()
        val near = NearClient()
        val ob = Onboarding(near)
        val sponsor = System.getenv("TAPVAULT_SPONSOR_ACCOUNT")?.let { acct ->
            System.getenv("TAPVAULT_SPONSOR_KEY")?.let { Onboarding.Sponsor(acct, NearKey.fromSecret(it)) }
        }

        step("new recovery phrase (12 words); its key owns the wallet account")
        val phrase = Mnemonic.generate()
        val seedKey = Mnemonic.nearKey(phrase)
        val plan = Onboarding.Plan(ByteArray(5).also { java.security.SecureRandom().nextBytes(it) }.hex(), seedKey, NearKey.random())
        // Only the wallet account is used; its faucet fee account is left unused (a fee account is funded below).
        val created = ob.createAccounts("tv-e2e", sponsor, plan) { step(it) }
        step("wallet ${created.wallet}")
        assertEquals(listOf(seedKey.publicKeyString), near.accessKeys(created.wallet))

        step("re-derive from the phrase (as an import would) and pay 0.1 NEAR with the phrase's key alone")
        val (imported, _) = SeedWallet.parseImport(phrase.uppercase())
        assertEquals(seedKey.publicKeyString, imported.publicKeyString)
        val sw = SeedWallet(near, created.wallet, imported)
        val receiver = NearKey.random().publicKey.hex()
        step("sent: ${near.explorerTx(sw.send(receiver, Near.parse("0.1")).hash)}")
        assertEquals(Near.parse("0.1"), near.balance(receiver))
        val found = runCatching { SeedWallet.findAccounts(near, imported) }.getOrDefault(emptyList())
        step("accounts found for the key: $found (the index can lag a few seconds)")

        step("another wallet app adds its own full-access key to the account")
        val other = NearKey.random()
        assertTrue(near.send(created.wallet, seedKey, created.wallet, listOf(NearAction.AddFullAccessKey(other.publicKey))).ok)
        assertEquals(2, near.accessKeys(created.wallet).size)

        step("protect: preflight, fee account funded by the wallet, deploy (deleting the other key), card, finalize")
        val pre = sw.preflight(wasm.size, needsFeeAccount = true)
        assertEquals(listOf(other.publicKeyString), pre.otherKeys)
        step("  needs ${Near.format(pre.needed, 2)} NEAR, has ${Near.format(pre.spendable, 2)}")
        val gasKey = NearKey.random()
        val gas = sw.fundFeeAccount(gasKey)
        step("  fee account $gas")
        val a = Onboarding.Accounts(created.wallet, seedKey, gas, gasKey)
        val phone = SoftP256Signer()
        step("  deployed: ${near.explorerTx(ob.deployAndInit(a, wasm, phone, pre.otherKeys).hash)}")
        assertEquals(listOf(seedKey.publicKeyString), near.accessKeys(created.wallet), "the other key is gone with the deploy")

        val card = SimCard(SimCard.Profile.valueOf(System.getenv("TAPVAULT_CARD_PROFILE") ?: "DDA"))
        val cards = CardService(CapkStore.load().plus(SimCard.TEST_CA))
        ob.addCard(a, phone, 1, cards.enrol(card) { k -> Domain.cardEnrollChallenge(a.wallet, 1, k.keyHash()) })
        ob.finalize(a, phone, FinalizeArgs(2, Delays.SHORTEST, listOf(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO))))
        assertTrue(near.accessKeys(a.wallet).isEmpty(), "no keys after finalize: the phrase is dead")
        val st = TapvaultWallet(near, a.wallet, a.gas, a.gasKey).state()
        assertEquals(2, st.threshold)
        assertEquals(1, st.cards().size)

        val dead = runCatching { sw.send(receiver, Near.parse("0.1")) }
        assertTrue(dead.isFailure, "the phrase's key must not be able to send any more")
        step("the phrase can no longer move funds (${dead.exceptionOrNull()?.message?.take(80)})")
        step("done. wallet: ${near.explorerAccount(a.wallet)}")
    }
}
