package sipreview.domain

import kotlinx.serialization.Serializable

/**
 * 单次重放的处理上下文。状态推进严格按虚拟时钟排序后的记录发生；
 * 同一连接不同代次、同一指纹不同方向都保持独立消息身份。
 */
class Ctx(
    val projectId: String,
    val paramRevisionId: String,
    val params: EngineParams,
    val fp: String,
    val decisions: List<Decision>,
) {
    val txs = LinkedHashMap<String, ReplayEngine.Tx>()
    val dialogs = mutableListOf<DialogNode>()
    val timers = mutableListOf<TimerEvent>()
    val retransmits = mutableListOf<RetransmitEvent>()
    val ownership = mutableListOf<OwnershipClaim>()
    val globalCandidates = mutableListOf<Candidate>()
    val connections = LinkedHashMap<String, ConnectionEra>()

    private val seenFingerprints = HashMap<String, RawRecord>()
    private val inviteByCallSeq = HashMap<String, MutableList<String>>() // callId -> invite tx ids

    fun prov(step: String, sources: List<String>) =
        Provenance(ReplayEngine.ALGO_VERSION, fp, sources, paramRevisionId, step)

    private fun txKey(branch: String, method: String) = "tx:$branch:$method"

    fun consume(r: RawRecord, clock: VirtualClock) {
        trackConnection(r)
        val p = r.parsed
        val fprint = messageFingerprint(r)
        val dup = seenFingerprints[fprint]
        if (dup != null) classifyRetransmit(dup, r) else seenFingerprints[fprint] = r

        when (p.kind) {
            StartLineKind.REQUEST -> onRequest(r, clock)
            StartLineKind.STATUS -> onStatus(r, clock)
            StartLineKind.UNKNOWN -> globalCandidates += Candidate(
                id = "cand:unparsed:${r.id}", candidateType = "unknown",
                summary = "记录 ${r.id} 无法解析为 SIP 消息，保留原字节",
                status = "proposed", confidence = "unknown",
                rejectReason = p.diagnostics.joinToString(";") { it.code },
                sources = listOf(r.id),
            )
        }
    }

    private fun messageFingerprint(r: RawRecord): String {
        val p = r.parsed
        val topBranch = p.viaBranches.firstOrNull() ?: "-"
        return listOf(
            r.direction.name, topBranch, p.callId ?: "-", p.method ?: "R${p.statusCode}",
            p.cseqSeq?.toString() ?: "-", p.cseqMethod ?: "-", p.toTag ?: "-",
            r.connectionId, r.connectionGeneration.toString(), r.bytesBase64,
        ).joinToString("|")
    }

    private fun classifyRetransmit(orig: RawRecord, re: RawRecord) {
        val gapMs = (re.recvNanos - orig.recvNanos) / 1_000_000
        val withinWindow = gapMs in 0..params.retransmitWindowMs
        val sameConnGen = orig.connectionId == re.connectionId &&
            orig.connectionGeneration == re.connectionGeneration
        val basis = if (re.transport.equals("UDP", true))
            "RFC3261 §17.1.1/§17.2.2 UDP 重传由虚拟时钟 T1 倍增序列解释"
        else "TCP 上相同字节不应视为重传；连接复用不能抹掉消息身份"
        val explained = withinWindow && sameConnGen && re.transport.equals("UDP", true)
        retransmits += RetransmitEvent(
            id = "rtx:${orig.id}:${re.id}", originalId = orig.id, retransId = re.id,
            gapMs = gapMs, explained = explained, basis = basis,
        )
        if (!withinWindow || !sameConnGen) {
            globalCandidates += Candidate(
                id = "cand:dup:${re.id}", candidateType = "retransmit",
                summary = "字节重复记录 ${re.id} 与 ${orig.id} 间隔 ${gapMs}ms",
                status = "proposed", confidence = "ambiguous",
                rejectReason = if (!sameConnGen) "连接代次不同：重连后同字节仍为独立消息"
                else if (!re.transport.equals("UDP", true)) "非 UDP 传输：字节重复不自动归并重传"
                else "超出重传判定窗口",
                sources = listOf(orig.id, re.id),
            )
        }
    }

    private fun trackConnection(r: RawRecord) {
        val era = connections[r.connectionId]
        if (era == null) {
            connections[r.connectionId] = ConnectionEra(
                r.connectionId, r.connectionGeneration, r.transport, r.recvNanos, null,
                mutableListOf(r.id), note = newConnNote(r),
            )
        } else {
            val ids = era.recordIds.toMutableList(); ids += r.id
            connections[r.connectionId] = era.copy(recordIds = ids, closedAtNanos = null)
            if (era.generation != r.connectionGeneration) {
                globalCandidates += Candidate(
                    id = "cand:reconnect:${r.connectionId}:${r.connectionGeneration}",
                    candidateType = "connection",
                    summary = "连接 ${r.connectionId} 第 ${r.connectionGeneration} 代：TCP 重连",
                    status = "proposed", confidence = "certain",
                    rejectReason = "连接复用不能抹掉消息身份：重连前后记录各自独立",
                    sources = listOf(r.id),
                )
            }
        }
    }

    private fun newConnNote(r: RawRecord) =
        if (r.connectionGeneration > 0) "首见即为第 ${r.connectionGeneration} 代（更早代次未捕获）" else ""

    private fun roleFor(r: RawRecord) = if (r.direction == Direction.SENT) "client" else "server"

    private fun onRequest(r: RawRecord, clock: VirtualClock) {
        val p = r.parsed
        val branch = p.viaBranches.firstOrNull()
        if (branch == null) {
            globalCandidates += rejectNoBranch(r); return
        }
        val method = p.method ?: "UNKNOWN"
        val key = txKey(branch, method)
        val tx = txs.getOrPut(key) {
            ReplayEngine.Tx(key, branch, method, method == "INVITE").also { it.role = roleFor(r) }
        }
        tx.sources += r.id
        when (method) {
            "INVITE" -> {
                inviteByCallSeq.getOrPut(p.callId ?: branch) { mutableListOf() }.add(key)
                if (tx.states.isEmpty()) tx.states += initialClient("CALLING") to r.recvNanos
                armInviteClientTimers(tx, r, clock)
            }
            "ACK" -> handleAck(r, branch)
            "CANCEL" -> handleCancel(r, branch, clock)
            else -> {
                if (tx.states.isEmpty()) tx.states += "TRYING" to r.recvNanos
                armNonInviteTimers(tx, r)
            }
        }
    }

    private fun initialClient(s: String) = s

    private fun rejectNoBranch(r: RawRecord) = Candidate(
        id = "cand:nobranch:${r.id}", candidateType = "transaction",
        summary = "记录 ${r.id} 缺少 Via branch，无法归入事务",
        status = "rejected", confidence = "unknown",
        rejectReason = "RFC3261 §17.2.3 事务键需要 branch 参数",
        rejectedByDecision = null, sources = listOf(r.id),
    )

    private fun armInviteClientTimers(tx: ReplayEngine.Tx, r: RawRecord, clock: VirtualClock) {
        val t1 = params.timerT1Ms
        timers += TimerEvent(
            id = "timer:B:${tx.id}", txId = tx.id, name = "Timer B",
            armedAtNanos = r.recvNanos, fireAtNanos = r.recvNanos + 64 * t1 * 1_000_000,
            durationMs = 64 * t1, basis = "RFC3261 §17.1.1 INVITE client 事务超时 64*T1",
            state = "armed", sources = listOf(r.id),
        )
        timers += TimerEvent(
            id = "timer:A:${tx.id}:1", txId = tx.id, name = "Timer A#1",
            armedAtNanos = r.recvNanos, fireAtNanos = r.recvNanos + t1 * 1_000_000,
            durationMs = t1, basis = "RFC3261 §17.1.1 UDP INVITE 重传 T1,2T1,...（虚拟时钟）",
            state = "armed", sources = listOf(r.id),
        )
    }

    private fun armNonInviteTimers(tx: ReplayEngine.Tx, r: RawRecord) {
        timers += TimerEvent(
            id = "timer:F:${tx.id}", txId = tx.id, name = "Timer F",
            armedAtNanos = r.recvNanos, fireAtNanos = r.recvNanos + params.nonInviteTimeoutMs * 1_000_000,
            durationMs = params.nonInviteTimeoutMs, basis = "RFC3261 §17.1.2 非 INVITE client 事务最终响应急限",
            state = "armed", sources = listOf(r.id),
        )
    }

    private fun handleCancel(r: RawRecord, branch: String, clock: VirtualClock) {
        val cancelKey = txKey(branch, "CANCEL")
        val cancelTx = txs.getOrPut(cancelKey) {
            ReplayEngine.Tx(cancelKey, branch, "CANCEL", false).also { it.role = roleFor(r) }
        }
        cancelTx.sources += r.id
        if (cancelTx.states.isEmpty()) cancelTx.states += "TRYING" to r.recvNanos
        timers += TimerEvent(
            id = "timer:F:${cancelKey}", txId = cancelKey, name = "Timer F",
            armedAtNanos = r.recvNanos,
            fireAtNanos = r.recvNanos + params.nonInviteTimeoutMs * 1_000_000,
            durationMs = params.nonInviteTimeoutMs, basis = "RFC3261 §17.1.2 CANCEL 是非 INVITE client 事务",
            state = "armed", sources = listOf(r.id),
        )
        val inviteKey = txKey(branch, "INVITE")
        val inviteTx = txs[inviteKey]
        val candidates = mutableListOf<String>()
        if (inviteTx != null) {
            inviteTx.cancelledAt = r.recvNanos
            candidates += inviteTx.id
            inviteTx.notes += "收到 CANCEL(${r.id})：CANCEL 与 INVITE 是不同事务"
        }
        ownership += OwnershipClaim(
            id = "own:${r.id}", messageId = r.id,
            claimedBy = (listOf(cancelKey) + candidates),
            resolved = cancelKey,
            basis = "CANCEL 建立独立非 INVITE 事务；它关联但不等于原 INVITE 事务（RFC3261 §9.1）",
            rejected = if (inviteTx != null) listOf(inviteTx.id to "CANCEL 不能并入 INVITE 事务") else emptyList(),
        )
    }

    private fun handleAck(r: RawRecord, branch: String) {
        val ackKey = txKey(branch, "ACK")
        val ackTx = txs.getOrPut(ackKey) {
            ReplayEngine.Tx(ackKey, branch, "ACK", false).also { it.role = roleFor(r) }
        }
        ackTx.sources += r.id
        if (ackTx.states.isEmpty()) ackTx.states += "SENT" to r.recvNanos
        val inviteTx = txs[txKey(branch, "INVITE")]
        val non2xx = inviteTx?.states?.any { it.first == "COMPLETED" } == true
        val accepted = inviteTx?.states?.any { it.first == "TERMINATED_2XX" || it.first == "ACCEPTED" } == true
        val basis = if (non2xx && !accepted)
            "RFC3261 §17.1.1.3：非 2xx 的 ACK 属于 INVITE client 事务的一部分（重传由事务层负责）"
        else
            "RFC3261 §13.2.2.4：2xx 的 ACK 是端到端独立事务（逐跳重传由 UAC 负责）"
        val claimResolved = if (non2xx && !accepted) inviteTx?.id else ackKey
        ownership += OwnershipClaim(
            id = "own:${r.id}", messageId = r.id,
            claimedBy = listOfNotNull(inviteTx?.id, ackKey).distinct(),
            resolved = claimResolved,
            basis = basis,
            rejected = if (claimResolved == ackKey && inviteTx != null)
                listOf(inviteTx.id to "2xx ACK 不归属 INVITE 事务：非二百 ACK 判定为独立端到端事务")
            else if (claimResolved == inviteTx?.id) listOf(ackKey to "非 2xx ACK 无独立事务身份")
            else emptyList(),
        )
        inviteTx?.ackedAt = r.recvNanos
    }

    private fun onStatus(r: RawRecord, clock: VirtualClock) {
        val p = r.parsed
        val branch = p.viaBranches.firstOrNull()
        val code = p.statusCode
        if (branch == null || code == null) {
            globalCandidates += Candidate(
                id = "cand:badresp:${r.id}", candidateType = "transaction",
                summary = "响应 ${r.id} 缺 branch 或状态码", status = "rejected",
                confidence = "unknown", rejectReason = "无法定位响应所属事务", sources = listOf(r.id),
            )
            return
        }
        val method = p.cseqMethod ?: "UNKNOWN"
        val key = txKey(branch, method)
        val tx = txs.getOrPut(key) {
            ReplayEngine.Tx(key, branch, method, method == "INVITE").also { it.role = roleFor(r) }
        }
        tx.sources += r.id
        when {
            tx.isInvite && code in 100..199 -> {
                if (tx.finalState == "CALLING" || tx.states.isEmpty()) tx.states += "PROCEEDING" to r.recvNanos
                if (tx.branchPoint == null) tx.branchPoint = r.recvNanos
                cancelArmedTimers(tx, r)
                createOrReuseEarlyDialog(r, tx)
            }
            tx.isInvite && code == 200 -> {
                // 晚到 200：若已 COMPLETED（已走 CANCEL/非 2xx 路径），仍记录，不静默抹掉
                if (tx.finalState == "COMPLETED") {
                    tx.notes += "晚到 200(${r.id})：事务已 COMPLETED，保留为独立候选，不覆盖历史"
                    globalCandidates += Candidate(
                        id = "cand:late2xx:${r.id}", candidateType = "transaction",
                        summary = "晚到的 200 响应 ${r.id}", status = "proposed", confidence = "ambiguous",
                        rejectReason = "CANCEL 竞态：此前已有终结性非 2xx；2xx ACK 归属需人工裁定",
                        sources = listOf(r.id),
                    )
                }
                tx.states += "TERMINATED_2XX" to r.recvNanos
                cancelArmedTimers(tx, r)
                createConfirmedDialog(r, tx)
                timers += TimerEvent(
                    id = "timer:ACK:${r.id}", txId = tx.id, name = "2xx-ACK retransmit",
                    armedAtNanos = r.recvNanos, fireAtNanos = r.recvNanos + params.timerT1Ms * 1_000_000,
                    durationMs = params.timerT1Ms,
                    basis = "RFC3261 §13.2.2.4 2xx 由 UAC 端到端重传 ACK（独立事务，虚拟时钟）",
                    state = "armed", sources = listOf(r.id),
                )
            }
            tx.isInvite && code in 300..699 -> {
                if (tx.finalState != "COMPLETED" && tx.finalState != "TERMINATED_2XX") {
                    tx.states += "COMPLETED" to r.recvNanos
                    timers += TimerEvent(
                        id = "timer:D:${r.id}", txId = tx.id, name = "Timer D",
                        armedAtNanos = r.recvNanos,
                        fireAtNanos = r.recvNanos + maxOf(32000, 64 * params.timerT1Ms) * 1_000_000,
                        durationMs = maxOf(32000, 64 * params.timerT1Ms),
                        basis = "RFC3261 §17.1.1.2 非 2xx 后等待吸收重传",
                        state = "armed", sources = listOf(r.id),
                    )
                    tx.states += "TERMINATED" to r.recvNanos + 1
                }
                cancelArmedTimers(tx, r)
            }
            !tx.isInvite && code in 100..199 -> {
                if (tx.finalState == "TRYING" || tx.states.isEmpty()) tx.states += "PROCEEDING" to r.recvNanos
            }
            !tx.isInvite && code in 200..699 -> {
                if (tx.finalState != "COMPLETED") tx.states += "COMPLETED" to r.recvNanos
                timers.firstOrNull { it.txId == tx.id && it.name == "Timer F" }?.let {
                    timers[timers.indexOf(it)] = it.copy(state = "cancelled")
                }
            }
        }
    }

    private fun cancelArmedTimers(tx: ReplayEngine.Tx, r: RawRecord) {
        val toCancel = timers.withIndex().filter { (_, t) ->
            (t.name == "Timer B" || t.name.startsWith("Timer A")) && t.txId == tx.id && t.state == "armed"
        }
        for ((idx, t) in toCancel) timers[idx] = t.copy(state = "cancelled")
    }

    private fun sipUri(v: String?): String? {
        if (v == null) return null
        val s = v.indexOf('<')
        val e = v.indexOf('>')
        return if (s >= 0 && e > s) v.substring(s + 1, e) else v.substringBefore(';').trim().ifBlank { null }
    }

    private fun createOrReuseEarlyDialog(r: RawRecord, tx: ReplayEngine.Tx) {
        val p = r.parsed
        val toTag = p.toTag
        val callId = p.callId ?: return
        if (toTag == null) {
            globalCandidates += Candidate(
                id = "cand:notag:${r.id}", candidateType = "dialog",
                summary = "临时响应 ${r.id} 无 To tag，仅维持事务，不建 dialog",
                status = "rejected", confidence = "certain",
                rejectReason = "RFC3261 §12.1.1 dialog 需要 Call-ID + local/remote tag",
                sources = listOf(r.id),
            )
            return
        }
        val id = "dlg:$callId:${p.fromTag ?: "uac"}:$toTag"
        val existing = dialogs.firstOrNull { it.id == id }
        if (existing != null) {
            // 同一 fork 的继续响应：只补充证据，不新建 dialog
            val more = existing.sources + r.id
            dialogs[dialogs.indexOf(existing)] = existing.copy(sources = more.distinct())
            return
        }
        // route set：UAC 侧 Record-Route 逆序；连接复用不影响 route 身份
        val routeSet = p.recordRoute.mapNotNull { sipUri(it) }.asReversed()
        val earlierTags = dialogs.filter {
            it.callId == callId && it.localTag == (p.fromTag ?: "uac") && it.phase == "early"
        }.map { it.remoteTag }
        val note = if (earlierTags.isNotEmpty())
            "forking：同-INVITE 事务上第 ${earlierTags.size + 1} 个 early dialog（不同 To tag）"
        else "首个 early dialog"
        val cand = Candidate(
            id = "cand:early:${id}", candidateType = "dialog",
            summary = "early dialog $id（$note）", status = "proposed",
            confidence = "certain", sources = listOf(r.id),
        )
        globalCandidates += cand
        dialogs += DialogNode(
            id = id, callId = callId, phase = "early",
            localTag = p.fromTag ?: "uac", remoteTag = toTag,
            localTarget = sipUri(p.contact), remoteTarget = null,
            cseq = p.cseqSeq, routeSet = routeSet, parentId = null,
            sources = listOf(r.id), candidates = listOf(cand), note = note,
        )
        detectTagConflict(callId, p.fromTag, toTag, r)
    }

    private fun createConfirmedDialog(r: RawRecord, tx: ReplayEngine.Tx) {
        val p = r.parsed
        val callId = p.callId ?: return
        val toTag = p.toTag
        if (toTag == null) {
            globalCandidates += Candidate(
                id = "cand:conf-notag:${r.id}", candidateType = "dialog",
                summary = "200 ${r.id} 缺 To tag，不能确认 dialog", status = "rejected",
                confidence = "unknown", rejectReason = "缺少 remote tag，dialog 身份不完整",
                sources = listOf(r.id),
            )
            return
        }
        val id = "dlg:$callId:${p.fromTag ?: "uac"}:$toTag"
        val routeSet = p.recordRoute.mapNotNull { sipUri(it) }.asReversed()
        val parent = dialogs.firstOrNull { it.id == id && it.phase == "early" }
        // tag 冲突：同 remote tag 在不同分支已被别的 early/confirmed 谱系占用
        val clash = dialogs.firstOrNull { it.remoteTag == toTag && it.callId == callId && it.id != id }
        if (clash != null) {
            globalCandidates += Candidate(
                id = "cand:tagclash:${r.id}", candidateType = "dialog",
                summary = "tag 冲突：$toTag 已属于 ${clash.id}", status = "proposed",
                confidence = "ambiguous",
                rejectReason = "无法唯一决定归属，保留两个候选等待人工裁定",
                sources = listOf(r.id) + clash.sources,
            )
        }
        val cand = Candidate(
            id = "cand:confirmed:${id}", candidateType = "dialog",
            summary = "confirmed dialog $id", status = "proposed", confidence = "certain",
            sources = listOf(r.id),
        )
        globalCandidates += cand
        dialogs += DialogNode(
            id = id + ":confirmed", callId = callId, phase = "confirmed",
            localTag = p.fromTag ?: "uac", remoteTag = toTag,
            localTarget = sipUri(p.contact), remoteTarget = sipUri(p.contact),
            cseq = p.cseqSeq, routeSet = routeSet,
            parentId = parent?.id, sources = listOf(r.id) + parent.sources,
            candidates = listOf(cand),
            note = if (parent == null) "无 early 父节点（200 前未见带 tag 临时响应）"
            else "由 early dialog ${parent.id} 确认",
        )
    }

    private fun detectTagConflict(callId: String, fromTag: String?, remoteTag: String, r: RawRecord) {
        // 同 Call-ID 下同一 remote tag 出现不同 from 组合 / 重复 tag 指向不同对话目标
        val clash = dialogs.firstOrNull { it.remoteTag == remoteTag && it.callId == callId && it.localTag != (fromTag ?: "uac") }
        if (clash != null) {
            globalCandidates += Candidate(
                id = "cand:tagconflict:${remoteTag}:${r.id}", candidateType = "dialog",
                summary = "tag 冲突：remote tag $remoteTag 对应不同 local tag",
                status = "proposed", confidence = "ambiguous",
                rejectReason = "RFC3261 §12 同一 tag 组合必须唯一；保留全部合法候选",
                sources = listOf(r.id) + clash.sources,
            )
        }
    }

    fun closeConnections(endNanos: Long) {
        for ((k, era) in connections) {
            connections[k] = era.copy(closedAtNanos = endNanos)
        }
    }

    fun buildTransactionStates(decisions: List<Decision>): List<TransactionState> {
        return txs.values.map { tx ->
            val cands = globalCandidates.filter { c ->
                c.candidateType == "transaction" && tx.sources.any { it in c.sources }
            }
            TransactionState(
                id = tx.id, branch = tx.branch, method = tx.method, role = tx.role,
                state = tx.finalState, candidates = cands, sources = tx.sources.distinct(),
                notes = tx.notes,
            )
        }
    }
}
