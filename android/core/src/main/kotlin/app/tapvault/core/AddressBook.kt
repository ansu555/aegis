package app.tapvault.core

/**
 * Saved receivers on this phone: a name for a NEAR account. Shared by every
 * wallet on the phone (an account is the same whichever wallet pays it).
 * Kept on the phone only; nothing here is on chain.
 */
class AddressBook(val entries: List<Entry>) {
    data class Entry(val name: String, val account: String)

    fun nameOf(account: String): String? = entries.firstOrNull { it.account == account }?.name

    /** Entries whose name or account contains [query] (all of them for an empty query), by name. */
    fun search(query: String): List<Entry> {
        val q = query.trim().lowercase()
        return entries.filter { q.isEmpty() || q in it.name.lowercase() || q in it.account }.sortedBy { it.name.lowercase() }
    }

    /** Save [account] as [name], replacing an entry with the same account. Throws a readable error for bad input. */
    fun put(name: String, account: String): AddressBook {
        val n = name.trim().replace(Regex("\\s+"), " ")
        val a = account.trim().lowercase()
        problem(n, a)?.let { throw IllegalArgumentException(it) }
        if (entries.any { it.name.equals(n, ignoreCase = true) && it.account != a }) throw IllegalArgumentException("You already have a contact called $n.")
        return AddressBook(entries.filter { it.account != a } + Entry(n, a))
    }

    fun remove(account: String) = AddressBook(entries.filter { it.account != account })

    fun encode() = entries.joinToString("\n") { "${it.account}|${it.name.replace("|", "/").replace("\n", " ")}" }

    companion object {
        fun decode(s: String?) = AddressBook(
            s.orEmpty().lines().mapNotNull { l -> l.split("|", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { Entry(it[1], it[0]) } },
        )

        private val ACCOUNT = Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")

        /** Why [name] / [account] can't be saved, or null. */
        fun problem(name: String, account: String): String? = when {
            name.isBlank() -> "Give the contact a name."
            name.length > 32 -> "Keep the name to 32 characters."
            !(ACCOUNT.matches(account) && account.length in 2..64) -> "That isn't a NEAR account name."
            else -> null
        }
    }
}
