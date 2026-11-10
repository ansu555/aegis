package app.tapvault.core

import java.math.BigInteger

/** RSA keys with public private halves, for tests and the demo card. */
class TestRsaKey(val modulus: ByteArray, val exponent: Int, private val d: BigInteger) {
    /** Raw RSA private operation: `m^d mod n`. */
    fun sign(m: ByteArray): ByteArray =
        m.toPositiveBigInteger().modPow(d, modulus.toPositiveBigInteger()).toFixed(modulus.size)

    companion object {
        fun of(n: String, d: String, e: Int = 3) = TestRsaKey(n.unhex(), e, d.toPositiveBigIntegerHex())
        private fun String.toPositiveBigIntegerHex() = BigInteger(this, 16)
    }
}

object TestCards {
    /** The card key shared with the Rust TestCard (1024-bit, e = 3). */
    val SMALL = TestRsaKey.of(SimKeys.ICC_N, SimKeys.ICC_D)
}

/**
 * A simulated contactless EMV card that answers real APDUs: PPSE and
 * application SELECT, GET PROCESSING OPTIONS, READ RECORD (with a demo CA ->
 * issuer -> card certificate chain) and INTERNAL AUTHENTICATE (DDA).
 *
 * It lets the whole card path, including certificate recovery, run without
 * hardware: in tests, and as the app's "demo card". Its keys are public, so
 * never use it for real funds.
 */
class SimCard : CardTransceiver {
    private val ca = TestRsaKey.of(SimKeys.CA_N, SimKeys.CA_D)
    private val issuer = TestRsaKey.of(SimKeys.ISSUER_N, SimKeys.ISSUER_D)
    private val icc = TestCards.SMALL
    private var gpoDone = false
    private var atc = 0
    var taps = 0
        private set

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
            Tlv.encode(0x5A, PAN),
            Tlv.encode(0x5F24, "301231".unhex()),
            Tlv.encode(0x9F49, "9F3704".unhex()),
            Tlv.encode(0x8F, byteArrayOf(CA_INDEX.toByte())),
            Tlv.encode(0x90, issuerCert.first),
            Tlv.encode(0x92, issuerCert.second),
            Tlv.encode(0x9F32, byteArrayOf(0x03)),
            Tlv.encode(0x9F4A, byteArrayOf(0x82.toByte())),
        )
        // Static data to authenticate: record 1's contents, then the AIP (9F4A = 82).
        val staticData = Tlv.parse(record1)[0].value + AIP
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
            0xA4 -> when {
                data.contentEquals("2PAY.SYS.DDF01".toByteArray()) -> ok(
                    Tlv.encode(0x6F, Tlv.encode(0x84, data), Tlv.encode(0xA5, Tlv.encode(0xBF0C,
                        Tlv.encode(0x61, Tlv.encode(0x4F, AID), Tlv.encode(0x50, LABEL), Tlv.encode(0x87, byteArrayOf(1)))
                    )))
                )
                data.contentEquals(AID) -> {
                    gpoDone = false
                    taps++
                    ok(Tlv.encode(0x6F, Tlv.encode(0x84, AID), Tlv.encode(0xA5, Tlv.encode(0x50, LABEL), Tlv.encode(0x9F38, "9F66049F3704".unhex()))))
                }
                else -> sw(0x6A82)
            }
            0xA8 -> {
                if (data.size != 2 + 8) return sw(0x6700)
                gpoDone = true
                ok(Tlv.encode(0x77, Tlv.encode(0x82, AIP), Tlv.encode(0x94, AFL)))
            }
            0xB2 -> when {
                p2 != 0x0C -> sw(0x6A82)
                p1 == 1 -> ok(record1)
                p1 == 2 -> ok(record2)
                else -> sw(0x6A83)
            }
            0x88 -> {
                if (!gpoDone) return sw(0x6985)
                if (data.size != 4) return sw(0x6700)
                atc++
                val iccDyn = byteArrayOf(2, (atc ushr 8).toByte(), atc.toByte())
                ok(Tlv.encode(0x80, sdad(iccDyn, data)))
            }
            else -> sw(0x6D00)
        }
    }

    private fun sdad(iccDyn: ByteArray, terminalData: ByteArray): ByteArray {
        val n = icc.modulus.size
        val body = byteArrayOf(0x05, 0x01, iccDyn.size.toByte()) + iccDyn
        val padded = body + ByteArray(n - 22 - body.size) { 0xBB.toByte() }
        val rec = byteArrayOf(0x6A) + padded + sha1(padded, terminalData) + byteArrayOf(0xBC.toByte())
        return icc.sign(rec)
    }

    private fun ok(b: ByteArray) = b + byteArrayOf(0x90.toByte(), 0x00)
    private fun sw(code: Int) = byteArrayOf((code ushr 8).toByte(), code.toByte())

    companion object {
        const val RID = "F054415056"
        const val CA_INDEX = 0x01
        val AID = "F0544150560001".unhex()
        val LABEL = "TAPVAULT DEMO".toByteArray()
        val PAN = "5999990000001234".unhex()
        val ISSUER_ID = "599999FF".unhex()
        val EXPIRY = "1230".unhex()
        val AIP = "2000".unhex() // DDA supported
        val AFL = "08010201".unhex() // SFI 1, records 1-2, record 1 used for offline auth

        /** The demo CA, to add to a [CapkStore] only when the user picks the demo card. */
        val DEMO_CA = CapkStore.Capk(RID, CA_INDEX, BigInteger.valueOf(3), SimKeys.CA_N.unhex())
    }
}
