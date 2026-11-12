package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmvTest {
    private val capks = CapkStore.load().plus(SimCard.TEST_CA)

    @Test
    fun capkFileLoadsWithChecksummedKeys() {
        val all = CapkStore.load()
        assertTrue(all.find("A000000003", 0x09).isNotEmpty(), "Visa 09")
        assertTrue(all.find("A000000004", 0xEF).isNotEmpty(), "Mastercard EF")
        assertTrue(all.find("A000000524", 0x6B).isNotEmpty(), "RuPay 6B")
        assertTrue(all.find("A000000524", 0x05).isNotEmpty(), "RuPay 05")
        assertTrue(all.find("A000000003", 0x10).isNotEmpty(), "Visa 10")
    }

    @Test
    fun tlvRoundTrip() {
        val enc = Tlv.encode(0x70, Tlv.encode(0x9F37, "01020304".unhex()), Tlv.encode(0x5A, ByteArray(200)))
        val t = Tlv.parse(enc)
        assertEquals(0x70, t[0].tag)
        assertContentEquals("01020304".unhex(), Tlv.find(t, 0x9F37)!!.value)
        assertEquals(200, Tlv.find(t, 0x5A)!!.value.size)
    }

    @Test
    fun enrolsTheSimulatedCardThroughItsCertificateChain() {
        val card = SimCard()
        val challengeFor = { k: SignerKey.EmvCard -> Domain.cardEnrollChallenge("w.testnet", 1, k.keyHash()) }
        val e = CardService(capks).enrol(card, challengeFor)
        // recovered key is exactly the card's ICC key
        assertContentEquals(TestCards.SMALL.modulus, e.key.modulus)
        assertEquals(3, e.key.exponent)
        assertEquals(0, e.key.unOffset); assertEquals(4, e.key.unLen); assertEquals(4, e.key.terminalDataLen)
        // the proof signs the enrolment challenge
        assertContentEquals(challengeFor(e.key).copyOf(4), e.proof.terminalData)
        assertTrue(Rsa.verifySdad(e.key.modulus, 3, e.proof.sdad, e.proof.terminalData))
        // the report never carries the PAN
        assertEquals("1234", e.report.panLast4)
        assertTrue(!e.report.text().contains("5999990000001234"))
        assertEquals("unknown", e.report.network)
        assertEquals(SignMethod.DDA, e.recipe.method)
    }

    @Test
    fun withoutTheTestCaTheChainIsRejected() {
        val err = assertFailsWith<CardCheckFailed> {
            CardService(CapkStore.load()).enrol(SimCard()) { ByteArray(32) }
        }
        assertTrue(err.message!!.contains("no CA key"), err.message)
    }

    @Test
    fun signsChallengesAndRejectsAnotherCard() {
        val svc = CardService(capks)
        val card = SimCard()
        val key = svc.enrol(card) { ByteArray(32) }.key
        val ch = sha256("challenge".toByteArray())
        val sig = svc.sign(card, key, ch)
        assertContentEquals(ch.copyOf(4), sig.terminalData)

        val other = SignerKey.EmvCard(TestRsaKey.of(SimKeys.ISSUER_N, SimKeys.ISSUER_D).modulus, 3, 0, 4, 4)
        assertFailsWith<WrongCardException> { svc.sign(card, other, ch) }
    }

    @Test
    fun inspectReportsDdaCapability() {
        val r = CardService(capks).inspect(SimCard())
        assertTrue(r.ddaSupported)
        assertTrue(r.notes.any { it.contains("can be a Tapvault signer") }, r.text())
    }

    @Test
    fun enrolsAndSignsWithEveryCardFamily() {
        for (profile in SimCard.Profile.values()) {
            val svc = CardService(capks)
            val card = SimCard(profile)
            val challengeFor = { k: SignerKey.EmvCard -> Domain.cardEnrollChallenge("w.testnet", 1, k.keyHash()) }
            val e = svc.enrol(card, challengeFor)
            val expected = mapOf(
                SimCard.Profile.DDA to SignMethod.DDA,
                SimCard.Profile.VISA_FDDA to SignMethod.FDDA,
                SimCard.Profile.MASTERCARD_CDA to SignMethod.CDA,
            )
            assertEquals(expected[profile], e.recipe.method, "$profile")
            assertContentEquals(TestCards.SMALL.modulus, e.key.modulus)
            assertTrue(e.report.canSign, e.report.text())
            // What the contract checks: terminal data length, the UN at its offset, and the SDAD.
            assertTrue(e.key.terminalDataLen <= 64 && e.key.unLen in 4..8)
            assertEquals(e.key.terminalDataLen, e.proof.terminalData.size)
            assertContentEquals(challengeFor(e.key).copyOf(e.key.unLen), e.proof.terminalData.copyOfRange(e.key.unOffset, e.key.unOffset + e.key.unLen))
            assertTrue(Rsa.verifySdad(e.key.modulus, e.key.exponent, e.proof.sdad, e.proof.terminalData))

            // Later taps, with the saved recipe and without it.
            for (recipe in listOf(e.recipe, null)) {
                val ch = sha256("challenge $profile $recipe".toByteArray())
                val sig = svc.sign(card, e.key, ch, recipe)
                assertEquals(e.key.terminalDataLen, sig.terminalData.size, "$profile $recipe")
                assertContentEquals(ch.copyOf(e.key.unLen), sig.terminalData.copyOfRange(e.key.unOffset, e.key.unOffset + e.key.unLen))
                assertTrue(Rsa.verifySdad(e.key.modulus, e.key.exponent, sig.sdad, sig.terminalData))
            }
        }
    }

    @Test
    fun visaStyleCardSignsFullFddaTerminalData() {
        val e = CardService(capks).enrol(SimCard(SimCard.Profile.VISA_FDDA)) { ByteArray(32) }
        assertEquals(CardRecipe.FDDA_FULL, e.recipe.variant)
        assertEquals(CardRecipe.TTQ_ONLINE, e.recipe.ttq)
        // UN 4 + amount 6 + currency 2 + card authentication data (9F69, read fresh from the last record) 7
        assertEquals(19, e.key.terminalDataLen)
        assertEquals(0, e.key.unOffset)
        // An online (ARQC) fDDA signature uses Signed Data Format 95.
        assertEquals(0x95, Rsa.sdadFormat(e.key.modulus, e.key.exponent, e.proof.sdad))
        assertTrue(e.report.attempts.any { it.contains("format 95") }, e.report.text())
        // The offline-allowed TTQ gives an offline approval signed with format 05, same layout.
        val card = SimCard(SimCard.Profile.VISA_FDDA)
        val ch = sha256("offline".toByteArray())
        val sig = CardService(capks).sign(card, e.key, ch, e.recipe.copy(ttq = CardRecipe.TTQ_OFFLINE_OK))
        assertEquals(0x05, Rsa.sdadFormat(e.key.modulus, e.key.exponent, sig.sdad))
        assertEquals(19, sig.terminalData.size)
    }

    @Test
    fun formatBytesOtherThan05And95AreRejected() {
        val n = TestCards.SMALL.modulus.size
        for ((format, ok) in listOf(0x05 to true, 0x95 to true, 0x03 to false, 0x04 to false, 0x15 to false)) {
            val body = byteArrayOf(format.toByte(), 0x01, 3, 2, 0, 1)
            val padded = body + ByteArray(n - 22 - body.size) { 0xBB.toByte() }
            val td = "01020304".unhex()
            val rec = byteArrayOf(0x6A) + padded + sha1(padded, td) + byteArrayOf(0xBC.toByte())
            val sdad = TestCards.SMALL.sign(rec)
            assertEquals(ok, Rsa.verifySdad(TestCards.SMALL.modulus, 3, sdad, td), "format %02X".format(format))
        }
    }

    @Test
    fun mastercardStyleCardFallsBackToANonZeroAmount() {
        val e = CardService(capks).enrol(SimCard(SimCard.Profile.MASTERCARD_CDA)) { ByteArray(32) }
        assertEquals(SignMethod.CDA, e.recipe.method)
        assertEquals(100L, e.recipe.amount)
        assertEquals(4, e.key.terminalDataLen)
        assertTrue(e.report.attempts.any { it.contains("AAC") }, e.report.text())
    }

    @Test
    fun recipesRoundTrip() {
        val r = CardRecipe("A0000000041010", SignMethod.CDA, 100, CardRecipe.CDA_TC)
        assertEquals(r, CardRecipe.decode(r.encode()))
        val v = CardRecipe("A0000000031010", SignMethod.FDDA, 0, CardRecipe.FDDA_FULL, CardRecipe.TTQ_OFFLINE_OK)
        assertEquals(v, CardRecipe.decode(v.encode()))
        assertEquals(null, CardRecipe.decode("garbage"))
        assertEquals(null, CardRecipe.decode(null))
    }

    @Test
    fun reportsAndTracesNeverCarryTheCardNumber() {
        for (profile in SimCard.Profile.values()) {
            val r = CardService(capks).inspect(SimCard(profile))
            val all = r.diagnostics()
            assertTrue(r.trace.isNotEmpty())
            assertTrue(!all.contains("5999990000001234") && !all.contains("59999900000012"), all)
            assertTrue(r.trace.none { it.contains("5A=") || it.contains("9F46=") || it.contains("90=") }, all)
        }
        // A card without the CA key still gets a useful report.
        val r = CardService(CapkStore.load()).inspect(SimCard())
        assertTrue(!r.canSign)
        assertTrue(r.notes.any { it.startsWith("can't be a Tapvault signer") }, r.text())
    }

    @Test
    fun p256DerConversionAndCompression() {
        val s = SoftP256Signer()
        assertEquals(33, s.publicKey.size)
        assertTrue(s.publicKey[0] == 0x02.toByte() || s.publicKey[0] == 0x03.toByte())
        assertEquals(64, s.sign(ByteArray(32)).signature.size)
    }

    @Test
    fun base58RoundTrip() {
        val b = "00000102030405ff".unhex()
        assertContentEquals(b, Base58.decode(Base58.encode(b)))
    }
}
