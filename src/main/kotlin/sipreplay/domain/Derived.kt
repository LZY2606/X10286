package sipreplay.domain

import kotlinx.serialization.Serializable

/** 算法版本与参数指纹：每个派生值都带着，可回到当时规则。 */
@Serializable
data class Provenance(
    val algoVersion: String,
    val paramFingerprint: String,
    val directSourceIds: List<String>,
    val derivedAtInputSeq: Long,
)

/** 拒绝原因（候选未被采纳时的一等数据）。 */
@Serializable
data class Rejection(
    val code: String,
    val detail: String,
    val evidenceMessageIds: List<String> = emptyList(),
)

/** 误差/未知状态：不让猜测伪装成确定结果。 */
@Serializable
data class Uncertainty(
    val code: String,
    val detail: String,
    val legalCandidates: List<String> = emptyList(),
    val requiresHumanDecision: Boolean = false,
)

/** 派生对象的生命周期：current / superseded（过期仍可看，不能冒充当前）。 */
@Serializable
enum class DerivedStatus { CURRENT, STALE, REJECTED }

/**
 * 规范化消息指纹（直接来源是原始消息本身）。
 * messageIdentity 跨重传保持稳定；每次出现仍是独立 RawMessage。
 */
@Serializable
data class MessageFingerprint(
    val fingerprintId: String,
    val messageId: String,
    val revisionId: String,
    val isRequest: Boolean,
    val method: String?,
    val statusCode: Int?,
    val callId: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val fromTag: String?,
    val toTag: String?,
    val topViaBranch: String?,
    val topViaSentBy: String?,
    val viaHopCount: Int,
    val identityKey: String,
    val bodyPresent: Boolean,
    val provenance: Provenance,
    val uncertainties: List<Uncertainty>,
)

/** 同一消息身份的一次出现（重传/多连接都保留）。 */
@Serializable
data class MessageOccurrence(
    val occurrenceId: String,
    val identityKey: String,
    val messageId: String,
    val direction: Direction,
    val connectionGenerationId: String,
    val receivedAt: VirtualInstant,
    val retransmissionOf: String?,
)

/** RFC 3261 定时器（值带单位 ms，采用虚拟时钟解释）。 */
@Serializable
data class TimerSpec(val name: String, val valueMs: Long, val rfc: String)

/** 事务种类。 */
@Serializable
enum class TransactionKind { INVITE_CLIENT, NON_INVITE_CLIENT, INVITE_SERVER, NON_INVITE_SERVER, ACK_2XX_PSEUDO }

/** 事务状态（ICT/ICT/NICT/NIST 合并枚举）。 */
@Serializable
enum class TxnState {
    ICT_CALLING, ICT_PROCEEDING, ICT_COMPLETED, ICT_TERMINATED,
    NICT_TRYING, NICT_PROCEEDING, NICT_COMPLETED, NICT_TERMINATED,
    IST_PROCEEDING, IST_COMPLETED, IST_CONFIRMED, IST_TERMINATED,
    NIST_TRYING, NIST_PROCEEDING, NIST_COMPLETED, NIST_TERMINATED,
    ACK2XX_ABSORBED,
    UNKNOWN;

    val label: String get() = name
}

/** 定时器决定：哪个时刻、哪个事务、触发了什么迁移。 */
@Serializable
data class TimerDecision(
    val decisionId: String,
    val transactionId: String,
    val timer: TimerSpec,
    val armedAt: VirtualInstant,
    val firesAt: VirtualInstant,
    val fired: Boolean,
    val effect: String,
    val canceledBy: String?,
    val provenance: Provenance,
)

/** 事务候选归属（CANCEL/ACK 归属判定可能有多个合法候选）。 */
@Serializable
data class TransactionCandidate(
    val candidateId: String,
    val transactionId: String,
    val scoreExplanation: Map<String, String>,
    val adopted: Boolean,
    val rejection: Rejection?,
)

/**
 * 重建出的事务。一个 Via branch + sent-by + method 标识一个 client transaction；
 * CANCEL 有自己的 branch，属于独立事务；2xx 的 ACK 无服务端事务，单独建模。
 */
@Serializable
data class ReconstructedTransaction(
    val transactionId: String,
    val kind: TransactionKind,
    val state: TxnState,
    val branch: String?,
    val viaSentBy: String?,
    val callId: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val viaMethod: String,
    val transport: Transport,
    val messageIds: List<String>,
    val candidates: List<TransactionCandidate>,
    val stateHistory: List<StateChange>,
    val timers: List<TimerDecision>,
    val relatedTransactionIds: List<String>,
    val status: DerivedStatus,
    val staleBecauseRevisionId: String?,
    val provenance: Provenance,
    val uncertainty: Uncertainty?,
)

/** 状态迁移事件（可回放）。 */
@Serializable
data class StateChange(
    val seq: Long,
    val at: VirtualInstant,
    val from: TxnState,
    val to: TxnState,
    val trigger: String,
    val evidenceMessageId: String?,
    val timerDecisionId: String?,
)

/** Dialog 生命周期阶段。 */
@Serializable
enum class DialogPhase { EARLY, CONFIRMED, TERMINATED, AMBIGUOUS }

/** 路由集条目（Record-Route/Route，顺序保留，原始文本保留）。 */
@Serializable
data class RouteSetEntry(
    val order: Int,
    val raw: String,
    val uri: String,
    val params: Map<String, String>,
    val fromHeader: String,
)

/**
 * Dialog（含 early dialog）。forking 时同一 Call-ID + From-tag + 不同 To-tag
 * 产生多个并列 early dialog；2xx 到达后其中一个（或多个）升级为 confirmed。
 */
@Serializable
data class ReconstructedDialog(
    val dialogId: String,
    val callId: String,
    val localTag: String?,
    val remoteTag: String?,
    val phase: DialogPhase,
    val parentDialogId: String?,
    val forkedFromMessageId: String?,
    val localSeq: Long?,
    val remoteSeq: Long?,
    val routeSet: List<RouteSetEntry>,
    val remoteTarget: String?,
    val establishedByMessageId: String?,
    val confirmedAt: VirtualInstant?,
    val stateHistory: List<DialogStateChange>,
    val transactionIds: List<String>,
    val status: DerivedStatus,
    val staleBecauseRevisionId: String?,
    val provenance: Provenance,
    val uncertainty: Uncertainty?,
)

@Serializable
data class DialogStateChange(
    val seq: Long,
    val at: VirtualInstant,
    val phase: DialogPhase,
    val trigger: String,
    val evidenceMessageId: String?,
)

/** tag 冲突：相同 (Call-ID,From-tag,To-tag) 被不一致的路由/contact 复用。 */
@Serializable
data class TagConflict(
    val conflictId: String,
    val dialogId: String,
    val at: VirtualInstant,
    val detail: String,
    val evidenceMessageIds: List<String>,
    val resolution: String?,
    val resolutionEventId: String?,
)

/** 人工裁定。 */
@Serializable
enum class DecisionKind { ACCEPT, REJECT, ADJUST_PARAMS, UNDECIDED }

@Serializable
data class HumanDecision(
    val eventId: String,
    val at: VirtualInstant,
    val kind: DecisionKind,
    val targetType: String,
    val targetId: String,
    val chosenCandidateId: String?,
    val params: Map<String, String>,
    val undoOfEventId: String?,
    val reason: String,
    val basedOnEventId: String?,
)
