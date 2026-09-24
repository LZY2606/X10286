package sipatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Timer / analysis parameters. Every derived value records the fingerprint of the parameter set used. */
@Serializable
data class AlgoParams(
    val t1Ms: Long = 500,          // RTT estimate, virtual ms
    val t2Ms: Long = 4000,         // max retransmit interval for non-INVITE, virtual ms
    val t4Ms: Long = 5000,         // max time a message remains in the network, virtual ms
    val timerBMs: Long = 32000,    // INVITE client transaction timeout (64*T1)
    val timerDMs: Long = 32000,    // wait time for answer retransmissions (server non-INVITE)
    val late200WindowMs: Long = 32000, // window in which a late 2xx is still attributable
) {
    val fingerprint: String get() = sha256(json.encodeToString(serializer(), this)).take(16)

    companion object {
        const val ALGO_VERSION = "sip-atlas-engine/1.0.0"
        const val UNIT = "virtual-ms"
    }
}

enum class SourceKind { SIP_MESSAGE, CONNECTION_EVENT, CLOCK_RECORD }

/** One immutable version of a submitted source input. */
@Serializable
data class SourceVersion(
    val id: String,            // ver-<n>
    val lineage: String,       // src-<n>, stable across revisions
    val versionNo: Int,
    val parentId: String?,
    val direction: Direction?,
    val kind: SourceKind,
    val raw: String,           // original bytes / original representation, never overwritten
    val receivedAtMs: Long?,   // normalized virtual-ms; null when unconvertible (kept raw + diagnostic)
    val receivedRaw: String,   // original time representation as submitted
    val connId: String?,
    val connGeneration: Int?,
    val submittedSeq: Long,
)

@Serializable
data class Connection(
    val id: String,
    val generation: Int,
    val transport: String,     // UDP | TCP
    val openedSeq: Long,
    val closedSeq: Long? = null,
)

enum class CandidateStatus { PROPOSED, ACCEPTED, REJECTED, STALE, UNKNOWN }

enum class CandidateKind {
    TRANSACTION,        // a client/server transaction with its state trail
    TRANSACTION_STATE,  // one state transition of a transaction
    DIALOG,             // early or confirmed dialog
    ROUTE_SET,          // route set of a dialog
    TIMER_DECISION,     // timer-driven explanation (retransmit, timeout)
    MESSAGE_IDENTITY,   // dedup / identity assertion for a message
    DIAGNOSTIC,         // unconvertible value, kept in place with diagnosis
}

/** A derived result. First-class data: candidates, rejection reasons, unknown states. */
@Serializable
data class Candidate(
    val id: String,
    val runId: String,
    val kind: CandidateKind,
    val naturalKey: String,      // stable identity across runs, e.g. txn:branch:INVITE:server
    var status: CandidateStatus,
    val payload: String,         // JSON, kind-specific
    val algoVersion: String,
    val paramFp: String,
    val sourceIds: List<String>, // direct provenance
    val rejectionReason: String? = null,
    val validFromSeq: Long,
    var validToSeq: Long? = null, // null = still current
)

enum class DecisionAction { ACCEPT, REJECT, PARAM_ADJUST, UNDO }

@Serializable
data class Decision(
    val id: String,
    val targetId: String,        // candidate id or param id
    val action: DecisionAction,
    val rationale: String,
    val payload: String = "{}",
    val seq: Long,
    val conflictsWith: String? = null,
    val undoneBy: String? = null,
)

/** Machine-readable event. Every judgment the system makes is one of these. */
@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val kind: String,
    val payload: String,         // JSON
    val algoVersion: String?,
    val paramFp: String?,
    val vclock: Long,            // virtual clock at creation
)

/** Machine-readable judgment payloads — the domain rules as data. */
object Judgments {
    const val FORKING_MULTI_EARLY_DIALOG = "FORKING_MULTI_EARLY_DIALOG"
    const val CANCEL_ACK_SEPARATE_TRANSACTIONS = "CANCEL_ACK_SEPARATE_TRANSACTIONS"
    const val NON2XX_ACK_SAME_TRANSACTION = "NON2XX_ACK_SAME_TRANSACTION"
    const val UDP_RETRANSMIT_VIRTUAL_CLOCK = "UDP_RETRANSMIT_VIRTUAL_CLOCK"
    const val CONNECTION_REUSE_PRESERVES_IDENTITY = "CONNECTION_REUSE_PRESERVES_IDENTITY"
    const val LATE_200_STILL_CREATES_DIALOG = "LATE_200_STILL_CREATES_DIALOG"
    const val TAG_CONFLICT_UNRESOLVED = "TAG_CONFLICT_UNRESOLVED"
    const val CANCEL_RACE_200_ALREADY_SENT = "CANCEL_RACE_200_ALREADY_SENT"
}

@Serializable
data class TxnStateEntry(val state: String, val atMs: Long?, val causeSourceId: String, val note: String = "")

@Serializable
data class TransactionPayload(
    val txnKey: String,          // branch|method|side
    val side: String,            // client | server
    val method: String,
    val branch: String,
    val callId: String,
    val states: List<TxnStateEntry>,
    val terminal: Boolean,
    val relatedTxnKey: String? = null,  // e.g. CANCEL txn -> INVITE txn
    val judgment: String? = null,       // one of Judgments
)

@Serializable
data class DialogPayload(
    val dialogKey: String,       // callId|localTag|remoteTag
    val callId: String,
    val localTag: String?,
    val remoteTag: String?,
    val state: String,           // early | confirmed | terminated
    val establishedBy: String,   // source id of the response that created it
    val confirmedBy: String? = null,
    val routeSet: List<String> = emptyList(),
    val judgment: String? = null,
    val forkIndex: Int = 0,
)

@Serializable
data class TimerPayload(
    val timer: String,           // e.g. T1-retransmit, Timer-B
    val subjectKey: String,
    val expectedAtMs: Long?,
    val observedAtMs: Long?,
    val driftMs: Long?,
    val unit: String = AlgoParams.UNIT,
    val explanation: String,
    val judgment: String? = null,
)

@Serializable
data class IdentityPayload(
    val messageKey: String,      // branch|callId|cseqNum|cseqMethod
    val sightings: List<String>, // source version ids carrying this identity
    val connections: List<String>, // connId:generation sightings
    val judgment: String,
)

@Serializable
data class DiagnosticPayload(
    val subject: String,
    val rawValue: String,
    val problem: String,
)
