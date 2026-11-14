package app.tapvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InheritanceTest {
    private fun view(s: String) = InheritanceInfo.parse(Json.parseToJsonElement(s).jsonObject)

    @Test
    fun parsesTheViewAndTellsTheStage() {
        val none = view("""{"heir":null,"inactivity":0,"last_active":0,"claim_ts":null,"claimable_at":null,"releasable_at":null,"alive_nonce":0,"alive_message":"00","card_alive_challenge":"00"}""")
        assertNull(none.heir)
        assertEquals(Inheritance.Stage.NONE, none.stage(10))

        val w = view("""{"heir":"mum.testnet","inactivity":3600,"last_active":1000,"claim_ts":null,"claimable_at":4601,"releasable_at":null,"alive_nonce":2,"alive_message":"00","card_alive_challenge":"00"}""")
        assertEquals("mum.testnet", w.heir)
        assertEquals(Inheritance.Stage.WATCHING, w.stage(4600))
        assertEquals(1L, w.secondsToClaimable(4600))
        assertEquals(Inheritance.Stage.CLAIMABLE, w.stage(4601))

        val c = w.copy(claimTs = 5000, releasableAt = 8600)
        assertEquals(Inheritance.Stage.CLAIMED, c.stage(8599))
        assertEquals(Inheritance.Stage.RELEASABLE, c.stage(8600))
    }

    @Test
    fun periodsMatchTheContractBounds() {
        assertTrue(Inheritance.choices(testnet = true).all { Inheritance.isValidPeriod(it) })
        assertEquals(Inheritance.HOUR, Inheritance.choices(testnet = true).first())
        assertTrue(Inheritance.HOUR !in Inheritance.choices(testnet = false))
        assertTrue(!Inheritance.isValidPeriod(3_599) && !Inheritance.isValidPeriod(3651L * 86_400))
        assertEquals("180 days", Inheritance.describePeriod(180 * Inheritance.DAY))
        assertEquals("1 year", Inheritance.describePeriod(365 * Inheritance.DAY))
        assertEquals("1 hour", Inheritance.describePeriod(3_600))
        assertEquals("2 d 3 h", Inheritance.describeLeft(2 * 86_400 + 3 * 3_600 + 59))
    }

    @Test
    fun thePromptSaysWhoInherits() {
        val text = Action.Config(listOf(ConfigOp.SetHeir("mum.testnet", 180 * Inheritance.DAY))).describe()
        assertTrue("LEAVE EVERYTHING TO mum.testnet" in text && "180 days" in text, text)
    }

    @Test
    fun theWatchtowerAnnouncesEachStateOnce() {
        val d = Inheritance.DAY
        val w = InheritanceInfo("mum.testnet", 180 * d, 0, null, 180 * d + 1, null, 0)
        assertNull(Watch.inheritance(null))
        assertNull(Watch.inheritance(w.copy(heir = null)))
        assertNull(Watch.inheritance(w, 100 * d))
        val soon = Watch.inheritance(w, 175 * d)!!
        assertEquals(Watch.Kind.INHERITANCE, soon.second.kind)
        assertTrue("Check in soon" in soon.second.title)
        val claimable = Watch.inheritance(w, 181 * d)!!
        assertTrue(claimable.first != soon.first)
        val claimed = Watch.inheritance(w.copy(claimTs = 181 * d, releasableAt = 182 * d), 181 * d + 5)!!
        assertTrue("claim" in claimed.second.title.lowercase() && claimed.first != claimable.first)
        // the same state gives the same key, so it is posted once
        assertEquals(claimed.first, Watch.inheritance(w.copy(claimTs = 181 * d, releasableAt = 182 * d), 181 * d + 500)!!.first)
    }
}
