package sipreview.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TxRole { CLIENT, SERVER }

@Serializable
enum class TxKind { INVITE, NON_INVITE, CANCEL, ACK_NON2XX }

@Serializable
enum class TxState {
    CALLING, TRYING, PROCEEDING, COMPLETED, CONFIRMED, TERMINATED,
    GENERAL_PROCESSING,
    UNKNOWN
}

@Serializable
enum class DialogKind { EARLY, CONFIRMED, AMBIGUOUS }

@Serializable
data class TimerDecision(
    val name: String,
    val txId: String,
    val startedTick: Long,
    val durationTicks: Long,
    val firesTick: Long,
    val eventType: String,
    val canceledTick: Long? = null,
    val fired: Boolean = false,
    val directSource: String
)

@Serializable
data class TxTransition(
    val tick: Long,
    val from: TxState,
    val to: TxState,
    val trigger: String,
    val messageRecordId: String? = null,
    val note: String? = null
)

@Serializable
data class TxView(
    val txId: String,
    val role: TxRole,
    val kind: TxKind,
    val branch: String?,
    val method: String,
    val cseqNumber: Long?,
    val callId: String,
    val states: List<TxTransition>,
    val finalState: TxState,
    val memberRecordIds: List<String>,
    val timers: List<TimerDecision>,
    val connectionIds: List<String>,
    val directSource: Provenance,
    val note: String? = null
)

@Serializable
data class RouteEntry(val order: Int, val raw: String, val uri: SipUri?, val sourceRecordId: String)

@Serializable
data class ForkLeg(
    val remoteTag: String,
    val firstResponseRecordId: String,
    val earliestProvisionalRecordId: String?,
    val terminalRecordId: String?
)

@Serializable
data class DialogView(
    val dialogId: String,
    val kind: DialogKind,
    val callId: String,
    val localTag: String,
    val remoteTag: String?,
    val localUriRaw: String?,
    val remoteUriRaw: String?,
    val remoteTargetRaw: String?,
    val routeSet: List<RouteEntry>,
    val cseqNext: Long?,
    val parentDialogId: String?,
    val forkedLegs: List<ForkLeg>,
    val memberRecordIds: List<String>,
    val openedTick: Long?,
    val confirmedTick: Long?,
    val closedTick: Long?,
    val directSource: Provenance,
    val note: String? = null
)

@Serializable
data class RetransmissionView(
    val retransId: String,
    val originalRecordId: String,
    val duplicateRecordIds: List<String>,
    val role: TxRole,
    val expectedTick: Long?,
    val actualTick: Long,
    val explainedBy: String?,
    val directSource: Provenance
)

@Serializable
data class ReconnectView(
    val reconnectId: String,
    val connectionId: String,
    val priorConnectionId: String?,
    val generation: Int,
    val messageRecordIds: List<String>,
    val identityKeptRecordIds: List<String>,
    val directSource: Provenance
)

@Serializable
data class Assignment(
    val recordId: String,
    val txId: String,
    val matchedKey: String,
    val accepted: Boolean,
    val rejectReason: String? = null
)

@Serializable
enum class CandidateType {
    TX_ASSIGNMENT,
    FORKED_EARLY_DIALOG,
    LATE_2XX,
    TAG_CONFLICT,
    TIMER_AMBIGUITY,
    CONNECTION_REUSE
}

@Serializable
data class Candidate(
    val candidateId: String,
    val type: CandidateType,
    val subjectRecordIds: List<String>,
    val options: List<CandOption>,
    val rejectedOptions: List<RejectedOption>,
    val unresolved: Boolean,
    val verdictEventId: String? = null,
    val resolution: String? = null,
    val note: String? = null,
    val directSource: Provenance
)

@Serializable
data class CandOption(
    val optionId: String,
    val label: String,
    val targetId: String,
    val accepted: Boolean,
    val rationale: String,
    val evidenceRecordIds: List<String>
)

@Serializable
data class RejectedOption(
    val optionId: String,
    val label: String,
    val reason: String,
    val evidenceRecordIds: List<String>
)

@Serializable
data class Finding(
    val findingId: String,
    val code: String,
    val severity: String,
    val message: String,
    val recordIds: List<String>,
    val candidateId: String? = null,
    val directSource: Provenance
)

@Serializable
data class Provenance(
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val runId: String
)

@Serializable
enum class EventType {
    PROJECT_CREATED,
    INPUT_COMMITTED,
    PARAMS_REVISED,
    REPLAY,
    DECISION,
    DECISION_REVOKED,
    CONFLICT_DETECTED,
    CONFLICT_RESOLVED,
    PROJECT_EXPORTED,
    PROJECT_IMPORTED,
    NOTE
}

@Serializable
data class EventEnvelope(
    val eventId: String,
    val seq: Int,
    val type: EventType,
    val tick: Long,
    val versionId: String?,
    val parentEventId: String?,
    val payloadJson: String,
    val projectId: String
)

@Serializable
data class RunArtifact(
    val runId: String,
    val seq: Int,
    val atEventId: String,
    val paramsJson: String,
    val algorithmVersion: String,
    val paramFingerprint: String
)

@Serializable
data class StaleInfo(
    val isStale: Boolean,
    val supersededByRunId: String?,
    val staleReason: String?
)

@Serializable
data class Snapshot(
    val run: RunArtifact,
    val transactions: List<TxView>,
    val dialogs: List<DialogView>,
    val retransmissions: List<RetransmissionView>,
    val reconnects: List<ReconnectView>,
    val candidates: List<Candidate>,
    val findings: List<Finding>,
    val timerDecisions: List<TimerDecision>,
    val assignments: List<Assignment>,
    val stale: Map<String, StaleInfo>,
    val acceptedVersions: List<String>,
    val liveVersionId: String
)

@Serializable
data class EngineParams(
    val timerTick: String = "T1=500ms,T2=4000ms,T4=5000ms,TimerA=500ms,TimerB=32000ms,TimerD=32000ms,TimerE=500ms,TimerF=32000ms,TimerG=500ms,TimerH=32000ms,TimerI=5000ms,TimerJ=32000ms,TimerK=5000ms",
    val late2xxWindowTicks: Long = 64000,
    val tagConflictPolicy: String = "REPORT_ALL",
    val strictBranchPrefix: Boolean = true
)
