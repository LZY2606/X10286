package sipreview.model

import kotlinx.serialization.Serializable

const val ALGO_VERSION = "sip-dialog-engine-1.0.0"
const val FORMAT_VERSION = 1
const val TIME_BASIS = "virtual-clock:case-t0"

@Serializable
data class TimeCoord(val value: Long, val unit: String = "ms", val basis: String = TIME_BASIS)

@Serializable
data class Derivation(
    val algo: String,
    val algoVersion: String = ALGO_VERSION,
    val paramsFingerprint: String? = null,
    val sourceRefs: List<String>,
    val parentDerivedId: String? = null,
    val basis: String? = null,
)

@Serializable
data class ConnectionSpec(
    val connectionId: String,
    val transport: String,
    val generation: Int,
    val reconnectOf: String? = null,
    val local: String? = null,
    val remote: String? = null,
    val openedAt: TimeCoord,
    val closedAt: TimeCoord? = null,
)

// ---- immutable raw inputs ----

@Serializable
data class RawMessage(
    val rawId: String,
    val slotId: String,
    val batchId: String,
    val blobId: String,
    val dir: String,
    val connectionId: String,
    val generation: Int,
    val receivedAt: TimeCoord,
    val parentRawId: String? = null,
    val byteLength: Int,
)

@Serializable
data class SipParams(
    val paramsId: String,
    val parentParamsId: String? = null,
    val timerT1Ms: Long = 500,
    val timerT2Ms: Long = 4000,
    val timerT4Ms: Long = 5000,
    val timerAMaxDoublings: Int = 6,
    val retransmitToleranceRatio: Double = 0.30,
    val branchMagicCookie: String = "z9hG4bK",
    val note: String? = null,
) {
    fun fingerprint(): String = Params.fingerprint(this)
}

object Params {
    fun canonical(p: SipParams): String =
        listOf(
            p.timerT1Ms, p.timerT2Ms, p.timerT4Ms, p.timerAMaxDoublings,
            p.retransmitToleranceRatio, p.branchMagicCookie
        ).joinToString("|")
    fun fingerprint(p: SipParams): String = "pfp=" + Digest.sha256(canonical(p)).take(16)
    fun v1() = SipParams(paramsId = "params-v1")
}

// ---- derived parse facts ----

@Serializable
data class Via(val raw: String, val transport: String?, val sentBy: String?, val branch: String?, val rport: Boolean)

@Serializable
data class NameAddr(val raw: String, val uri: String?, val tag: String?)

@Serializable
data class RawNorm(val raw: String, val normalized: String?, val sourceRawId: String, val ordinal: Int)

@Serializable
data class ParseFact(
    val factId: String,
    val rawId: String,
    val kind: String,
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reason: String? = null,
    val viaTop: Via? = null,
    val callId: String? = null,
    val from: NameAddr? = null,
    val to: NameAddr? = null,
    val cseqNumber: Long? = null,
    val cseqMethod: String? = null,
    val recordRoutes: List<RawNorm> = emptyList(),
    val contact: NameAddr? = null,
    val headerBytes: Int = 0,
    val bodyBytes: Int = 0,
    val warnings: List<String> = emptyList(),
    val provenance: Derivation,
)

// ---- transactions ----

@Serializable
data class TxTransition(
    val from: String?,
    val to: String,
    val at: TimeCoord,
    val triggerRawId: String,
    val basis: String,
)

@Serializable
data class TxnView(
    val txnId: String,
    val role: String,
    val family: String,
    val branch: String?,
    val method: String,
    val state: String,
    val transitions: List<TxTransition>,
    val memberRawIds: List<String>,
    val callId: String?,
    val cseqNumber: Long?,
    val linkedTxnIds: List<String> = emptyList(),
    val stale: Boolean = false,
)

// ---- dialogs / routes ----

@Serializable
data class RouteEntry(
    val appliedIndex: Int,
    val rawOrdinal: Int,
    val raw: String,
    val normalizedUri: String?,
    val sourceRawId: String,
    val basis: String,
)

@Serializable
data class RouteSet(
    val dialogId: String,
    val ordered: List<RouteEntry>,
    val basis: String,
    val provenance: Derivation,
)

@Serializable
data class DialogGeneration(
    val generation: Int,
    val state: String,
    val fromRawId: String,
    val contact: String?,
    val at: TimeCoord,
)

@Serializable
data class DialogView(
    val dialogId: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val state: String,
    val generations: List<DialogGeneration>,
    val createdFromRawId: String,
    val routeSet: RouteSet? = null,
    val expectedAckRawIds: List<String> = emptyList(),
    val ackRawIds: List<String> = emptyList(),
    val conflict: Boolean = false,
)

// ---- candidates & decisions ----

object CandidateStatus {
    const val PROPOSED = "proposed"
    const val UNKNOWN = "unknown"
    const val ACCEPTED = "accepted"
    const val REJECTED = "rejected"
    const val ADJUSTED = "adjusted"
    const val CONFLICT = "conflict"
    const val STALE = "stale"
}

@Serializable
data class Candidate(
    val candidateId: String,
    val kind: String,
    val status: String,
    val summary: String,
    val subjectRef: String,
    val evidenceRefs: List<String>,
    val rejectReason: String? = null,
    val uncertainty: String? = null,
    val alternativeIds: List<String> = emptyList(),
    val basis: String? = null,
    val delta: DeltaInfo? = null,
    val decisionIds: List<String> = emptyList(),
    val supersededBy: String? = null,
    val stale: Boolean = false,
    val paramsFingerprint: String? = null,
)

@Serializable
data class DeltaInfo(val changedField: String, val from: String, val to: String)

@Serializable
data class Decision(
    val decisionId: String,
    val candidateId: String,
    val verdict: String,
    val note: String,
    val author: String,
    val at: TimeCoord,
    val overridesJson: String? = null,
    val resolvesConflict: Boolean = false,
    val eventId: String,
)

// ---- timers / diagnostics ----

@Serializable
data class TimerDecision(
    val timerId: String,
    val name: String,
    val txnId: String,
    val scheduled: List<TimeCoord>,
    val observedRawIds: List<String>,
    val explanation: String,
    val status: String,
    val basis: String,
    val provenance: Derivation,
    val stale: Boolean = false,
)

@Serializable
data class Diagnostic(
    val diagnosticId: String,
    val severity: String,
    val code: String,
    val message: String,
    val refs: List<String>,
)

// ---- counter-example archive ----

@Serializable
data class BaselineField(
    val rawId: String,
    val dir: String,
    val byteLength: Int,
    val bytesSha256: String,
    val viaBranch: String?,
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val cseq: String?,
    val recordRoutes: List<String>,
    val connectionId: String,
    val generation: Int,
    val receivedAt: TimeCoord,
)

@Serializable
data class ImpactScope(val added: List<String>, val removed: List<String>, val changed: List<String>, val unchanged: List<String>)

@Serializable
data class ExperimentRecord(
    val experimentId: String,
    val scenario: String,
    val principleKey: String,
    val directSourceRef: String,
    val changedField: String,
    val baseline: List<BaselineField>,
    val scope: ImpactScope,
    val adopted: Boolean,
    val rationale: String,
    val rejectedAlternative: String? = null,
    val candidateIds: List<String>,
    val judgmentBasis: String,
    val at: TimeCoord,
)

@Serializable
data class Snapshot(
    val seqHead: Long,
    val currentParamsId: String?,
    val rawMessages: List<RawMessage>,
    val connections: List<ConnectionSpec>,
    val facts: List<ParseFact>,
    val txns: List<TxnView>,
    val dialogs: List<DialogView>,
    val candidates: List<Candidate>,
    val decisions: List<Decision>,
    val timers: List<TimerDecision>,
    val diagnostics: List<Diagnostic>,
    val experiments: List<ExperimentRecord>,
)
