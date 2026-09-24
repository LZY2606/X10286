package forensics.sip.engine

import forensics.sip.sip.Identity
import kotlinx.serialization.Serializable

@Serializable
data class EngineParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4_000,
    val t4Ms: Long = 5_000,
    val timerAMs: Long? = null,
    val timerBMs: Long? = null,
    val timerDMs: Long? = null,
    val timerEMs: Long? = null,
    val timerFMs: Long? = null,
    val timerGMs: Long? = null,
    val timerHMs: Long? = null,
    val timerIMs: Long? = null,
    val timerJMs: Long? = null,
    val timerKMs: Long? = null,
    val treat100AsEarly: Boolean = false,
    val matchRawBytesForRetransmit: Boolean = true,
) {
    val algorithmVersion = "sip-rcv-dialog/1.0.0"

    fun timerA(): Long = timerAMs ?: t1Ms
    fun timerB(): Long = timerBMs ?: 64 * t1Ms
    fun timerD(): Long = timerDMs ?: 32 * t1Ms
    fun timerE(): Long = timerEMs ?: t1Ms
    fun timerF(): Long = timerFMs ?: 64 * t1Ms
    fun timerG(): Long = timerGMs ?: minOf(2 * t1Ms, t2Ms)
    fun timerH(): Long = timerHMs ?: 64 * t1Ms
    fun timerI(): Long = timerIMs ?: t4Ms
    fun timerJ(): Long = timerJMs ?: 64 * t1Ms
    fun timerK(): Long = timerKMs ?: t4Ms

    fun fingerprint(): String = Identity.fingerprint(
        mapOf(
            "alg" to algorithmVersion,
            "t1Ms" to t1Ms.toString(),
            "t2Ms" to t2Ms.toString(),
            "t4Ms" to t4Ms.toString(),
            "timerA" to timerA().toString(),
            "timerB" to timerB().toString(),
            "timerD" to timerD().toString(),
            "timerE" to timerE().toString(),
            "timerF" to timerF().toString(),
            "timerG" to timerG().toString(),
            "timerH" to timerH().toString(),
            "timerI" to timerI().toString(),
            "timerJ" to timerJ().toString(),
            "timerK" to timerK().toString(),
            "treat100AsEarly" to treat100AsEarly.toString(),
            "matchRawBytes" to matchRawBytesForRetransmit.toString(),
        )
    )
}
