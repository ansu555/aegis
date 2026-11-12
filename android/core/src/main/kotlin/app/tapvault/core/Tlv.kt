package app.tapvault.core

import java.io.ByteArrayOutputStream

/** BER-TLV as used by EMV. */
class Tlv(val tag: Int, val value: ByteArray, val children: List<Tlv>) {
    val constructed get() = isConstructed(tag)

    companion object {
        private fun isConstructed(tag: Int): Boolean {
            var first = tag
            while (first > 0xff) first = first ushr 8
            return first and 0x20 != 0
        }

        fun parse(b: ByteArray): List<Tlv> {
            val out = mutableListOf<Tlv>()
            var i = 0
            while (i < b.size) {
                val lead = b[i].toInt() and 0xff
                if (lead == 0x00 || lead == 0xff) { i++; continue } // inter-object padding
                var tag = lead
                i++
                if (lead and 0x1f == 0x1f) {
                    do {
                        require(i < b.size) { "truncated tag" }
                        val n = b[i++].toInt() and 0xff
                        tag = (tag shl 8) or n
                    } while (n and 0x80 != 0)
                }
                require(i < b.size) { "truncated length" }
                var len = b[i++].toInt() and 0xff
                if (len and 0x80 != 0) {
                    val n = len and 0x7f
                    require(n in 1..3 && i + n <= b.size) { "bad length" }
                    len = 0
                    repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xff) }
                }
                require(i + len <= b.size) { "value overruns buffer" }
                val v = b.copyOfRange(i, i + len)
                i += len
                out += Tlv(tag, v, if (isConstructed(tag)) parse(v) else emptyList())
            }
            return out
        }

        /** Depth-first search for `tag`. */
        fun find(list: List<Tlv>, tag: Int): Tlv? {
            for (t in list) {
                if (t.tag == tag) return t
                find(t.children, tag)?.let { return it }
            }
            return null
        }

        fun encode(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val tagBytes = generateSequence(tag) { if (it > 0xff) it ushr 8 else null }.toList().reversed()
                .map { it and 0xff }
            tagBytes.forEach { out.write(it) }
            when {
                value.size < 0x80 -> out.write(value.size)
                value.size <= 0xff -> { out.write(0x81); out.write(value.size) }
                else -> { out.write(0x82); out.write(value.size ushr 8); out.write(value.size and 0xff) }
            }
            out.write(value)
            return out.toByteArray()
        }

        fun encode(tag: Int, vararg children: ByteArray): ByteArray =
            encode(tag, children.fold(ByteArray(0)) { acc, c -> acc + c })
    }
}

/** A Data Object List: (tag, length) pairs the card asks the terminal to supply. */
data class DolEntry(val tag: Int, val len: Int)

object Dol {
    /** Throws IllegalArgumentException on a truncated list, like [Tlv.parse]. */
    fun parse(b: ByteArray): List<DolEntry> {
        val out = mutableListOf<DolEntry>()
        var i = 0
        while (i < b.size) {
            var tag = b[i++].toInt() and 0xff
            if (tag and 0x1f == 0x1f) {
                do {
                    require(i < b.size) { "truncated DOL tag" }
                    val n = b[i++].toInt() and 0xff
                    tag = (tag shl 8) or n
                } while (n and 0x80 != 0)
            }
            require(i < b.size) { "truncated DOL length" }
            out += DolEntry(tag, b[i++].toInt() and 0xff)
        }
        return out
    }

    fun encode(entries: List<DolEntry>): ByteArray =
        entries.fold(ByteArray(0)) { acc, e ->
            val t = if (e.tag > 0xff) byteArrayOf((e.tag ushr 8).toByte(), e.tag.toByte()) else byteArrayOf(e.tag.toByte())
            acc + t + byteArrayOf(e.len.toByte())
        }
}
