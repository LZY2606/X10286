package sipreview.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sipreview.model.*

class DialogMachine(val st: ReplayState, val sink: EventSink) {

    private fun viewpoint(msg: ParsedMessage): String = if (msg.direction == "OUT") "UAC" else "UAS"

    private fun callerCalleeTags(msg: ParsedMessage): Pair<String?, String?> =
        if (viewpoint(msg) == "UAC") msg.fromTag to msg.toTag else msg.toTag to msg.fromTag

    private fun callerCalleeUris(msg: ParsedMessage): Pair<String?, String?> =
        if (viewpoint(msg) == "UAC") msg.fromUri to msg.toUri else msg.toUri to msg.fromUri

    private fun invTxOf(msg: ParsedMessage): MutableTx? {
        val role = if (msg.direction == "IN") TxRole.CLIENT else TxRole.SERVER
        val probe = msg.copy(method = "INVITE")
        val key = if (role == TxRole.CLIENT) TxKeys.responseClient(probe) else TxKeys.responseServer(probe)
        return st.txs[key]
    }

    fun onResponse(msg: ParsedMessage) {
        if (msg.cseqMethod != "INVITE") return
        val code = msg.statusCode ?: return
        if (code < 180) return
        val callId = msg.callId ?: return
        val (callerTag, calleeTag) = callerCalleeTags(msg)
        if (callerTag == null) {
            st.ensureUnknown(Ids.derived("dlg", st.projectId, "$callId|notag|${msg.toTag ?: "?"}"), "dialog", st.nowMs,
                "cannot anchor dialog: caller-side tag missing", listOf(msg.materialUid))
            return
        }
        val groupKey = TxKeys.dialogForkKey(callId, callerTag, viewpoint(msg))
        val groupDialogs = st.dialogs.values.filter { it.forkGroupKey == groupKey }
        val existing = if (calleeTag != null)
            groupDialogs.firstOrNull { it.remoteTag == calleeTag || it.localTag == calleeTag } else null

        val dlg = existing ?: run {
            val forkIndex = groupDialogs.size
            val logicalKey = TxKeys.dialogKey(callId, callerTag, calleeTag ?: "early-notag-$forkIndex-${msg.materialUid}")
            val d = MutableDialog(logicalKey)
            d.callId = callId
            d.forkGroupKey = groupKey
            d.forkIndex = forkIndex
            val (callerUri, calleeUri) = callerCalleeUris(msg)
            if (viewpoint(msg) == "UAC") {
                d.localTag = callerTag; d.remoteTag = calleeTag
                d.localUri = callerUri; d.remoteUri = calleeUri
                d.localTarget = msg.contact ?: callerUri
                d.remoteTarget = msg.contact
            } else {
                d.localTag = calleeTag ?: callerTag; d.remoteTag = callerTag
                d.localUri = calleeUri ?: callerUri; d.remoteUri = callerUri
                d.remoteTarget = callerUri; d.localTarget = msg.contact ?: calleeUri
            }
            d.connectionId = msg.connectionId
            msg.cseqNumber?.let { d.cseqNext = it + 1 }
            st.dialogs[logicalKey] = d
            st.dep(logicalKey, msg.materialUid)
            invTxOf(msg)?.let { d.invTxUids += it.uid(st.projectId) }
            applyRouteSet(d, msg, creating = true)
            val phase = if (code in 200..299 && calleeTag != null) "CONFIRMED" else "EARLY"
            d.phase = phase
            d.createdByResponseUid = msg.materialUid
            if (phase == "CONFIRMED") d.confirmedByResponseUid = msg.materialUid
            sink.emit(if (forkIndex > 0) EventKind.DL_FORK_EARLY else EventKind.DL_CREATED,
                st.nowMs, d.uid(st.projectId), "dialog",
                basis = listOf(msg.materialUid), sources = listOf(msg.materialUid),
                payload = buildJsonObject {
                    put("forkGroupKey", groupKey); put("forkIndex", forkIndex)
                    put("phase", phase); put("statusCode", code); put("toTag", calleeTag ?: "")
                    put("note", if (forkIndex > 0) "each distinct to-tag on a 18x/2xx establishes its own early dialog" else "")
                })
            if (phase == "CONFIRMED") confirmed(d, msg)
            return
        }

        st.dep(dlg.logicalKey, msg.materialUid)
        if (msg.contact != null) {
            if (viewpoint(msg) == "UAC") dlg.remoteTarget = msg.contact else dlg.localTarget = msg.contact
        }
        when {
            code in 180..199 && calleeTag != null -> {
                if (dlg.phase == "CONFIRMED") {
                    recordTagClash(dlg, msg, code, "provisional with new to-tag after dialog confirmed; candidate kept but not promoted")
                }
            }
            code in 200..299 -> {
                if (calleeTag == null) {
                    st.ensureUnknown(dlg.uid(st.projectId), "dialog", st.nowMs,
                        "2xx without to-tag cannot confirm a dialog; response retained, phase left EARLY", listOf(msg.materialUid))
                } else if (dlg.phase == "CONFIRMED" && dlg.remoteTag != calleeTag && dlg.localTag != calleeTag) {
                    recordTagClash(dlg, msg, code, "2xx to-tag differs from confirmed dialog tag; late fork / tag conflict candidate")
                } else if (dlg.phase != "CONFIRMED") {
                    applyRouteSet(dlg, msg, creating = false)
                    dlg.phase = "CONFIRMED"
                    dlg.confirmedByResponseUid = msg.materialUid
                    confirmed(dlg, msg)
                } else {
                    sink.emit(EventKind.DL_TAG_CONFLICT, st.nowMs, dlg.uid(st.projectId), "dialog",
                        basis = listOf(msg.materialUid, dlg.confirmedByResponseUid ?: msg.materialUid),
                        payload = buildJsonObject {
                            put("clash", "DUPLICATE_CONFIRM")
                            put("statusCode", code); put("toTag", calleeTag)
                            put("note", "re-targeted retransmit of 2xx; identity retained separately at message layer")
                        })
                }
            }
            code >= 300 -> {
                if (dlg.phase == "EARLY" && groupDialogs.count { it.phase != "TERMINATED" } <= 1) {
                    dlg.phase = "TERMINATED"
                    sink.emit(EventKind.DL_TAG_CONFLICT, st.nowMs, dlg.uid(st.projectId), "dialog",
                        basis = listOf(msg.materialUid),
                        payload = buildJsonObject { put("note", "sole early dialog rejected by non-2xx final"); put("statusCode", code); put("clash", "EARLY_REJECTED") })
                }
            }
        }
    }

    private fun applyRouteSet(d: MutableDialog, msg: ParsedMessage, creating: Boolean) {
        if (msg.recordRoute.isEmpty()) return
        val vantage = viewpoint(msg)
        val ordered = when {
            vantage == "UAC" && msg.direction == "IN" -> msg.recordRoute.reversed()
            vantage == "UAS" && msg.direction == "OUT" -> msg.recordRoute.reversed()
            else -> msg.recordRoute
        }
        d.routeSet.clear(); d.routeSet.addAll(ordered)
        sink.emit(EventKind.DL_ROUTE_SET, st.nowMs, d.uid(st.projectId), "dialog",
            basis = msg.recordRoute.map { msg.materialUid }.ifEmpty { listOf(msg.materialUid) },
            payload = buildJsonObject {
                put("vantage", vantage)
                kotlinx.serialization.json.putJsonArray(this, "routeSet") { ordered.forEach { add(it) } }
                put("rawRecordRouteCount", msg.recordRoute.size)
                put("note", "UAC copies reversed Record-Route; UAS copies in received order; raw headers retained on message")
            })
    }

    private fun confirmed(d: MutableDialog, msg: ParsedMessage) {
        sink.emit(EventKind.DL_CONFIRMED, st.nowMs, d.uid(st.projectId), "dialog",
            basis = listOf(msg.materialUid),
            payload = buildJsonObject {
                put("logicalKey", d.logicalKey); put("byResponseUid", msg.materialUid)
                put("note", "ACK for this 2xx is a separate request outside any transaction; keyed by dialog + CSeq")
            })
    }

    private fun recordTagClash(d: MutableDialog, msg: ParsedMessage, code: Int, note: String) {
        val candUid = Ids.of("cand", st.projectId, d.logicalKey, msg.materialUid, code.toString())
        val payload = buildJsonObject {
            put("kind", "TAG_CONFLICT_CANDIDATE")
            put("statusCode", code); put("observedTag", msg.toTag ?: "")
            put("confirmedTag", d.remoteTag ?: d.localTag ?: "")
            put("note", note)
        }
        st.candidates += Candidate(candUid, d.uid(st.projectId), "dialog", "TAG_CONFLICT", payload,
            confidence = "LOW", rejectReason = null, rejected = false,
            sourceUids = listOf(msg.materialUid), basisEventUids = emptyList(), generationSeq = st.genSeq)
        st.ensureUnknown(d.uid(st.projectId), "dialog", st.nowMs, note, listOf(candUid))
        sink.emit(EventKind.DL_TAG_CONFLICT, st.nowMs, d.uid(st.projectId), "dialog",
            basis = listOf(msg.materialUid),
            payload = buildJsonObject {
                put("clash", "TAG_MISMATCH"); put("statusCode", code)
                put("observedTag", msg.toTag ?: ""); put("candidateUid", candUid)
            })
    }

    fun onAck2xx(msg: ParsedMessage) {
        val callId = msg.callId ?: return
        val vantage = viewpoint(msg)
        val (localTag, remoteTag) = if (vantage == "UAC") msg.fromTag to msg.toTag else msg.toTag to msg.fromTag
        val dlg = st.dialogs.values.firstOrNull {
            it.callId == callId && (it.localTag == localTag || it.remoteTag == localTag) &&
                (remoteTag == null || it.localTag == remoteTag || it.remoteTag == remoteTag)
        }
        if (dlg == null) {
            st.ensureUnknown(Ids.derived("ack2xx", st.projectId, "$callId|$localTag|$remoteTag"), "dialog", st.nowMs,
                "2xx ACK with no confirmed dialog to attach to; retained outside transactions", listOf(msg.materialUid))
            sink.emit(EventKind.CLASH, st.nowMs, Ids.derived("ack2xx", st.projectId, "$callId|$localTag|$remoteTag"), "dialog",
                basis = listOf(msg.materialUid),
                payload = buildJsonObject { put("clash", "ORPHAN_2XX_ACK") })
            return
        }
        st.dep(dlg.logicalKey, msg.materialUid)
        sink.emit(EventKind.TX_ACK_MATCH, st.nowMs, dlg.uid(st.projectId), "dialog",
            basis = listOf(msg.materialUid, dlg.confirmedByResponseUid ?: msg.materialUid),
            payload = buildJsonObject {
                put("ackMaterialUid", msg.materialUid)
                put("confirmedByResponseUid", dlg.confirmedByResponseUid ?: "")
                put("note", "2xx ACK belongs to the dialog, never to the INVITE transaction or to a non-2xx ACK tx")
                put("outsideTransaction", true)
            })
        msg.cseqNumber?.let { cseq ->
            sink.emit(EventKind.DL_CSEQ, st.nowMs, dlg.uid(st.projectId), "dialog",
                basis = listOf(msg.materialUid),
                payload = buildJsonObject { put("cseq", cseq); put("method", "ACK") })
        }
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) { add(kotlinx.serialization.json.JsonPrimitive(value)) }
