package review

import kotlinx.serialization.Serializable

/** 方向：观测点视角下报文的去向。 */
@Serializable
enum class Direction { INGRESS, EGRESS }

/** 传输协议。 */
@Serializable
enum class Transport { UDP, TCP, TLS }

/** 连接事件类型。 */
@Serializable
enum class ConnectionEventKind { OPEN, CLOSE, RESET }

/** 带单位/坐标基准的量：导入时保留原始表示，派生层才做规范化。 */
@Serializable
data class UnitValue(
    val raw: String,
    val unit: String,
    val basis: String,
    val value: Double? = null,
    val diagnostic: String? = null,
)

/** 格式诊断：不可转换/不合法的值留在原位置并附诊断，不丢弃。 */
@Serializable
data class FormatDiagnostic(
    val line: Int,
    val code: String,
    val message: String,
    val context: String = "",
)

/** 连接代次（同一 4 元组可有多代次，连接复用不能抹掉消息身份）。 */
@Serializable
data class ConnectionRecord(
    val id: String,
    val generation: Int,
    val transport: Transport,
    val localEndpoint: String,
    val remoteEndpoint: String,
    val openedAt: UnitValue,
    val closedAt: UnitValue? = null,
    val closeKind: ConnectionEventKind? = null,
    val sourceEventId: String,
)

/** 不可变原始报文记录：原报文字节按方向保存。 */
@Serializable
data class MessageRecord(
    val id: String,
    val seq: Int,
    val direction: Direction,
    val transport: Transport,
    val connectionId: String,
    val connectionGeneration: Int,
    val receivedAt: UnitValue,
    val rawBytes: String,
    val rawSha256: String,
    val sourceEventId: String,
    val parseDiagnostics: List<FormatDiagnostic> = emptyList(),
    // 解析得到的 SIP 头字段（可能为 null 并附诊断）
    val kind: MessageKind? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val viaBranch: String? = null,
    val viaReceived: String? = null,
    val viaRport: String? = null,
    val callId: String? = null,
    val fromTag: String? = null,
    val toTag: String? = null,
    val fromUri: String? = null,
    val toUri: String? = null,
    val cseqNumber: Long? = null,
    val cseqMethod: String? = null,
    val recordRoute: List<String> = emptyList(),
    val contact: List<String> = emptyList(),
    val topVia: String? = null,
)

@Serializable
enum class MessageKind { REQUEST, RESPONSE }

/** 提交（原始输入批次）。一经提交不可覆盖；修订以新版本 + 父版本表达。 */
@Serializable
data class SubmissionRecord(
    val id: String,
    val seq: Int,
    val parentSubmissionId: String?,
    val submittedAt: String,
    val payloadSha256: String,
    val payloadText: String,
    val kind: SubmissionKind,
    val note: String = "",
)

@Serializable
enum class SubmissionKind { CAPTURE, PARAM_REVISION, SOURCE_PATCH, NOTE }

/** 参数修订（算法参数，带指纹）。 */
@Serializable
data class EngineParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val timerAMaxRetransmits: Int = 7,
    val timerBTimeoutMs: Long = 32000,
    val timerDTimeoutMs: Long = 32000,
    val timerFTimeoutMs: Long = 32000,
    val timerKTimeoutMs: Long = 5000,
    val retransmitSameByteWindowMs: Long = 200,
    val virtualClockOriginMs: Long = 0,
    val algorithmVersion: String = "sip-fsm-1.0.0",
) {
    fun fingerprint(): String =
        sha256Hex(
            listOf(
                algorithmVersion, t1Ms, t2Ms, t4Ms, timerAMaxRetransmits,
                timerBTimeoutMs, timerDTimeoutMs, timerFTimeoutMs, timerKTimeoutMs,
                retransmitSameByteWindowMs, virtualClockOriginMs,
            ).joinToString("|")
        )
}

@Serializable
enum class TxRole { CLIENT, SERVER }

@Serializable
enum class TxFamily { INVITE, NON_INVITE }

/** 事务（派生值，带算法版本/参数指纹/直接来源）。 */
@Serializable
data class TransactionRecord(
    val id: String,
    val branch: String,
    val method: String,
    val family: TxFamily,
    val role: TxRole,
    val callId: String?,
    val cseq: Long?,
    val state: String,
    val messageIds: List<String>,
    val linkedTransactionId: String? = null,
    val linkReason: String? = null,
    val algorithmVersion: String,
    val paramsFingerprint: String,
    val sourceMessageIds: List<String>,
    val basisEventId: String,
)

/** Dialog 状态。 */
@Serializable
enum class DialogState { EARLY, CONFIRMED, TERMINATED, AMBIGUOUS }

@Serializable
data class DialogRecord(
    val id: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val state: DialogState,
    val forkedFromDialogId: String?,
    val establishedByMessageId: String,
    val routeSet: List<String>,
    val remoteTarget: String?,
    val localSeq: Long?,
    val remoteSeq: Long?,
    val messageIds: List<String>,
    val algorithmVersion: String,
    val paramsFingerprint: String,
    val sourceMessageIds: List<String>,
    val basisEventId: String,
)

/** 候选/判定：候选、拒绝原因、未知状态都是一等数据。 */
@Serializable
enum class CandidateStatus { ACCEPTED, REJECTED, UNKNOWN }

@Serializable
data class CandidateRecord(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val claim: String,
    val status: CandidateStatus,
    val rationale: String,
    val rejectedReason: String? = null,
    val confidence: Double = 1.0,
    val messageIds: List<String>,
    val timerName: String? = null,
    val timerDeadline: UnitValue? = null,
    val algorithmVersion: String,
    val paramsFingerprint: String,
    val sourceMessageIds: List<String>,
    val basisEventId: String,
)

/** 定时器决定（虚拟时钟解释）。 */
@Serializable
data class TimerDecisionRecord(
    val id: String,
    val transactionId: String,
    val timerName: String,
    val armedAt: UnitValue,
    val deadline: UnitValue,
    val durationMs: Long,
    val fired: Boolean,
    val canceledByMessageId: String? = null,
    val rationale: String,
    val algorithmVersion: String,
    val paramsFingerprint: String,
    val basisEventId: String,
)

/** 事件（事件溯源 + 人工决定/撤销的统一历史）。 */
@Serializable
data class EventRecord(
    val id: String,
    val seq: Long,
    val at: String,
    val type: EventType,
    val submissionId: String? = null,
    val basisSubmissionIds: List<String> = emptyList(),
    val paramsFingerprint: String? = null,
    val algorithmVersion: String? = null,
    val payload: String = "{}",
    val supersedesEventId: String? = null,
    val decision: DecisionKind? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val reason: String = "",
)

@Serializable
enum class EventType {
    SUBMISSION_ACCEPTED,
    SOURCE_PATCHED,
    PARAMS_REVISED,
    ENGINE_DERIVED,
    DECISION_MADE,
    DECISION_REVOKED,
    PROJECT_RESET,
}

@Serializable
enum class DecisionKind { ACCEPT, REJECT, ADJUST_PARAMS }

/** 某次引擎派生快照（可被局部过期，但仍可查看）。 */
@Serializable
data class DerivedSnapshot(
    val id: String,
    val eventId: String,
    val basisEventId: String,
    val transactions: List<TransactionRecord>,
    val dialogs: List<DialogRecord>,
    val candidates: List<CandidateRecord>,
    val timers: List<TimerDecisionRecord>,
    val retransmits: List<RetransmitLink>,
    val stale: Boolean,
)

/** 重传关联（与某条首传消息的身份绑定，而非抹掉）。 */
@Serializable
data class RetransmitLink(
    val id: String,
    val originalMessageId: String,
    val retransmitMessageId: String,
    val transport: Transport,
    val connectionGeneration: Int,
    val elapsedMs: Long,
    val rationale: String,
    val algorithmVersion: String,
    val paramsFingerprint: String,
    val basisEventId: String,
)
