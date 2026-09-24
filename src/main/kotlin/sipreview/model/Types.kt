package sipreview.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@JvmInline
value class Direction(val wire: String) {
    init { require(wire == "IN" || wire == "OUT") { "direction must be IN|OUT, got $wire" } }
    val isIn: Boolean get() = wire == "IN"
    val isOut: Boolean get() = wire == "OUT"
    companion object { val IN = Direction("IN"); val OUT = Direction("OUT") }
}

@Serializable
enum class Transport { UDP, TCP, TLS }

@Serializable
enum class MaterialKind { MESSAGE, CONNECTION, CLOCK, SAMPLE_HEADER }

@Serializable
data class MaterialRecord(
    val uid: String,
    val projectId: String,
    val seq: Int,
    val kind: MaterialKind,
    val direction: String?,
    val rawBytesBase64: String,
    val rawText: String,
    val rawHash: String,
    val receivedAtMs: Long?,
    val receivedAtOriginal: String?,
    val timeUnit: String?,
    val connectionId: String?,
    val connectionGeneration: Int?,
    val parentUid: String?,
    val revisionReason: String?,
    val payload: JsonObject,
    val committedAtSeq: Int
)

@Serializable
data class ViaHeader(
    val raw: String,
    val transport: String?,
    val sentBy: String?,
    val branch: String?,
    val rport: String?,
    val received: String?,
    val viaIndex: Int
)

@Serializable
data class Diag(
    val code: String,
    val message: String,
    val severity: String = "ERROR",
    val field: String? = null,
    val rawValue: String? = null
)

@Serializable
data class ParsedMessage(
    val materialUid: String,
    val direction: String,
    val isRequest: Boolean,
    val method: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val startLine: String,
    val headers: JsonObject,
    val via: List<ViaHeader>,
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val fromUri: String?,
    val toUri: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val recordRoute: List<String>,
    val contact: String?,
    val maxForwards: Long?,
    val bodyLengthBytes: Int,
    val bodySdp: Boolean,
    val topViaBranch: String? = via.firstOrNull()?.branch,
    val connectionId: String? = null,
    val connectionGeneration: Int? = null,
    val receivedAtMs: Long? = null,
    val rawHash: String = "",
    val diagnostics: List<Diag>
)

@Serializable
enum class TxClass { INVITE, NON_INVITE }

@Serializable
enum class TxRole { CLIENT, SERVER }

@Serializable
enum class InviteTxState { CALLING, PROCEEDING, COMPLETED, CONFIRMED, TERMINATED, EARLY_ABANDONED }

@Serializable
enum class NonInviteTxState { TRYING, PROCEEDING, COMPLETED, TERMINATED }

@Serializable
data class TimerFire(
    val timer: String,
    val atMs: Long,
    val fired: Boolean,
    val matchedByMaterialUid: String?
)

@Serializable
data class TxView(
    val uid: String,
    val logicalKey: String,
    val role: TxRole,
    val txClass: TxClass,
    val method: String,
    val branch: String?,
    val callId: String?,
    val state: String,
    val requestUid: String?,
    val responseUids: List<String>,
    val ackUids: List<String>,
    val cancelTxUid: String?,
    val canceledInviteTxUid: String?,
    val connectionIds: List<String>,
    val timers: List<TimerFire>,
    val enteredAtMs: Long?,
    val terminatedAtMs: Long?,
    val unknownReasons: List<String>,
    val generationSeq: Int
)

@Serializable
data class DialogView(
    val uid: String,
    val logicalKey: String,
    val callId: String,
    val localTag: String?,
    val remoteTag: String?,
    val localUri: String?,
    val remoteUri: String?,
    val localTarget: String?,
    val remoteTarget: String?,
    val routeSet: List<String>,
    val phase: String,
    val createdByResponseUid: String?,
    val confirmedByResponseUid: String?,
    val invTxUids: List<String>,
    val forkGroupKey: String?,
    val forkIndex: Int?,
    val connectionId: String?,
    val cseqNext: Long?,
    val unknownReasons: List<String>,
    val generationSeq: Int
)

@Serializable
data class ConnectionView(
    val uid: String,
    val logicalKey: String,
    val connectionId: String,
    val generation: Int,
    val transport: String,
    val localEndpoint: String?,
    val remoteEndpoint: String?,
    val openedAtMs: Long?,
    val closedAtMs: Long?,
    val reusedAfter: String?,
    val messageUids: List<String>,
    val generationSeq: Int
)

@Serializable
data class RetransmissionView(
    val uid: String,
    val logicalKey: String,
    val originalUid: String,
    val duplicateUid: String,
    val role: TxRole,
    val txUid: String,
    val intervalMs: Long?,
    val expectedTimer: String?,
    val withinTolerance: Boolean?,
    val clockBasis: String,
    val generationSeq: Int
)

@Serializable
data class Candidate(
    val uid: String,
    val subjectUid: String,
    val subjectKind: String,
    val kind: String,
    val payload: JsonObject,
    val confidence: String,
    val rejectReason: String?,
    val rejected: Boolean,
    val sourceUids: List<String>,
    val basisEventUids: List<String>,
    val generationSeq: Int
)

@Serializable
data class UnknownState(
    val subjectUid: String,
    val subjectKind: String,
    val atMs: Long?,
    val reasons: List<String>,
    val legalCandidateUids: List<String>,
    val generationSeq: Int
)

@Serializable
enum class EventKind {
    MSG_RECEIVED,
    TX_CREATED, TX_RESPONSE_MATCH, TX_ACK_MATCH, TX_CANCEL_LINK,
    TX_STATE_CHANGED, TX_TIMER_SCHEDULED, TX_TIMER_FIRED, TX_RETRANSMISSION,
    TX_TERMINATED,
    DL_FORK_EARLY, DL_CREATED, DL_CONFIRMED, DL_ROUTE_SET,
    DL_TAG_CONFLICT, DL_CSEQ,
    CONN_OPENED, CONN_CLOSED, CONN_REUSE,
    CLASH,
    DERIVED_SUPERSEDED, DERIVED_CURRENT,
    PARAM_REVISION,
    HUMAN_DECISION, CONFLICT_RECORDED, DECISION_UNDONE,
    ARCHIVE_RUN, ARCHIVE_VERDICT,
    IMPORT_HEADER, DIAGNOSTIC
}

@Serializable
data class EventRecord(
    val uid: String,
    val projectId: String,
    val seq: Int,
    val kind: EventKind,
    val atMs: Long?,
    val subjectUid: String?,
    val subjectKind: String?,
    val generationSeq: Int?,
    val basisUids: List<String>,
    val sourceUids: List<String>,
    val algorithmVersion: String?,
    val paramFingerprint: String?,
    val payload: JsonObject,
    val supersededByUid: String? = null,
    val decisionTargetUid: String? = null
)

@Serializable
data class DecisionRecord(
    val uid: String,
    val projectId: String,
    val seq: Int,
    val targetUid: String,
    val action: String,
    val rationale: String,
    val paramSetVersion: String?,
    val undoneByUid: String?,
    val conflictsWithUid: String?,
    val conflictResolution: String?,
    val payload: JsonObject
)

@Serializable
data class ArchiveRun(
    val uid: String,
    val projectId: String,
    val seq: Int,
    val scenario: String,
    val mutation: String,
    val directSourceUid: String,
    val baselineFingerprint: String,
    val mutatedFingerprint: String,
    val affectedSubjectUids: List<String>,
    val unaffectedSubjectUids: List<String>,
    val expectedScopeUids: List<String>,
    val scopeCorrect: Boolean,
    val accepted: Boolean,
    val rationale: String,
    val basisEventUids: List<String>
)

@Serializable
data class Projection(
    val generationSeq: Int,
    val paramsVersion: String,
    val paramsFingerprint: String,
    val txs: List<TxView>,
    val dialogs: List<DialogView>,
    val connections: List<ConnectionView>,
    val retransmissions: List<RetransmissionView>,
    val candidates: List<Candidate>,
    val unknowns: List<UnknownState>,
    val messages: List<ParsedMessage>,
    val staleSubjects: List<String>,
    val materialUids: List<String>
)

@Serializable
data class ProjectInfo(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val casePackId: String?
)
