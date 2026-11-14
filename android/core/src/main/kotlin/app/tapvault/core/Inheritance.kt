package app.tapvault.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Contract v0.4 inheritance, as the `get_inheritance` view reports it.
 *
 * The owner names an heir and an inactivity period (a delayed config change,
 * [ConfigOp.SetHeir]). Every signature by any key of the wallet counts as
 * activity. After the whole period with none, anyone may start a claim; after
 * a further grace period (the wallet's config delay) with still none, the
 * funds can go only to the heir. Any activity voids an open claim.
 */
data class InheritanceInfo(
    val heir: String?,
    val inactivity: Long,
    val lastActive: Long,
    val claimTs: Long?,
    /** When a claim may start (null: no heir). */
    val claimableAt: Long?,
    /** When funds may go to the heir (null: no open claim). */
    val releasableAt: Long?,
    val aliveNonce: Long,
) {
    val hasHeir get() = heir != null

    fun stage(now: Long): Inheritance.Stage = when {
        heir == null -> Inheritance.Stage.NONE
        releasableAt != null && now >= releasableAt -> Inheritance.Stage.RELEASABLE
        releasableAt != null -> Inheritance.Stage.CLAIMED
        claimableAt != null && now >= claimableAt -> Inheritance.Stage.CLAIMABLE
        else -> Inheritance.Stage.WATCHING
    }

    /** Seconds until a claim may start (0 when it may already). */
    fun secondsToClaimable(now: Long): Long = ((claimableAt ?: now) - now).coerceAtLeast(0)

    companion object {
        fun parse(o: JsonObject) = InheritanceInfo(
            heir = o["heir"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            inactivity = o["inactivity"]!!.jsonPrimitive.long,
            lastActive = o["last_active"]!!.jsonPrimitive.long,
            claimTs = o["claim_ts"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            claimableAt = o["claimable_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            releasableAt = o["releasable_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            aliveNonce = o["alive_nonce"]!!.jsonPrimitive.long,
        )
    }
}

object Inheritance {
    enum class Stage {
        /** No heir named. */
        NONE,
        /** Heir named; the owner has been active within the period. */
        WATCHING,
        /** The period has passed with no activity: anyone may start a claim. */
        CLAIMABLE,
        /** A claim is open; the grace period is running. Any owner activity voids it. */
        CLAIMED,
        /** The grace period passed: funds can be released to the heir. */
        RELEASABLE,
    }

    /** Bounds of the contract (`policy::MIN_INACTIVITY`, `MAX_INACTIVITY`). */
    const val MIN_INACTIVITY = 3_600L
    const val MAX_INACTIVITY = 3650L * 86_400

    const val HOUR = 3_600L
    const val DAY = 86_400L

    /** The periods the app offers; the 1-hour one is for testnet demos. */
    fun choices(testnet: Boolean): List<Long> =
        (if (testnet) listOf(HOUR) else emptyList()) + listOf(30 * DAY, 90 * DAY, 180 * DAY, 365 * DAY)

    fun isValidPeriod(secs: Long) = secs in MIN_INACTIVITY..MAX_INACTIVITY

    fun describePeriod(secs: Long): String = when {
        secs % (365 * DAY) == 0L -> (secs / (365 * DAY)).let { if (it == 1L) "1 year" else "$it years" }
        secs % DAY == 0L -> (secs / DAY).let { if (it == 1L) "1 day" else "$it days" }
        secs % HOUR == 0L -> (secs / HOUR).let { if (it == 1L) "1 hour" else "$it hours" }
        else -> "${secs / 60} min"
    }

    /** "3 d 4 h", "2 h 5 min", "40 min" for a countdown. */
    fun describeLeft(secs: Long): String {
        val s = secs.coerceAtLeast(0)
        val d = s / DAY
        val h = (s % DAY) / HOUR
        val m = (s % HOUR) / 60
        return when {
            d > 0 -> "$d d $h h"
            h > 0 -> "$h h $m min"
            else -> "${m.coerceAtLeast(1)} min"
        }
    }
}
