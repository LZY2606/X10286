package sipreview.engine

import kotlinx.serialization.json.JsonObject
import sipreview.model.*

data class TimerInst(
    val name: String,
    val atMs: Long,
    val txKey: String,
    val once: Boolean = true
)

class ReplayState(val params: Params) {
    val messages = mutableMapOf<String, ParsedMessage>()
    val order = mutableListOf<String>()
    val txs = linkedMapOf<String, MutableTx>()
    val dialogs = linkedMapOf<String, MutableDialog>()
    val connections = linkedMapOf<String, MutableConnection>()
    val retransmissions = mutableListOf<MutableRetrans>()
    val candidates = mutableListOf<Candidate>()
    val unknowns = mutableMapOf<String, UnknownState>()
    val events = mutableListOf<EventRecord>()
    val dependsOn = mutableMapOf<String, MutableSet<String>>()
    var eventIndex = 0
    var projectId = ""
    var genSeq = 0
    var nowMs: Long = Long.MIN_VALUE

    fun dep(subjectKey: String, vararg sources: String) {
        val set = dependsOn.getOrPut(subjectKey) { mutableSetOf() }
        set.addAll(sources)
    }

    fun ensureUnknown(subjectUid: String, kind: String, atMs: Long?, reason: String, legal: List<String> = emptyList()) {
        val cur = unknowns[subjectUid]
        unknowns[subjectUid] = UnknownState(
            subjectUid = subjectUid, subjectKind = kind, atMs = atMs,
            reasons = (cur?.reasons ?: emptyList()) + reason,
            legalCandidateUids = (cur?.legalCandidateUids ?: emptyList()) + legal,
            generationSeq = genSeq
        )
    }
}

class MutableTx(val logicalKey: String) {
    var role: TxRole = TxRole.CLIENT
    var txClass: TxClass = TxClass.INVITE
    var method: String = "INVITE"
    var branch: String? = null
    var callId: String? = null
    var state: String = "UNKNOWN"
    var requestUid: String? = null
    val responseUids = mutableListOf<String>()
    val ackUids = mutableListOf<String>()
    var cancelTxUid: String? = null
    var canceledInviteTxUid: String? = null
    val connectionIds = mutableSetOf<String>()
    val timers = mutableListOf<TimerFire>()
    var enteredAtMs: Long? = null
    var terminatedAtMs: Long? = null
    val unknownReasons = mutableListOf<String>()
    var lastResponseCode: Int? = null
    var provisionalToTags = linkedSetOf<String>()
    var finalized = false
    var transport: String = "UDP"

    fun uid(projectId: String): String = Ids.derived("tx", projectId, logicalKey)
}

class MutableDialog(val logicalKey: String) {
    var callId: String = ""
    var localTag: String? = null
    var remoteTag: String? = null
    var localUri: String? = null
    var remoteUri: String? = null
    var localTarget: String? = null
    var remoteTarget: String? = null
    val routeSet = mutableListOf<String>()
    var phase: String = "EARLY"
    var createdByResponseUid: String? = null
    var confirmedByResponseUid: String? = null
    val invTxUids = mutableListOf<String>()
    var forkGroupKey: String? = null
    var forkIndex: Int? = null
    var connectionId: String? = null
    var cseqNext: Long? = null
    val unknownReasons = mutableListOf<String>()

    fun uid(projectId: String): String = Ids.derived("dlg", projectId, logicalKey)
}

class MutableConnection(val logicalKey: String) {
    var connectionId: String = ""
    var generation: Int = 1
    var transport: String = "UDP"
    var localEndpoint: String? = null
    var remoteEndpoint: String? = null
    var openedAtMs: Long? = null
    var closedAtMs: Long? = null
    var reusedAfter: String? = null
    val messageUids = mutableListOf<String>()

    fun uid(projectId: String): String = Ids.derived("conn", projectId, logicalKey)
}

class MutableRetrans(val logicalKey: String) {
    var originalUid: String = ""
    var duplicateUid: String = ""
    var role: TxRole = TxRole.CLIENT
    var txKey: String = ""
    var intervalMs: Long? = null
    var expectedTimer: String? = null
    var withinTolerance: Boolean? = null
    var clockBasis: String = "virtual"
    fun uid(projectId: String): String = Ids.derived("rtx", projectId, logicalKey)
}

object TxKeys {
    private fun normBranch(branch: String?): String = branch?.takeIf { it.isNotBlank() } ?: "no-branch"

    fun client(msg: ParsedMessage): String {
        val top = msg.via.firstOrNull()
        val branchPart = normBranch(top?.branch)
        val sentBy = top?.sentBy?.replace(Regex(":\\d+$"), "") ?: "no-sentby"
        val method = if (msg.cseqMethod == "ACK") "ACKNON2XX" else msg.method ?: "UNKNOWN"
        return "C|$method|${msg.callId ?: "no-callid"}|$branchPart|$sentBy"
    }

    fun server(msg: ParsedMessage): String {
        val top = msg.via.firstOrNull()
        val branchPart = normBranch(top?.branch)
        val sentBy = top?.sentBy ?: "no-sentby"
        val method = if (msg.cseqMethod == "ACK") "ACKNON2XX" else msg.method ?: "UNKNOWN"
        return "S|$method|${msg.callId ?: "no-callid"}|$branchPart|$sentBy"
    }

    fun responseClient(msg: ParsedMessage): String {
        val top = msg.via.firstOrNull()
        val branchPart = normBranch(top?.branch)
        val sentBy = top?.sentBy?.replace(Regex(":\\d+$"), "") ?: "no-sentby"
        val method = when (msg.cseqMethod) { "ACK" -> "ACKNON2XX"; null -> "UNKNOWN"; else -> msg.cseqMethod }
        return "C|$method|${msg.callId ?: "no-callid"}|$branchPart|$sentBy"
    }

    fun responseServer(msg: ParsedMessage): String {
        val top = msg.via.firstOrNull()
        val branchPart = normBranch(top?.branch)
        val sentBy = top?.sentBy ?: "no-sentby"
        val method = when (msg.cseqMethod) { "ACK" -> "ACKNON2XX"; null -> "UNKNOWN"; else -> msg.cseqMethod }
        return "S|$method|${msg.callId ?: "no-callid"}|$branchPart|$sentBy"
    }

    fun dialogForkKey(callId: String, fromTag: String?, roleOfCallerTag: String): String =
        "$callId|${fromTag ?: "notag"}|$roleOfCallerTag"

    fun dialogKey(callId: String, callerTag: String, calleeTag: String): String =
        "$callId|${callerTag}|${calleeTag}"

    fun connectionKey(connId: String, gen: Int): String = "$connId#$gen"
}
