package sipreview.domain

import kotlinx.serialization.Serializable

const val ALGO_VERSION = "sip-atlas-1.0.0"

@Serializable
enum class Direction { IN, OUT }

@Serializable
enum class Transport { UDP, TCP }

@Serializable
enum class RecordKind { MESSAGE, CONNECTION, CLOCK }

@Serializable
enum class SourceState { ACTIVE, SUPERSEDED, DRAFT }

/** A direction-tagged transport connection generation. identity = (transport, peer coordinate, generation). */
@Serializable
data class ConnectionFact(
    val transport: Transport,
    val localCoordinate: String,
    val peerCoordinate: String,
    val generation: Int,
    val openedAtMs: Long,
    val closedAtMs: Long? = null,
)

/** Virtual clock anchor: all receivedAtMs values are milliseconds on this clock basis. */
@Serializable
data class ClockFact(
    val clockId: String,
    val basis: String = "virtual-clock-ms",
    val epochAnchorIso: String? = null,
    val startAtMs: Long = 0L,
)

@Serializable
data class ImportRecord(
    val id: String? = null,
    val kind: RecordKind,
    val direction: Direction? = null,
    /** Virtual-clock receive/send time in ms (unit encoded in ClockFact.basis). */
    val atMs: Long,
    val connectionId: String? = null,
    /** Original SIP bytes as hex, preserved verbatim (CRLF included). */
    val rawHex: String? = null,
    /** Original textual representation for non-SIP rows, preserved verbatim. */
    val rawText: String? = null,
    val connection: ConnectionFact? = null,
    val clock: ClockFact? = null,
    val note: String? = null,
)

@Serializable
data class ImportRequest(val batchLabel: String, val records: List<ImportRecord>)

@Serializable
data class ImportError(val index: Int, val code: String, val message: String)

@Serializable
data class ImportResult(
    val eventId: String,
    val saved: List<String>,
    val rejected: List<ImportError>,
)

@Serializable
data class HeaderLine(val name: String, val rawValue: String)

@Serializable
data class ParsedMessage(
    val recordId: String,
    val direction: Direction,
    val atMs: Long,
    val connectionId: String?,
    val rawHex: String,
    val byteLength: Int,
    val isRequest: Boolean,
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val callId: String? = null,
    val cseqNumber: Long? = null,
    val cseqMethod: String? = null,
    val fromTag: String? = null,
    val toTag: String? = null,
    val fromUri: String? = null,
    val toUri: String? = null,
    val viaBranch: String? = null,
    val viaTop: String? = null,
    val recordRoutes: List<String> = emptyList(),
    val contact: String? = null,
    val headers: List<HeaderLine> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)

@Serializable
enum class TxnRole { CLIENT, SERVER }

@Serializable
enum class TxnKind { INVITE, NON_INVITE, CANCEL, ACK_2XX, ACK_NON2XX }

@Serializable
enum class TxnState {
    PENDING, CALLING, PROCEEDING, COMPLETED, CONFIRMED, TERMINATED,
    TRYING, CANCEL_SENT, CANCEL_RACE, LATE_FINAL,
}

@Serializable
data class TxnStep(
    val atMs: Long,
    val state: TxnState,
    val why: String,
    val sourceRecordIds: List<String>,
)

@Serializable
data class TxnView(
    val id: String,
    val role: TxnRole,
    val kind: TxnKind,
    val branch: String?,
    val callId: String?,
    val cseqNumber: Long?,
    val method: String,
    val transport: Transport,
    val connectionIds: List<String>,
    val steps: List<TxnStep>,
    val state: TxnState,
    val linkedInviteTxnId: String? = null,
    val directSources: List<String>,
    val algoVersion: String = ALGO_VERSION,
    val paramsFingerprint: String,
)

@Serializable
enum class DialogState { EARLY, CONFIRMED, TERMINATED, AMBIGUOUS }

@Serializable
data class RouteHop(val raw: String, val order: Int)

@Serializable
data class RouteSetView(
    val id: String,
    val dialogId: String,
    /** On-the-wire order exactly as seen in Record-Route. */
    val wireOrder: List<RouteHop>,
    /** Normalized request-target order (reversed per RFC 3261 12.1.2) plus remote Contact. */
    val normalizedOrder: List<RouteHop>,
    val remoteContact: String?,
    val directSources: List<String>,
)

@Serializable
data class DialogView(
    val id: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String?,
    val state: DialogState,
    val forkIndex: Int?,
    val parentDialogId: String?,
    val inviteTxnId: String?,
    val firstRecordId: String,
    val establishedAtMs: Long?,
    val routeSetId: String?,
    val flags: List<String>,
    val directSources: List<String>,
    val algoVersion: String = ALGO_VERSION,
    val paramsFingerprint: String,
)

@Serializable
enum class TimerVerdict {
    SCHEDULED, FIRED_EXPLAINED, FIRED_UNEXPECTED_TIME, OBSERVED_WITHOUT_TIMER, CANCELED_BEFORE_FIRE
}

@Serializable
data class TimerDecision(
    val id: String,
    val txnId: String,
    val timerName: String,
    val scheduledAtMs: Long?,
    val intervalMs: Long?,
    val observedAtMs: Long?,
    val observedRecordId: String?,
    val verdict: TimerVerdict,
    val explanation: String,
    val timeBasis: String = "virtual-clock-ms",
    val directSources: List<String>,
)

@Serializable
enum class CandidateStatus { PROPOSED, ACCEPTED, REJECTED, CONFLICTED, RESOLVED }

@Serializable
data class CandidateView(
    val id: String,
    val kind: String,
    val status: CandidateStatus,
    val summary: String,
    val objectIds: List<String>,
    val rejectReasons: List<String>,
    val errorMarginMs: Long?,
    val unknown: Boolean,
    val directSources: List<String>,
    val decisionEventIds: List<String> = emptyList(),
)

@Serializable
enum class FindingClass {
    FORK_EARLY_DIALOGS,
    LATE_2XX,
    CANCEL_RACE,
    NON2XX_ACK_SAME_TXN,
    ACK_2XX_NO_TXN,
    UDP_RETRANSMISSION,
    TCP_RECONNECT,
    TAG_CONFLICT,
    UNPARSEABLE_INPUT,
    UNCONVERTIBLE_VALUE,
}

@Serializable
data class Finding(
    val id: String,
    val clazz: FindingClass,
    val summary: String,
    val adopted: Boolean,
    val adoptedReason: String?,
    val objectIds: List<String>,
    val directSources: List<String>,
    val machineEventCode: String,
)

@Serializable
data class IdentityFact(
    val id: String,
    val recordId: String,
    val direction: Direction,
    val field: String,
    val value: String,
    val coordinateBasis: String,
)

/** Machine-readable judgment event backing the four canonical SIP routing statements. */
@Serializable
data class JudgmentEvent(
    val code: String,
    val statement: String,
    val holds: Boolean,
    val evidenceObjectIds: List<String>,
    val evidenceRecordIds: List<String>,
    val detail: String,
)

@Serializable
data class WorldView(
    val projectId: String,
    val eventSeq: Int,
    val eventId: String,
    val isCurrent: Boolean,
    val algoVersion: String,
    val paramsFingerprint: String,
    val messages: List<ParsedMessage>,
    val connections: List<ConnectionFact>,
    val clocks: List<ClockFact>,
    val transactions: List<TxnView>,
    val dialogs: List<DialogView>,
    val routeSets: List<RouteSetView>,
    val timers: List<TimerDecision>,
    val candidates: List<CandidateView>,
    val findings: List<Finding>,
    val identityFacts: List<IdentityFact>,
    val judgments: List<JudgmentEvent>,
    val diagnostics: List<String>,
)
