package sipgsb

import kotlinx.serialization.Serializable

enum class Direction { C2S, S2C }

enum class Transport { UDP, TCP }

/** All domain quantities carry their unit/coordinate basis. Times are virtual-clock milliseconds. */
@Serializable
data class EngineParams(
    val t1Ms: Long = 500,          // RTT estimate, unit: virtual ms
    val t2Ms: Long = 4000,         // max retransmit interval for non-INVITE, virtual ms
    val t4Ms: Long = 5000,         // max linger, virtual ms
    val timerIMs: Long = 5000,     // server INVITE confirmed linger (Timer I), virtual ms
    val timerLMs: Long = 32000,    // late-2xx absorb window (64*T1), virtual ms
    val seed: Long = 42L           // fixed seed: deterministic behavior everywhere
) { companion object { const val ALGO_VERSION: String = "sipgsb-engine/1.4.0" } }

@Serializable
data class SipMessage(
    val isRequest: Boolean,
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val viaBranch: String? = null,
    val viaSentBy: String? = null,
    val viaTransport: String? = null,
    val callId: String? = null,
    val fromTag: String? = null,
    val toTag: String? = null,
    val cseqNum: Long? = null,
    val cseqMethod: String? = null,
    val recordRoute: List<String> = emptyList(),   // in received (on-the-wire) order
    val route: List<String> = emptyList(),
    val contact: String? = null,
    val startLine: String = ""
) {
    val cseqMethodUpper: String? get() = cseqMethod?.uppercase()
}

@Serializable
data class RawInput(
    val id: String,
    val version: Int,
    val parentId: String?,          // revision chain: originals have null
    val direction: Direction,
    val rawBytes: String,           // original wire bytes, never overwritten
    val receivedAtMs: Long,         // virtual clock, unit: ms
    val connectionId: String,
    val connectionGeneration: Int,  // transport connection generation
    val parsed: SipMessage?,
    val parseError: String?,
    val source: String              // provenance label (case pack file / manual entry)
)

@Serializable
data class Connection(
    val connectionId: String,
    val generation: Int,
    val transport: Transport,
    val openedAtMs: Long,
    val closedAtMs: Long? = null
)

enum class TxKind { INVITE, NON_INVITE, CANCEL, ACK_2XX }
enum class TxState { NONE, CALLING, TRYING, PROCEEDING, COMPLETED, CONFIRMED, ACCEPTED, TERMINATED }

@Serializable
data class TxTransition(val atMs: Long, val from: TxState, val to: TxState, val byMessageId: String, val note: String = "")

@Serializable
data class Transaction(
    val id: String,
    val kind: TxKind,
    val branch: String?,
    val cseqMethod: String?,
    val callId: String?,
    val clientState: TxState = TxState.NONE,
    val serverState: TxState = TxState.NONE,
    val clientHistory: List<TxTransition> = emptyList(),
    val serverHistory: List<TxTransition> = emptyList(),
    val messageIds: List<String> = emptyList(),      // attributed messages, in receive order
    val retransmissions: Map<String, Int> = emptyMap() // messageId -> extra copies absorbed
)

enum class DialogState { EARLY, CONFIRMED, TERMINATED }

@Serializable
data class Dialog(
    val id: String,
    val callId: String,
    val localTag: String,    // from the team's server perspective: to-tag of responses
    val remoteTag: String,   // from-tag of the UAC
    val state: DialogState,
    val routeSet: List<String> = emptyList(),  // as-received Record-Route order; coordinate basis noted in UI
    val establishedByMessageId: String,
    val confirmedByMessageId: String? = null,
    val forkedFromDialogId: String? = null,    // lineage: sibling early dialogs share the INVITE
    val inviteTransactionId: String? = null,
    val messageIds: List<String> = emptyList()
)

enum class CandidateStatus { OPEN, ACCEPTED, REJECTED, UNKNOWN, SUPERSEDED }

/** A first-class candidate: an ambiguity or anomaly the engine refuses to silently resolve. */
@Serializable
data class Candidate(
    val id: String,
    val kind: String,                 // forking | late_200 | cancel_race | tag_conflict | orphan_response | late_cancel
    val summary: String,
    val options: List<String>,        // legal interpretations
    val involvedMessageIds: List<String>,
    val involvedObjectIds: List<String>,
    val status: CandidateStatus = CandidateStatus.OPEN,
    val rejectReasons: Map<String, String> = emptyMap(), // option -> why rejected
    val createdAtMs: Long
)

@Serializable
data class Decision(
    val id: String,
    val candidateId: String,
    val action: String,               // accept:<option> | reject:<option> | adjust:<param>=<value>
    val rationale: String,
    val decidedBy: String,
    val eventSeq: Int,
    val undoneByEventSeq: Int? = null
)

/** Machine-readable judgment event: the four core rules and everything else the engine concludes. */
@Serializable
data class Judgment(
    val rule: String,                 // e.g. "forking-multi-early-dialog", "cancel-ack-distinct-tx",
                                      // "udp-retransmission-virtual-clock", "connection-reuse-keeps-identity"
    val summary: String,
    val objectIds: List<String>,
    val messageIds: List<String>,
    val detail: Map<String, String> = emptyMap()
)

@Serializable
data class TimerDecision(
    val id: String,
    val timer: String,                // e.g. "Timer L (late 2xx absorb)"
    val atMs: Long,
    val decision: String,
    val basis: String,                // which rule/param/virtual-clock reading justified it
    val messageIds: List<String> = emptyList()
)

@Serializable
data class DerivedValue(
    val id: String,
    val kind: String,                 // transaction | dialog | route_set | timer_explanation
    val objectId: String,
    val algoVersion: String,
    val paramsFingerprint: String,
    val sourceIds: List<String>,      // direct sources: raw input ids / upstream object ids
    val valueJson: String,
    val status: String = "CURRENT",   // CURRENT | STALE
    val eventSeq: Int
)

@Serializable
data class Event(
    val seq: Int,
    val type: String,
    val vtimeMs: Long,
    val payloadJson: String,
    val parentSeq: Int? = null
)
