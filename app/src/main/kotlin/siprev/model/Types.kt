package siprev.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** Algorithm / derivation version. Bump when reconstruction semantics change. */
const val ALGO_VERSION = "1.0.0"
const val SCHEMA_VERSION = 1

val CanonicalJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

fun sha1(text: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun stableId(kind: String, vararg parts: String): String =
    kind + ":" + sha1(parts.joinToString("|")).substring(0, 16)

/** Canonical JSON used for fingerprints: object keys sorted recursively. */
fun canonicalize(element: JsonElement): String {
    fun render(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    sb.append(CanonicalJson.encodeToString(k))
                    sb.append(':')
                    render(v, sb)
                }
                sb.append('}')
            }
            is kotlinx.serialization.json.JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    render(v, sb)
                }
                sb.append(']')
            }
            else -> sb.append(CanonicalJson.encodeToString(JsonElement.serializer(), e))
        }
    }
    val sb = StringBuilder()
    render(element, sb)
    return sb.toString()
}

fun fingerprintOf(json: JsonElement): String = sha1(canonicalize(json))

enum class Direction { INBOUND, OUTBOUND }

enum class Transport { UDP, TCP }

enum class InputKind { SIP_MESSAGE, CONNECTION_EVENT, CLOCK_NOTE }

enum class ConnEventKind { OPEN, CLOSE }

enum class Severity { INFO, WARN, ERROR }

@Serializable
data class RawInput(
    val inputId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val session: String,
    val direction: Direction,
    val transport: Transport,
    val connId: String,
    val connGen: Int,
    val observedAtMicros: Long,
    val ordinal: Int = 0,
    val kind: InputKind,
    val payload: String,
    val note: String = ""
)

@Serializable
data class ViaHeader(
    val transport: String,
    val host: String,
    val port: Int? = null,
    val branch: String? = null,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class NameAddr(
    val display: String? = null,
    val uri: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class CSeq(val number: Long, val method: String)

@Serializable
data class SipMessage(
    val isRequest: Boolean,
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val vias: List<ViaHeader> = emptyList(),
    val from: NameAddr? = null,
    val to: NameAddr? = null,
    val callId: String? = null,
    val cseq: CSeq? = null,
    val recordRoutes: List<NameAddr> = emptyList(),
    val routes: List<NameAddr> = emptyList(),
    val contacts: List<NameAddr> = emptyList(),
    val maxForwards: Int? = null,
    val contentLength: Int = 0,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
    val parseErrors: List<String> = emptyList()
) {
    val topVia: ViaHeader? get() = vias.firstOrNull()
    val fromTag: String? get() = from?.params?.get("tag")
    val toTag: String? get() = to?.params?.get("tag")
    val isAck: Boolean get() = method == "ACK"
    val isCancel: Boolean get() = method == "CANCEL"
}

@Serializable
data class Params(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val timerBMs: Long = 32000,
    val timerFMs: Long = 32000,
    val timerDMs: Long = 32000,
    val timerJMs: Long = 32000,
    val timerKMs: Long = 5000,
    val timerIMs: Long = 5000,
    val timerHMs: Long = 32000,
    val timerAMs: Long = 500,
    val timerEMs: Long = 500,
    val timerGMs: Long = 500,
    val timerCms: Long = 180000,
    val maxRetransmissions: Int = 7,
    val udpDuplicateWindowMs: Long = 64000
) {
    fun fingerprint(): String =
        fingerprintOf(CanonicalJson.encodeToJsonElement(serializer(), this))
}

@Serializable
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val messageIds: List<String> = emptyList(),
    val artifactIds: List<String> = emptyList()
)

@Serializable
data class MessageRecord(
    val messageId: String,
    val inputId: String,
    val version: Int,
    val session: String,
    val direction: Direction,
    val transport: Transport,
    val connId: String,
    val connGen: Int,
    val observedAtMicros: Long,
    val ordinal: Int,
    val parsed: SipMessage,
    val rawBytes: String,
    val identityKey: String,
    val duplicateOf: String? = null,
    val branch: String? = null,
    val callId: String? = null,
    val fromTag: String? = null,
    val toTag: String? = null,
    val cseqNumber: Long? = null,
    val cseqMethod: String? = null
)

enum class TxKind { IST, NIST, ICT, NICT, ACK2XX_PSEUDO }

enum class TxState {
    CALLING, TRYING, PROCEEDING, COMPLETED, CONFIRMED, TERMINATED
}

@Serializable
data class TransactionRecord(
    val txId: String,
    val kind: TxKind,
    val method: String,
    val branch: String,
    val callId: String,
    val cseqNumber: Long,
    val side: Direction,
    val state: TxState,
    val stateHistory: List<TxTransition> = emptyList(),
    val messageIds: List<String> = emptyList(),
    val linkedTxIds: List<String> = emptyList(),
    val lastResponseCode: Int? = null,
    val terminatedAtMicros: Long? = null,
    val notes: List<String> = emptyList()
)

@Serializable
data class TxTransition(
    val from: TxState?,
    val to: TxState,
    val atMicros: Long,
    val causeMessageId: String? = null,
    val cause: String
)

enum class DialogState { EARLY, CONFIRMED, TERMINATED }

@Serializable
data class DialogRecord(
    val dialogId: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val side: Direction,
    val state: DialogState,
    val routeSet: List<String> = emptyList(),
    val remoteTarget: String? = null,
    val localCSeq: Long? = null,
    val remoteCSeq: Long? = null,
    val createdByMessageId: String,
    val confirmedByMessageId: String? = null,
    val terminatedByMessageId: String? = null,
    val messageIds: List<String> = emptyList(),
    val txIds: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)

enum class CandidateStatus { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED, UNKNOWN }

@Serializable
data class CandidateRecord(
    val candidateId: String,
    val kind: String,
    val subjectId: String,
    val payload: String,
    val status: CandidateStatus,
    val reason: String? = null,
    val sources: List<String> = emptyList()
)

@Serializable
data class TimerDecision(
    val timerId: String,
    val txId: String? = null,
    val dialogId: String? = null,
    val name: String,
    val scheduledAtMicros: Long,
    val firesAtMicros: Long,
    val intervalMs: Long,
    val fired: Boolean = false,
    val cancelledBy: String? = null,
    val causeMessageId: String? = null,
    val note: String = ""
)

@Serializable
data class ConnectionRecord(
    val connId: String,
    val generation: Int,
    val transport: Transport,
    val openedAtMicros: Long,
    val closedAtMicros: Long? = null,
    val messageIds: List<String> = emptyList()
)

@Serializable
data class Reconstruction(
    val algoVersion: String,
    val paramsFingerprint: String,
    val params: Params,
    val messages: List<MessageRecord> = emptyList(),
    val transactions: List<TransactionRecord> = emptyList(),
    val dialogs: List<DialogRecord> = emptyList(),
    val candidates: List<CandidateRecord> = emptyList(),
    val timers: List<TimerDecision> = emptyList(),
    val connections: List<ConnectionRecord> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList()
)

@Serializable
data class Judgment(
    val code: String,
    val expected: String,
    val actual: String,
    val pass: Boolean,
    val subjectIds: List<String> = emptyList(),
    val detail: String = ""
)

@Serializable
data class MutationSpec(
    val name: String,
    val description: String,
    val change: String,
    val expectScope: String,
    val judgments: List<Judgment> = emptyList(),
    val adopted: Boolean = false,
    val adoptedReason: String = ""
)

@Serializable
data class EventEnvelope(
    val eventId: Long,
    val projectId: String,
    val atMicros: Long,
    val actor: String,
    val type: String,
    val payload: JsonElement
)
