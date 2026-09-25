package sipgsb.domain

import kotlinx.serialization.Serializable
import sipgsb.sip.Direction
import sipgsb.sip.Transport

const val ALGO_VERSION = "sip-gsb/1.0.0"

/** Tunable protocol parameters. Any change produces a new fingerprint. */
@Serializable
data class Params(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val timerBMs: Long = 32_000,
    val timerDMs: Long = 32_000,
    val timerHMs: Long = 32_000,
    val timerIMs: Long = 5_000,
    val retransTolerancePct: Int = 25,
)

enum class TxnKind { CLIENT_INVITE, CLIENT_NON_INVITE, SERVER_INVITE, SERVER_NON_INVITE }

enum class TxnState {
    CALLING, TRYING, PROCEEDING, COMPLETED, CONFIRMED, ACCEPTED, TERMINATED
}

@Serializable
data class StateStep(val state: String, val atMs: Long, val causeMsgId: String, val note: String)

@Serializable
data class TransactionRec(
    val id: String,
    val kind: String,
    val branch: String,
    val method: String,
    val callId: String,
    val state: String,
    val history: List<StateStep>,
    val messageIds: List<String>,
    val basis: String,
)

enum class DialogState { EARLY, CONFIRMED, TERMINATED }

@Serializable
data class DialogRec(
    val id: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val state: String,
    val routeSet: List<String>,
    val history: List<StateStep>,
    val messageIds: List<String>,
    val basis: String,
)

@Serializable
data class TimerRec(
    val id: String,
    val name: String,
    val txnId: String,
    val firesAtMs: Long,
    val status: String, // SCHEDULED, FIRED, CANCELLED, EXPLAINED
    val note: String,
)

@Serializable
data class CandidateOption(
    val id: String,
    val label: String,
    val status: String, // PROPOSED, ACCEPTED, REJECTED
    val reason: String,
)

@Serializable
data class CandidateRec(
    val id: String,
    val kind: String, // CANCEL_RACE, LATE_200, TAG_CONFLICT, RETRANS_ANOMALY
    val subjectIds: List<String>,
    val options: List<CandidateOption>,
    val status: String, // PENDING, RESOLVED, UNKNOWN
    val detail: String,
)

@Serializable
data class RetransmissionRec(
    val id: String,
    val originalMsgId: String,
    val duplicateMsgId: String,
    val expectedWindowMs: List<Long>,
    val actualDeltaMs: Long,
    val explained: Boolean,
    val basis: String,
)

@Serializable
data class AnalysisResult(
    val algoVersion: String,
    val paramFingerprint: String,
    val transactions: List<TransactionRec>,
    val dialogs: List<DialogRec>,
    val timers: List<TimerRec>,
    val candidates: List<CandidateRec>,
    val retransmissions: List<RetransmissionRec>,
    val events: List<DomainEvent>,
)

@Serializable
data class DomainEvent(
    val kind: String,
    val vclockMs: Long,
    val payload: Map<String, String>,
)

fun txnId(kind: TxnKind, branch: String, method: String): String =
    "txn-" + sipgsb.sip.sha256("$kind|$branch|$method").take(12)

fun dialogId(callId: String, localTag: String, remoteTag: String): String =
    "dlg-" + sipgsb.sip.sha256("$callId|$localTag|$remoteTag").take(12)

data class InputMessage(
    val id: String,
    val raw: String,
    val direction: Direction,
    val transport: Transport,
    val connGen: Int,
    val recvMs: Long,
    val rawSha: String,
)
