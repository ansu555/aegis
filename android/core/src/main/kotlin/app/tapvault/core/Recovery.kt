package app.tapvault.core

/**
 * Recover on a new phone (plan 5.5): the old phone is lost, so the wallet's
 * cards propose and approve one settings change that adds this phone's key
 * and removes the old phone key(s).
 *
 * The contract already allows it: a Config approved by cards only counts
 * as approved but waits `delays.recovery` (at least 24 h), and any phone key
 * still on the wallet can cancel it in that time. That is the defence
 * against someone who has stolen enough cards: the owner's old phone (or a
 * guardian phone) sees the request and cancels it.
 *
 * Nothing here signs anything; [plan] only builds and checks the action.
 */
object Recovery {
    /** Most ops one Config may carry (`contract/src/types.rs` MAX_CONFIG_OPS). */
    const val MAX_OPS = 4
    const val MAX_SIGNERS = 8

    data class Plan(
        val action: Action.Config,
        /** Signers the change removes (old phones, and lost cards if asked). */
        val removed: List<SignerInfo>,
        /** Cards that stay: [WalletState.threshold] of them must approve. */
        val cardsKept: List<SignerInfo>,
        /** Approvals needed, all from cards. */
        val approvalsNeeded: Int,
        /** Seconds from the last approval until it can execute. */
        val delaySecs: Long,
        /** True when the change also unfreezes the wallet. */
        val unfreezes: Boolean,
    )

    /**
     * The recovery change for [state]: remove every phone key in
     * [removePhones] (default: all of them) and every card in [lostCards],
     * then add [newPhone]; unfreeze too if the wallet is frozen and
     * [unfreeze] is true. Throws [TapvaultException] with a readable reason
     * when the cards that stay can't approve it or the result is invalid.
     */
    fun plan(
        state: WalletState,
        newPhone: SignerKey.P256,
        removePhones: Collection<String> = state.signers.filter { !it.isCard }.map { it.keyHash },
        lostCards: Collection<String> = emptyList(),
        unfreeze: Boolean = true,
    ): Plan {
        if (state.setupMode) throw TapvaultException("This wallet hasn't finished setup, so it can't be recovered.")
        val newHash = newPhone.keyHash().hex()
        if (state.signerByKeyHash(newHash) != null) throw TapvaultException("This phone's key is already a signer of the wallet.")

        val removed = (removePhones + lostCards).map { h ->
            state.signerByKeyHash(h) ?: throw TapvaultException("Signer ${h.take(12)}… isn't on this wallet any more. Refresh and try again.")
        }.distinctBy { it.keyHash.lowercase() }
        if (lostCards.any { h -> state.signerByKeyHash(h)?.isCard == false }) throw TapvaultException("Only cards can be marked lost here.")
        if (removePhones.any { h -> state.signerByKeyHash(h)?.isCard == true }) throw TapvaultException("Cards are removed as lost cards, not as phones.")

        val removedHashes = removed.map { it.keyHash.lowercase() }.toSet()
        val cardsKept = state.cards().filter { it.keyHash.lowercase() !in removedHashes }
        if (cardsKept.size < state.threshold) {
            throw TapvaultException(
                "Recovery needs ${state.threshold} of this wallet's cards to approve, and it has ${cardsKept.size}" +
                    (if (lostCards.isNotEmpty()) " that aren't marked lost" else "") + ". " +
                    "A wallet with one card can only be recovered from a phone that is still a signer.",
            )
        }
        val after = state.signers.size - removed.size + 1
        if (after > MAX_SIGNERS) throw TapvaultException("The wallet would have more than $MAX_SIGNERS signers. Remove an old phone or card too.")
        if (state.threshold > after) throw TapvaultException("The wallet would have fewer signers than the ${state.threshold} it needs.")

        val doUnfreeze = unfreeze && state.frozen
        val ops = buildList {
            // Removals first so the add never trips MAX_SIGNERS mid-change.
            removed.forEach { add(ConfigOp.RemoveSigner(it.keyHash.unhex())) }
            add(ConfigOp.AddSigner(newPhone))
            if (doUnfreeze) add(ConfigOp.Unfreeze)
        }
        if (ops.size > MAX_OPS) throw TapvaultException("That is ${ops.size} changes in one request; the wallet takes at most $MAX_OPS. Remove fewer old signers now and the rest later.")

        return Plan(Action.Config(ops), removed, cardsKept, state.threshold, state.delays.recovery, doUnfreeze)
    }

    /** True when [p] is a recovery request: it adds a phone key and was proposed by a card. */
    fun isRecovery(p: ProposalInfo, state: WalletState): Boolean {
        val ops = runCatching { ActionJson.parse(p.action) }.getOrNull() as? Action.Config ?: return false
        val addsPhone = ops.ops.any { it is ConfigOp.AddSigner && it.key is SignerKey.P256 }
        return addsPhone && state.signers.getOrNull(p.proposer)?.isCard == true
    }
}
