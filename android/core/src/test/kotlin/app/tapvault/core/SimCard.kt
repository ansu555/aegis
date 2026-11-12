package app.tapvault.core

import java.math.BigInteger

/** RSA keys with their private half, for test cards. */
class TestRsaKey(val modulus: ByteArray, val exponent: Int, private val d: BigInteger) {
    /** Raw RSA private operation: `m^d mod n`. */
    fun sign(m: ByteArray): ByteArray =
        m.toPositiveBigInteger().modPow(d, modulus.toPositiveBigInteger()).toFixed(modulus.size)

    companion object {
        fun of(n: String, d: String, e: Int = 3) = TestRsaKey(n.unhex(), e, BigInteger(d, 16))
    }
}

object TestCards {
    /** The card key shared with the Rust TestCard (1024-bit, e = 3). */
    val SMALL = TestRsaKey.of(SimKeys.ICC_N, SimKeys.ICC_D)
}

/**
 * A simulated contactless EMV card for the automated tests (it is not part of
 * the app). It answers real APDUs: PPSE and application SELECT, GET
 * PROCESSING OPTIONS, READ RECORD (with a test CA -> issuer -> card
 * certificate chain), and signs the way one real card family does ([Profile]).
 * Its keys are public test keys.
 */
class SimCard(val profile: Profile = Profile.DDA) : CardTransceiver {
    enum class Profile {
        /** Classic DDA: signs on INTERNAL AUTHENTICATE. */
        DDA,

        /** Like Visa payWave: fDDA (version 01) signature in the GPO answer; no INTERNAL AUTHENTICATE or GENERATE AC. */
        VISA_FDDA,

        /** Like Mastercard contactless: CDA signature from GENERATE AC, declines (AAC) a zero amount; no INTERNAL AUTHENTICATE. */
        MASTERCARD_CDA,
    }

    private val ca = TestRsaKey.of(SimKeys.CA_N, SimKeys.CA_D)
    private val issuer = TestRsaKey.of(SimKeys.ISSUER_N, SimKeys.ISSUER_D)
    private val icc = TestCards.SMALL
    private var gpoDone = false
    private var acDone = false
    private var atc = 0
    var taps = 0
        private set

    private val aip = when (profile) {
        Profile.DDA, Profile.VISA_FDDA -> "2000".unhex() // (f)DDA supported
        Profile.MASTERCARD_CDA -> "1980".unhex() // CDA supported, no DDA
    }
    private val pdol = when (profile) {
        Profile.DDA -> "9F66049F3704".unhex()
        Profile.VISA_FDDA -> "9F66049F02069F03069F1A0295055F2A029A039C019F3704".unhex()
        Profile.MASTERCARD_CDA -> ByteArray(0)
    }

    private val record1: ByteArray
    private val record2: ByteArray

    init {
        val issuerCert = cert(
            signer = ca,
            header = byteArrayOf(0x02) + ISSUER_ID + EXPIRY + "000001".unhex() + byteArrayOf(0x01, 0x01),
            key = issuer.modulus,
            extra = ByteArray(0),
            leftmostRoom = ca.modulus.size - 36,
        )
        record1 = Tlv.encode(
            0x70,
            *listOfNotNull(
                Tlv.encode(0x5A, PAN),
                Tlv.encode(0x5F24, "301231".unhex()),
                if (profile == Profile.DDA) Tlv.encode(0x9F49, "9F3704".unhex()) else null,
                if (profile == Profile.MASTERCARD_CDA) Tlv.encode(0x8C, CDOL1) else null,
                Tlv.encode(0x8F, byteArrayOf(CA_INDEX.toByte())),
                Tlv.encode(0x90, issuerCert.first),
                Tlv.encode(0x92, issuerCert.second),
                Tlv.encode(0x9F32, byteArrayOf(0x03)),
                Tlv.encode(0x9F4A, byteArrayOf(0x82.toByte())),
            ).toTypedArray(),
        )
        // Static data to authenticate: record 1's contents, then the AIP (9F4A = 82).
        val staticData = Tlv.parse(record1)[0].value + aip
        val iccCert = cert(
            signer = issuer,
            header = byteArrayOf(0x04) + PAN + byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + EXPIRY + "000001".unhex() + byteArrayOf(0x01, 0x01),
            key = icc.modulus,
            extra = staticData,
            leftmostRoom = issuer.modulus.size - 42,
        )
        record2 = Tlv.encode(
            0x70,
            Tlv.encode(0x9F46, iccCert.first),
            Tlv.encode(0x9F48, iccCert.second),
            Tlv.encode(0x9F47, byteArrayOf(0x03)),
        )
    }

    /** Builds an EMV public-key certificate; returns (certificate, remainder). */
    private fun cert(signer: TestRsaKey, header: ByteArray, key: ByteArray, extra: ByteArray, leftmostRoom: Int): Pair<ByteArray, ByteArray> {
        val exp = byteArrayOf(0x03)
        val leftmost = if (key.size <= leftmostRoom) key + ByteArray(leftmostRoom - key.size) { 0xBB.toByte() } else key.copyOf(leftmostRoom)
        val remainder = if (key.size > leftmostRoom) key.copyOfRange(leftmostRoom, key.size) else ByteArray(0)
        val body = header + byteArrayOf(key.size.toByte(), exp.size.toByte()) + leftmost
        val hash = sha1(body, remainder, exp, extra)
        val rec = byteArrayOf(0x6A) + body + hash + byteArrayOf(0xBC.toByte())
        check(rec.size == signer.modulus.size)
        return signer.sign(rec) to remainder
    }

    override fun transceive(apdu: ByteArray): ByteArray {
        val ins = apdu[1].toInt() and 0xff
        val p1 = apdu[2].toInt() and 0xff
        val p2 = apdu[3].toInt() and 0xff
        val data = if (apdu.size > 5) apdu.copyOfRange(5, 5 + (apdu[4].toInt() and 0xff)) else ByteArray(0)
        return when (ins) {
            0xA4 -> select(data)
            0xA8 -> gpo(data)
            0xB2 -> when {
                p2 != 0x0C -> sw(0x6A82)
                p1 == 1 -> ok(record1)
                p1 == 2 -> ok(record2)
                else -> sw(0x6A83)
            }
            0x88 -> internalAuthenticate(data)
            0xAE -> generateAc(p1, data)
            else -> sw(0x6D00)
        }
    }

    private fun select(name: ByteArray): ByteArray = when {
        name.contentEquals("2PAY.SYS.DDF01".toByteArray()) -> ok(
            Tlv.encode(0x6F, Tlv.encode(0x84, name), Tlv.encode(0xA5, Tlv.encode(0xBF0C,
                Tlv.encode(0x61, Tlv.encode(0x4F, AID), Tlv.encode(0x50, LABEL), Tlv.encode(0x87, byteArrayOf(1)))
            )))
        )
        name.contentEquals(AID) -> {
            gpoDone = false
            acDone = false
            taps++
            val fci = listOfNotNull(Tlv.encode(0x50, LABEL), if (pdol.isNotEmpty()) Tlv.encode(0x9F38, pdol) else null)
            ok(Tlv.encode(0x6F, Tlv.encode(0x84, AID), Tlv.encode(0xA5, *fci.toTypedArray())))
        }
        else -> sw(0x6A82)
    }

    private fun gpo(data: ByteArray): ByteArray {
        val len = dolLength(pdol)
        if (data.size != 2 + len || data[0] != 0x83.toByte() || (data[1].toInt() and 0xff) != len) return sw(0x6700)
        gpoDone = true
        acDone = false
        if (profile != Profile.VISA_FDDA) return ok(Tlv.encode(0x77, Tlv.encode(0x82, aip), Tlv.encode(0x94, AFL)))

        // Visa-style: the card signs right here (fDDA version 01).
        atc++
        val v = dolValues(pdol, data.copyOfRange(2, data.size))
        val cardData = byteArrayOf(0x01) + sha1(v.getValue(0x9F37), atcBytes()).copyOf(4) + "8000".unhex() // 9F69: version, card UN, CTQ
        val signed = v.getValue(0x9F37) + v.getValue(0x9F02) + v.getValue(0x5F2A) + cardData
        return ok(
            Tlv.encode(
                0x77,
                Tlv.encode(0x82, aip),
                Tlv.encode(0x94, AFL),
                Tlv.encode(0x9F36, atcBytes()),
                Tlv.encode(0x9F27, byteArrayOf(0x80.toByte())),
                Tlv.encode(0x9F26, sha1(signed).copyOf(8)),
                Tlv.encode(0x9F10, IAD),
                Tlv.encode(0x9F4B, sdad(iccDynamic(), signed)),
                Tlv.encode(0x9F69, cardData),
                Tlv.encode(0x9F6C, "0000".unhex()),
            ),
        )
    }

    private fun internalAuthenticate(data: ByteArray): ByteArray {
        if (profile != Profile.DDA) return sw(0x6D00)
        if (!gpoDone) return sw(0x6985)
        if (data.size != 4) return sw(0x6700)
        atc++
        return ok(Tlv.encode(0x80, sdad(byteArrayOf(2) + atcBytes(), data)))
    }

    private fun generateAc(p1: Int, data: ByteArray): ByteArray {
        if (profile != Profile.MASTERCARD_CDA) return sw(0x6D00)
        if (!gpoDone || acDone) return sw(0x6985)
        if (data.size != dolLength(CDOL1)) return sw(0x6700)
        acDone = true
        atc++
        val v = dolValues(CDOL1, data)
        val un = v.getValue(0x9F37)
        // Declines a zero amount; otherwise gives the cryptogram asked for (TC or ARQC).
        val cid = if (v.getValue(0x9F02).all { it == 0.toByte() }) 0x00 else if (p1 and 0xC0 == 0x40) 0x40 else 0x80
        val ac = sha1(data, atcBytes()).copyOf(8)
        val head = arrayOf(Tlv.encode(0x9F27, byteArrayOf(cid.toByte())), Tlv.encode(0x9F36, atcBytes()))
        if (cid == 0x00 || p1 and 0x10 == 0) {
            return ok(Tlv.encode(0x77, *head, Tlv.encode(0x9F26, ac), Tlv.encode(0x9F10, IAD)))
        }
        // CDA: ICC dynamic number, CID, AC and a transaction data hash, signed over the UN only.
        val dynamic = byteArrayOf(8) + iccDynamic().copyOfRange(1, 9) + byteArrayOf(cid.toByte()) + ac + sha1(data)
        return ok(Tlv.encode(0x77, *head, Tlv.encode(0x9F4B, sdad(dynamic, un)), Tlv.encode(0x9F10, IAD)))
    }

    private fun atcBytes() = byteArrayOf((atc ushr 8).toByte(), atc.toByte())

    /** ICC dynamic data: an 8-byte dynamic number with its length. */
    private fun iccDynamic() = byteArrayOf(8) + sha1(atcBytes(), byteArrayOf(0x11)).copyOf(8)

    private fun sdad(iccDyn: ByteArray, terminalData: ByteArray): ByteArray {
        val n = icc.modulus.size
        val body = byteArrayOf(0x05, 0x01, iccDyn.size.toByte()) + iccDyn
        val padded = body + ByteArray(n - 22 - body.size) { 0xBB.toByte() }
        val rec = byteArrayOf(0x6A) + padded + sha1(padded, terminalData) + byteArrayOf(0xBC.toByte())
        return icc.sign(rec)
    }

    private fun dolLength(dol: ByteArray) = Dol.parse(dol).sumOf { it.len }

    private fun dolValues(dol: ByteArray, data: ByteArray): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        var pos = 0
        for (e in Dol.parse(dol)) {
            out[e.tag] = data.copyOfRange(pos, pos + e.len)
            pos += e.len
        }
        return out
    }

    private fun ok(b: ByteArray) = b + byteArrayOf(0x90.toByte(), 0x00)
    private fun sw(code: Int) = byteArrayOf((code ushr 8).toByte(), code.toByte())

    companion object {
        const val RID = "F054415056"
        const val CA_INDEX = 0x01
        val AID = "F0544150560001".unhex()
        val LABEL = "TAPVAULT TEST".toByteArray()
        val PAN = "5999990000001234".unhex()
        val ISSUER_ID = "599999FF".unhex()
        val EXPIRY = "1230".unhex()
        val AFL = "08010201".unhex() // SFI 1, records 1-2, record 1 used for offline auth
        val IAD = "06010A03A00000".unhex()

        /** A typical Mastercard CDOL1. */
        val CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F37049F35019F45029F4C089F34039F21039F7C14".unhex()

        /** The test CA; add it to a [CapkStore] in tests only. */
        val TEST_CA = CapkStore.Capk(RID, CA_INDEX, BigInteger.valueOf(3), SimKeys.CA_N.unhex())
    }
}
