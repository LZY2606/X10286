package sipreview.engine

import sipreview.domain.Direction
import sipreview.domain.SipParsed
import sipreview.domain.Transport

/** RFC 3261 17 transaction role + INVITE/non-INVITE classification. */
object TxClassifier {

    fun branchIsRfc3261(branch: String?): Boolean =
        branch != null && branch.startsWith("z9hG4bK")

    /** Stable key of the client transaction an observation belongs to (or null). */
    fun clientTxKey(o: Observed): String? {
        val p = o.parsed
        if (p.isRequest && o.direction == Direction.EGRESS) {
            return keyFromRequest(p, "ct")
        }
        if (p.isResponse && o.direction == Direction.INGRESS) {
            val method = p.cseqMethod ?: return null
            val branch = p.viaBranch ?: return null
            return "ct:" + Ids.sha1(listOf(branch, method, p.callId ?: "")).take(20)
        }
        return null
    }

    /** Stable key of the server transaction. */
    fun serverTxKey(o: Observed): String? {
        val p = o.parsed
        if (p.isRequest && o.direction == Direction.INGRESS) {
            return keyFromRequest(p, "st")
        }
        if (p.isResponse && o.direction == Direction.EGRESS) {
            val method = p.cseqMethod ?: return null
            val branch = p.viaBranch ?: return null
            return "st:" + Ids.sha1(listOf(branch, method, p.callId ?: "")).take(20)
        }
        return null
    }

    /** Non-2xx ACK shares the INVITE's branch (RFC 3261 17.1.1.3); 2xx ACK has its own. */
    fun isNon2xxAckLinkedToInvite(o: Observed, inviteBranches: Set<String>): Boolean {
        val p = o.parsed
        return p.isRequest && p.method == "ACK" && o.direction == Direction.EGRESS &&
            p.viaBranch != null && p.viaBranch in inviteBranches
    }

    /** CANCEL always owns a separate branch; it is *linked* to an INVITE, not the same tx. */
    fun cancelLinkKey(p: SipParsed): String? {
        if (p.method != "CANCEL" || p.callId == null || p.cseqNumber == null) return null
        return "invlink:" + Ids.sha1(listOf(p.callId, p.cseqNumber.toString(), p.fromTag ?: "")).take(20)
    }

    fun inviteLinkKey(p: SipParsed): String? {
        if (p.method != "INVITE" || p.callId == null || p.cseqNumber == null) return null
        return "invlink:" + Ids.sha1(listOf(p.callId, p.cseqNumber.toString(), p.fromTag ?: "")).take(20)
    }

    private fun keyFromRequest(p: SipParsed, prefix: String): String? {
        val method = p.method ?: return null
        val branch = p.viaBranch
        if (branch != null && branchIsRfc3261(branch)) {
            return "$prefix:" + Ids.sha1(listOf(branch, method, p.callId ?: "")).take(20)
        }
        if (branch != null) {
            // RFC 2543-style branch: fall back to weak key, flagged later as ambiguous.
            return "$prefix:legacy:" + Ids.sha1(
                listOf(branch, method, p.callId ?: "", p.fromTag ?: "", p.cseqNumber?.toString() ?: "")
            ).take(20)
        }
        return null
    }
}

/** Minimal RFC 3261 client/server transaction state machines, evaluated on a virtual clock. */
class TransactionState(
    val id: String,
    val role: String,
    val invite: Boolean,
    val transport: Transport,
    val branch: String?,
    val callId: String?,
    val method: String,
    val firstAtMs: Long,
    var linkKey: String? = null,
    val sourceIds: MutableList<String> = mutableListOf()
) {
    var state: String = if (role == "client") "Calling/Capture" else "Proceeding"
    val history = mutableListOf<Pair<Long, String>>()
    val pendingTimers = mutableMapOf<String, Long>()
    var firedTimers = mutableListOf<Pair<String, Long>>()
    var lastRetransmitAt: Long? = null
    var retransmitCount = 0
    var lateFinal: Int? = null

    fun transition(atMs: Long, newState: String, why: String) {
        if (newState != state) {
            history += atMs to "$state -> $newState ($why)"
            state = newState
        }
    }
}
