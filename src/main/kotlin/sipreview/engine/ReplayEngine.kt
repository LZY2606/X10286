package sipreview.engine

import sipreview.domain.Decision
import sipreview.domain.Direction
import sipreview.domain.RawVersion
import sipreview.domain.SipParser
import sipreview.domain.Transport

/**
 * Core deterministic replay. Inputs: ordered immutable raw observations + one parameter set.
 * Outputs: state-machine entities, timer firings, candidates (adopted/rejected/unknown) and a
 * machine-readable event stream. The engine never mutates raw input and never guesses silently:
 * ambiguity becomes a candidate set, not a single number.
 */
class ReplayEngine {

    fun replay(snapshot: SnapshotInputs): ReplayResult {
        val params = snapshot.params
        val ctx = Ctx(snapshot, params)
        val ordered = snapshot.raw.sortedWith(
            compareBy({ it.receivedAtMillis }, { it.ordinal })
        ).map { Observed(it, SipParser.parse(it.rawText)) }

        // Drive a virtual clock: next observation vs. next scheduled timer firing.
        var idx = 0
        while (idx < ordered.size || ctx.timerQueue.isNotEmpty()) {
            val nextObs = ordered.getOrNull(idx)
            val nextTimer = ctx.timerQueue.firstOrNull()
            when {
                nextObs != null && (nextTimer == null || nextObs.atMs <= nextTimer.atMs) -> {
                    handleObservation(ctx, nextObs)
                    idx++
                }
                nextTimer != null -> {
                    ctx.timerQueue.removeAt(0)
                    if (nextTimer.atMs <= (nextObs?.atMs ?: Long.MAX_VALUE)) {
                        fireTimer(ctx, nextTimer)
                    }
                }
                else -> idx++
            }
        }
        finalize(ctx)
        return ctx.buildResult(ordered)
    }

    private fun handleObservation(ctx: Ctx, o: Observed) {
        recordConnection(ctx, o)
        val p = o.parsed
        ctx.emit("message.observed", o.id, mapOf(
            "direction" to o.direction.name.lowercase(),
            "method" to p.method, "statusCode" to p.statusCode,
            "branch" to p.viaBranch, "callId" to p.callId,
            "cseq" to (if (p.cseqNumber != null && p.cseqMethod != null)
                "${p.cseqNumber} ${p.cseqMethod}" else null),
            "fromTag" to p.fromTag, "toTag" to p.toTag,
            "connectionGeneration" to o.raw.connectionGeneration,
            "atMs" to o.atMs
        ), sources = listOf(o.id))

        if (p.parseErrors.isNotEmpty()) {
            ctx.emit("parse.diagnostic", o.id,
                mapOf("errors" to p.parseErrors, "rawKept" to true), listOf(o.id))
            ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", o.id, "parse"),
                "kind" to "parse-error", "label" to "unparseable material retained at source",
                "sources" to listOf(o.id), "detail" to p.parseErrors)
        }

        when {
            p.isRequest && p.method == "ACK" -> handleAck(ctx, o)
            p.isRequest -> handleRequest(ctx, o)
            p.isResponse -> handleResponse(ctx, o)
        }
    }

    private fun handleRequest(ctx: Ctx, o: Observed) {
        val p = o.parsed
        val clientKey = TxClassifier.clientTxKey(o)
        val serverKey = TxClassifier.serverTxKey(o)
        val key = if (o.direction == Direction.EGRESS) clientKey else serverKey
        val role = if (o.direction == Direction.EGRESS) "client" else "server"
        if (key == null) {
            reject(ctx, o, "tx", "no transaction key: missing/legacy branch",
                mapOf("method" to p.method))
            if (p.viaBranch == null || !TxClassifier.branchIsRfc3261(p.viaBranch)) {
                ctx.emit("tx.ambiguous-key", o.id,
                    mapOf("branch" to p.viaBranch, "method" to p.method), listOf(o.id))
                ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", o.id, "txkey"),
                    "kind" to "ambiguous-transaction-key",
                    "label" to "request without RFC3261 branch cannot be uniquely keyed",
                    "sources" to listOf(o.id))
            }
            return
        }

        var tx = ctx.txs[key]
        val invite = p.method == "INVITE"
        if (tx != null && o.id !in tx.sourceIds) tx.sourceIds += o.id
        if (tx == null) {
            tx = TransactionState(key, role, invite, o.transport, p.viaBranch, p.callId,
                p.method ?: "?", o.atMs)
            tx.sourceIds += o.id
            ctx.txs[key] = tx
            ctx.emit("tx.created", key, mapOf(
                "role" to role, "method" to p.method, "invite" to invite,
                "transport" to o.transport.name, "branch" to p.viaBranch), listOf(o.id))
            seedTimers(ctx, tx, o)
        } else {
            // Retransmission of the request on the same transaction.
            noteRetransmission(ctx, tx, o)
        }

        when {
            p.method == "CANCEL" -> {
                val link = TxClassifier.cancelLinkKey(p)
                if (role == "client" && link != null) {
                    ctx.cancelSent[link] = minOf(ctx.cancelSent[link] ?: Long.MAX_VALUE, o.atMs)
                }
                val inviteTx = link?.let { ctx.inviteLinks[it] }
                ctx.candidates += Candidate(
                    id = Ids.prefixed("cand:", key, o.id, "cancel"),
                    kind = "cancel-ownership", subjectId = key,
                    label = "CANCEL belongs to its own transaction, linked (not equal) to INVITE",
                    status = "adopted", atMs = o.atMs, sources = listOf(o.id) +
                        (inviteTx?.let { listOf(it.id) } ?: emptyList()),
                    payload = mapOf(
                        "cancelTx" to key,
                        "inviteTx" to inviteTx?.id,
                        "separateTransactions" to true,
                        "basis" to "RFC3261 9.1: CANCEL has its own branch and CSeq method")
                )
                ctx.emit("cancel.separate-tx", key, mapOf(
                    "linkedInvite" to inviteTx?.id), listOf(o.id))
                if (inviteTx == null) {
                    ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", key, "noinvite"),
                        "kind" to "cancel-without-observed-invite",
                        "label" to "CANCEL links to an INVITE not present in the capture",
                        "sources" to listOf(o.id))
                }
                if (role == "server") tx.transition(o.atMs, "Trying", "CANCEL received")
            }
            invite && role == "client" -> {
                val lk = TxClassifier.inviteLinkKey(p)
                tx.linkKey = lk
                lk?.let { ctx.inviteLinks[it] = tx }
            }
            invite && role == "server" -> {
                tx.linkKey = TxClassifier.inviteLinkKey(p)
                tx.linkKey?.let { ctx.inviteLinks[it] = tx }
                tx.transition(o.atMs, "Proceeding", "INVITE accepted into IST")
            }
        }
    }

    private fun handleResponse(ctx: Ctx, o: Observed) {
        val p = o.parsed
        val status = p.statusCode ?: return
        val key = if (o.direction == Direction.INGRESS)
            TxClassifier.clientTxKey(o) else TxClassifier.serverTxKey(o)
        if (key == null) {
            reject(ctx, o, "tx", "response cannot be matched to a transaction (branch/CSeq missing)",
                mapOf("status" to status))
            return
        }
        val tx = ctx.txs[key]
        if (tx != null && o.id !in tx.sourceIds) tx.sourceIds += o.id
        if (tx == null) {
            // Orphan response: keep it as an unknown instead of silently creating an ICT.
            ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", key, o.id),
                "kind" to "orphan-response",
                "label" to "response with no observable transaction ($status)",
                "sources" to listOf(o.id), "status" to status)
            ctx.emit("response.orphan", key, mapOf("status" to status), listOf(o.id))
            return
        }

        if (isRetransmission(ctx, tx, o)) {
            ctx.emit("retransmit.response", key,
                mapOf("status" to status, "atMs" to o.atMs), listOf(o.id))
        }

        if (tx.role == "client" && tx.invite) {
            when {
                status in 100..199 -> {
                    tx.transition(o.atMs, "Proceeding", "$status provisional")
                    ctx.stopTimer(key, "A")
                    // Every distinct to-tag on a provisional/final response forks an early dialog.
                    noteFork(ctx, tx, o, status)
                }
                status in 200..299 -> {
                    if (tx.state == "Completed" || tx.state == "Terminated") {
                        // Late 200 after CANCEL/487: cannot be absorbed by the dead ICT.
                        tx.lateFinal = status
                        val cand = Candidate(
                            id = Ids.prefixed("cand:", key, o.id, "late2xx"),
                            kind = "late-2xx-after-cancel", subjectId = key,
                            label = "late 2xx arrives after transaction completed; stays unowned by ICT",
                            status = "unknown", atMs = o.atMs, sources = listOf(o.id),
                            payload = mapOf("status" to status, "ictState" to tx.state,
                                "legalInterpretations" to listOf(
                                    "upstream-core must ACK and BYE the confirmed dialog (RFC3261 13.2.2.4)",
                                    "2xx is a transport retransmission of an earlier accepted 2xx"),
                                "decidable" to false),
                            rejectedReason = null
                        )
                        ctx.candidates += cand
                        ctx.emit("fork.late-2xx", key,
                            mapOf("status" to status, "ictState" to tx.state,
                                "candidate" to cand.id), listOf(o.id))
                        noteFork(ctx, tx, o, status, confirmed = true)
                    } else {
                        tx.transition(o.atMs, "Terminated", "$status final 2xx")
                        ctx.stopTimer(key, "B"); ctx.stopTimer(key, "D")
                        noteFork(ctx, tx, o, status, confirmed = true)
                    }
                }
                status in 300..699 -> {
                    val wasProceeding = tx.state == "Proceeding"
                    tx.transition(o.atMs, "Completed", "$status non-2xx final")
                    ctx.stopTimer(key, "A"); ctx.stopTimer(key, "B"); ctx.stopTimer(key, "G")
                    schedule(ctx, tx, "D", o.atMs + ctx.params.timerD0Ms, "wait-for-retransmits-ack")
                    // CANCEL race: if a CANCEL is already in flight, expect 487, not the other code.
                    val raceKey = tx.linkKey; if (raceKey != null && ctx.cancelSent.containsKey(raceKey)) {
                        val race = if (status == 487) "487-as-expected" else "error-final-before-cancel-effect"
                        ctx.emit("cancel.race-resolved", key,
                            mapOf("status" to status, "outcome" to race,
                                "cancelAtMs" to (raceKey?.let { ctx.cancelSent[it] })), listOf(o.id))
                        ctx.candidates += Candidate(
                            id = Ids.prefixed("cand:", key, "cancelrace", status.toString()),
                            kind = "cancel-race", subjectId = key,
                            label = "CANCEL race resolved with $status",
                            status = "adopted", atMs = o.atMs, sources = listOf(o.id),
                            payload = mapOf("expected" to 487, "observed" to status,
                                "outcome" to race,
                                "ack" to "non-2xx ACK generated hop-by-hop within ICT")
                        )
                    }
                    if (wasProceeding) noteFork(ctx, tx, o, status, killEarly = true)
                }
            }
        } else if (tx.role == "client") {
            when (status) {
                in 100..199 -> tx.transition(o.atMs, "Proceeding", "$status")
                in 200..699 -> {
                    tx.transition(o.atMs, "Completed", "$status final")
                    ctx.stopTimer(key, "E")
                    schedule(ctx, tx, "K", o.atMs + ctx.params.timerKMs, "nict-absorb")
                }
            }
        } else {
            // Server side transitions driven here for completeness of the review surface.
            when (status) {
                in 100..199 -> tx.transition(o.atMs, "Proceeding", "sent $status")
                in 200..699 -> {
                    tx.transition(o.atMs, if (status in 200..299) "Terminated" else "Completed",
                        "sent $status")
                    if (status !in 200..299) {
                        if (tx.invite) schedule(ctx, tx, "H", o.atMs + ctx.params.timerH0Ms, "ist-await-ack")
                        else schedule(ctx, tx, "J", o.atMs + ctx.params.timerJMs, "nist-absorb")
                    }
                }
            }
        }
    }

    private fun handleAck(ctx: Ctx, o: Observed) {
        val p = o.parsed
        val inviteBranch = p.viaBranch
        // Distinguish hop-by-hop non-2xx ACK (same branch as INVITE) from end-to-end 2xx ACK.
        val linked = inviteBranch?.let { b -> ctx.txs.values.firstOrNull {
            it.invite && it.branch == b
        } }
        val kind = if (linked != null && linked.state in listOf("Completed", "Terminated"))
            "non-2xx-ack" else "2xx-ack-or-unknown"
        val cand = Candidate(
            id = Ids.prefixed("cand:", "ack", o.id),
            kind = "ack-ownership", subjectId = linked?.id ?: "ack:${o.id}",
            label = if (kind == "non-2xx-ack")
                "ACK for non-2xx belongs to the INVITE transaction (hop-by-hop)"
            else "ACK for 2xx is outside the transaction (end-to-end core dialog hop)",
            status = if (linked == null) "unknown" else "adopted",
            atMs = o.atMs, sources = listOf(o.id) + (linked?.let { listOf(it.id) } ?: emptyList()),
            payload = mapOf(
                "ackBranch" to inviteBranch,
                "inviteTx" to linked?.id,
                "sameTransactionAsInvite" to (kind == "non-2xx-ack"),
                "basis" to if (kind == "non-2xx-ack")
                    "RFC3261 17.1.1.3: non-2xx ACK shares Via branch"
                else "RFC3261 13.2.2.4: 2xx ACK is a separate request with its own branch")
        )
        ctx.candidates += cand
        ctx.emit("ack.ownership", linked?.id ?: o.id,
            mapOf("kind" to kind, "candidate" to cand.id, "branch" to inviteBranch),
            cand.sources)
        if (linked != null && kind == "non-2xx-ack" && linked.state == "Completed") {
            linked.transition(o.atMs, "Terminated", "non-2xx ACK consumed")
            ctx.stopTimer(linked.id, "D")
        }
        if (linked == null) {
            ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", "ack", o.id),
                "kind" to "ack-no-invite-tx",
                "label" to "ACK matches no observable INVITE transaction",
                "sources" to listOf(o.id))
        }
    }

    private fun seedTimers(ctx: Ctx, tx: TransactionState, o: Observed) {
        if (tx.role != "client") return
        if (tx.invite) {
            schedule(ctx, tx, "B", o.atMs + ctx.params.timerB0Ms, "transaction-timeout")
            if (o.transport == Transport.UDP) {
                schedule(ctx, tx, "A", o.atMs + ctx.params.timerA0Ms, "invite-retransmit")
            }
        } else {
            schedule(ctx, tx, "F", o.atMs + (ctx.params.t1Ms * 64), "nict-timeout")
            if (o.transport == Transport.UDP) {
                schedule(ctx, tx, "E", o.atMs + ctx.params.timerE0Ms, "noninvite-retransmit")
            }
        }
    }

    private fun schedule(ctx: Ctx, tx: TransactionState, name: String, atMs: Long, kind: String) {
        val fire = TimerFire("$name@${tx.id}", atMs, tx.id, kind)
        ctx.timerQueue.add(fire)
        ctx.timerQueue.sortBy { it.atMs }
        tx.pendingTimers[name] = atMs
        ctx.emit("timer.scheduled", tx.id, mapOf(
            "timer" to name, "atMs" to atMs, "delayMs" to (atMs - tx.firstAtMs).coerceAtLeast(0),
            "unit" to "ms", "kind" to kind), emptyList())
    }

    private fun fireTimer(ctx: Ctx, fire: TimerFire) {
        val tx = ctx.txs[fire.subjectId] ?: return
        if (tx.pendingTimers[fire.name.removeSuffix("?")] != fire.atMs) return
        tx.pendingTimers.remove(fire.name.removeSuffix("?"))
        tx.firedTimers += fire.name to fire.atMs
        ctx.timerFired += fire
        ctx.emit("timer.fired", tx.id, mapOf(
            "timer" to fire.name, "atMs" to fire.atMs, "state" to tx.state,
            "unit" to "ms", "clock" to "virtual"),
            tx.sources())
        when (fire.name) {
            "A" -> {
                // Virtual-clock interpretation of a UDP INVITE retransmission: only count if the
                // observed packet stream also shows one; otherwise the timer fired unanswered.
                tx.retransmitCount += 1
                val next = (ctx.params.timerA0Ms shl tx.retransmitCount)
                    .coerceAtMost(ctx.params.t2Ms)
                if (tx.state in listOf("Calling/Capture", "Proceeding") &&
                    next < ctx.params.t2Ms + 1) {
                    schedule(ctx, tx, "A", fire.atMs + next, "invite-retransmit")
                }
                ctx.emit("retransmit.virtual", tx.id, mapOf(
                    "timer" to "A", "expectedNextAtMs" to fire.atMs + next,
                    "matchPolicy" to "observed identical request within jitter window counts"),
                    tx.sources())
            }
            "B", "F" -> tx.transition(fire.atMs, "Terminated", "${fire.name} timeout")
            "E" -> {
                tx.retransmitCount += 1
                val next = (ctx.params.timerE0Ms shl tx.retransmitCount)
                    .coerceAtMost(ctx.params.t2Ms)
                if (tx.state == "Calling/Capture") {
                    schedule(ctx, tx, "E", fire.atMs + next, "noninvite-retransmit")
                }
            }
            "D", "K", "H", "J" -> tx.transition(fire.atMs, "Terminated", "${fire.name} absorb done")
        }
    }

    private fun noteRetransmission(ctx: Ctx, tx: TransactionState, o: Observed) {
        val sameMethod = o.parsed.method == tx.method
        if (!sameMethod) return
        tx.lastRetransmitAt = o.atMs
        tx.retransmitCount += 1
        ctx.emit("retransmit.request", tx.id, mapOf(
            "atMs" to o.atMs, "transport" to o.transport.name,
            "connectionGeneration" to o.raw.connectionGeneration,
            "interpretation" to if (o.transport == Transport.UDP)
                "UDP request retransmission (virtual clock correlation)"
            else "TCP: same bytes on a new connection generation is a reconnect, not a retransmit"),
            listOf(o.id))
        if (o.transport != Transport.UDP) {
            // Connection reuse may not erase message identity: same branch on a new generation
            // stays the same transaction but produces a distinct identity-bearing observation.
            ctx.candidates += Candidate(
                id = Ids.prefixed("cand:", tx.id, o.id, "reconnect"),
                kind = "tcp-reconnect-identity", subjectId = tx.id,
                label = "message identity survives TCP connection reuse/reconnect",
                status = "adopted", atMs = o.atMs, sources = listOf(o.id),
                payload = mapOf(
                    "connectionId" to o.raw.connectionId,
                    "connectionGeneration" to o.raw.connectionGeneration,
                    "txPreserved" to true,
                    "observationStillDistinct" to true,
                    "basis" to "RFC3261 18: connection is transport, not message identity"))
        }
    }

    private fun isRetransmission(ctx: Ctx, tx: TransactionState, o: Observed): Boolean {
        val sig = o.id
        val key = tx.id
        val seenStatus = ctx.responseSignatures.getOrPut(key) { mutableSetOf() }
        val fingerprint = listOf(o.parsed.statusCode?.toString(), o.parsed.toTag,
            o.parsed.cseqNumber?.toString()).joinToString(":")
        val dup = fingerprint in seenStatus
        seenStatus += fingerprint
        if (dup) {
            ctx.emit("retransmit.identity", key, mapOf(
                "observationId" to sig, "status" to o.parsed.statusCode,
                "note" to "identical status+toTag repeated; identity kept per observation"),
                listOf(sig))
        }
        return dup
    }

    private fun recordConnection(ctx: Ctx, o: Observed) {
        val key = o.raw.connectionId
        val generations = ctx.connections.getOrPut(key) { sortedMapOf() }
        val gen = o.raw.connectionGeneration
        val firstSeen = generations[gen] ?: o.atMs
        generations[gen] = minOf(firstSeen, o.atMs)
        connectionSources.getOrPut(key) { mutableListOf() }.let {
            if (o.id !in it) it += o.id
        }
        if (gen > 0 && gen !in (generations.keys - gen)) {
            ctx.emit("transport.reconnect", key, mapOf(
                "connectionId" to key, "generation" to gen,
                "firstAtMs" to generations[gen],
                "identityPolicy" to "new generation, same logical peer; message ids unchanged"),
                listOf(o.id))
        }
    }

    private fun noteFork(ctx: Ctx, tx: TransactionState, o: Observed, status: Int,
                         confirmed: Boolean = false, killEarly: Boolean = false) {
        val p = o.parsed
        val callId = p.callId ?: return
        val fromTag = p.fromTag ?: return
        val toTag = p.toTag
        if (toTag == null) {
            // 100 Trying has no to-tag: it cannot create a dialog leg.
            if (status == 100) {
                ctx.emit("dialog.no-tag-trying", tx.id,
                    mapOf("status" to status, "reason" to "100 Trying carries no to-tag"),
                    listOf(o.id))
            } else {
                ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", o.id, "notag"),
                    "kind" to "dialog-without-to-tag",
                    "label" to "$status response lacks to-tag; dialog leg undecidable",
                    "sources" to listOf(o.id))
            }
            return
        }
        val dialogId = "dlg:" + Ids.sha1(listOf(callId, fromTag, toTag)).take(20)
        val existing = ctx.dialogs[dialogId]
        if (existing == null) {
            // Tag conflict guard: identical to-tag from different remote endpoints.
            val tagOwner = ctx.tagOwners.putIfAbsent(toTag, o.raw.remoteAddress)
            val conflict = tagOwner != null && tagOwner != o.raw.remoteAddress
            val routes = p.recordRoutes.mapIndexed { i, rr -> mapOf(
                "order" to i, "raw" to rr, "direction" to "record-route") }
            val dlg = mutableMapOf<String, Any?>(
                "id" to dialogId,
                "callId" to callId, "fromTag" to fromTag, "toTag" to toTag,
                "state" to if (confirmed) "confirmed" else "early",
                "createdByStatus" to status,
                "createdAtMs" to o.atMs,
                "parentTx" to tx.id,
                "routeSet" to routes,
                "routeSetRaw" to p.recordRoutes,
                "sources" to listOf(o.id),
                "tagConflict" to conflict
            )
            ctx.dialogs[dialogId] = dlg
            ctx.emit(if (confirmed) "dialog.confirmed" else "dialog.early-created",
                dialogId, mapOf(
                    "status" to status, "toTag" to toTag, "tx" to tx.id,
                    "forking" to (ctx.dialogs.size > 1 ||
                        ctx.tagToDialogs[callId + "|" + fromTag].orEmpty().isNotEmpty()),
                    "routeCount" to p.recordRoutes.size), listOf(o.id, tx.id))
            // Forking: more than one to-tag for the same (Call-ID, from-tag) => multiple early dialogs.
            val legKey = "$callId|$fromTag"
            val legs = ctx.tagToDialogs.getOrPut(legKey) { mutableSetOf() }
            if (legs.isNotEmpty()) {
                ctx.candidates += Candidate(
                    id = Ids.prefixed("cand:", dialogId, "fork"),
                    kind = "forking-early-dialog", subjectId = dialogId,
                    label = "forking response creates an additional early dialog leg",
                    status = "adopted", atMs = o.atMs, sources = listOf(o.id, tx.id),
                    payload = mapOf("siblingDialogIds" to legs.toList(),
                        "earlyDialogCount" to legs.size + 1,
                        "basis" to "RFC3261 13.2.2.4: each distinct to-tag forks a dialog; " +
                            "only one may confirm"))
            }
            legs += dialogId
            if (conflict) {
                ctx.emit("dialog.tag-conflict", dialogId, mapOf(
                    "toTag" to toTag,
                    "ownerA" to tagOwner, "ownerB" to o.raw.remoteAddress,
                    "policy" to "do not merge; present both candidates"), listOf(o.id))
                ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", dialogId, "tagconflict"),
                    "kind" to "tag-conflict",
                    "label" to "to-tag reused by different remote endpoints; lineage ambiguous",
                    "sources" to listOf(o.id),
                    "owners" to listOf(tagOwner, o.raw.remoteAddress))
            }
        } else if (confirmed && existing["state"] != "confirmed") {
            existing["state"] = "confirmed"
            existing["confirmedAtMs"] = o.atMs
            existing["confirmedBy"] = o.id
            ctx.emit("dialog.confirmed", dialogId,
                mapOf("status" to status, "from" to "early", "by" to o.id), listOf(o.id))
            // Sibling early dialogs that did not confirm must be torn down (487 / CANCEL).
            val legKey = "$callId|$fromTag"
            for (siblingId in ctx.tagToDialogs[legKey].orEmpty() - dialogId) {
                val sib = ctx.dialogs[siblingId]
                if (sib != null && sib["state"] == "early") {
                    sib["state"] = "early-terminated"
                    sib["terminatedReason"] = "another leg confirmed"
                    ctx.emit("dialog.early-superseded", siblingId,
                        mapOf("confirmedBy" to dialogId), listOf(o.id))
                }
            }
        }
        if (killEarly) {
            val legKey = "$callId|$fromTag"
            for (id in ctx.tagToDialogs[legKey].orEmpty()) {
                if (ctx.dialogs[id]?.get("state") == "early") {
                    ctx.dialogs[id]!!["state"] = "early-terminated"
                    ctx.dialogs[id]!!["terminatedReason"] = "$status final"
                }
            }
        }
    }

    private fun reject(ctx: Ctx, o: Observed, kind: String, reason: String,
                       payload: Map<String, Any?>) {
        ctx.candidates += Candidate(
            id = Ids.prefixed("cand:", kind, o.id),
            kind = kind, subjectId = o.id,
            label = "rejected: $reason", status = "rejected", atMs = o.atMs,
            sources = listOf(o.id), payload = payload, rejectedReason = reason)
        ctx.emit("candidate.rejected", o.id, mapOf("kind" to kind, "reason" to reason),
            listOf(o.id))
    }

    private fun finalize(ctx: Ctx) {
        // Timers still pending at end of capture are reported (not silently deleted).
        for ((id, tx) in ctx.txs) {
            for ((name, at) in tx.pendingTimers) {
                ctx.unknowns += mapOf("id" to Ids.prefixed("unk:", id, name),
                    "kind" to "timer-never-fired",
                    "label" to "timer $name scheduled at $at ms but capture ended first",
                    "sources" to tx.sources())
            }
        }
        // Apply human decisions as overlay events without rewriting computed history.
        for (d in ctx.snapshot.decisions) {
            if (d.supersededBy != null) {
                ctx.emit("decision.superseded", d.targetId,
                    mapOf("decision" to d.id, "by" to d.supersededBy,
                        "verdict" to d.verdict), emptyList(), decisionRef = d.id)
                continue
            }
            ctx.emit("decision.applied", d.targetId,
                mapOf("decision" to d.id, "verdict" to d.verdict,
                    "rationale" to d.rationale, "conflictsWith" to d.conflictsWith),
                emptyList(), decisionRef = d.id)
            if (d.conflictsWith != null) {
                ctx.emit("decision.conflict-kept", d.targetId,
                    mapOf("decision" to d.id, "conflictsWith" to d.conflictsWith,
                        "resolution" to "both retained; later decision supersedes without deleting"),
                    emptyList(), decisionRef = d.id)
            }
        }
    }
}

/** Mutable replay workspace. */
private class Ctx(val snapshot: SnapshotInputs, val params: TimerParams) {
    val txs = linkedMapOf<String, TransactionState>()
    val dialogs = linkedMapOf<String, MutableMap<String, Any?>>()
    val timerQueue = mutableListOf<TimerFire>()
    val timerFired = mutableListOf<TimerFire>()
    val candidates = mutableListOf<Candidate>()
    val unknowns = mutableListOf<Map<String, Any?>>()
    val eventLog = mutableListOf<Map<String, Any?>>()
    val connections = linkedMapOf<String, MutableMap<Int, Long>>()
    val connectionSources = linkedMapOf<String, MutableList<String>>()
    val inviteLinks = mutableMapOf<String, TransactionState>()
    val cancelSent = mutableMapOf<String, Long>()
    val responseSignatures = mutableMapOf<String, MutableSet<String>>()
    val tagOwners = mutableMapOf<String, String>()
    val tagToDialogs = mutableMapOf<String, MutableSet<String>>()
    private var seq = 0L

    fun emit(type: String, subjectId: String, payload: Map<String, Any?>,
             sources: List<String>, decisionRef: String? = null) {
        val atMs = (payload["atMs"] as? Long)
            ?: timerFired.lastOrNull()?.atMs
            ?: 0L
        eventLog += mapOf(
            "id" to (eventLog.size + 1L),
            "seq" to ++seq,
            "type" to type, "subjectId" to subjectId,
            "atMs" to atMs, "payload" to payload,
            "sources" to sources, "decisionRef" to decisionRef,
            "runId" to snapshot.runId, "projectId" to snapshot.projectId)
    }

    fun stopTimer(txId: String, name: String) {
        val it = timerQueue.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.subjectId == txId && f.name == name) {
                it.remove()
                emit("timer.cancelled", txId, mapOf("timer" to name, "atMs" to f.atMs,
                    "reason" to "state made it unnecessary"), emptyList())
            }
        }
        txs[txId]?.pendingTimers?.remove(name)
    }

    fun buildResult(ordered: List<Observed>): ReplayResult {
        val txOut = txs.values.map { tx ->
            mapOf("id" to tx.id, "role" to tx.role, "invite" to tx.invite,
                "method" to tx.method, "transport" to tx.transport.name,
                "branch" to tx.branch, "callId" to tx.callId,
                "state" to tx.state, "firstAtMs" to tx.firstAtMs,
                "transitions" to tx.history.map { (at, why) ->
                    mapOf("atMs" to at, "change" to why) },
                "retransmitCount" to tx.retransmitCount,
                "firedTimers" to tx.firedTimers.map { (n, at) ->
                    mapOf("timer" to n, "atMs" to at, "unit" to "ms") },
                "sources" to tx.sources())
        }
        val timerOut = timerFired.map { f -> mapOf(
            "id" to "${f.name}@${f.subjectId}@${f.atMs}",
            "timer" to f.name, "atMs" to f.atMs, "unit" to "ms",
            "subjectId" to f.subjectId, "kind" to f.kind) }
        val connOut = connections.map { (cid, gens) -> mapOf(
            "id" to "conn:$cid", "connectionId" to cid,
            "generations" to gens.map { (g, at) ->
                mapOf("generation" to g, "firstAtMs" to at, "unit" to "ms") },
            "sources" to connectionSources[cid].orEmpty()) }
        return ReplayResult(
            observed = ordered,
            transactions = txOut,
            dialogs = dialogs.values.toList(),
            timers = timerOut,
            candidates = candidates.sortedBy { it.atMs },
            events = eventLog,
            connections = connOut,
            unknowns = unknowns)
    }
}


private fun TransactionState.sources(): List<String> = sourceIds.toList()
