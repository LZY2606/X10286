package sipviz

import java.security.MessageDigest

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

enum class Direction { C2S, S2C }

/** 算法版本：任何派生值都携带它，规则变化时递增。 */
const val ALGO_VERSION = "sip-atlas/1.0"

/** 参数集：修订以新版本+父版本表达，指纹参与派生值身份。 */
data class ParamSet(
    val id: String,
    val version: Int,
    val parentId: String?,
    val t1: Long = 500,          // 重传基础间隔（虚拟 tick）
    val timerI: Long = 32000,    // INVITE server CONFIRMED 吸收 ACK 窗口
    val timerJ: Long = 32000,    // non-INVITE server COMPLETED 窗口
    val timerK: Long = 5000,     // client COMPLETED 窗口
    val retransTolerance: Long = 250,
) {
    val fingerprint: String get() = sha256("$t1|$timerI|$timerJ|$timerK|$retransTolerance").take(16)

    fun toJson(): String =
        """{"id":"$id","version":$version,"parentId":${parentId?.let { "\"$it\"" } ?: "null"},"t1":$t1,"timerI":$timerI,"timerJ":$timerJ,"timerK":$timerK,"retransTolerance":$retransTolerance,"fingerprint":"$fingerprint"}"""
}

/** 传输连接：代次(generation)区分 TCP 重连前后的逻辑连接。 */
data class ConnectionRec(
    val id: String,
    val version: Int,
    val parentId: String?,
    val protocol: String,       // UDP / TCP
    val local: String,
    val remote: String,
    val generation: Int,
    val openedTick: Long,
    val closedTick: Long?,
)

/** 时钟记录：虚拟时钟 tick 与说明。 */
data class ClockRec(
    val id: String,
    val version: Int,
    val parentId: String?,
    val tick: Long,
    val note: String,
)

/** 派生值来源：直接来源（输入 id+版本）。 */
data class SourceRef(val kind: String, val id: String, val version: Int) {
    fun key(): String = "$kind:$id@$version"
}

/** 派生事实。status: CURRENT / CANDIDATE / STALE / ACCEPTED / REJECTED */
data class Fact(
    val id: String,
    val kind: String,
    val key: String,
    val status: String,
    val payload: String,          // JSON
    val algoVersion: String,
    val paramsFp: String,
    val sources: List<SourceRef>,
)

/** 机器可读取的判断事件。 */
data class Event(
    val kind: String,
    val tick: Long,
    val payload: String,          // JSON
    val algoVersion: String,
    val paramsFp: String,
    val sources: List<SourceRef>,
)

data class Decision(
    val id: String,
    val target: String,
    val action: String,           // ACCEPT / REJECT / PARAM_ADJUST / UNDO / NOTE
    val note: String,
    val tick: Long,
    val undoneBy: String?,
)
