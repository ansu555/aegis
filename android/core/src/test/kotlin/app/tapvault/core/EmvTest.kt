package app.tapvault.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmvTest {
    private val capks = CapkStore.load().plus(SimCard.DEMO_CA)

    @Test
    fun capkFileLoadsWithChecksummedKeys() {
        val all = CapkStore.load()
        assertTrue(all.find("A000000003", 0x09).isNotEmpty(), "Visa 09")
        assertTrue(all.find("A000000004", 0xEF).isNotEmpty(), "Mastercard EF")
        assertTrue(all.find("A000000524", 0x6B).isNotEmpty(), "RuPay 6B")
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
        assertEquals("Tapvault demo card", e.report.network)
    }

    @Test
    fun withoutTheDemoCaTheChainIsRejected() {
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
        assertFailsWith<EmvException> { svc.sign(card, other, ch) }
    }

    @Test
    fun inspectReportsDdaCapability() {
        val r = CardService(capks).inspect(SimCard())
        assertTrue(r.ddaSupported)
        assertTrue(r.notes.any { it.contains("can be a Tapvault signer") }, r.text())
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
