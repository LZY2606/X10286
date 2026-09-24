package org.sipreview.domain

import kotlinx.serialization.Serializable

/**
 * RFC 3261 Section 17 timer table. All durations are milliseconds and the clock is
 * virtual so retransmission explanations are fully deterministic.
 */
@Serializable
data class TimerParams(
    val T1_ms: Long = 500L,
    val T2_ms: Long = 4000L,
    val T4_ms: Long = 5000L,
    val TimerA_ms: Long = T1_ms,
    val TimerB_ms: Long = 64L * T1_ms,
    val TimerD_ms: Long = 32_000L,
    val TimerE_ms: Long = T1_ms,
    val TimerF_ms: Long = 64L * T1_ms,
    val TimerG_ms: Long = T1_ms,
    val TimerH_ms: Long = 64L * T1_ms,
    val TimerI_ms: Long = T4_ms,
    val TimerJ_ms: Long = 64L * T1_ms,
    val TimerK_ms: Long = T4_ms
) {
    fun fingerprint(): String = Ids.short(
        listOf(
            "timerparams", T1_ms.toString(), T2_ms.toString(), T4_ms.toString(),
            TimerA_ms.toString(), TimerB_ms.toString(), TimerD_ms.toString(),
            TimerE_ms.toString(), TimerF_ms.toString(), TimerG_ms.toString(),
            TimerH_ms.toString(), TimerI_ms.toString(), TimerJ_ms.toString(), TimerK_ms.toString()
        )
    )

    companion object { const val VERSION = "timers/rfc3261-1.0.0" }
}

/**
 * Monotonic virtual clock. Domain code advances it explicitly; the wall clock is only
 * used for human display and never for protocol decisions.
 */
class VirtualClock(var nowMs: Long = 0L) {
    fun advanceTo(timeMs: Long) { if (timeMs > nowMs) nowMs = timeMs }
    fun advance(deltaMs: Long) { nowMs += deltaMs }
}

/**
 * Explains an observed duplicate in terms of the RFC 3261 retransmission schedule.
 * A retransmission at virtual time t is consistent if it lands in the expected
 * doubling window for Timer A (INVITE over UDP) / Timer E (non-INVITE), capped at T2.
 */
@Serializable
data class RetransmitExpectation(
    val index: Int,
    val earliestMs: Long,
    val scheduledMs: Long,
    val latestMs: Long,
    val timerName: String
)

object RetransmitSchedule {
    /** Expected send times for a UDP client retransmission series, starting from [firstMs]. */
    fun inviteClientSeries(firstMs: Long, p: TimerParams, count: Int = 8): List<Long> {
        val out = mutableListOf<Long>()
        var interval = p.TimerA_ms
        var t = firstMs
        repeat(count) {
            t += interval
            out += t
            interval = (interval * 2).coerceAtMost(p.T2_ms)
        }
        return out
    }

    fun nonInviteClientSeries(firstMs: Long, p: TimerParams, count: Int = 8): List<Long> {
        val out = mutableListOf<Long>()
        var interval = p.TimerE_ms
        var t = firstMs
        repeat(count) {
            t += interval
            out += t
            interval = (interval * 2).coerceAtMost(p.T2_ms)
        }
        return out
    }

    /**
     * Decide whether a duplicate reception can be explained by the virtual clock.
     * Returns the matched expectation (1-based duplicate number) or null if it falls
     * outside every doubling window (then it is not a standard retransmit).
     */
    fun explain(
        duplicateIndex: Int,
        receivedAtMs: Long,
        firstMs: Long,
        invite: Boolean,
        p: TimerParams,
        toleranceMs: Long = 0L
    ): RetransmitExpectation? {
        val series = if (invite) inviteClientSeries(firstMs, p) else nonInviteClientSeries(firstMs, p)
        if (duplicateIndex <= 0 || duplicateIndex > series.size) return null
        val scheduled = series[duplicateIndex - 1]
        return RetransmitExpectation(
            index = duplicateIndex,
            earliestMs = scheduled - toleranceMs,
            scheduledMs = scheduled,
            latestMs = scheduled + toleranceMs,
            timerName = if (invite) "TimerA" else "TimerE"
        ).takeIf { receivedAtMs in it.earliestMs..it.latestMs }
    }
}
