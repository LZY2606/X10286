package forensics.sip.engine

import forensics.sip.sip.Direction
import forensics.sip.sip.IndexedMessage
import forensics.sip.sip.NameAddr
import forensics.sip.sip.Transport
import kotlinx.serialization.Serializable

enum class DialogPhase { EARLY, CONFIRMED, TERMINATED, AMBIGUOUS }

@Serializable
data class RouteUri(
    val ordinal: Int,
    val raw: String,
    val origin: String, // RECORD_ROUTE / CONTACT_FALLBACK
    val directionAtRecord: Direction,
)

@Serializable
data class DialogCandidate(
    val id: String,
    val responseMessageId: String,
    val topViaBranch: String,
    val statusCode: Int,
    val localTag: String,
    val remoteTag: String,
    val targetUri: String,
    val routeSet: List<RouteUri>,
    val early: Boolean,
    val state: DialogPhase,
    val cseq: Long,
    val rejectedReason: String? = null,
    val conflictWith: List<String> = emptyList(),
)

@Serializable
data class DialogView(
    val id: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val parentForkId: String?,
    val state: DialogPhase,
    val candidates: List<DialogCandidate>,
    val acceptedCandidateId: String?,
    val inviteMessageId: String,
    val forkBranches: List<String>,
    val uncertainty: List<String>,
    val timeline: List<DialogEvent>,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val derivedFromRun: Long,
    val stale: Boolean = false,
)

@Serializable
data class DialogEvent(
    val atTicksUtc: Long,
    val kind: String,
    val messageId: String?,
    val candidateId: String?,
    val detail: String,
)

@Serializable
data class TagConflict(
    val id: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val candidateIds: List<String>,
    val differingFields: List<String>,
    val messageIds: List<String>,
    val detail: String,
)

data class ForkGroup(val invite: IndexedMessage, val responses: MutableList<IndexedMessage>)

class DialogReconstructor(private val params: EngineParams) {

    fun analyze(messages: List<IndexedMessage>, runId: Long): Pair<List<DialogView>, List<TagConflict>> {
        val invites = messages.filter { it.parsed.method == "INVITE" && it.record.direction == Direction.SENT }
        val byCall = invites.groupBy { it.parsed.callId }
        val dialogs = mutableListOf<DialogView>()
        val conflicts = mutableListOf<TagConflict>()
        for ((callId, inviteList) in byCall) {
            for ((idx, invite) in inviteList.withIndex()) {
                val key = DialogKey(callId!!, invite.parsed.from!!.tag, invite.parsed.to!!.tag, invite.parsed.cseq!!.seq)
                val relatedResponses = messages.filter { resp ->
                    resp.parsed.statusCode != null &&
                        resp.parsed.callId == callId &&
                        resp.parsed.cseq?.seq == invite.parsed.cseq.seq &&
                        resp.parsed.to != null
                }.sortedWith(compareBy({ it.record.receivedAtTicksUtc }, { it.record.ordinal }))

                val candidates = mutableListOf<DialogCandidate>()
                val seen = mutableSetOf<String>()
                for (resp in relatedResponses) {
                    val toTag = resp.parsed.to?.tag
                    val fromTag = invite.parsed.from.tag
                    val branch = resp.parsed.vias.firstOrNull()?.branch
                    if (toTag == null || fromTag == null || branch == null) continue
                    val remoteTarget = resp.parsed.header("Contact")?.let { forensics.sip.sip.SipParser.splitSemi(it.rawValue).first().trim() }
                        ?.let { raw -> if (raw.contains('<')) raw.substringAfter('<').substringBefore('>') else raw.substringBefore(';').trim() }
                    val rr = resp.parsed.recordRoute.mapIndexed { i, raw ->
                        RouteUri(i, raw.substringBefore(';').trim().let { v ->
                            if (v.contains('<')) v.substringAfter('<').substringBefore('>') else v
                        }, "RECORD_ROUTE", resp.record.direction)
                    }
                    val route = if (rr.isEmpty() && remoteTarget != null)
                        listOf(RouteUri(0, remoteTarget, "CONTACT_FALLBACK", resp.record.direction)) else rr
                    val cid = "cand:${callId.take(8)}:${branch.takeLast(8)}:${toTag.take(8)}:${resp.parsed.statusCode}:${resp.record.id.take(8)}"
                    val early = resp.parsed.statusCode in 180..199
                    val state = if (resp.parsed.statusCode in 200..299) DialogPhase.CONFIRMED else DialogPhase.EARLY
                    val conflict = seen.firstOrNull { existingTagKey(it, toTag, candidates) == existingTagKey(it, toTag, candidates) && false }
                    candidates += DialogCandidate(
                        id = cid,
                        responseMessageId = resp.record.id,
                        topViaBranch = branch,
                        statusCode = resp.parsed.statusCode,
                        localTag = fromTag,
                        remoteTag = toTag,
                        targetUri = remoteTarget ?: "(no Contact; use request URI)",
                        routeSet = route,
                        early = early,
                        state = state,
                        cseq = invite.parsed.cseq.seq,
                    )
                    seen += cid
                }

                // same (localTag,remoteTag) candidates arriving over distinct branches with conflicting routing facts
                val conflictGroups = candidates.groupBy { it.localTag + "|" + it.remoteTag }.filter { it.value.map { c -> c.topViaBranch }.distinct().size > 1 ||
                        it.value.map { c -> c.targetUri }.distinct().size > 1 }
                for ((k, grp) in conflictGroups) {
                    val differing = buildList {
                        if (grp.map { it.topViaBranch }.distinct().size > 1) add("top-via-branch")
                        if (grp.map { it.targetUri }.distinct().size > 1) add("contact-uri")
                        if (grp.map { it.routeSet.map { r -> r.raw } }.distinct().size > 1) add("record-route")
                    }
                    val marked = grp.map { c ->
                        c.copy(
                            rejectedReason = "tag collision: same tag pair over distinct forks (${differing.joinToString(",")}); both identities retained",
                            conflictWith = grp.map { it.id } - c.id,
                        )
                    }
                    candidates[ candidates.indices.first { candidates[it].id in marked.map { m -> m.id } } ] = marked.first()
                    for (i in candidates.indices) {
                        val m = marked.firstOrNull { it.id == candidates[i].id }
                        if (m != null) candidates[i] = m
                    }
                    conflicts += TagConflict(
                        id = "tagconf:" + forensics.sip.sip.Identity.sha256Hex("$callId|$k").take(16),
                        callId = callId,
                        localTag = grp.first().localTag,
                        remoteTag = grp.first().remoteTag,
                        candidateIds = grp.map { it.id },
                        differingFields = differing,
                        messageIds = grp.map { it.responseMessageId },
                        detail = "identical tag pair observed on distinct Via branches; RFC-correct tags must be unique per dialog, so fork membership is ambiguous and neither candidate silently wins",
                    )
                }

                val confirmed = candidates.filter { it.state == DialogPhase.CONFIRMED }
                val early = candidates.filter { it.early }
                val phase = when {
                    conflicts.any { it.candidateIds.any { id -> id in candidates.map { c -> c.id } } } -> DialogPhase.AMBIGUOUS
                    confirmed.isNotEmpty() -> DialogPhase.CONFIRMED
                    early.isNotEmpty() -> DialogPhase.EARLY
                    else -> DialogPhase.TERMINATED
                }
                val accepted = when {
                    confirmed.size == 1 -> confirmed.first().id
                    phase == DialogPhase.EARLY -> null
                    else -> null
                }
                val timeline = mutableListOf<DialogEvent>()
                timeline += DialogEvent(invite.record.receivedAtTicksUtc, "INVITE_SENT", invite.record.id, null,
                    "outgoing INVITE opens dialog key; route set is empty pre-dialog")
                for (c in candidates) {
                    timeline += DialogEvent(
                        runCatching { messages.first { it.record.id == c.responseMessageId }.record.receivedAtTicksUtc }.getOrDefault(0),
                        if (c.state == DialogPhase.CONFIRMED) "RESPONSE_2XX" else "RESPONSE_1XX",
                        c.responseMessageId, c.id,
                        "branch=${c.topViaBranch} to-tag=${c.remoteTag} status=${c.statusCode}; ${c.routeSet.size} route entries"
                    )
                }
                val uncertainty = mutableListOf<String>()
                if (early.size > 1) uncertainty += "FORK: ${early.size} early dialogs are legal simultaneously; reviewer must pick or accept all"
                if (confirmed.size > 1) uncertainty += "LATE_2XX: multiple final 2xx on one INVITE: ACK each; first does not cancel others"
                val id = "dlg:${callId.take(10)}:${invite.parsed.from.tag.take(8)}:${invite.parsed.cseq.seq}:$idx"
                dialogs += DialogView(
                    id = id,
                    callId = callId,
                    localTag = invite.parsed.from.tag,
                    remoteTag = candidates.firstOrNull()?.remoteTag ?: "*",
                    parentForkId = if (idx > 0) "dlg:${callId.take(10)}:${invite.parsed.from.tag.take(8)}:${invite.parsed.cseq.seq}:0" else null,
                    state = phase,
                    candidates = candidates,
                    acceptedCandidateId = accepted,
                    inviteMessageId = invite.record.id,
                    forkBranches = candidates.map { it.topViaBranch }.distinct(),
                    uncertainty = uncertainty,
                    timeline = timeline,
                    algorithmVersion = params.algorithmVersion,
                    paramFingerprint = params.fingerprint(),
                    directSources = listOf(invite.record.id) + candidates.map { it.responseMessageId },
                    derivedFromRun = runId,
                )
            }
        }
        return dialogs.sortedBy { it.inviteMessageId } to conflicts.sortedBy { it.id }
    }

    private fun existingTagKey(id: String, toTag: String, candidates: List<DialogCandidate>): String? =
        candidates.firstOrNull { it.id == id }?.let { it.localTag + "|" + it.remoteTag }
}

private data class DialogKey(val callId: String, val fromTag: String, val toTag: String?, val cseq: Long)
