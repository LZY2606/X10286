package sipreview.engine

import kotlinx.serialization.Serializable
import sipreview.model.Ids
import java.security.MessageDigest

@Serializable
data class TimerParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4_000,
    val t4Ms: Long = 5_000,
    val timerDudpMs: Long = 32_000,
    val timerDtcpMs: Long = 0,
    val timerHMs: Long = 32_000,
    val timerFMs: Long = 32_000,
    val timerKUdpMs: Long = 5_000,
    val timerJUdpMs: Long = 32_000,
    val timerIcmpTcpMs: Long = 0,
    val retransToleranceRatio: Double = 0.20
)

@Serializable
data class Params(
    val algorithmVersion: String = "sipreview-engine-1.0.0",
    val seed: Long = 20260925L,
    val timers: TimerParams = TimerParams(),
    val version: String = "param-init-1"
) {
    fun fingerprint(): String {
        val canon = "$algorithmVersion|$seed|${timers.t1Ms}|${timers.t2Ms}|${timers.t4Ms}|" +
                "${timers.timerDudpMs}|${timers.timerDtcpMs}|${timers.timerHMs}|${timers.timerFMs}|" +
                "${timers.timerKUdpMs}|${timers.timerJUdpMs}|${timers.retransToleranceRatio}"
        return "fp_" + MessageDigest.getInstance("SHA-256").digest(canon.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
    }
}
