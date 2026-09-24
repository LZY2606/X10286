package sipreview.core

import kotlinx.serialization.Serializable

/** Algorithm version stamped on every derived value. Bump when computation changes. */
const val ALGO_VERSION = "sip-engine-1.3.0"

/** Coordinate base used for every virtual-clock instant in a project. */
const val CLOCK_BASE = "virtual-origin:0"
const val TIME_UNIT = "virtual-ms"
const val DURATION_UNIT = "ms"

// ---------------------------------------------------------------------------
// Input-side events (append only)
// ---------------------------------------------------------------------------

@Serializable
data class CaptureInput(
    /** Stable client-supplied id, unique within a project and preserved on export/import. */
    val clientId: String,
    /** Revision number within a lineage; 1 for first submission. */
    val rev: Int,
    val rootClientId: String,
    /** Previous revision clientId, or null for rev 1. */
    val parentClientId: String?,
    val direction: String, // "in" | "out"
    val rawBytesB64: String,
    val receivedAt: TimestampValue,
    val transport: String, // UDP | TCP | TLS | WS | WSS
    val connectionId: String?,
    val connectionGen: Int,
    val localAddress: Endpoint?,
    val remoteAddress: Endpoint?,
    val note: String = "",
)

@Serializable
data class TimestampValue(
    /** Original textual representation, never rewritten. */
    val raw: String,
    /** Unit declared by the importer: iso8601 | epoch-ms | relative-ms | unknown. */
    val unit: String,
    /** Coordinate base label, e.g. "capture-origin:t0" or "clock:utc". */
    val coordinateBase: String,
    /** Normalized virtual-clock milliseconds relative to the project origin; null when not convertible. */
    val vclockMs: Long?,
    val diagnostic: String? = null,
)

@Serializable
data class Endpoint(val host: String, val port: Int?) {
    override fun toString(): String = if (port != null) "$host:$port" else host
}

@Serializable
data class CaptureEvent(
    val eventId: String,
    val capture: CaptureInput,
    /** Parser diagnostics captured at ingest time; raw bytes are immutable afterwards. */
    val diagnostics: List<String>,
)

@Serializable
data class ParamRevisionEvent(
    val eventId: String,
    val paramSetId: String,
    val parentParamSetId: String?,
    val values: TimerParams,
    val note: String,
)

@Serializable
data class DecisionEvent(
    val eventId: String,
    val decisionId: String,
    /** candidate | conflict | dialog | transaction */
    val targetKind: String,
    val targetId: String,
    /** accepted | rejected | chosen */
    val verdict: String,
    val rationale: String,
    /** When this decision replaces an earlier one, the previous decisionId. */
    val supersedesDecisionId: String? = null,
    /** Free-form parameter adjustment, e.g. {"assumeLate2xx":"confirmed"}. */
    val adjustments: Map<String, String> = emptyMap(),
)

@Serializable
data class NoteEvent(
    val eventId: String,
    val targetKind: String,
    val targetId: String,
    val text: String,
)

@Serializable
data class EventEnvelope(
    val seq: Long,
    val eventId: String,
    /** Wall-clock ISO-8601 string at recording, for audit only. */
    val recordedAt: String,
    /** Virtual-clock ms at recording, unit [TIME_UNIT], base [CLOCK_BASE]. */
    val vclockMs: Long,
    val kind: String,
    val capture: CaptureEvent? = null,
    val paramRevision: ParamRevisionEvent? = null,
    val decision: DecisionEvent? = null,
    val note: NoteEvent? = null,
)

@Serializable
data class TimerParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val timerD_UdpMs: Long = 32_000,
    val timerD_TcpMs: Long = 0,
    val timerHMs: Long = 32_000,
    val timerI_T4Ms: Long = 5000,
    val timerJ_UdpMs: Long = 32_000,
    val timerJ_TcpMs: Long = 0,
    val timerK_UdpMs: Long = 5000,
    val timerK_TcpMs: Long = 0,
    /** Acceptable clock skew when comparing an observed retransmission with a timer. */
    val retransToleranceMs: Long = 250,
) {
    fun fingerprint(): String = Fingerprint.of(
        listOf(
            t1Ms, t2Ms, t4Ms, timerD_UdpMs, timerD_TcpMs, timerHMs, timerI_T4Ms,
            timerJ_UdpMs, timerJ_TcpMs, timerK_UdpMs, timerK_TcpMs, retransToleranceMs,
        ).joinToString(",")
    )
}

// ---------------------------------------------------------------------------
// Derived side
// ---------------------------------------------------------------------------

@Serializable
data class Provenance(
    val algoVersion: String = ALGO_VERSION,
    val paramSetId: String,
    val paramFingerprint: String,
    val sourceEventSeqs: List<Long>,
    val sourceCaptureIds: List<String>,
    val steps: List<DerivationStep>,
    val decisionIds: List<String> = emptyList(),
)

@Serializable
data class DerivationStep(
    val ordinal: Int,
    val rule: String,
    val detail: String,
    val refs: List<String> = emptyList(),
)

@Serializable
data class Candidate(
    val id: String,
    val kind: String,
    val summary: String,
    val confidence: String, // high | medium | low
    /** Why this candidate is (currently) rejected, when not chosen. */
    val rejectReason: String? = null,
    val chosen: Boolean = false,
)

@Serializable
data class CaptureView(
    val seq: Long,
    val clientId: String,
    val rev: Int,
    val rootClientId: String,
    val parentClientId: String?,
    val active: Boolean,
    val direction: String,
    val transport: String,
    val connectionId: String?,
    val connectionGen: Int,
    val local: Endpoint?,
    val remote: Endpoint?,
    val rawByteLength: Int,
    val rawBytesB64: String,
    val receivedRaw: String,
    val receivedUnit: String,
    val receivedBase: String,
    val vclockMs: Long?,
    val vclockUnit: String = TIME_UNIT,
    val vclockBase: String = CLOCK_BASE,
    val timeDiagnostic: String?,
    val diagnostics: List<String>,
    val kind: String?,
    val method: String?,
    val statusCode: Int?,
    val branch: String?,
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val recordRoute: List<String>,
    val route: List<String>,
    val contact: String?,
    val requestUri: String?,
    val note: String,
)

@Serializable
data class TransactionView(
    val id: String,
    val role: String, // client | server
    val invite: Boolean,
    val branch: String?,
    val transport: String,
    val method: String,
    val callId: String?,
    val state: String,
    val stale: Boolean,
    val openQuestion: String? = null,
    val captureSeqs: List<Long>,
    val members: List<String>,
    val candidates: List<Candidate>,
    val links: List<TxLink>,
    val timerTracks: List<TimerTrack>,
    val provenance: Provenance,
)

@Serializable
data class TxLink(
    val relation: String, // ack-in-same-txn | cancel-txn | ack-2xx-txn | response-to | dialog
    val otherTxnId: String?,
    val captureIds: List<String>,
    val reason: String,
)

@Serializable
data class TimerTrack(
    val timer: String,
    val state: String, // scheduled | fired | suppressed | observed-retransmission
    val dueAtMs: Long?,
    val durationMs: Long?,
    val durationUnit: String = DURATION_UNIT,
    val detail: String,
    val matchCaptureId: String? = null,
    val clockBase: String = CLOCK_BASE,
)

@Serializable
data class DialogView(
    val id: String,
    val phase: String, // early | confirmed | terminated | candidate | unknown
    val side: String, // uac | uas
    val callId: String?,
    val localTag: String?,
    val remoteTag: String?,
    val stale: Boolean,
    val openQuestion: String? = null,
    val createdByCapture: String?,
    val confirmedByCapture: String?,
    val routeSet: List<RouteEntry>,
    val remoteTarget: String?,
    val cseq: Long?,
    val forkedFrom: String? = null,
    val candidates: List<Candidate>,
    val members: List<String>,
    val provenance: Provenance,
)

@Serializable
data class RouteEntry(
    val ordinal: Int,
    val uri: String,
    val origin: String, // record-route(response) | record-route(request) | route(request)
    val direction: String,
    val captureId: String,
)

@Serializable
data class ConflictView(
    val id: String,
    val type: String, // tag-conflict | late-2xx | cancel-race | ack-ambiguous | retransmission-bytes | connection-identity
    val summary: String,
    val status: String, // open | resolved | stale
    val stale: Boolean,
    val involvedCaptures: List<String>,
    val involvedObjects: List<String>,
    val candidates: List<Candidate>,
    val resolutionDecisionId: String?,
    val provenance: Provenance,
)

@Serializable
data class InvalidInputView(
    val clientId: String,
    val rawByteLength: Int,
    val rawBytesB64: String,
    val receivedRaw: String,
    val errors: List<String>,
)

@Serializable
data class Snapshot(
    val projectId: String,
    val asOfSeq: Long,
    val current: Boolean,
    val algoVersion: String = ALGO_VERSION,
    val activeParamSetId: String?,
    val captures: List<CaptureView>,
    val transactions: List<TransactionView>,
    val dialogs: List<DialogView>,
    val conflicts: List<ConflictView>,
    val invalidInputs: List<InvalidInputView>,
    val invalidations: List<Invalidation>,
    val events: List<EventEnvelope>,
)

@Serializable
data class Invalidation(
    val changedLineage: String,
    val newRev: Int,
    val invalidatedObjectIds: List<String>,
    val unaffectedObjectIds: List<String>,
    val reason: String,
)

@Serializable
data class ImportReport(
    val accepted: Int,
    val rejected: Int,
    val capturedClientIds: List<String>,
    val errors: List<ImportError>,
)

@Serializable
data class ImportError(
    val index: Int,
    val clientId: String?,
    val code: String,
    val message: String,
    val fatal: Boolean,
)

@Serializable
data class ExportBundle(
    val format: String = "sip-review-export-1",
    val projectId: String,
    val exportedAt: String,
    val seed: Long,
    val events: List<EventEnvelope>,
    val invalidInputs: List<InvalidInputView> = emptyList(),
)
