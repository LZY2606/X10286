package org.sipreview.model

import kotlinx.serialization.Serializable

/** Transport direction of a captured message, from the *local observer's* point of view. */
@Serializable
enum class Direction { INBOUND, OUTBOUND }

@Serializable
enum class Transport { UDP, TCP, TLS }

/**
 * A transport connection as seen by the observer. UDP uses one logical connection per
 * 5-tuple with generation always 1; TCP/TLS may have multiple generations (reconnects).
 */
@Serializable
data class ConnectionRecord(
    val id: String,
    val transport: Transport,
    val localAddr: String,
    val localPort: Int,
    val remoteAddr: String,
    val remotePort: Int,
    val generation: Int,
    val connectedAtMs: Long?,
    val closedAtMs: Long?,
    val sourceRefs: List<String>,
)

@Serializable
data class RawFrame(
    val id: String,
    val versionId: String,
    val direction: Direction,
    val connectionId: String,
    val connectionGeneration: Int,
    /** Virtual-clock receive time, milliseconds on the project's shared clock. */
    val receivedAtMs: Long,
    /** Original representation of the timestamp exactly as authored. */
    val receivedAtRaw: String,
    val seq: Int,
    /** Original message bytes, verbatim (CRLF preserved), base64 in JSON. */
    val bytesBase64: String,
    val byteLength: Int,
    val sha256: String,
    /** Parser diagnostics; a frame with fatal=true is kept but never parsed. */
    val diagnostics: List<String>,
)

@Serializable
data class ViaEntry(
    val raw: String,
    val protocol: String,
    val sentBy: String,
    val branch: String?,
    val rport: String?,
    val received: String?,
)

@Serializable
data class ParsedMessage(
    val frameId: String,
    val isValid: Boolean,
    val isRequest: Boolean,
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val via: List<ViaEntry>,
    val topBranch: String?,
    val callId: String?,
    val fromTag: String?,
    val fromUri: String?,
    val toTag: String?,
    val toUri: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val recordRoute: List<String>,
    val contact: String?,
    val maxForwards: Int?,
    val contentLength: Int?,
    val bodyLength: Int,
    val parseDiagnostics: List<String>,
) {
    val transactionKey: String? get() = topBranch
}

/** Which side of the transaction the observer reconstructs. */
@Serializable
enum class TransactionRole { CLIENT, SERVER }

@Serializable
enum class TransactionKind { INVITE, NON_INVITE }

@Serializable
enum class TxnState {
    /* RFC 3261 17.1.1 ICT */
    ICT_CALLING, ICT_PROCEEDING, ICT_COMPLETED, ICT_TERMINATED,
    /* RFC 3261 17.2.1 IST */
    IST_PROCEEDING, IST_COMPLETED, IST_CONFIRMED, IST_TERMINATED,
    /* RFC 3261 17.1.2 NICT */
    NICT_TRYING, NICT_PROCEEDING, NICT_COMPLETED, NICT_TERMINATED,
    /* RFC 3261 17.2.2 NIST */
    NIST_TRYING, NIST_PROCEEDING, NIST_COMPLETED, NIST_TERMINATED,
    UNKNOWN,
}

@Serializable
data class StateTransition(
    val atMs: Long,
    val fromState: TxnState,
    val toState: TxnState,
    val triggerFrameId: String,
    val explanation: String,
)

@Serializable
data class TimerDecision(
    val name: String,
    val armAtMs: Long?,
    val fireAtMs: Long?,
    val durationMs: Long?,
    val cancelledAtMs: Long?,
    val fired: Boolean,
    val sourceFrameId: String?,
    val explanation: String,
)

@Serializable
data class Rejection(val code: String, val message: String, val evidenceFrameIds: List<String>)

@Serializable
data class TxnCandidate(
    val id: String,
    val frameId: String,
    val matchedTransactionId: String,
    val confidence: Double,
    val rejected: Rejection?,
)

@Serializable
data class TransactionView(
    val id: String,
    val stableKey: String,
    val branch: String?,
    val method: String,
    val kind: TransactionKind,
    val role: TransactionRole,
    val callId: String?,
    val states: List<StateTransition>,
    val currentState: TxnState,
    val timers: List<TimerDecision>,
    val requestFrameIds: List<String>,
    val responseFrameIds: List<String>,
    val retransmittedRequestFrameIds: List<String>,
    val ackFrameIds: List<String>,
    val cancelTxnIds: List<String>,
    val pairedTxnId: String?,
    val candidates: List<TxnCandidate>,
    val notes: List<String>,
    val validUntilEventSeq: Int?,
)

@Serializable
enum class DialogState { EARLY, CONFIRMED, TERMINATED, AMBIGUOUS }

@Serializable
data class DialogTransition(
    val atMs: Long,
    val fromState: DialogState?,
    val toState: DialogState,
    val triggerFrameId: String,
    val explanation: String,
)

@Serializable
data class RouteSetChange(
    val atMs: Long,
    val frameId: String,
    val before: List<String>,
    val after: List<String>,
    val explanation: String,
)

@Serializable
data class DialogView(
    val id: String,
    val callId: String,
    val localTag: String?,
    val remoteTag: String?,
    val localUri: String?,
    val remoteUri: String?,
    val targetUri: String?,
    val state: DialogState,
    val transitions: List<DialogTransition>,
    val routeSet: List<String>,
    val routeSetHistory: List<RouteSetChange>,
    val establishedByFrameId: String?,
    val earlyProvisionalFrameIds: List<String>,
    val confirming2xxFrameIds: List<String>,
    val cseqTracked: List<Long>,
    val parentDialogId: String?,
    val forkedFromTxnId: String?,
    val candidateTagConflicts: List<String>,
    val ambiguity: Rejection?,
    val validUntilEventSeq: Int?,
)

@Serializable
data class Finding(
    val code: String,
    val severity: String,
    val message: String,
    val evidenceFrameIds: List<String>,
    val objectIds: List<String>,
)

/** Provenance attached to every derived value. */
@Serializable
data class Provenance(
    val algorithm: String,
    val algorithmVersion: String,
    val paramsFingerprint: String,
    val directSources: List<String>,
    val derivedAtEventSeq: Int,
)

@Serializable
data class EngineResult(
    val engineVersion: String,
    val paramsFingerprint: String,
    val atEventSeq: Int,
    val parsed: List<ParsedMessage>,
    val transactions: List<TransactionView>,
    val dialogs: List<DialogView>,
    val findings: List<Finding>,
    val provenanceByObject: Map<String, Provenance>,
)

@Serializable
enum class EventType {
    PROJECT_CREATED,
    INPUT_COMMITTED,
    FRAME_RECORDED,
    CONNECTION_RECORDED,
    PARAMS_REVISED,
    RECOMPUTED,
    CANDIDATE_ACCEPTED,
    CANDIDATE_REJECTED,
    DECISION_MADE,
    DECISION_WITHDRAWN,
    CONFLICT_RECORDED,
    ARCHIVE_BASELINE_SAVED,
    ARCHIVE_MUTATION_RUN,
}

@Serializable
data class ProjectEvent(
    val seq: Int,
    val id: String,
    val type: EventType,
    val atMs: Long,
    val parentEventId: String?,
    val payloadJson: String,
    val summary: String,
)

@Serializable
data class TimerParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val timerALimitMs: Long = 64 * 500L,
    val timerBMs: Long = 64 * 500L,
    val timerDMultiplier: Long = 64,
    val timerHMs: Long = 64 * 500L,
    val timerGMs: Long = 32 * 500L,
    val timerIMs: Long = 5000,
    val algorithmVersion: String = "sip-engine-1.0.0",
)

@Serializable
data class HumanDecision(
    val id: String,
    val atEventSeq: Int,
    val kind: String, // ACCEPT | REJECT | PARAMETER_CHANGE | OVERRIDE
    val targetObjectId: String,
    val chosenValueJson: String?,
    val reason: String,
    val withdrawn: Boolean = false,
    val conflictsWithDecisionId: String? = null,
)

@Serializable
data class CounterexampleCase(
    val id: String,
    val title: String,
    val scenarioCode: String,
    val baselineEventSeq: Int,
    val mutationDescription: String,
    val directSourceChanged: String?,
    val adopted: Boolean,
    val verdict: String,
    val machineEvents: List<ArchiveEvent>,
)

@Serializable
data class ArchiveEvent(
    val code: String,
    val atMs: Long,
    val objectIds: List<String>,
    val judgment: String,
    val evidence: String,
)
