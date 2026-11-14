package app.tapvault.core

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract v0.4 inheritance against the live NEAR testnet. A phone-only
 * wallet names an heir with the shortest inactivity period (1 h); the phone
 * checks in; a claim is refused while the owner is active. Creates two fresh
 * testnet accounts.
 *
 *   TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*InheritanceE2E*' --rerun
 *
 * The SetHeir change waits the config delay (1 h), so by default the run
 * stops after it is refused early. With TAPVAULT_WAIT_DELAYS=1 it waits
 * (about 3 h 10 min in all): SetHeir executes, the owner stays silent for the
 * inactivity period, anyone starts the claim, and after the grace period (the
 * config delay) the wallet's NEAR goes to the heir.
 */
class InheritanceE2ETest {
    private fun step(s: String) = println("[e2e] $s")

    private fun waitUntil(t: Long, what: String) {
        while (true) {
            val left = t + 20 - System.currentTimeMillis() / 1000
            if (left <= 0) return
            step("  waiting ${left / 60} min for $what")
            Thread.sleep(minOf(left, 600) * 1000)
        }
    }

    @Test
    fun heirReceivesTheWalletAfterInactivity() {
        if (System.getenv("TAPVAULT_TESTNET") != "1") {
            println("skipped: set TAPVAULT_TESTNET=1 to run against testnet")
            return
        }
        val wasm = File(System.getenv("TAPVAULT_WASM") ?: fail("TAPVAULT_WASM not set")).readBytes()
        val near = NearClient()
        val phone = SoftP256Signer()
        val ob = Onboarding(near)
        val sponsor = System.getenv("TAPVAULT_SPONSOR_ACCOUNT")?.let { acct ->
            System.getenv("TAPVAULT_SPONSOR_KEY")?.let { Onboarding.Sponsor(acct, NearKey.fromSecret(it)) }
        }
        val a = ob.createAccounts("tv-e2e", sponsor) { step(it) }
        step("wallet ${a.wallet}, gas ${a.gas}")
        near.explorerTx(ob.deployAndInit(a, wasm, phone).hash).let(::step)
        ob.finalize(a, phone, FinalizeArgs(1, Delays.SHORTEST, listOf(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO))))
        val w = TapvaultWallet(near, a.wallet, a.gas, a.gasKey)
        assertEquals("0.4.0", w.contractVersion())
        assertTrue(w.isV04())
        val st = w.state()
        val phoneIdx = st.phoneIndexOf(phone.publicKey)!!
        assertTrue(!w.inheritance().hasHeir)

        val heir = NearKey.random().publicKey.hex()
        step("name the heir $heir with ${Inheritance.describePeriod(Inheritance.MIN_INACTIVITY)} of inactivity")
        val set = Action.Config(listOf(ConfigOp.SetHeir(heir, Inheritance.MIN_INACTIVITY)))
        val id = w.proposeByPhone(set, phone, phoneIdx)
        val p = w.proposal(id)!!
        val early = runCatching { w.execute(id) }
        assertTrue(early.exceptionOrNull()?.message?.contains("Delay has not elapsed") == true, "got ${early.exceptionOrNull()?.message}")
        step("SetHeir refused before the config delay, as expected")

        if (System.getenv("TAPVAULT_WAIT_DELAYS") != "1") {
            step("done (set TAPVAULT_WAIT_DELAYS=1 to wait for the claim). wallet: ${near.explorerAccount(a.wallet)}")
            return
        }
        waitUntil(p.readyTs!! + st.delays.config, "SetHeir #$id")
        step("heir named: ${near.explorerTx(w.execute(id).hash)}")
        assertEquals(heir, w.inheritance().heir)

        step("the owner checks in with the phone")
        near.explorerTx(w.checkInByPhone(phone, phoneIdx).hash).let(::step)
        val info = w.inheritance()
        assertEquals(1L, info.aliveNonce)
        val active = runCatching { w.startInheritanceClaim() }
        assertTrue(active.exceptionOrNull()?.message?.contains("active within") == true, "got ${active.exceptionOrNull()?.message}")
        step("claim refused while the owner is active, as expected")

        waitUntil(info.claimableAt!!, "the inactivity period to end")
        step("claim started by anyone: ${near.explorerTx(w.startInheritanceClaim().hash)}")
        val claimed = w.inheritance()
        assertEquals(Inheritance.Stage.CLAIMED, claimed.stage(System.currentTimeMillis() / 1000))
        val grace = runCatching { w.releaseAllToHeir(heir, emptyMap()) }.getOrThrow()
        assertTrue(grace.single().second?.contains("grace period") == true, "got $grace")
        step("release refused during the grace period, as expected")

        waitUntil(claimed.releasableAt!!, "the grace period to end")
        val walletBefore = near.balance(a.wallet)
        val out = w.releaseAllToHeir(heir, emptyMap())
        assertTrue(out.all { it.second == null }, "release: $out")
        val got = near.balance(heir)
        step("heir received ${Near.format(got)} NEAR (the wallet had ${Near.format(walletBefore)}; it keeps its storage)")
        assertTrue(got > Near.ONE, "heir got $got")
        step("done. wallet: ${near.explorerAccount(a.wallet)}  heir: ${near.explorerAccount(heir)}")
    }
}
