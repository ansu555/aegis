package app.tapvault.core

/**
 * What this phone knows about one card of a wallet: how it signs, its network
 * and last four digits (display only), keyed by the card key's hash, the same
 * `key_hash` that `get_wallet` lists for the signer. Nothing else about the
 * card is kept (invariant 5: no PAN, track data, name or certificates).
 *
 * [keyHash] is empty only for a card migrated from a build that stored a
 * single card without its key hash; [CardBook.bind] fills it in from the chain.
 */
data class KnownCard(val keyHash: String, val recipe: CardRecipe?, val network: String?, val last4: String?) {
    /** "Visa •••• 0093", or "Bank card". */
    val name get() = listOfNotNull(network, last4?.let { "•••• $it" }).joinToString(" ").ifBlank { "Bank card" }

    fun encode() = listOf(keyHash, recipe?.encode().orEmpty(), network.orEmpty(), last4.orEmpty()).joinToString("|")

    companion object {
        fun decode(s: String): KnownCard? = s.split("|").takeIf { it.size == 4 }?.let { f ->
            KnownCard(f[0].lowercase(), CardRecipe.decode(f[1].ifEmpty { null }), f[2].ifEmpty { null }, f[3].ifEmpty { null })
        }
    }
}

/** The cards of one wallet known to this phone, in the order they were added. */
class CardBook(val cards: List<KnownCard>) {
    fun encode() = cards.joinToString("\n") { it.encode() }

    fun find(keyHash: String) = cards.firstOrNull { it.keyHash.equals(keyHash, ignoreCase = true) }

    /** Add [card], or replace the entry with the same key hash. */
    fun put(card: KnownCard) = CardBook(
        if (find(card.keyHash) != null) cards.map { if (it.keyHash.equals(card.keyHash, true)) card else it } else cards + card,
    )

    /**
     * Give a migrated entry (no key hash yet) the key hash of the card added
     * during setup. Setup always adds that card as signer 1, the first card
     * signer, but signers can move down when one is removed, so the first card
     * signer not already in the book is taken. Returns null if nothing changed.
     */
    fun bind(state: WalletState): CardBook? {
        val i = cards.indexOfFirst { it.keyHash.isEmpty() }
        if (i < 0) return null
        val free = state.cards().firstOrNull { s -> find(s.keyHash) == null } ?: return null
        return CardBook(cards.mapIndexed { j, c -> if (j == i) c.copy(keyHash = free.keyHash.lowercase()) else c })
    }

    companion object {
        fun decode(s: String?): CardBook = CardBook(s.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { KnownCard.decode(it) })

        /**
         * The card list stored under [encoded], or, for a wallet made before
         * several cards per wallet, its single enrolled card as entry 1.
         */
        fun load(encoded: String?, legacyRecipe: String?, legacyNetwork: String?, legacyLast4: String?): CardBook {
            if (encoded != null) return decode(encoded)
            if (legacyRecipe == null && legacyNetwork == null && legacyLast4 == null) return CardBook(emptyList())
            return CardBook(listOf(KnownCard("", CardRecipe.decode(legacyRecipe), legacyNetwork, legacyLast4)))
        }
    }
}
