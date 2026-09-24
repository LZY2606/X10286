package app.core

import app.sip.Sip
import app.sip.SipMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFmt = Json { prettyPrint = false; ignoreUnknownKeys = true; classDiscriminator = "t" }

/** A domain quantity: normalized value + unit + coordinate basis + original representation. */
@Serializable
data class Qty(
    val valueMs: Long?,
    val unit: String = "ms",
    val basis: String = "virtual-clock",
    val original: String,
    val diagnostic: String? = null,
)

/** Algorithm parameters. Any change creates a new fingerprint on all derived values. */
@Serializable
data class Params(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val tdMs: Long = 32000,
    val retransToleranceMs: Long = 120,
    val algoVersion: String = "tx-dialog/1.3.0",
) {
    fun fingerprint(): String = Sip.sha1(JsonFmt.encodeToString(serializer(), this))
}

enum class Direction { C2S, S2C }

enum class StreamKind { TRACE, CONNECTIONS, CLOCK }

/** One committed, immutable version of a source stream. */
@Serializable
data class StreamVersion(
    val streamId: String,
    val kind: StreamKind,
    val version: Int,
    val parentVersion: Int?,
    val content: String,
    val note: String = "",
) { val id: String get() = "sv-$streamId-v$version" }

// ---------------- Events ----------------

@Serializable
sealed interface Event {
    val id: String
    val atVirtualMs: Long
}

@Serializable
@kotlinx.serialization.SerialName("project")
data class ProjectCreated(override val id: String, override val atVirtualMs: Long, val name: String) : Event

@Serializable
@kotlinx.serialization.SerialName("stream")
data class StreamCommitted(
    override val id: String, override val atVirtualMs: Long, val sv: StreamVersion,
) : Event

@Serializable
@kotlinx.serialization.SerialName("params")
data class ParamsRevised(
    override val id: String, override val atVirtualMs: Long, val params: Params, val reason: String,
) : Event

enum class DecisionAction { ACCEPT, REJECT, ADJUST }

@Serializable
@kotlinx.serialization.SerialName("decision")
data class DecisionMade(
    override val id: String, override val atVirtualMs: Long,
    val targetKind: String, val targetId: String,
    val action: DecisionAction, val candidateId: String? = null,
    val note: String = "", val author: String = "reviewer",
) : Event

@Serializable
@kotlinx.serialization.SerialName("revert")
data class DecisionReverted(
    override val id: String, override val atVirtualMs: Long,
    val decisionId: String, val note: String = "",
) : Event

// ---------------- Ingested records ----------------

/** A message as received: raw bytes + direction + connection generation + receive time. */
@Serializable
data class MessageRec(
    val id: String,               // stable: m-<stream>-v<ver>-<idx>
    val streamId: String,
    val streamVersion: Int,
    val index: Int,
    val direction: Direction,
    val connId: String?,
    val connGeneration: Int?,
    val receivedAt: Qty,          // original representation preserved inside
    val raw: String,              // original datagram/segment bytes, never overwritten
    val parsed: SipMessage? = null,
    val diagnostic: String? = null, // set when parsing failed; record stays in place
) {
    val fingerprint: String? get() = parsed?.fingerprint()
}

@Serializable
data class ConnectionRec(
    val connId: String, val generation: Int, val transport: String,
    val local: String, val remote: String,
    val openedAt: Qty, val closedAt: Qty?,
)

// ---------------- Derived objects ----------------

enum class DerivedKind { TRANSACTION, DIALOG, ROUTE_SET, TIMER, CLASSIFICATION, CANDIDATE_SET }

@Serializable
data class Derived(
    val id: String,
    val kind: DerivedKind,
    val algoVersion: String,
    val paramFingerprint: String,
    val sources: List<String>,      // direct source ids: messages, stream versions, decisions
    val payload: String,            // JSON payload specific to kind
    val label: String,
)

enum class TxKind { INVITE_CLIENT, NONINVITE_CLIENT, INVITE_SERVER, NONINVITE_SERVER, ACK2XX }

@Serializable
data class TxPayload(
    val key: String,              // branch + cseq method
    val branch: String,
    val method: String,
    val kind: TxKind,
    val state: String,            // Calling/Trying/Proceeding/Completed/Confirmed/Terminated
    val transport: String,
    val messageIds: List<String>,
    val absorbedIds: List<String>, // retransmissions / ACKs absorbed
    val transitions: List<TxTransition>,
    val cancelTarget: String? = null, // for CANCEL tx: key of the INVITE tx it cancels
)

@Serializable
data class TxTransition(val from: String, val to: String, val byMessageId: String, val atMs: Long?, val note: String = "")

@Serializable
data class DialogPayload(
    val callId: String, val localTag: String, val remoteTag: String,
    val state: String,            // early | confirmed | terminated
    val forkedFrom: String?,      // dialog id this forked from (shared call-id+from-tag)
    val messageIds: List<String>,
    val decidedBy: String? = null, // decision id if human-confirmed
)

@Serializable
data class RouteSetPayload(val dialogId: String, val routes: List<String>, val remoteTarget: String?)

@Serializable
data class TimerPayload(
    val txId: String, val timer: String,   // A/B/D/E/G/H/I/J/K style label
    val expectedAtMs: Long?, val observedAtMs: Long?,
    val withinTolerance: Boolean?, val basis: String, val note: String,
)

@Serializable
data class ClassPayload(
    val messageId: String,
    val classification: String,   // original|retransmission|reconnect-replay|non2xx-ack|ack-2xx|late-2xx|orphan|unparsed
    val note: String,
)

@Serializable
data class Candidate(val id: String, val label: String, val rationale: String)

@Serializable
data class CandidateSetPayload(
    val subject: String,          // human-readable subject
    val scenario: String,         // forking|late-200|cancel-race|tag-conflict|orphan
    val options: List<Candidate>,
    val status: String,           // OPEN | RESOLVED | UNDECIDABLE
    val chosenId: String? = null,
    val resolvedBy: String? = null,
)
