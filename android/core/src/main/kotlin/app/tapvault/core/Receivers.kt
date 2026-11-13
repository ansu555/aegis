package app.tapvault.core

/**
 * Receivers this phone has paid before (plan 4.4 review warnings): first-time
 * receiver, and look-alikes of a known receiver. Kept on the phone only.
 */
class Receivers(val known: Map<String, Long>) {
    fun encode() = known.entries.joinToString("\n") { "${it.key}|${it.value}" }

    fun isKnown(account: String) = account in known

    fun add(account: String, atSecs: Long = System.currentTimeMillis() / 1000) =
        if (account in known) this else Receivers(known + (account to atSecs))

    /** A known receiver that differs from [account] by one or two edits (a typo, or someone imitating it), if any. */
    fun lookAlike(account: String): String? =
        if (account in known) null else known.keys.filter { distance(it, account) in 1..2 }.minByOrNull { distance(it, account) }

    companion object {
        fun decode(s: String?) = Receivers(
            s.orEmpty().lines().mapNotNull { l -> l.split("|").takeIf { it.size == 2 }?.let { f -> f[1].toLongOrNull()?.let { f[0] to it } } }.toMap(),
        )

        /** Levenshtein distance (accounts are at most 64 characters). */
        fun distance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val cur = IntArray(b.length + 1)
                cur[0] = i + 1
                for (j in b.indices) cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }
    }
}
