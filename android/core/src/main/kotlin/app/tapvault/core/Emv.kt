package app.tapvault.core

import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime

/** Sends one command APDU and returns the response including SW1 SW2. */
fun interface CardTransceiver {
    fun transceive(apdu: ByteArray): ByteArray
}

open class EmvException(message: String) : Exception(message)

/** The card answered [command] with an error status word. */
class StatusWordException(val command: String, val sw: Int) :
    EmvException("card answered $command with %04X (%s)".format(sw, swMeaning(sw)))

fun swMeaning(sw: Int): String = when (sw) {
    0x6283 -> "application blocked"
    0x6700 -> "wrong length"
    0x6981 -> "command incompatible"
    0x6984 -> "data invalid"
    0x6985 -> "conditions of use not satisfied"
    0x6986 -> "command not allowed"
    0x6A81 -> "function not supported"
    0x6A82 -> "not found"
    0x6A83 -> "record not found"
    0x6A86 -> "wrong P1/P2"
    0x6A88 -> "data not found"
    0x6D00 -> "command not supported"
    0x6E00 -> "class not supported"
    0x6F00 -> "card error"
    else -> if (sw ushr 8 == 0x63) "warning" else "error"
}

/** EMV CA public keys, from resources/capk.txt plus any extra keys (tests). */
class CapkStore(private val keys: List<Capk>) {
    data class Capk(val rid: String, val index: Int, val exponent: BigInteger, val modulus: ByteArray)

    fun find(rid: String, index: Int) = keys.filter { it.rid.equals(rid, true) && it.index == index }

    fun plus(extra: Capk) = CapkStore(keys + extra)

    companion object {
        fun load(): CapkStore {
            val text = CapkStore::class.java.getResource("/capk.txt")!!.readText()
            return CapkStore(text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
                val (rid, idx, exp, mod) = it.trim().split(Regex("\\s+"))
                Capk(rid, idx.toInt(16), BigInteger(exp, 16), mod.unhex())
            })
        }
    }
}

object Rsa {
    /** `sig^e mod n` as a big-endian string of the modulus length; rejects sig >= n. */
    fun recover(modulus: ByteArray, exponent: BigInteger, sig: ByteArray): ByteArray {
        if (sig.size != modulus.size) throw EmvException("signature length ${sig.size} != modulus ${modulus.size}")
        val n = modulus.toPositiveBigInteger()
        val s = sig.toPositiveBigInteger()
        if (s >= n) throw EmvException("signature not reduced")
        return s.modPow(exponent, n).toFixed(modulus.size)
    }

    /** Port of tapvault_core::emv::verify_sdad. */
    fun verifySdad(modulus: ByteArray, exponent: Int, sdad: ByteArray, terminalData: ByteArray): Boolean {
        val rec = try { recover(modulus, BigInteger.valueOf(exponent.toLong()), sdad) } catch (e: EmvException) { return false }
        val n = modulus.size
        if (n < 26 || rec[0] != 0x6A.toByte() || rec[n - 1] != 0xBC.toByte() || rec[1] != 0x05.toByte() || rec[2] != 0x01.toByte()) return false
        val ldd = rec[3].toInt() and 0xff
        if (ldd + 25 > n) return false
        val hashStart = n - 21
        if ((4 + ldd until hashStart).any { rec[it] != 0xBB.toByte() }) return false
        return sha1(rec.copyOfRange(1, hashStart), terminalData).contentEquals(rec.copyOfRange(hashStart, n - 1))
    }
}

/**
 * The three ways a contactless card can be made to RSA-sign an Unpredictable
 * Number (UN) the phone chooses. All three produce the same kind of signature
 * (an SDAD whose SHA-1 covers the terminal data holding the UN), so the
 * contract checks them all the same way.
 */
enum class SignMethod(val label: String) {
    /** INTERNAL AUTHENTICATE, classic DDA. Many contact cards; fewer cards over NFC. */
    DDA("INTERNAL AUTHENTICATE (DDA)"),

    /** Visa payWave fDDA: the card signs inside its GET PROCESSING OPTIONS answer. */
    FDDA("signature in GET PROCESSING OPTIONS (fDDA)"),

    /** CDA: the card signs along with its GENERATE AC cryptogram (Mastercard and others). */
    CDA("signature in GENERATE AC (CDA)"),
}

/**
 * How one card signed at enrolment: the application, the method, the amount
 * it was shown, and a method-specific variant (fDDA terminal-data form, or the
 * cryptogram requested for CDA). Later taps reuse it so every signature has the
 * terminal-data layout the contract stored. Kept on the phone only.
 */
data class CardRecipe(val aid: String, val method: SignMethod, val amount: Long = 0, val variant: Int = 0) {
    fun encode() = "$aid:${method.name}:$amount:$variant"

    companion object {
        /** fDDA terminal data: UN, amount, currency and the card's 9F69 data (fDDA version 01). */
        const val FDDA_FULL = 0
        /** fDDA terminal data: UN, amount, currency. */
        const val FDDA_AMOUNT = 1
        /** fDDA terminal data: UN only (fDDA version 00). */
        const val FDDA_UN = 2

        /** CDA variants: which cryptogram GENERATE AC asks for. */
        const val CDA_ARQC = 0
        const val CDA_TC = 1

        fun decode(s: String?): CardRecipe? = s?.split(":")?.takeIf { it.size == 4 }?.let {
            runCatching { CardRecipe(it[0], SignMethod.valueOf(it[1]), it[2].toLong(), it[3].toInt()) }.getOrNull()
        }
    }
}

/** Everything we learned from a card, minus the PAN. Safe to show and share. */
data class CardReport(
    val network: String,
    val aid: String,
    val label: String,
    val aip: String,
    val sdaSupported: Boolean,
    val ddaSupported: Boolean,
    val cdaSupported: Boolean,
    val caIndex: String?,
    val issuerKeyBits: Int?,
    val iccKeyBits: Int?,
    val iccExponent: Int?,
    val ddol: String?,
    val panLast4: String?,
    val notes: List<String>,
    /** The [SignMethod] label the card signed with, if it signed at all. */
    val signsWith: String? = null,
    /** Every signing attempt and its result. */
    val attempts: List<String> = emptyList(),
    /** Every command and the card's answer, with card-identifying values left out. */
    val trace: List<String> = emptyList(),
) {
    val canSign get() = signsWith != null

    fun text() = buildString {
        appendLine("network: $network ($aid, \"$label\")")
        appendLine("AIP: $aip  SDA=$sdaSupported DDA=$ddaSupported CDA=$cdaSupported")
        appendLine("CA index: ${caIndex ?: "-"}  issuer key: ${issuerKeyBits ?: "-"} bit")
        appendLine("card key: ${iccKeyBits ?: "-"} bit, e=${iccExponent ?: "-"}  DDOL: ${ddol ?: "-"}")
        appendLine("card: ****${panLast4 ?: "????"}")
        appendLine("signs with: ${signsWith ?: "nothing that worked"}")
        notes.forEach { appendLine("- $it") }
        if (attempts.isNotEmpty()) {
            appendLine("attempts:")
            attempts.forEach { appendLine("  $it") }
        }
    }

    /** [text] plus the command trace: what to send a developer when a card misbehaves. */
    fun diagnostics() = buildString {
        append(text())
        if (trace.isNotEmpty()) {
            appendLine("trace (card number, name, track data and certificates left out):")
            trace.forEach { appendLine("  $it") }
        }
    }
}

/**
 * One contactless session with an EMV card: select the payment application,
 * GET PROCESSING OPTIONS, read the records, recover the card's public key
 * through its certificate chain, and get the card to sign a challenge with
 * whichever [SignMethod] it supports.
 *
 * The PAN is read (it is inside the certificates) and checked, but never
 * leaves this object: [CardReport] only carries the last four digits, and the
 * trace never contains the PAN, track data, cardholder name or certificates.
 */
class EmvSession(private val card: CardTransceiver, private val capks: CapkStore) {
    private val notes = mutableListOf<String>()
    private val attempts = mutableListOf<String>()
    private val trace = mutableListOf<String>()
    private var apps: List<Pair<ByteArray, String>>? = null
    private var aid = ByteArray(0)
    private var label = ""
    private var pdol: List<DolEntry> = emptyList()
    private var aip = ByteArray(2)
    private var afl = ByteArray(0)
    private var gpoResponse: List<Tlv> = emptyList()
    /** The PDOL values sent in the last GET PROCESSING OPTIONS, by tag. */
    private val gpoSent = mutableMapOf<Int, ByteArray>()
    private val records = mutableListOf<Tlv>()
    private var recordsRead = false
    private var staticData = ByteArray(0)
    private var issuerBits: Int? = null
    private var iccKey: Pair<ByteArray, Int>? = null
    private var signsWith: String? = null

    val rid get() = aid.copyOfRange(0, minOf(5, aid.size)).hex().uppercase()
    val aidHex get() = aid.hex().uppercase()
    private val ddaFlag get() = aip[0].toInt() and 0x20 != 0
    private val cdaFlag get() = aip[0].toInt() and 0x01 != 0

    // ---- APDU plumbing ----

    private fun send(apdu: ByteArray, what: String): ByteArray {
        var r = card.transceive(apdu)
        if (r.size < 2) throw EmvException("short response to $what")
        var sw = r.sw()
        if (sw ushr 8 == 0x6C) { // wrong Le: resend with the right one
            r = card.transceive(apdu.copyOf(apdu.size - 1) + byteArrayOf(sw.toByte()))
            if (r.size < 2) throw EmvException("short response to $what")
            sw = r.sw()
        }
        var body = r.copyOf(r.size - 2)
        while (sw ushr 8 == 0x61) { // more data available
            val more = card.transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw.toByte()))
            if (more.size < 2) throw EmvException("short response to GET RESPONSE")
            body += more.copyOf(more.size - 2)
            sw = more.sw()
        }
        trace += "$what -> %04X%s".format(sw, if (body.isEmpty()) "" else " " + describe(body))
        if (sw != 0x9000) throw StatusWordException(what, sw)
        return body
    }

    private fun ByteArray.sw() = ((this[size - 2].toInt() and 0xff) shl 8) or (this[size - 1].toInt() and 0xff)

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?): ByteArray {
        val head = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
        return if (data == null) head + byteArrayOf(0) else head + byteArrayOf(data.size.toByte()) + data + byteArrayOf(0)
    }

    /** A response's TLV structure for the trace: values only for tags that can't identify the card. */
    private fun describe(body: ByteArray): String = try {
        Tlv.parse(body).joinToString(" ") { show(it) }
    } catch (e: IllegalArgumentException) {
        "(${body.size} bytes, not TLV)"
    }

    private fun show(t: Tlv): String {
        val tag = "%X".format(t.tag)
        return when {
            t.constructed -> "$tag[" + t.children.joinToString(" ") { show(it) } + "]"
            t.tag == 0x50 || t.tag == 0x9F12 -> "$tag=\"${t.value.printable()}\""
            t.tag in TRACE_VALUES || t.tag == 0x80 && t.value.size <= 48 -> "$tag=${t.value.hex().uppercase()}"
            else -> "$tag(${t.value.size})"
        }
    }

    // ---- session steps ----

    /** The card's payment applications, best first (from the PPSE), or well-known AIDs if it has no PPSE. */
    fun applications(): List<Pair<ByteArray, String>> {
        apps?.let { return it }
        val found = mutableListOf<Pair<ByteArray, String>>()
        try {
            val fci = Tlv.parse(send(apdu(0x00, 0xA4, 0x04, 0x00, PPSE), "SELECT PPSE"))
            val dir = Tlv.find(fci, 0xBF0C)?.children ?: emptyList()
            dir.filter { it.tag == 0x61 }
                .sortedBy { e -> Tlv.find(e.children, 0x87)?.value?.firstOrNull()?.toInt()?.and(0x0f)?.takeIf { it != 0 } ?: 15 }
                .forEach { e ->
                    Tlv.find(e.children, 0x4F)?.let { found += it.value to (Tlv.find(e.children, 0x50)?.value?.printable() ?: "") }
                }
        } catch (e: EmvException) {
            notes += "no PPSE (${e.message}); trying known applications"
        } catch (e: IllegalArgumentException) {
            notes += "unreadable PPSE (${e.message}); trying known applications"
        }
        if (found.isEmpty()) KNOWN_AIDS.forEach { found += it.unhex() to "" }
        apps = found
        return found
    }

    /** SELECT one application and remember its PDOL. Records stay cached for the same application. */
    fun select(want: ByteArray) {
        val fci = Tlv.parse(send(apdu(0x00, 0xA4, 0x04, 0x00, want), "SELECT ${want.hex().uppercase()}"))
        val selected = Tlv.find(fci, 0x84)?.value?.takeIf { it.size in 5..16 } ?: want
        if (!selected.contentEquals(aid)) {
            records.clear(); recordsRead = false; staticData = ByteArray(0); issuerBits = null; iccKey = null
        }
        aid = selected
        label = apps?.firstOrNull { it.first.contentEquals(want) }?.second?.takeIf { it.isNotEmpty() }
            ?: Tlv.find(fci, 0x50)?.value?.printable() ?: ""
        pdol = Tlv.find(fci, 0x9F38)?.value?.let { Dol.parse(it) } ?: emptyList()
        gpoResponse = emptyList()
        gpoSent.clear()
    }

    /** GET PROCESSING OPTIONS with [un] as the Unpredictable Number and [amount] (minor units). */
    fun gpo(un: ByteArray, amount: Long) {
        gpoSent.clear()
        val data = pdol.fold(ByteArray(0)) { acc, e -> acc + terminalValue(e, un, amount).also { gpoSent[e.tag] = it } }
        val r = Tlv.parse(send(apdu(0x80, 0xA8, 0x00, 0x00, Tlv.encode(0x83, data)), "GPO"))
        val fmt1 = r.firstOrNull { it.tag == 0x80 }
        if (fmt1 != null) {
            if (fmt1.value.size < 2) throw EmvException("GPO response too short")
            aip = fmt1.value.copyOfRange(0, 2)
            afl = fmt1.value.copyOfRange(2, fmt1.value.size)
        } else {
            aip = Tlv.find(r, 0x82)?.value?.takeIf { it.size == 2 } ?: throw EmvException("GPO response has no AIP")
            afl = Tlv.find(r, 0x94)?.value ?: ByteArray(0)
        }
        gpoResponse = r
    }

    /** Did the last GPO answer carry a signature (Visa fDDA)? */
    fun gpoSigned() = Tlv.find(gpoResponse, 0x9F4B) != null

    /** Read the records named in the AFL (once per application) and collect the static data for offline authentication. */
    fun readRecords() {
        if (recordsRead) return
        records.clear()
        var oda = ByteArray(0)
        for (i in afl.indices step 4) {
            if (i + 3 >= afl.size) break
            val sfi = (afl[i].toInt() and 0xff) ushr 3
            val first = afl[i + 1].toInt() and 0xff
            val last = afl[i + 2].toInt() and 0xff
            val odaCount = afl[i + 3].toInt() and 0xff
            for (rec in first..last) {
                val raw = send(apdu(0x00, 0xB2, rec, (sfi shl 3) or 4, null), "READ RECORD $sfi.$rec")
                val parsed = Tlv.parse(raw)
                records += parsed
                if (rec < first + odaCount) {
                    oda += if (sfi <= 10) (parsed.firstOrNull { it.tag == 0x70 }?.value ?: raw) else raw
                }
            }
        }
        val sdaTags = find(0x9F4A)?.value
        if (sdaTags != null && sdaTags.contentEquals(byteArrayOf(0x82.toByte()))) oda += aip
        staticData = oda
        recordsRead = true
    }

    private fun find(tag: Int) = Tlv.find(records, tag)

    /** Recover and check the issuer and card certificates; returns the card's RSA key. */
    fun recoverCardKey(): Pair<ByteArray, Int> {
        iccKey?.let { return it }
        val caIndex = find(0x8F)?.value?.firstOrNull()?.toInt()?.and(0xff) ?: throw EmvException("card has no CA key index (no offline data authentication)")
        val issuerCert = find(0x90)?.value ?: throw EmvException("no issuer certificate")
        val issuerExp = find(0x9F32)?.value ?: throw EmvException("no issuer exponent")
        val issuerRem = find(0x92)?.value ?: ByteArray(0)
        val pan = find(0x5A)?.value ?: throw EmvException("no PAN record")

        val cas = capks.find(rid, caIndex)
        if (cas.isEmpty()) throw EmvException("no CA key for $rid index %02X".format(caIndex))
        val issuerKey = cas.firstNotNullOfOrNull { ca ->
            runCatching { issuerKeyFrom(ca, issuerCert, issuerRem, issuerExp, pan) }.getOrNull()
        } ?: throw EmvException("issuer certificate did not verify against CA %02X".format(caIndex))
        issuerBits = issuerKey.size * 8

        val iccCert = find(0x9F46)?.value ?: throw EmvException("no card certificate (the card has no key of its own)")
        val iccExp = find(0x9F47)?.value ?: throw EmvException("no card exponent")
        val iccRem = find(0x9F48)?.value ?: ByteArray(0)
        val nI = issuerKey.size
        val r = Rsa.recover(issuerKey, issuerExp.toPositiveBigInteger(), iccCert)
        if (r[0] != 0x6A.toByte() || r[nI - 1] != 0xBC.toByte() || r[1] != 0x04.toByte()) throw EmvException("card certificate format")
        val certPan = r.copyOfRange(2, 12)
        if (!panMatches(certPan, pan)) throw EmvException("card certificate is for a different card number")
        val pkLen = r[19].toInt() and 0xff
        val field = r.copyOfRange(21, nI - 21)
        val modulus = if (pkLen <= field.size) field.copyOf(pkLen) else field + iccRem
        if (modulus.size != pkLen) throw EmvException("card key length mismatch")
        val hash = sha1(r.copyOfRange(1, nI - 21), iccRem, iccExp, staticData)
        if (!hash.contentEquals(r.copyOfRange(nI - 21, nI - 1))) {
            throw EmvException("card certificate hash mismatch (static data)")
        }
        val exponent = iccExp.toPositiveBigInteger()
        if (exponent.bitLength() > 31) throw EmvException("card key exponent too large")
        return (modulus to exponent.toInt()).also { iccKey = it }
    }

    private fun issuerKeyFrom(ca: CapkStore.Capk, cert: ByteArray, rem: ByteArray, exp: ByteArray, pan: ByteArray): ByteArray {
        val n = ca.modulus.size
        val r = Rsa.recover(ca.modulus, ca.exponent, cert)
        check(r[0] == 0x6A.toByte() && r[n - 1] == 0xBC.toByte() && r[1] == 0x02.toByte() && r[11] == 0x01.toByte())
        check(panMatches(r.copyOfRange(2, 6), pan)) { "issuer id doesn't match the card number" }
        val pkLen = r[13].toInt() and 0xff
        val field = r.copyOfRange(15, n - 21)
        val modulus = if (pkLen <= field.size) field.copyOf(pkLen) else field + rem
        check(modulus.size == pkLen)
        check(sha1(r.copyOfRange(1, n - 21), rem, exp).contentEquals(r.copyOfRange(n - 21, n - 1)))
        val mm = (r[6].toInt() and 0xff).toString(16).padStart(2, '0')
        val yy = (r[7].toInt() and 0xff).toString(16).padStart(2, '0')
        runCatching {
            val expiry = LocalDate.of(2000 + yy.toInt(), mm.toInt(), 1).plusMonths(1)
            if (expiry.isBefore(LocalDate.now())) notes += "issuer certificate expired $mm/$yy"
        }
        return modulus
    }

    /** Compare BCD digits, treating 'F' nibbles as padding. */
    private fun panMatches(certField: ByteArray, pan: ByteArray): Boolean {
        val a = certField.hex().lowercase().trimEnd('f')
        val b = pan.hex().lowercase().trimEnd('f')
        return a.isNotEmpty() && b.startsWith(a)
    }

    // ---- signing ----

    /** A signature and where the challenge's first bytes sit in the terminal data it covers. */
    class Signed(val recipe: CardRecipe, val sig: CardSig, val unOffset: Int, val unLen: Int)

    /**
     * One signing attempt from SELECT onwards, with the first bytes of
     * [challenge] as the Unpredictable Number (also in the GPO, like a real
     * terminal that uses one UN per transaction). fDDA can return several
     * terminal-data candidates; the caller keeps the one that verifies.
     */
    fun attempt(r: CardRecipe, challenge: ByteArray): List<Signed> {
        select(r.aid.unhex())
        gpo(challenge, r.amount)
        return when (r.method) {
            SignMethod.FDDA -> fdda(r)
            SignMethod.DDA -> listOf(dda(r, challenge))
            SignMethod.CDA -> listOf(cda(r, challenge))
        }
    }

    private fun fdda(r: CardRecipe): List<Signed> {
        val sdad = Tlv.find(gpoResponse, 0x9F4B)?.value
            ?: throw EmvException("no signature in the GPO answer (${cryptogram(gpoResponse, gpo = true)})")
        val un = gpoSent[0x9F37] ?: throw EmvException("the card's PDOL has no Unpredictable Number")
        val amount = gpoSent[0x9F02]
        val currency = gpoSent[0x5F2A]
        val cardData = Tlv.find(gpoResponse, 0x9F69)?.value ?: if (recordsRead) find(0x9F69)?.value else null
        val forms = buildList {
            if (amount != null && currency != null && cardData != null) add(CardRecipe.FDDA_FULL to un + amount + currency + cardData)
            if (amount != null && currency != null) add(CardRecipe.FDDA_AMOUNT to un + amount + currency)
            add(CardRecipe.FDDA_UN to un)
        }
        return forms.map { (form, td) -> Signed(r.copy(variant = form), CardSig(sdad, td), 0, un.size) }
    }

    private fun dda(r: CardRecipe, challenge: ByteArray): Signed {
        readRecords() // the DDOL lives in the records
        val ddol = ddol()
        var off = -1
        var len = 0
        var pos = 0
        for (e in ddol) {
            if (e.tag == 0x9F37 && off < 0) { off = pos; len = e.len }
            pos += e.len
        }
        if (off < 0) throw EmvException("the card's DDOL has no Unpredictable Number")
        val td = ddol.fold(ByteArray(0)) { acc, e -> acc + terminalValue(e, challenge, r.amount) }
        val resp = Tlv.parse(send(apdu(0x00, 0x88, 0x00, 0x00, td), "INTERNAL AUTHENTICATE"))
        val sdad = resp.firstOrNull { it.tag == 0x80 }?.value
            ?: Tlv.find(resp, 0x9F4B)?.value
            ?: throw EmvException("INTERNAL AUTHENTICATE returned no signature")
        return Signed(r, CardSig(sdad, td), off, len)
    }

    private fun cda(r: CardRecipe, challenge: ByteArray): Signed {
        readRecords() // CDOL1 lives in the records
        val cdol = find(0x8C)?.value?.let { Dol.parse(it) } ?: throw EmvException("no CDOL1, so no GENERATE AC")
        var un: ByteArray? = null
        val data = cdol.fold(ByteArray(0)) { acc, e ->
            acc + terminalValue(e, challenge, r.amount).also { if (e.tag == 0x9F37 && un == null) un = it }
        }
        val u = un ?: throw EmvException("CDOL1 has no Unpredictable Number")
        val tc = r.variant == CardRecipe.CDA_TC
        // P1: cryptogram type (TC 0x40 / ARQC 0x80) + 0x10 "CDA signature requested".
        val resp = Tlv.parse(send(apdu(0x80, 0xAE, if (tc) 0x50 else 0x90, 0x00, data), if (tc) "GENERATE AC (TC, CDA)" else "GENERATE AC (ARQC, CDA)"))
        val sdad = Tlv.find(resp, 0x9F4B)?.value
            ?: throw EmvException("GENERATE AC answered without a signature (${cryptogram(resp, gpo = false)})")
        // CDA hashes only the UN after the recovered data (EMV Book 2, 6.6.2).
        return Signed(r, CardSig(sdad, u), 0, u.size)
    }

    private fun ddol() = find(0x9F49)?.value?.let { Dol.parse(it) } ?: DEFAULT_DDOL

    private fun cryptogram(resp: List<Tlv>, gpo: Boolean): String {
        val cid = Tlv.find(resp, 0x9F27)?.value?.firstOrNull()?.toInt()
            ?: (if (gpo) null else resp.firstOrNull { it.tag == 0x80 }?.value?.firstOrNull()?.toInt())
            ?: return "no cryptogram"
        return when (cid and 0xC0) {
            0x00 -> "declined: AAC"
            0x40 -> "approved offline: TC"
            0x80 -> "online: ARQC"
            else -> "CID %02X".format(cid and 0xff)
        }
    }

    /** The signing methods worth trying on the selected application, most likely first. */
    fun plans(gpoSigned: Boolean): List<CardRecipe> {
        val a = aidHex
        val cdol = find(0x8C) != null
        val pdolUn = pdol.any { it.tag == 0x9F37 }
        val list = mutableListOf<CardRecipe>()
        if (gpoSigned) list += CardRecipe(a, SignMethod.FDDA)
        if (ddaFlag) list += CardRecipe(a, SignMethod.DDA)
        if (cdol && cdaFlag) {
            list += CardRecipe(a, SignMethod.CDA, 0, CardRecipe.CDA_ARQC)
            list += CardRecipe(a, SignMethod.CDA, 100, CardRecipe.CDA_ARQC)
            list += CardRecipe(a, SignMethod.CDA, 0, CardRecipe.CDA_TC)
        }
        if (!gpoSigned && pdolUn) list += CardRecipe(a, SignMethod.FDDA, 100)
        if (!ddaFlag) list += CardRecipe(a, SignMethod.DDA)
        if (cdol && !cdaFlag) list += CardRecipe(a, SignMethod.CDA, 0, CardRecipe.CDA_ARQC)
        return list
    }

    fun tried(r: CardRecipe, result: String) {
        attempts += "${r.method} ${r.aid}" + (if (r.amount != 0L) " amount ${r.amount}" else "") +
            (if (r.method == SignMethod.CDA && r.variant == CardRecipe.CDA_TC) " TC" else "") + ": $result"
    }

    fun succeeded(r: CardRecipe) {
        tried(r, "signature OK")
        signsWith = r.method.label
        notes += "signs with ${r.method.label}: this card can be a Tapvault signer"
    }

    fun report(key: Pair<ByteArray, Int>?, verdict: String? = null): CardReport {
        val a0 = aip[0].toInt()
        val pan = find(0x5A)?.value?.hex()?.trimEnd('f', 'F')
        return CardReport(
            network = NETWORKS[rid] ?: "unknown",
            aid = aidHex,
            label = label,
            aip = aip.hex().uppercase(),
            sdaSupported = a0 and 0x40 != 0,
            ddaSupported = a0 and 0x20 != 0,
            cdaSupported = a0 and 0x01 != 0,
            caIndex = find(0x8F)?.value?.hex()?.uppercase(),
            issuerKeyBits = issuerBits,
            iccKeyBits = key?.first?.size?.times(8),
            iccExponent = key?.second,
            ddol = find(0x9F49)?.value?.hex()?.uppercase() ?: "default 9F3704",
            panLast4 = pan?.takeLast(4),
            notes = notes.toList() + listOfNotNull(verdict?.let { "can't be a Tapvault signer: $it" }),
            signsWith = signsWith,
            attempts = attempts.toList(),
            trace = trace.toList(),
        )
    }

    fun note(s: String) { notes += s }

    companion object {
        val PPSE = "2PAY.SYS.DDF01".toByteArray()
        val KNOWN_AIDS = listOf("A0000000031010", "A0000000041010", "A0000005241010", "A0000000043060", "A0000000032010")
        val NETWORKS = mapOf("A000000003" to "Visa", "A000000004" to "Mastercard", "A000000524" to "RuPay")
        val DEFAULT_DDOL = listOf(DolEntry(0x9F37, 4))

        /**
         * Terminal Transaction Qualifiers: qVSDC, contact chip, online PIN and
         * signature supported, offline data authentication for online
         * authorisations supported (so online-only Visa cards still sign), and
         * consumer-device CVM.
         */
        val TTQ = "37004000".unhex()

        /** Tags whose values can go in a trace: none of them identify the card or its holder. */
        private val TRACE_VALUES = setOf(
            0x4F, 0x82, 0x84, 0x87, 0x8C, 0x8D, 0x8E, 0x8F, 0x94, 0x9F07, 0x9F08, 0x9F0D, 0x9F0E, 0x9F0F,
            0x9F10, 0x9F26, 0x9F27, 0x9F32, 0x9F36, 0x9F38, 0x9F42, 0x9F44, 0x9F47, 0x9F49, 0x9F4A,
            0x9F66, 0x9F69, 0x9F6C, 0x9F6E, 0x5F28, 0x5F2D,
        )

        fun randomUn(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        private fun bcd(value: Long, bytes: Int) = "%0${bytes * 2}d".format(value).unhex()

        /** Terminal values for DOL entries; the UN comes from [un], the amount (minor units) from [amount]. */
        fun terminalValue(e: DolEntry, un: ByteArray, amount: Long = 0): ByteArray {
            val v: ByteArray = when (e.tag) {
                0x9F37 -> un.copyOf(e.len)
                0x9F66 -> TTQ
                0x9F02 -> bcd(amount, 6)
                0x9F03 -> ByteArray(6)
                0x9F1A, 0x5F2A -> "0356".unhex() // India
                0x5F36 -> byteArrayOf(0x02)
                0x9A -> LocalDate.now().let { "%02d%02d%02d".format(it.year % 100, it.monthValue, it.dayOfMonth).unhex() }
                0x9F21 -> LocalTime.now().let { "%02d%02d%02d".format(it.hour, it.minute, it.second).unhex() }
                0x9C -> byteArrayOf(0)
                0x9F35 -> byteArrayOf(0x22) // attended, online with offline capability
                0x9F33 -> "E068C8".unhex() // terminal capabilities: chip, no-CVM/signature/online PIN, SDA DDA CDA
                0x9F34 -> "1F0302".unhex() // CVM results: no CVM required, successful
                else -> ByteArray(e.len)
            }
            return if (v.size >= e.len) v.copyOf(e.len) else ByteArray(e.len - v.size) + v
        }
    }
}

private fun ByteArray.printable() = decodeToString().filter { it in ' '..'~' }

/** High-level card operations used by the app. */
class CardService(private val capks: CapkStore) {
    class Enrolment(val key: SignerKey.EmvCard, val proof: CardSig, val report: CardReport, val recipe: CardRecipe)

    /**
     * One tap: read the card's key through its certificate chain, find a way
     * to make it sign, and sign `challengeFor(key)` (e.g. the enrolment
     * challenge) in the same session. Tries up to three payment applications.
     */
    fun enrol(card: CardTransceiver, challengeFor: (SignerKey.EmvCard) -> ByteArray): Enrolment {
        val s = EmvSession(card, capks)
        var failure: CardCheckFailed? = null
        for ((aid, _) in s.applications().take(3)) {
            try {
                return enrolWith(s, aid, challengeFor)
            } catch (e: CardCheckFailed) {
                failure = e
            } catch (e: EmvException) {
                s.note("${aid.hex().uppercase()}: ${e.message}")
                failure = CardCheckFailed(e.message ?: "card error", s.report(null, e.message))
            } catch (e: IllegalArgumentException) {
                s.note("${aid.hex().uppercase()}: unreadable answer (${e.message})")
                failure = CardCheckFailed("the card's answer couldn't be read", s.report(null, "unreadable answer"))
            }
        }
        throw failure ?: CardCheckFailed("the card has no payment application", s.report(null, "no payment application"))
    }

    private fun enrolWith(s: EmvSession, aid: ByteArray, challengeFor: (SignerKey.EmvCard) -> ByteArray): Enrolment {
        s.select(aid)
        s.gpo(EmvSession.randomUn(), 0)
        val gpoSigned = s.gpoSigned()
        s.readRecords()
        val icc = try {
            s.recoverCardKey()
        } catch (e: EmvException) {
            throw CardCheckFailed(e.message ?: "card check failed", s.report(null, e.message))
        } catch (e: IllegalStateException) {
            throw CardCheckFailed("certificate check failed", s.report(null, "certificate check failed"))
        }
        val (modulus, exponent) = icc
        val probe = SignerKey.EmvCard(modulus, exponent, 0, 4, 4)
        val challenge = challengeFor(probe)
        for (plan in s.plans(gpoSigned)) {
            val results = try {
                s.attempt(plan, challenge)
            } catch (e: EmvException) {
                s.tried(plan, e.message ?: "failed"); continue
            } catch (e: IllegalArgumentException) {
                s.tried(plan, "unreadable answer (${e.message})"); continue
            }
            val good = results.firstOrNull {
                it.unLen in 4..8 && it.sig.terminalData.size <= 64 && Rsa.verifySdad(modulus, exponent, it.sig.sdad, it.sig.terminalData)
            }
            if (good == null) {
                s.tried(plan, "signature didn't verify with the card's certified key"); continue
            }
            val key = SignerKey.EmvCard(modulus, exponent, good.unOffset, good.unLen, good.sig.terminalData.size)
            // The challenge was made for the probe key; the key hash (all it may depend on) ignores the layout.
            check(key.keyHash().contentEquals(probe.keyHash()))
            s.succeeded(good.recipe)
            return Enrolment(key, good.sig, s.report(icc), good.recipe)
        }
        throw CardCheckFailed("this card won't sign a challenge over NFC", s.report(icc, "it wouldn't sign a challenge with any method"))
    }

    /**
     * One tap: get the enrolled card to sign [challenge] with the terminal-data
     * layout stored on-chain. Uses [recipe] (how it signed at enrolment) first,
     * then any other method that gives the same layout.
     */
    fun sign(card: CardTransceiver, key: SignerKey.EmvCard, challenge: ByteArray, recipe: CardRecipe? = null): CardSig {
        val s = EmvSession(card, capks)
        val aid = recipe?.aid ?: s.applications().first().first.hex().uppercase()
        val plans = (listOfNotNull(recipe) + fallbacks(key, aid))
            .distinctBy { Triple(it.method, it.amount, if (it.method == SignMethod.CDA) it.variant else 0) }
        var signed = false
        var lastProblem = "no answer"
        for (plan in plans) {
            val results = try {
                s.attempt(plan, challenge)
            } catch (e: EmvException) {
                lastProblem = e.message ?: "failed"; continue
            } catch (e: IllegalArgumentException) {
                lastProblem = "unreadable answer"; continue
            }
            for (r in results) {
                signed = true
                val td = r.sig.terminalData
                if (td.size != key.terminalDataLen) continue
                if (!td.copyOfRange(key.unOffset, key.unOffset + key.unLen).contentEquals(challenge.copyOf(key.unLen))) continue
                if (Rsa.verifySdad(key.modulus, key.exponent, r.sig.sdad, td)) return r.sig
            }
        }
        throw EmvException(if (signed) "this isn't the card enrolled in the wallet" else "the card didn't sign ($lastProblem)")
    }

    /** Methods that can produce the enrolled layout: UN-only data, or fDDA's longer terminal data. */
    private fun fallbacks(key: SignerKey.EmvCard, aid: String): List<CardRecipe> =
        if (key.terminalDataLen == 4) listOf(
            CardRecipe(aid, SignMethod.DDA),
            CardRecipe(aid, SignMethod.CDA, 0, CardRecipe.CDA_ARQC),
            CardRecipe(aid, SignMethod.CDA, 100, CardRecipe.CDA_ARQC),
            CardRecipe(aid, SignMethod.FDDA),
        ) else listOf(
            CardRecipe(aid, SignMethod.FDDA),
            CardRecipe(aid, SignMethod.FDDA, 100),
            CardRecipe(aid, SignMethod.DDA),
        )

    /** Read-only check: what kind of card is this, and can it be a signer? */
    fun inspect(card: CardTransceiver): CardReport = try {
        enrol(card) { EmvSession.randomUn() }.report
    } catch (e: CardCheckFailed) {
        e.report
    }
}

class CardCheckFailed(message: String, val report: CardReport) : Exception(message)
