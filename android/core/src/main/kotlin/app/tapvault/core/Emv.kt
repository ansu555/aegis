package app.tapvault.core

import java.math.BigInteger
import java.time.LocalDate

/** Sends one command APDU and returns the response including SW1 SW2. */
fun interface CardTransceiver {
    fun transceive(apdu: ByteArray): ByteArray
}

class EmvException(message: String) : Exception(message)

/** EMV CA public keys, from resources/capk.txt plus any extra (demo) keys. */
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
) {
    fun text() = buildString {
        appendLine("network: $network ($aid, \"$label\")")
        appendLine("AIP: $aip  SDA=$sdaSupported DDA=$ddaSupported CDA=$cdaSupported")
        appendLine("CA index: ${caIndex ?: "-"}  issuer key: ${issuerKeyBits ?: "-"} bit")
        appendLine("card key: ${iccKeyBits ?: "-"} bit, e=${iccExponent ?: "-"}  DDOL: ${ddol ?: "-"}")
        appendLine("card: ****${panLast4 ?: "????"}")
        notes.forEach { appendLine("- $it") }
    }
}

/**
 * One contactless session with an EMV card: select the payment application,
 * GET PROCESSING OPTIONS, read the records, recover the card's public key
 * through its certificate chain, and sign challenges with INTERNAL
 * AUTHENTICATE (DDA).
 *
 * The PAN is read (it is inside the certificates) and checked, but never
 * leaves this object: [CardReport] only carries the last four digits.
 */
class EmvSession(private val card: CardTransceiver, private val capks: CapkStore) {
    private val notes = mutableListOf<String>()
    private var aid = ByteArray(0)
    private var label = ""
    private var aip = ByteArray(2)
    private var afl = ByteArray(0)
    private val records = mutableListOf<Tlv>()
    private var staticData = ByteArray(0)
    private var ddol: List<DolEntry> = emptyList()

    val rid get() = aid.copyOfRange(0, minOf(5, aid.size)).hex().uppercase()

    // ---- APDU plumbing ----

    private fun send(apdu: ByteArray): ByteArray {
        var r = card.transceive(apdu)
        if (r.size < 2) throw EmvException("short response")
        var sw1 = r[r.size - 2].toInt() and 0xff
        var sw2 = r[r.size - 1].toInt() and 0xff
        if (sw1 == 0x6C) { // wrong Le: resend with the right one
            r = card.transceive(apdu.copyOf(apdu.size - 1) + byteArrayOf(sw2.toByte()))
            sw1 = r[r.size - 2].toInt() and 0xff; sw2 = r[r.size - 1].toInt() and 0xff
        }
        var body = r.copyOf(r.size - 2)
        while (sw1 == 0x61) { // more data available
            val more = card.transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte()))
            body += more.copyOf(more.size - 2)
            sw1 = more[more.size - 2].toInt() and 0xff; sw2 = more[more.size - 1].toInt() and 0xff
        }
        if (sw1 != 0x90 || sw2 != 0x00) throw StatusWord(sw1, sw2)
        return body
    }

    class StatusWord(sw1: Int, sw2: Int) : Exception("card returned %02X%02X".format(sw1, sw2))

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?): ByteArray {
        val head = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
        return if (data == null) head + byteArrayOf(0) else head + byteArrayOf(data.size.toByte()) + data + byteArrayOf(0)
    }

    private fun select(name: ByteArray) = send(apdu(0x00, 0xA4, 0x04, 0x00, name))

    // ---- session steps ----

    /** Select the payment application and run GET PROCESSING OPTIONS. */
    fun start(un: ByteArray = randomUn()) {
        val candidates = mutableListOf<Pair<ByteArray, String>>()
        try {
            val fci = Tlv.parse(select("2PAY.SYS.DDF01".toByteArray()))
            val dir = Tlv.find(fci, 0xBF0C)?.children ?: emptyList()
            dir.filter { it.tag == 0x61 }
                .sortedBy { e -> Tlv.find(e.children, 0x87)?.value?.firstOrNull()?.toInt()?.and(0x0f) ?: 15 }
                .forEach { e ->
                    Tlv.find(e.children, 0x4F)?.let { candidates += it.value to (Tlv.find(e.children, 0x50)?.value?.decodeToString() ?: "") }
                }
        } catch (e: Exception) {
            notes += "no PPSE (${e.message}); trying known applications"
        }
        if (candidates.isEmpty()) KNOWN_AIDS.forEach { candidates += it.unhex() to "" }

        var fci: List<Tlv>? = null
        for ((a, l) in candidates) {
            try {
                fci = Tlv.parse(select(a))
                aid = a
                label = l.ifEmpty { Tlv.find(fci, 0x50)?.value?.decodeToString() ?: "" }
                break
            } catch (_: Exception) {
            }
        }
        if (fci == null) throw EmvException("no supported payment application on this card")

        val pdol = Tlv.find(fci, 0x9F38)?.value?.let { Dol.parse(it) } ?: emptyList()
        val pdolData = pdol.fold(ByteArray(0)) { acc, e -> acc + terminalValue(e, un) }
        val gpo = Tlv.parse(send(apdu(0x80, 0xA8, 0x00, 0x00, Tlv.encode(0x83, pdolData))))
        val fmt1 = gpo.firstOrNull { it.tag == 0x80 }
        if (fmt1 != null) {
            aip = fmt1.value.copyOfRange(0, 2)
            afl = fmt1.value.copyOfRange(2, fmt1.value.size)
        } else {
            aip = Tlv.find(gpo, 0x82)?.value ?: throw EmvException("GPO response has no AIP")
            afl = Tlv.find(gpo, 0x94)?.value ?: ByteArray(0)
        }
    }

    /** Read the records named in the AFL and collect the static data for offline authentication. */
    fun readRecords() {
        records.clear()
        var oda = ByteArray(0)
        for (i in afl.indices step 4) {
            if (i + 3 >= afl.size) break
            val sfi = (afl[i].toInt() and 0xff) ushr 3
            val first = afl[i + 1].toInt() and 0xff
            val last = afl[i + 2].toInt() and 0xff
            val odaCount = afl[i + 3].toInt() and 0xff
            for (rec in first..last) {
                val raw = send(apdu(0x00, 0xB2, rec, (sfi shl 3) or 4, null))
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
        ddol = find(0x9F49)?.value?.let { Dol.parse(it) } ?: listOf(DolEntry(0x9F37, 4))
    }

    private fun find(tag: Int) = Tlv.find(records, tag)

    /** Recover and check the issuer and card certificates; returns the card's RSA key. */
    fun recoverCardKey(): Pair<ByteArray, Int> {
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

        val iccCert = find(0x9F46)?.value ?: throw EmvException("no card certificate (card doesn't do DDA)")
        val iccExp = find(0x9F47)?.value ?: throw EmvException("no card exponent")
        val iccRem = find(0x9F48)?.value ?: ByteArray(0)
        val nI = issuerKey.size
        val r = Rsa.recover(issuerKey, issuerExp.toPositiveBigInteger(), iccCert)
        check(r[0] == 0x6A.toByte() && r[nI - 1] == 0xBC.toByte() && r[1] == 0x04.toByte()) { "card certificate format" }
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
        return modulus to iccExp.toPositiveBigInteger().toInt()
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

    /**
     * Where the challenge goes in the terminal data this card signs: the
     * Unpredictable Number's offset and length inside the DDOL data.
     */
    fun layout(): Triple<Int, Int, Int> {
        var off = 0
        for (e in ddol) {
            if (e.tag == 0x9F37) return Triple(off, e.len, ddol.sumOf { it.len })
            off += e.len
        }
        throw EmvException("card's DDOL has no Unpredictable Number")
    }

    /** INTERNAL AUTHENTICATE with the UN set to the first bytes of [challenge]. */
    fun authenticate(challenge: ByteArray): CardSig {
        val td = ddol.fold(ByteArray(0)) { acc, e -> acc + terminalValue(e, challenge) }
        val resp = Tlv.parse(send(apdu(0x00, 0x88, 0x00, 0x00, td)))
        val sdad = resp.firstOrNull { it.tag == 0x80 }?.value
            ?: Tlv.find(resp, 0x9F4B)?.value
            ?: throw EmvException("INTERNAL AUTHENTICATE returned no signature")
        return CardSig(sdad, td)
    }

    fun report(key: Pair<ByteArray, Int>?, issuerBits: Int? = null): CardReport {
        val a0 = aip[0].toInt()
        val pan = find(0x5A)?.value?.hex()?.trimEnd('f', 'F')
        return CardReport(
            network = NETWORKS[rid] ?: "unknown",
            aid = aid.hex().uppercase(),
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
            notes = notes.toList(),
        )
    }

    fun note(s: String) { notes += s }

    companion object {
        val KNOWN_AIDS = listOf("A0000000031010", "A0000000041010", "A0000005241010", "A0000000043060", "A0000000032010")
        val NETWORKS = mapOf("A000000003" to "Visa", "A000000004" to "Mastercard", "A000000524" to "RuPay", "F054415056" to "Tapvault demo card")

        fun randomUn(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        /** Terminal values for DOL entries; the UN comes from [un]. */
        fun terminalValue(e: DolEntry, un: ByteArray): ByteArray {
            val v: ByteArray = when (e.tag) {
                0x9F37 -> un.copyOf(e.len)
                0x9F66 -> "36004000".unhex() // TTQ: qVSDC + contact chip + online PIN + signature
                0x9F1A, 0x5F2A -> "0356".unhex() // India
                0x9A -> LocalDate.now().let { "%02d%02d%02d".format(it.year % 100, it.monthValue, it.dayOfMonth).unhex() }
                0x9C -> byteArrayOf(0)
                0x9F35 -> byteArrayOf(0x22)
                else -> ByteArray(e.len)
            }
            return if (v.size >= e.len) v.copyOf(e.len) else ByteArray(e.len - v.size) + v
        }
    }
}

/** High-level card operations used by the app. */
class CardService(private val capks: CapkStore) {
    class Enrolment(val key: SignerKey.EmvCard, val proof: CardSig, val report: CardReport)

    /**
     * One tap: read the card's key, then sign `challengeFor(key)` (which
     * depends on the key, e.g. the enrolment challenge) in the same session.
     */
    fun enrol(card: CardTransceiver, challengeFor: (SignerKey.EmvCard) -> ByteArray): Enrolment {
        val s = EmvSession(card, capks)
        s.start()
        s.readRecords()
        val icc = try {
            s.recoverCardKey()
        } catch (e: Exception) {
            throw CardCheckFailed(e.message ?: "card check failed", s.report(null))
        }
        val (off, len, tdLen) = s.layout()
        if (len !in 4..8) throw CardCheckFailed("unsupported UN length $len", s.report(icc))
        val key = SignerKey.EmvCard(icc.first, icc.second, off, len, tdLen)
        val proof = try {
            s.authenticate(challengeFor(key))
        } catch (e: Exception) {
            throw CardCheckFailed("card can't sign (no DDA): ${e.message}", s.report(icc))
        }
        if (!Rsa.verifySdad(key.modulus, key.exponent, proof.sdad, proof.terminalData)) {
            throw CardCheckFailed("card signature didn't verify with its certified key", s.report(icc))
        }
        return Enrolment(key, proof, s.report(icc))
    }

    /** One tap: sign [challenge]; fails early if this isn't the enrolled card. */
    fun sign(card: CardTransceiver, key: SignerKey.EmvCard, challenge: ByteArray): CardSig {
        val s = EmvSession(card, capks)
        s.start()
        s.readRecords()
        val sig = s.authenticate(challenge)
        if (!Rsa.verifySdad(key.modulus, key.exponent, sig.sdad, sig.terminalData)) {
            throw EmvException("this isn't the card enrolled in the wallet")
        }
        return sig
    }

    /** Read-only check: what kind of card is this, and can it be a signer? */
    fun inspect(card: CardTransceiver): CardReport {
        val s = EmvSession(card, capks)
        s.start()
        s.readRecords()
        val key = runCatching { s.recoverCardKey() }.onFailure { s.note("key: ${it.message}") }.getOrNull()
        if (key != null) {
            runCatching { s.layout(); s.authenticate(EmvSession.randomUn()) }
                .onSuccess { s.note("INTERNAL AUTHENTICATE works: this card can be a Tapvault signer") }
                .onFailure { s.note("INTERNAL AUTHENTICATE failed: ${it.message}") }
        }
        return s.report(key)
    }
}

class CardCheckFailed(message: String, val report: CardReport) : Exception(message)
