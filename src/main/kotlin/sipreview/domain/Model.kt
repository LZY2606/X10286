package sipreview.domain

import kotlinx.serialization.Serializable

/** 方向：该条记录是本侧发出还是对端到达。 */
@Serializable
enum class Direction { SENT, RECV }

/** 消息起始行类型。 */
@Serializable
enum class StartLineKind { REQUEST, STATUS, UNKNOWN }

/** 规范化后的解析结果；无法转换的值留在 diagnostics 中，不删除原始字节。 */
@Serializable
data class ParsedMessage(
    val kind: StartLineKind,
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val version: String? = null,
    val viaBranches: List<String> = emptyList(),
    val viaTop: String? = null,
    val callId: String? = null,
    val fromTag: String? = null,
    val toTag: String? = null,
    val cseqSeq: Long? = null,
    val cseqMethod: String? = null,
    val recordRoute: List<String> = emptyList(),
    val contact: String? = null,
    val topViaReceived: String? = null,
    val topViaRport: String? = null,
    val headers: List<HeaderView> = emptyList(),
    val bodyBytes: Int = 0,
    val diagnostics: List<Diagnostic> = emptyList(),
)

@Serializable
data class HeaderView(val name: String, val rawValue: String, val ordinal: Int)

@Serializable
data class Diagnostic(val code: String, val message: String, val ordinal: Int? = null)

/**
 * 原始捕获记录。提交后不可变（DB 层强制 insert-only）。
 * 时间使用纳秒虚拟时钟 + 原始表示双存：norm 为可比较时间，orig 保留导入时写法与坐标基准。
 */
@Serializable
data class RawRecord(
    val id: String,
    val projectId: String,
    val ordinal: Long,
    val direction: Direction,
    val bytesBase64: String,
    val connectionId: String,
    val connectionGeneration: Long,
    val transport: String,
    val srcAddr: String? = null,
    val dstAddr: String? = null,
    val recvOrig: String,
    val recvBasis: String,
    val recvNanos: Long,
    val parentRecordId: String? = null,
    val revisionOf: String? = null,
    val supersedes: String? = null,
    val parserVersion: String,
    val paramFingerprint: String,
    val parsed: ParsedMessage,
    val committedAtNanos: Long,
)

/** 参数修订：新版本 + 父版本关系；旧参数不删除。 */
@Serializable
data class ParamRevision(
    val id: String,
    val projectId: String,
    val parentId: String?,
    val seq: Long,
    val note: String,
    val params: EngineParams,
    val createdAtNanos: Long,
)

@Serializable
data class EngineParams(
    /** T1（RFC3261 §17），单位毫秒；0 表示用默认 500。 */
    val timerT1Ms: Long = 500,
    val timerT2Ms: Long = 4000,
    val timerT4Ms: Long = 5000,
    /** 非 INVITE 事务定时器 E2/最终响应急限，毫秒。 */
    val nonInviteTimeoutMs: Long = 32_000,
    /** INVITE client 事务完成态 H 时长（默认 64*T1）。 */
    val inviteCompletedMs: Long = 32_000,
    /** 重传判定窗口：相同指纹间隔 <= 该值视为 UDP 重传（虚拟时钟）。 */
    val retransmitWindowMs: Long = 32_000,
    /** 解析参数指纹算法版本固定由此字段共同参与。 */
    val fingerprintAlgoVersion: String = "fp-v1",
)

/** 人工决定（接受/否定/参数调整/冲突解决）。写入事件历史，永不就地覆盖。 */
@Serializable
data class Decision(
    val id: String,
    val projectId: String,
    val eventId: String,
    val atNanos: Long,
    val kind: String,            // accept | reject | param_change | resolve_conflict | undo
    val targetRef: String,       // 被决定对象的稳定身份
    val choice: String? = null,  // 选中的候选 id
    val rejectedCandidates: List<String> = emptyList(),
    val reason: String = "",
    val undoOf: String? = null,
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val seed: Long,
    val createdAtNanos: Long,
    val note: String = "",
)
