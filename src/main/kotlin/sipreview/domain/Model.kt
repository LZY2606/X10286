package sipreview.domain

import kotlinx.serialization.Serializable

/** 传输连接代次记录（open/close/reset/reuse）。 */
@Serializable
data class RawConnEvent(
    val id: String? = null,
    val connectionId: String,
    val generation: Int,
    val kind: String, // open | close | reset | reuse
    val transport: String = "TCP",
    val at: String,
    val tsUnit: String = "virtual-seconds",
    val local: String? = null,
    val remote: String? = null,
    val note: String? = null
)

/** 时钟记录（虚拟时钟锚点/校时说明）。 */
@Serializable
data class RawClockAnchor(
    val id: String? = null,
    val at: String,
    val tsUnit: String = "virtual-seconds",
    val virtualEpochMs: Long? = null,
    val note: String? = null
)

/**
 * 导入的单条双向消息。raw 是提交时的原始表示，永不被覆盖；
 * encoding=text 时按 UTF-8 取字节，encoding=hex 时必须为偶数长度十六进制。
 */
@Serializable
data class RawMessageInput(
    val id: String? = null,
    val direction: String, // out：被观测 UA 发出；in：被观测 UA 收到
    val transport: String = "UDP", // UDP | TCP | TLS | WS | WSS
    val local: String? = null,
    val remote: String? = null,
    val connectionId: String? = null,
    val connectionGeneration: Int? = null,
    val receivedAt: String,
    val tsUnit: String = "virtual-seconds", // virtual-seconds | virtual-ms | iso8601 | unknown
    val encoding: String = "text", // text | hex
    val raw: String,
    val note: String? = null
)

/** 一次导入提交：原始输入一经提交不可修改，修订以新批次+父批次关系表达。 */
@Serializable
data class InputCommitPayload(
    val batchId: String,
    val messages: List<RawMessageInput> = emptyList(),
    val connections: List<RawConnEvent> = emptyList(),
    val clocks: List<RawClockAnchor> = emptyList(),
    val supersedesBatches: List<String> = emptyList(),
    val note: String? = null
)

/** RFC 3261 定时器参数（可被参数修订事件替换）。 */
@Serializable
data class EngineParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val timerBMultiplier: Long = 64,
    val timerDUdpMultiplier: Long = 64,
    val note: String? = null
)

@Serializable
sealed class HistoryEvent {
    abstract val eventId: String
    abstract val parentEventId: String?
    abstract val at: String
}

@Serializable
@SerialName("project")
data class ProjectCreated(
    override val eventId: String,
    override val parentEventId: String? = null,
    override val at: String,
    val projectId: String,
    val name: String,
    val seed: Long = 0L
) : HistoryEvent()

@Serializable
@SerialName("input")
data class InputCommitted(
    override val eventId: String,
    override val parentEventId: String?,
    override val at: String,
    val commit: InputCommitPayload
) : HistoryEvent()

@Serializable
@SerialName("params")
data class ParamsRevised(
    override val eventId: String,
    override val parentEventId: String?,
    override val at: String,
    val params: EngineParams,
    val reason: String? = null
) : HistoryEvent()

@Serializable
@SerialName("decision")
data class DecisionRecorded(
    override val eventId: String,
    override val parentEventId: String?,
    override val at: String,
    val candidateId: String,
    val verdict: String, // accept | reject | choose
    val targetFingerprint: String,
    val note: String? = null
) : HistoryEvent()

@Serializable
@SerialName("undo")
data class DecisionUndone(
    override val eventId: String,
    override val parentEventId: String?,
    override val at: String,
    val undoneEventId: String,
    val reason: String? = null
) : HistoryEvent()

/** 直接来源指针：任何派生值都能回溯到具体输入/事件/字段。 */
@Serializable
data class DirectSource(
    val kind: String, // unit | event | field | param | decision
    val id: String,
    val version: Int? = null,
    val field: String? = null
)

/** 规范化的一步（raw -> norm），使派生层变换可重放。 */
@Serializable
data class TransformStep(
    val step: String,
    val raw: String? = null,
    val norm: String? = null,
    val sources: List<DirectSource> = emptyList(),
    val diagnostic: String? = null
)

@Serializable
data class RouteEntry(
    val id: String,
    val index: Int,
    val raw: String,
    val normalized: String?,
    val sources: List<DirectSource>
)

@Serializable
data class ParsedView(
    val kind: String, // request | response
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reason: String?,
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val topViaBranch: String?,
    val topViaTransport: String?,
    val viaSentBy: String?,
    val contact: String?,
    val contentLength: Long?,
    val recordRoutes: List<RouteEntry>,
    val transformations: List<TransformStep>,
    val diagnostics: List<String>
)

@Serializable
data class MessageView(
    val unitId: String,
    val version: Int,
    val parentUnitId: String?,
    val batchId: String,
    val direction: String,
    val transport: String,
    val local: String?,
    val remote: String?,
    val connectionId: String?,
    val connectionGeneration: Int?,
    val receivedAtRaw: String,
    val tsUnit: String,
    val vtimeMs: Double?,
    val timeDiagnostic: String?,
    val encoding: String,
    val rawOriginal: String,
    val rawHex: String,
    val byteLength: Int,
    val note: String?,
    val parseStatus: String, // parsed | unparseable
    val parsed: ParsedView?,
    val sources: List<DirectSource>,
    val supersededByEvent: String? = null
)

@Serializable
data class ConnEventView(
    val unitId: String,
    val version: Int,
    val connectionId: String,
    val generation: Int,
    val kind: String,
    val transport: String,
    val atRaw: String,
    val vtimeMs: Double?,
    val local: String?,
    val remote: String?,
    val sources: List<DirectSource>
)

@Serializable
data class StateStep(
    val stepId: String,
    val state: String,
    val atMs: Double?,
    val label: String,
    val messageRefs: List<String>
)

@Serializable
data class TxView(
    val id: String,
    val role: String, // client | server
    val kind: String, // invite | noninvite | cancel | ack2xx
    val state: String,
    val stateHistory: List<StateStep>,
    val callId: String?,
    val branch: String?,
    val peerBranch: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val memberMessages: List<String>,
    val links: Map<String, String>, // cancels | ackFor | acked2xxFor | sameInviteLeg
    val candidateIds: List<String>,
    val rejectedIds: List<String>,
    val timerIds: List<String>,
    val diagnostics: List<String>,
    val sources: List<DirectSource>,
    val sourceFingerprint: String
)

@Serializable
data class DialogView(
    val id: String,
    val state: String, // early | confirmed | terminated
    val callId: String,
    val localTag: String?,
    val remoteTag: String?,
    val localTarget: String?,
    val remoteTarget: String?,
    val routeSetId: String?,
    val localCseq: Long?,
    val remoteCseq: Long?,
    val forkGroupId: String?,
    val parentDialogId: String?,
    val bornFromMessage: String?,
    val confirmedByMessage: String?,
    val endedByMessage: String?,
    val candidateIds: List<String>,
    val sources: List<DirectSource>,
    val sourceFingerprint: String
)

@Serializable
data class RouteSetView(
    val id: String,
    val dialogId: String,
    val direction: String, // uac-reversed | uas-as-is
    val entries: List<RouteEntry>,
    val sourceFingerprint: String
)

@Serializable
data class TimerView(
    val id: String,
    val ownerId: String,
    val name: String,
    val scheduledAtMs: Double,
    val fireAtMs: Double,
    val durationMs: Long,
    val durationUnit: String = "ms",
    val basis: String,
    val status: String, // scheduled | fired | cancelled | observed-match | observed-deviation
    val observedAtMs: Double? = null,
    val observedGapMs: Double? = null,
    val sources: List<DirectSource>,
    val sourceFingerprint: String
)

@Serializable
data class CandidateView(
    val id: String,
    val kind: String,
    val about: List<DirectSource>,
    val proposal: String,
    val rationale: String,
    val confidence: String, // high | medium | low | undecidable
    val status: String, // open | accepted | rejected
    val decidedByEvent: String? = null,
    val sources: List<DirectSource>,
    val sourceFingerprint: String,
    val rejectReason: String? = null
)

/** 机器可读判据事件：每个判断依据都有规则号、参数指纹与证据。 */
@Serializable
data class JudgementView(
    val id: String,
    val code: String,
    val severity: String, // info | warn | ambiguous
    val title: String,
    val detail: String,
    val ruleId: String,
    val about: List<DirectSource>,
    val evidence: List<DirectSource>,
    val candidateIds: List<String>,
    val atMs: Double?,
    val machine: Map<String, String>,
    val sourceFingerprint: String
)

@Serializable
data class DecisionView(
    val eventId: String,
    val candidateId: String,
    val verdict: String,
    val note: String?,
    val atEventSeq: Int,
    val active: Boolean,
    val stale: Boolean,
    val undoneBy: String?
)

@Serializable
data class EventSummary(
    val eventId: String,
    val seq: Int,
    val type: String,
    val parentEventId: String?,
    val at: String,
    val label: String
)

@Serializable
data class ProjectView(
    val projectId: String,
    val name: String,
    val seed: Long,
    val algoVersion: String,
    val paramFingerprint: String,
    val params: EngineParams,
    val atEventId: String,
    val atEventSeq: Int,
    val headEventId: String,
    val messages: List<MessageView>,
    val connections: List<ConnEventView>,
    val clocks: List<RawClockAnchor>,
    val transactions: List<TxView>,
    val dialogs: List<DialogView>,
    val routeSets: List<RouteSetView>,
    val timers: List<TimerView>,
    val candidates: List<CandidateView>,
    val judgements: List<JudgementView>,
    val decisions: List<DecisionView>,
    val history: List<EventSummary>
)

/** 导入校验错误。 */
@Serializable
data class ImportError(val path: String, val error: String)

/** 导出包：删除本地数据库后再导入，身份/顺序/来源关系保持。 */
@Serializable
data class ExportBundle(
    val format: String = "sip-review-export",
    val formatVersion: Int = 1,
    val projectId: String,
    val name: String,
    val seed: Long,
    val events: List<HistoryEvent>
)
