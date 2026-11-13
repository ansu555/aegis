package app.tapvault.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityKeyTest {
    private fun pepperWith(secret: ByteArray): (ByteArray) -> ByteArray = { input ->
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(input)
    }

    private val pepper = pepperWith(ByteArray(32) { 7 })

    @Test
    fun theRightKeyVerifiesAndAWrongOneDoesNot() {
        val r = SecurityKey.create("blue-river-42", pepper, iterations = 1_000)
        assertTrue(SecurityKey.verify(r, "blue-river-42", pepper))
        assertFalse(SecurityKey.verify(r, "blue-river-43", pepper))
        assertFalse(SecurityKey.verify(r, "Blue-river-42", pepper))
        // the same key on another phone (another hardware secret) doesn't verify: no offline guessing from a copy of storage
        assertFalse(SecurityKey.verify(r, "blue-river-42", pepperWith(ByteArray(32) { 8 })))
    }

    @Test
    fun recordsRoundTripAndSaltDiffers() {
        val a = SecurityKey.create("blue-river-42", pepper, iterations = 1_000)
        val b = SecurityKey.create("blue-river-42", pepper, iterations = 1_000)
        assertFalse(a.hash.contentEquals(b.hash), "a fresh salt each time")
        val back = SecurityKey.Record.decode(a.encode())
        assertNotNull(back)
        assertTrue(SecurityKey.verify(back, "blue-river-42", pepper))
        assertNull(SecurityKey.Record.decode("garbage"))
        assertNull(SecurityKey.Record.decode(null))
        assertFalse("blue-river-42" in a.encode(), "the key itself is never stored")
    }

    @Test
    fun weakOrMismatchedKeysAreRefused() {
        assertNotNull(SecurityKey.problem("12345", "12345"))
        assertNotNull(SecurityKey.problem("123456", "123456"))
        assertNotNull(SecurityKey.problem("aaaaaaa", "aaaaaaa"))
        assertNotNull(SecurityKey.problem("blue-river-42", "blue-river-24"))
        assertNull(SecurityKey.problem("blue-river-42", "blue-river-42"))
        assertNull(SecurityKey.problem("482913", "482913"))
    }

    @Test
    fun wrongTriesPauseLongerEachTime() {
        assertEquals(0, SecurityKey.pauseAfter(4))
        assertEquals(30, SecurityKey.pauseAfter(5))
        assertEquals(60, SecurityKey.pauseAfter(6))
        assertEquals(3600, SecurityKey.pauseAfter(40))
    }

    @Test
    fun addressBookSavesSearchesAndRefusesBadInput() {
        var b = AddressBook(emptyList()).put("Alice", "alice.testnet").put("Bob", " Bob.Testnet ")
        assertEquals("Bob", b.nameOf("bob.testnet"))
        assertEquals(listOf("Alice"), b.search("ali").map { it.name })
        assertEquals(listOf("Bob"), b.search("bob.t").map { it.name })
        b = b.put("Alice C", "alice.testnet")
        assertEquals("Alice C", b.nameOf("alice.testnet"))
        assertEquals(2, b.entries.size)
        assertFailsWith<IllegalArgumentException> { b.put("bob", "carol.testnet") }
        assertFailsWith<IllegalArgumentException> { b.put("Carol", "not an account") }
        assertFailsWith<IllegalArgumentException> { b.put(" ", "carol.testnet") }
        val back = AddressBook.decode(b.encode())
        assertEquals(b.entries, back.entries)
        assertEquals(1, back.remove("alice.testnet").entries.size)
    }
}
