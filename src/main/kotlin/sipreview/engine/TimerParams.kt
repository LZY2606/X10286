package sipreview.engine

import sipreview.domain.ParameterSet

/**
 * RFC 3261 timer parameters, all quantities carry milliseconds. Timer G starts at T1/2
 * per Section 17.2.1 (that choice is surfaced as a judgement note in the replay, not hidden).
 */
data class TimerParams(
    val t1Ms: Long,
    val t2Ms: Long,
    val t4Ms: Long,
    val timerA0Ms: Long,
    val timerB0Ms: Long,
    val timerD0Ms: Long,
    val timerE0Ms: Long,
    val timerG0Ms: Long,
    val timerH0Ms: Long,
    val timerIMs: Long,
    val timerJMs: Long,
    val timerKMs: Long,
    val virtualClockOriginMs: Long,
    val source: String
) {
    fun asMap(): Map<String, String> = mapOf(
        "T1" to "${t1Ms}ms", "T2" to "${t2Ms}ms", "T4" to "${t4Ms}ms",
        "timerA.initial" to "${timerA0Ms}ms", "timerB" to "${timerB0Ms}ms",
        "timerD" to "${timerD0Ms}ms", "timerE.initial" to "${timerE0Ms}ms",
        "timerG.initial" to "${timerG0Ms}ms", "timerH" to "${timerH0Ms}ms",
        "timerI" to "${timerIMs}ms", "timerJ" to "${timerJMs}ms",
        "timerK" to "${timerKMs}ms",
        "clock.origin" to "${virtualClockOriginMs}ms-epoch",
        "clock.basis" to "virtual"
    )

    companion object {
        const val FINGERPRINT_VERSION = "params-1"

        fun defaults(originMs: Long): TimerParams = TimerParams(
            t1Ms = 500, t2Ms = 4000, t4Ms = 5000,
            timerA0Ms = 500, timerB0Ms = 500 * 64, timerD0Ms = 32000,
            timerE0Ms = 500, timerG0Ms = 250, timerH0Ms = 32000,
            timerIMs = 5000, timerJMs = 32000, timerKMs = 5000,
            virtualClockOriginMs = originMs, source = "rfc3261-defaults"
        )

        fun fromParameterSet(ps: ParameterSet, originMs: Long): TimerParams {
            fun ms(key: String, default: Long): Long =
                ps.values[key]?.trimSuffix("ms")?.toLongOrNull() ?: default
            return defaults(originMs).copy(
                t1Ms = ms("T1", 500),
                t2Ms = ms("T2", 4000),
                t4Ms = ms("T4", 5000),
                timerG0Ms = ms("timerG.initial", 250),
                timerB0Ms = ms("timerB", 32000),
                timerD0Ms = ms("timerD", 32000),
                timerH0Ms = ms("timerH", 32000),
                timerIMs = ms("timerI", 5000),
                timerJMs = ms("timerJ", 32000),
                timerKMs = ms("timerK", 5000),
                virtualClockOriginMs = originMs,
                source = ps.fingerprint
            )
        }

        private fun String.trimSuffix(suffix: String) =
            if (endsWith(suffix)) dropLast(suffix.length) else this
    }
}
