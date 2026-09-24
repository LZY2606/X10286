package sipreview.engine

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sipreview.model.*

class TxStateMachine(val st: ReplayState, val sink: EventSink) {

    private val p get() = st.params.timers

    private fun addTimer(tx: MutableTx, name: String, atMs: Long, basis: String) {
        tx.timers += TimerFire(name, atMs, fired = false, matchedByMaterialUid = null)
        sink.emit(EventKind.TX_TIMER_SCHEDULED, atMs, tx.uid(st.projectId), "transaction",
            basis = listOf(tx.requestUid ?: "").filter { it.isNotEmpty() },
            payload = buildJsonObject {
                put("timer", name); put("atMs", atMs); put("clockBasis", basis)
                put("t1Ms", p.t1Ms); put("t2Ms", p.t2Ms); put("t4Ms", p.t4Ms)
            })
    }

    private fun stateChange(tx: MutableTx, newState: String, sourceUid: String?, note: String, code: Int? = null) {
        val old = tx.state
        tx.state = newState
        if (newState == "TERMINATED" || newState == "CONFIRMED") tx.terminatedAtMs = st.nowMs
        sink.emit(EventKind.TX_STATE_CHANGED, st.nowMs, tx.uid(st.projectId), "transaction",
            basis = listOfNotNull(sourceUid), sources = listOfNotNull(tx.requestUid),
            payload = buildJsonObject {
                put("logicalKey", tx.logicalKey); put("role", tx.role.name); put("txClass", tx.txClass.name)
                put("method", tx.method); put("fromState", old); put("toState", newState)
                if (code != null) put("statusCode", code); put("note", note)
            })
    }

    private fun ensureTx(key: String, msg: ParsedMessage, role: TxRole): MutableTx {
        st.txs[key]?.let { return it }
        val tx = MutableTx(key)
        tx.role = role
        tx.txClass = when {
            (msg.method ?: msg.cseqMethod) == "INVITE" -> TxClass.INVITE
            (msg.cseqMethod) == "ACK" -> TxClass.INVITE
            else -> TxClass.NON_INVITE
        }
        tx.method = msg.method ?: msg.cseqMethod ?: "UNKNOWN"
        tx.branch = msg.topViaBranch
        tx.callId = msg.callId
        tx.transport = msg.via.firstOrNull()?.transport ?: "UDP"
        tx.enteredAtMs = st.nowMs
        st.txs[key] = tx
        val initState = when {
            tx.txClass == TxClass.INVITE && role == TxRole.CLIENT -> "CALLING"
            tx.txClass == TxClass.INVITE && role == TxRole.SERVER -> "TRYING"
            role == TxRole.CLIENT -> "TRYING"
            else -> "TRYING"
        }
        tx.state = initState
        sink.emit(EventKind.TX_CREATED, st.nowMs, tx.uid(st.projectId), "transaction",
            sources = listOf(msg.materialUid),
            payload = buildJsonObject {
                put("logicalKey", key); put("role", role.name); put("txClass", tx.txClass.name)
                put("method", tx.method); put("branch", tx.branch ?: "")
                put("callId", tx.callId ?: ""); put("initialState", initState)
            })
        return tx
    }

    fun onRequest(msg: ParsedMessage) {
        val role = if (msg.direction == "OUT") TxRole.CLIENT else TxRole.SERVER
        if (msg.method == "ACK") { onAck(msg, role); return }
        val key = if (role == TxRole.CLIENT) TxKeys.client(msg) else TxKeys.server(msg)
        val existing = st.txs[key]
        if (existing != null) { onRetransmission(existing, msg, role); return }
        val tx = ensureTx(key, msg, role)
        tx.requestUid = msg.materialUid
        msg.connectionId?.let { tx.connectionIds += it }
        st.dep(tx.logicalKey, msg.materialUid)
        val isInvite = tx.txClass == TxClass.INVITE
        when {
            isInvite && role == TxRole.CLIENT -> {
                addTimer(tx, "A", st.nowMs + p.t1Ms, "T1 on request send")
                addTimer(tx, "B", st.nowMs + 64 * p.t1Ms, "64*T1 transaction timeout")
            }
            isInvite && role == TxRole.SERVER -> {
                addTimer(tx, "G", st.nowMs + p.t1Ms, "T1 on 1xx send (if needed, scheduled early)")
                addTimer(tx, "H", st.nowMs + p.timerHMs, "H waits ACK")
            }
            !isInvite && role == TxRole.CLIENT -> {
                addTimer(tx, "E", st.nowMs + p.t1Ms, "T1 non-INVITE")
                addTimer(tx, "F", st.nowMs + p.timerFMs, "F non-INVITE timeout")
            }
            else -> addTimer(tx, "J", st.nowMs + p.timerJUdpMs, "J absorbs retransmits")
        }
        if (msg.method == "CANCEL") linkCancel(msg, tx)
    }

    private fun linkCancel(cancel: ParsedMessage, cancelTx: MutableTx) {
        val viabranch = cancel.topViaBranch ?: return
        val invite = st.txs.values.firstOrNull { it.method == "INVITE" && it.callId == cancel.callId && it.branch == viabranch && it.role == cancelTx.role }
        if (invite != null) {
            cancelTx.canceledInviteTxUid = invite.uid(st.projectId)
            invite.cancelTxUid = cancelTx.uid(st.projectId)
            st.dep(cancelTx.logicalKey, invite.requestUid ?: cancel.materialUid)
            sink.emit(EventKind.TX_CANCEL_LINK, st.nowMs, cancelTx.uid(st.projectId), "transaction",
                basis = listOf(cancel.materialUid, invite.requestUid ?: "").filter { it.isNotEmpty() },
                payload = buildJsonObject {
                    put("cancelTxUid", cancelTx.uid(st.projectId))
                    put("inviteTxUid", invite.uid(st.projectId))
                    put("sameBranch", true)
                    put("note", "CANCEL is its own transaction, linked to INVITE only by Via branch + Call-ID")
                })
        } else {
            st.ensureUnknown(cancelTx.uid(st.projectId), "transaction", st.nowMs,
                "CANCEL has no in-flight INVITE with same top Via branch; target transaction not uniquely identified",
                listOf(cancel.materialUid))
            sink.emit(EventKind.CLASH, st.nowMs, cancelTx.uid(st.projectId), "transaction",
                basis = listOf(cancel.materialUid),
                payload = buildJsonObject {
                    put("clash", "CANCEL_ORPHAN")
                    put("branch", viabranch); put("callId", cancel.callId ?: "")
                })
        }
    }

    fun onResponse(msg: ParsedMessage) {
        val role = if (msg.direction == "IN") TxRole.CLIENT else TxRole.SERVER
        val key = if (role == TxRole.CLIENT) TxKeys.responseClient(msg) else TxKeys.responseServer(msg)
        val tx = st.txs[key]
        if (tx == null) {
            val ghost = MutableTx(key).also {
                it.role = role
                it.txClass = if (msg.cseqMethod == "INVITE") TxClass.INVITE else TxClass.NON_INVITE
                it.method = msg.cseqMethod ?: "UNKNOWN"
                it.branch = msg.topViaBranch; it.callId = msg.callId
            }
            st.txs[key] = ghost
            st.ensureUnknown(ghost.uid(st.projectId), "transaction", st.nowMs,
                "response with no matching request transaction; kept as unmatched (lossy capture?)", listOf(msg.materialUid))
            sink.emit(EventKind.CLASH, st.nowMs, ghost.uid(st.projectId), "transaction",
                basis = listOf(msg.materialUid),
                payload = buildJsonObject { put("clash", "ORPHAN_RESPONSE"); put("statusCode", msg.statusCode ?: -1) })
            return
        }
        tx.responseUids += msg.materialUid
        msg.connectionId?.let { tx.connectionIds += it }
        st.dep(tx.logicalKey, msg.materialUid)
        val code = msg.statusCode
        tx.lastResponseCode = code
        sink.emit(EventKind.TX_RESPONSE_MATCH, st.nowMs, tx.uid(st.projectId), "transaction",
            basis = listOf(msg.materialUid, tx.requestUid ?: "").filter { it.isNotEmpty() },
            payload = buildJsonObject {
                put("statusCode", code ?: -1); put("toTag", msg.toTag ?: "")
                put("matchRule", "same top Via branch, sent-by, method=CSeq method, Call-ID")
                put("note", if (msg.cseqMethod == "ACK") "non-2xx ACK response matches the ACK's own transaction, never the INVITE" else "")
            })

        val provisional = code != null && code in 100..199
        val success = code != null && code in 200..299
        val final = code != null && code >= 300
        if (provisional) {
            msg.toTag?.let { tx.provisionalToTags += it }
            if (tx.txClass == TxClass.INVITE) {
                if (tx.state == "CALLING" || tx.state == "TRYING") stateChange(tx, "PROCEEDING", msg.materialUid, "provisional received", code)
                tx.timers.filter { it.timer in listOf("A", "G") }.forEach { it }
            } else {
                if (tx.state == "TRYING") stateChange(tx, "PROCEEDING", msg.materialUid, "provisional received", code)
            }
            return
        }
        if (success) {
            when {
                tx.txClass == TxClass.INVITE && role == TxRole.CLIENT -> {
                    if (tx.state == "COMPLETED" && tx.cancelTxUid != null) {
                        st.ensureUnknown(tx.uid(st.projectId), "transaction", st.nowMs,
                            "2xx arrives after non-2xx completion/CANCEL race; RFC3261 14.1: UAC MUST pass 2xx to TU, ACK is a separate exchange",
                            listOf(msg.materialUid))
                        sink.emit(EventKind.CLASH, st.nowMs, tx.uid(st.projectId), "transaction",
                            basis = listOf(msg.materialUid),
                            payload = buildJsonObject {
                                put("clash", "LATE_2XX_AFTER_CANCEL")
                                put("statusCode", code); put("rfc", "RFC3261#14.1")
                            })
                        stateChange(tx, "COMPLETED", msg.materialUid, "late 2xx does not reshape the canceled tx; 2xx ACK handled at dialog layer", code)
                    } else {
                        stateChange(tx, "TERMINATED", msg.materialUid, "2xx terminates INVITE client tx; its ACK is not a transaction", code)
                        tx.finalized = true
                    }
                }
                tx.txClass == TxClass.INVITE && role == TxRole.SERVER -> {
                    stateChange(tx, "TERMINATED", msg.materialUid, "2xx sent: no server transaction state; 2xx ACK tracked at dialog layer", code)
                    tx.finalized = true
                }
                else -> stateChange(tx, "COMPLETED", msg.materialUid, "final 2xx", code)
            }
            return
        }
        if (final) {
            if (tx.txClass == TxClass.INVITE) {
                if (role == TxRole.CLIENT) {
                    stateChange(tx, "COMPLETED", msg.materialUid, "non-2xx final: expect ACK on same branch (ACKNON2XX)", code)
                    if (tx.transport == "UDP") addTimer(tx, "D", st.nowMs + p.timerDudpMs, "D wait (32s UDP)")
                    else stateChange(tx, "TERMINATED", msg.materialUid, "D=0 on connection transport", code)
                } else {
                    if (tx.state != "CONFIRMED") stateChange(tx, "COMPLETED", msg.materialUid, "final sent, awaiting ACK", code)
                    if (tx.transport == "UDP") addTimer(tx, "H", st.nowMs + p.timerHMs, "H retransmit final until ACK")
                    addTimer(tx, "I", st.nowMs + p.t4Ms, "T4 absorb ACK")
                }
            } else {
                stateChange(tx, "COMPLETED", msg.materialUid, "final response", code)
                if (role == TxRole.SERVER) addTimer(tx, "J", st.nowMs + p.timerJUdpMs, "J")
                if (role == TxRole.CLIENT) addTimer(tx, "K", st.nowMs + if (tx.transport == "UDP") p.timerKUdpMs else p.t4Ms, "K")
            }
        }
    }

    private fun onAck(msg: ParsedMessage, role: TxRole) {
        val key = if (role == TxRole.CLIENT) TxKeys.client(msg) else TxKeys.server(msg)
        val tx = st.txs[key]
        if (tx == null) {
            st.ensureUnknown(Ids.derived("ack", st.projectId, key), "transaction", st.nowMs,
                "non-2xx ACK with no matching server/client transaction record", listOf(msg.materialUid))
            sink.emit(EventKind.CLASH, st.nowMs, Ids.derived("ack", st.projectId, key), "transaction",
                basis = listOf(msg.materialUid),
                payload = buildJsonObject { put("clash", "ORPHAN_ACK") })
            return
        }
        tx.ackUids += msg.materialUid
        st.dep(tx.logicalKey, msg.materialUid)
        sink.emit(EventKind.TX_ACK_MATCH, st.nowMs, tx.uid(st.projectId), "transaction",
            basis = listOf(msg.materialUid, tx.requestUid ?: "").filter { it.isNotEmpty() },
            payload = buildJsonObject {
                put("ackTransaction", key)
                put("distinctFromInvite", tx.method == "ACKNON2XX" || msg.topViaBranch != st.txs.values.firstOrNull { it.logicalKey == key.replace("ACKNON2XX", "INVITE") }?.branch)
                put("note", "ACK for non-2xx is request-in-server-transaction with its own branch; never keyed onto 2xx ACK")
            })
        if (tx.txClass == TxClass.INVITE && tx.state == "COMPLETED" && role == TxRole.SERVER) {
            stateChange(tx, "CONFIRMED", msg.materialUid, "ACK received for non-2xx final")
            stateChange(tx, "TERMINED", msg.materialUid, "I/T4 then terminated (virtual instant)")
        }
    }

    private fun onRetransmission(tx: MutableTx, dup: ParsedMessage, role: TxRole) {
        val originalUid = tx.requestUid
        val rtKey = "${tx.logicalKey}|${dup.rawHash}"
        val rt = MutableRetrans(rtKey)
        rt.originalUid = originalUid ?: dup.materialUid
        rt.duplicateUid = dup.materialUid
        rt.role = role
        rt.txKey = tx.logicalKey
        rt.clockBasis = "virtual"
        val origTime = st.messages[originalUid]?.receivedAtMs
        rt.intervalMs = if (origTime != null) st.nowMs - origTime else null
        val expected = expectedNextInterval(tx, dup)
        rt.expectedTimer = expected.first
        rt.withinTolerance = expected.second?.let { exp -> rt.intervalMs != null && kotlin.math.abs(rt.intervalMs!! - exp) <= exp * p.retransToleranceRatio }
        st.retransmissions += rt
        st.dep("rtx:$rtKey", tx.requestUid ?: dup.materialUid, dup.materialUid)
        sink.emit(EventKind.TX_RETRANSMISSION, st.nowMs, rt.uid(st.projectId), "retransmission",
            basis = listOf(rt.originalUid, dup.materialUid),
            payload = buildJsonObject {
                put("logicalKey", rtKey); put("txUid", tx.uid(st.projectId)); put("role", role.name)
                put("intervalMs", rt.intervalMs ?: -1)
                put("expectedTimer", expected.first ?: "")
                put("expectedIntervalMs", expected.second ?: -1)
                put("withinTolerance", rt.withinTolerance ?: false)
                put("clockBasis", "virtual")
                put("identityNote", "duplicate bytes do not replace the original message; each retains its own material uid")
            })
        if (role == TxRole.SERVER && tx.txClass == TxClass.INVITE && tx.state == "COMPLETED") {
            sink.emit(EventKind.TX_TIMER_FIRED, st.nowMs, tx.uid(st.projectId), "transaction",
                basis = listOf(dup.materialUid),
                payload = buildJsonObject { put("timer", "G"); put("effect", "retransmit final response"); put("matchedByMaterialUid", dup.materialUid) })
        }
    }

    private fun MutableTx.retransCount(): Int = st.retransmissions.count { it.txKey == logicalKey && it.duplicateUid != requestUid }

    private fun expectedNextInterval(tx: MutableTx, dup: ParsedMessage): Pair<String?, Long?> {
        val n = tx.retransCount()
        val t1 = p.t1Ms
        return when {
            tx.txClass == TxClass.INVITE && tx.role == TxRole.CLIENT -> "A" to minOf(t1 * (1L shl n.coerceAtMost(6)), p.t2Ms)
            tx.txClass == TxClass.INVITE && tx.role == TxRole.SERVER -> "G" to minOf(t1 * (1L shl n.coerceAtMost(6)), p.t2Ms)
            tx.role == TxRole.CLIENT -> "E" to minOf(t1 * (1L shl n.coerceAtMost(6)), p.t2Ms)
            else -> null to null
        }
    }

}
