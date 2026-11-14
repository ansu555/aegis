package app.tapvault.core

/**
 * Watchtower rules (plan 5.9): what changed in a wallet since the last look,
 * worth an alert on this phone. Pure logic; the app runs it from a periodic
 * background job and on every refresh (where it only updates the memory: the
 * user is looking at the app already).
 */
object Watch {
    /** What was seen last time: open proposal ids, frozen, the signer set (key hashes), the last integrity verdict. */
    data class Memory(val seen: Set<Long>, val frozen: Boolean, val signers: List<String>, val integrityOk: Boolean = true) {
        fun encode() = listOf(seen.joinToString(","), frozen, signers.joinToString(","), integrityOk).joinToString("|")

        companion object {
            fun decode(s: String?): Memory? = s?.split("|")?.takeIf { it.size == 4 }?.let { f ->
                runCatching {
                    Memory(
                        f[0].split(",").filter { it.isNotEmpty() }.map { it.toLong() }.toSet(),
                        f[1].toBooleanStrict(), f[2].split(",").filter { it.isNotEmpty() }, f[3].toBooleanStrict(),
                    )
                }.getOrNull()
            }
        }
    }

    enum class Kind { UNKNOWN_REQUEST, FROZEN, SIGNERS_CHANGED, INTEGRITY, INHERITANCE }

    data class Alert(val kind: Kind, val title: String, val text: String, val proposalId: Long? = null)

    fun memoryOf(state: WalletState, proposals: List<ProposalInfo>, integrityOk: Boolean = true) =
        Memory(proposals.map { it.id }.toSet(), state.frozen, state.signers.map { it.keyHash.lowercase() }, integrityOk)

    /**
     * Alerts for what is new since [before] (none on the first look, which only
     * records): a proposal this phone didn't approve (someone else started
     * it), the wallet becoming frozen, the signer set changing (a config change
     * executed), the integrity check starting to fail.
     */
    fun check(
        before: Memory?,
        state: WalletState,
        proposals: List<ProposalInfo>,
        phoneIndex: Int?,
        integrityOk: Boolean = true,
        nowSecs: Long = System.currentTimeMillis() / 1000,
    ): Pair<List<Alert>, Memory> {
        val now = memoryOf(state, proposals, integrityOk)
        if (before == null) return emptyList<Alert>() to now
        val alerts = mutableListOf<Alert>()
        for (p in proposals) {
            if (p.id in before.seen || p.stale) continue
            if (phoneIndex != null && p.approvedBy(phoneIndex)) continue
            val runs = p.readyTs?.let { r ->
                val d = if (p.isTransfer) state.delays.transfer else state.delays.recovery
                val left = r + d - nowSecs
                if (left > 0) " · can execute in ${left / 60} min" else " · can execute now"
            } ?: " · not approved yet"
            alerts += if (Recovery.isRecovery(p, state)) {
                Alert(Kind.UNKNOWN_REQUEST, "Someone is recovering this wallet", "Cards are adding a new phone and removing this one$runs. If you didn't start a recovery, cancel it now.", p.id)
            } else {
                Alert(Kind.UNKNOWN_REQUEST, "A request you didn't make", p.describe() + runs + ". Cancel it if it isn't yours.", p.id)
            }
        }
        if (state.frozen && !before.frozen) alerts += Alert(Kind.FROZEN, "Wallet frozen", "No transfers can go out until it is unfrozen. If you didn't freeze it, a card of this wallet did.")
        if (now.signers != before.signers) {
            val added = now.signers.count { it !in before.signers }
            val removed = before.signers.count { it !in now.signers }
            alerts += Alert(Kind.SIGNERS_CHANGED, "Signers changed", "A settings change executed: $added signer${if (added == 1) "" else "s"} added, $removed removed. Open the app to check it was you.")
        }
        if (!integrityOk && before.integrityOk) alerts += Alert(Kind.INTEGRITY, "Wallet check failed", "The wallet account doesn't look the way this phone expects. Open Security Center.")
        return alerts to now
    }

    /**
     * v0.4 inheritance: an alert when an heir's claim is open or can open
     * (the owner should check in now if they're alive), or when the
     * inactivity period is nearly over (under a tenth of it, at most 7 days,
     * left). Returns a key with it: post the alert only when the key differs
     * from the last one posted, so a state is announced once.
     */
    fun inheritance(info: InheritanceInfo?, nowSecs: Long = System.currentTimeMillis() / 1000): Pair<String, Alert>? {
        if (info?.heir == null) return null
        val heir = info.heir
        return when (info.stage(nowSecs)) {
            Inheritance.Stage.CLAIMED, Inheritance.Stage.RELEASABLE -> "claim:${info.claimTs}" to Alert(
                Kind.INHERITANCE, "Inheritance claim open",
                "A claim to send this wallet to $heir has started. If you're here, open the app and check in: that voids the claim.",
            )
            Inheritance.Stage.CLAIMABLE -> "claimable:${info.claimableAt}" to Alert(
                Kind.INHERITANCE, "Your heir can claim this wallet",
                "No key of this wallet has signed for ${Inheritance.describePeriod(info.inactivity)}. Check in now, or $heir can start a claim.",
            )
            Inheritance.Stage.WATCHING -> {
                val left = info.secondsToClaimable(nowSecs)
                if (left < minOf(info.inactivity / 10, 7 * Inheritance.DAY)) "soon:${info.claimableAt}" to Alert(
                    Kind.INHERITANCE, "Check in soon",
                    "In ${Inheritance.describeLeft(left)} your heir $heir can claim this wallet. Open the app and tap I'm still here.",
                ) else null
            }
            Inheritance.Stage.NONE -> null
        }
    }
}
