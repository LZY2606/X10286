package review.engine

import review.*

/** 单个事务的运行态。 */
class TxDynamic(
    val rec: TransactionRecord,
) {
    var state: String = rec.state
    val messageIds: MutableList<String> = rec.messageIds.toMutableList()
    var linkedId: String? = rec.linkedTransactionId
    var linkReason: String? = rec.linkReason
    val timers: MutableList<TimerRuntime> = mutableListOf()
}

class TimerRuntime(
    val name: String,
    val armedAtMs: Double,
    var deadlineMs: Double,
    var fired: Boolean = false,
    var canceledBy: String? = null,
    val rationale: String,
    var retransmitOf: String? = null,
)

class EngineContext(
    val input: EngineInput,
    val basisEventId: String,
) {
    val p: EngineParams = input.params
    private val fp: String = p.fingerprint()
    val txs: MutableMap<String, TxDynamic> = LinkedHashMap()
    private val txByBranchMethod: MutableMap<String, String> = HashMap()
    private val inviteTxByCallId: MutableMap<String, MutableList<String>> = LinkedHashMap()
    val dialogs: MutableMap<String, DialogRecord> = LinkedHashMap()
    val candidates: MutableList<CandidateRecord> = mutableListOf()
    val retransmits: MutableList<RetransmitLink> = mutableListOf()
    val diags: MutableList<FormatDiagnostic> = mutableListOf()
    val timerRecords: MutableList<TimerDecisionRecord> = mutableListOf()
    private var candidateSeq = 0
    private var timerSeq = 0

    private fun now(m: MessageRecord): Double? = m.receivedAt.value

    fun deliver(m: MessageRecord) {
        if (m.kind == null) {
            candidate(
                "message", m.id, "message-parseable", CandidateStatus.UNKNOWN,
                "报文起始行/头字段无法解析，无法进入任何事务",
                "起始行或必要头字段缺失/损坏", confidence = 0.0, messages = listOf(m.id),
            )
            return
        }
        detectRetransmit(m)
        when (m.kind) {
            MessageKind.REQUEST -> onRequest(m)
            MessageKind.RESPONSE -> onResponse(m)
        }
    }

    private fun onRequest(m: MessageRecord) {
        val branch = m.viaBranch
        val method = m.method!!
        if (branch == null) {
            candidate("message", m.id, "tx-branch", CandidateStatus.UNKNOWN,
                "请求缺少 Via branch，无法按 RFC3261 17.2.3 匹配事务",
                "Via 头缺少 branch 参数", confidence = 0.0, messages = listOf(m.id))
            return
        }
        val role = if (m.direction == Direction.EGRESS) TxRole.CLIENT else TxRole.SERVER
        val key = txKey(branch, method, role)
        // ACK：与 INVITE 同 branch 的是非 2xx ACK（属于该 INVITE 事务）；branch 不同的是 2xx ACK（独立事务）
        if (method == "ACK") {
            val invite = txByBranchMethod[txKey(branch, "INVITE", role)]
            if (invite != null) {
                val tx = txs.getValue(invite)
                tx.messageIds += m.id
                candidate("transaction", tx.rec.id, "non2xx-ack-tx", CandidateStatus.ACCEPTED,
                    "ACK branch 与 INVITE 相同：逐跳 ACK 属于本 INVITE 事务（不另建事务、不走 NICT 重传）",
                    messages = listOf(m.id))
                if (tx.state == "COMPLETED" && tx.rec.role == TxRole.SERVER) {
                    tx.state = "TERMINATED"
                    cancelTimer(tx, "Timer H", m.id)
                }
                return
            }
        }
        val existing = txByBranchMethod[key]
        if (existing != null) {
            // 同 branch+method+role 的再次出现：UDP 重传请求；TCP 上则是协议异常但身份不抹掉
            val tx = txs.getValue(existing)
            tx.messageIds += m.id
            if (m.transport == Transport.UDP) {
                onServerRetransmit(tx, m)
            } else {
                candidate("transaction", tx.rec.id, "tcp-reuse-request", CandidateStatus.REJECTED,
                    "同一 branch+method 在 ${m.transport} 连接代次 ${m.connectionGeneration} 再次到达，连接复用未抹掉消息身份",
                    "面向流传输上 branch 应唯一；保留为独立消息 ${m.id}，不合并",
                    messages = listOf(m.id))
            }
            return
        }
        val family = if (method == "INVITE") TxFamily.INVITE else TxFamily.NON_INVITE
        val initial = if (role == TxRole.CLIENT) {
            if (family == TxFamily.INVITE) "CALLING" else "TRYING"
        } else {
            if (family == TxFamily.INVITE) "PROCEEDING" else "TRYING"
        }
        val tx = createTx(branch, method, family, role, m, initial)
        when {
            role == TxRole.CLIENT && method == "ACK" ->
                candidate("transaction", tx.rec.id, "2xx-ack-tx", CandidateStatus.ACCEPTED,
                    "2xx 的 ACK 使用独立 branch：端到端 ACK 是独立事务，重传由 TU/dialog 负责而非 NICT 定时器",
                    messages = listOf(m.id))
            role == TxRole.CLIENT && family == TxFamily.INVITE -> {
                arm(tx, "Timer A", now(m)!!, p.t1Ms.toDouble(), "RFC3261 17.1.1.2: INVITE 重传，T1 起步倍增", m.id)
                arm(tx, "Timer B", now(m)!!, p.timerBTimeoutMs.toDouble(), "RFC3261 17.1.1.2: 事务层超时 64*T1", m.id)
            }
            role == TxRole.CLIENT -> {
                arm(tx, "Timer E", now(m)!!, p.t1Ms.toDouble(), "RFC3261 17.1.2.2: 非 INVITE 请求重传", m.id)
                arm(tx, "Timer F", now(m)!!, p.timerFTimeoutMs.toDouble(), "RFC3261 17.1.2.2: 非 INVITE 事务超时", m.id)
            }
            role == TxRole.SERVER && family == TxFamily.INVITE ->
                arm(tx, "Timer G", now(m)!!, p.t1Ms.toDouble(), "RFC3261 17.2.1: 非可靠传输上 INVITE 响应重传", m.id)
            role == TxRole.SERVER ->
                arm(tx, "Timer H", now(m)!!, p.t1Ms.toDouble() * 64, "RFC3261 17.2.2: 等待 ACK 到达 (64*T1)", m.id)
        }
        if (role == TxRole.CLIENT && method == "ACK") {
            // ACK 归属：2xx 的 ACK 是独立事务；非 2xx ACK 在 onResponse 时已链接到 INVITE 事务
            linkAckToInvite(tx, m, twoXxAck = true)
        }
        if (method == "CANCEL" && role == TxRole.CLIENT) {
            linkCancelToInvite(tx, m)
        }
    }

    private fun onResponse(m: MessageRecord) {
        val branch = m.viaBranch
        val code = m.statusCode!!
        if (branch == null) {
            candidate("message", m.id, "tx-branch", CandidateStatus.UNKNOWN,
                "响应缺少 Via branch，无法匹配客户端事务", "Via 头缺少 branch 参数",
                confidence = 0.0, messages = listOf(m.id)); return
        }
        // 响应的 method 取自 CSeq（响应中 CSeq 携带被应答的方法）
        val method = m.cseqMethod
        val role = if (m.direction == Direction.INGRESS) TxRole.CLIENT else TxRole.SERVER
        val key = txKey(branch, method ?: "UNKNOWN", role)
        val existing = txByBranchMethod[key]
        if (existing == null) {
            candidate("message", m.id, "orphan-response", CandidateStatus.UNKNOWN,
                "响应 $code 找不到 branch=$branch method=$method 的${if (role == TxRole.CLIENT) "客户端" else "服务端"}事务",
                "可能为晚到响应：原始请求不在当前输入或已被新版本替换",
                confidence = 0.3, messages = listOf(m.id))
            return
        }
        val tx = txs.getValue(existing)
        tx.messageIds += m.id
        when (tx.rec.family) {
            TxFamily.INVITE -> inviteResponse(tx, m, code)
            TxFamily.NON_INVITE -> nonInviteResponse(tx, m, code, role)
        }
    }

    private fun nonInviteResponse(tx: TxDynamic, m: MessageRecord, code: Int, role: TxRole) {
        if (role == TxRole.CLIENT) {
            when {
                code in 100..199 -> { tx.state = "PROCEEDING"; cancelTimer(tx, "Timer E", m.id) }
                else -> {
                    tx.state = "COMPLETED"
                    cancelTimer(tx, "Timer E", m.id); cancelTimer(tx, "Timer F", m.id)
                    val k = if (m.transport == Transport.UDP) p.timerKTimeoutMs else 0L
                    if (k > 0) arm(tx, "Timer K", now(m)!!, k.toDouble(), "RFC3261 17.1.2.2: COMPLETED 中吸收重传", m.id)
                    else tx.state = "TERMINATED"
                }
            }
        } else {
            when {
                code in 100..199 -> tx.state = "PROCEEDING"
                else -> {
                    tx.state = "COMPLETED"
                    if (m.transport == Transport.UDP) arm(tx, "Timer J", now(m)!!, p.t4Ms.toDouble(), "RFC3261 17.2.2: 吸收重传响应", m.id)
                    else tx.state = "TERMINATED"
                }
            }
        }
        // CANCEL 的 200 OK 不取消 INVITE 事务本身
        if (tx.rec.method == "CANCEL" && code in 200..299) {
            candidate("transaction", tx.rec.id, "cancel-2xx", CandidateStatus.ACCEPTED,
                "CANCEL 收到 $code：CANCEL 事务完成；INVITE 事务等待 487（独立状态机）",
                messages = listOf(m.id), confidence = 1.0)
        }
    }

    private fun onServerRetransmit(tx: TxDynamic, m: MessageRecord) {
        if (tx.rec.family == TxFamily.INVITE && tx.state == "COMPLETED") {
            // 重传最后响应由 Timer G 负责；Timer H 仍在等 ACK
            candidate("transaction", tx.rec.id, "ist-invite-retransmit", CandidateStatus.ACCEPTED,
                "COMPLETED 状态收到 INVITE 重传：重传最后的响应，继续等待 ACK（Timer H）",
                messages = listOf(m.id))
        } else {
            candidate("transaction", tx.rec.id, "server-request-retransmit", CandidateStatus.ACCEPTED,
                "${tx.state} 状态收到请求重传：重发最后响应，状态不变",
                messages = listOf(m.id))
        }
    }

    private fun createTx(
        branch: String, method: String, family: TxFamily, role: TxRole,
        first: MessageRecord, initial: String,
    ): TxDynamic {
        val id = stableId("tx", listOf(branch, method, role.name, first.callId, first.cseqNumber).joinToString("|"))
        val rec = TransactionRecord(
            id = id, branch = branch, method = method, family = family, role = role,
            callId = first.callId, cseq = first.cseqNumber, state = initial,
            messageIds = listOf(first.id),
            algorithmVersion = p.algorithmVersion, paramsFingerprint = fp,
            sourceMessageIds = listOf(first.id), basisEventId = basisEventId,
        )
        txs[id] = TxDynamic(rec)
        txByBranchMethod[txKey(branch, method, role)] = id
        if (method == "INVITE" && first.callId != null)
            inviteTxByCallId.getOrPut(first.callId) { mutableListOf() }.add(id)
        return txs.getValue(id)
    }

    private fun txKey(branch: String, method: String, role: TxRole) = "$branch|$method|${role.name}"

    fun candidate(
        subjectType: String, subjectId: String, claim: String, status: CandidateStatus,
        rationale: String, rejectedReason: String? = null, confidence: Double = 1.0,
        messages: List<String>, timerName: String? = null, timerDeadline: UnitValue? = null,
    ) {
        candidateSeq += 1
        val id = stableId(
            "cand",
            listOf(subjectType, subjectId, claim, status.name, rationale, messages.joinToString(","), candidateSeq.toString())
                .joinToString("|"),
        )
        candidates += CandidateRecord(
            id = id, subjectType = subjectType, subjectId = subjectId, claim = claim,
            status = status, rationale = rationale, rejectedReason = rejectedReason,
            confidence = confidence, messageIds = messages, timerName = timerName,
            timerDeadline = timerDeadline, algorithmVersion = p.algorithmVersion,
            paramsFingerprint = fp, sourceMessageIds = messages, basisEventId = basisEventId,
        )
    }

    private fun inviteResponse(tx: TxDynamic, m: MessageRecord, code: Int) {
        val isClient = tx.rec.role == TxRole.CLIENT
        val toTag = m.toTag
        when {
            code in 100..199 -> {
                tx.state = "PROCEEDING"
                if (isClient) cancelTimer(tx, "Timer A", m.id)
                else if (tx.timers.any { it.name == "Timer G" }) cancelTimer(tx, "Timer G", m.id)
                candidate("transaction", tx.rec.id, "provisional", CandidateStatus.ACCEPTED,
                    "$code Proceeding：停止重传，等待后续响应", messages = listOf(m.id))
            }
            code in 200..299 -> {
                if (isClient) {
                    // 晚到 2xx：B 已触发（已 TERMINATED）时 2xx 仍必须被 ACK，且 ACK 是端到端独立事务
                    if (tx.state == "TERMINATED") {
                        candidate("transaction", tx.rec.id, "late-2xx", CandidateStatus.ACCEPTED,
                            "晚到 $code：事务已终止，但 2xx 不能用事务层重传吸收，必须由 TU 发独立 ACK（RFC3261 13.2.2.4）",
                            messages = listOf(m.id), confidence = 1.0)
                    } else {
                        tx.state = "TERMINATED"
                        cancelTimer(tx, "Timer A", m.id); cancelTimer(tx, "Timer B", m.id)
                        candidate("transaction", tx.rec.id, "2xx-terminated", CandidateStatus.ACCEPTED,
                            "$code：ICT 直接进入 TERMINATED；ACK 由 TU 用独立事务发送（不匹配本事务 branch）",
                            messages = listOf(m.id))
                    }
                } else {
                    tx.state = "CONFIRMED"
                    cancelTimer(tx, "Timer H", m.id)
                    if (m.transport == Transport.UDP) arm(tx, "Timer I", now(m)!!, p.t4Ms.toDouble(), "RFC3261 17.2.1: CONFIRMED 中重传 2xx 直到 ACK", m.id)
                    else tx.state = "TERMINATED"
                }
                upsertDialog(tx, m, confirmed = true)
                checkTagConflict(tx, m)
            }
            code in 300..699 -> {
                if (isClient) {
                    if (tx.state == "TERMINATED") {
                        candidate("transaction", tx.rec.id, "late-non2xx", CandidateStatus.REJECTED,
                            "晚到 $code：事务已 TERMINATED，非 2xx 响应被丢弃（应由 408/已发 ACK 覆盖）",
                            rejectedReason = "ICT 已终止后的非 2xx 响应无状态处理", messages = listOf(m.id))
                        return
                    }
                    tx.state = "COMPLETED"
                    cancelTimer(tx, "Timer A", m.id); cancelTimer(tx, "Timer B", m.id)
                    if (m.transport == Transport.UDP) arm(tx, "Timer D", now(m)!!, p.timerDTimeoutMs.toDouble(),
                        "RFC3261 17.1.1.2: COMPLETED 等待重传吸收 (>=32s)", m.id)
                    else tx.state = "TERMINATED"
                    candidate("transaction", tx.rec.id, "non-2xx-ack", CandidateStatus.ACCEPTED,
                        "$code：ACK 逐跳属于本 INVITE 事务（同 branch），与 2xx 的端到端 ACK 归属不同",
                        messages = listOf(m.id))
                } else {
                    tx.state = "COMPLETED"
                    cancelTimer(tx, "Timer G", m.id)
                    arm(tx, "Timer H", now(m)!!, 0.0, "RFC3261 17.2.1: COMPLETED，等待 ACK（忽略后续重传）", m.id)
                }
                upsertDialog(tx, m, confirmed = false)
            }
        }
    }

    /** forking：每个不同 to-tag 的 1xx/2xx 都建立一个 dialog 候选（多个 early dialog 合法）。 */
    private fun upsertDialog(tx: TxDynamic, m: MessageRecord, confirmed: Boolean) {
        val callId = m.callId ?: return
        val localTag = m.fromTag
        val remoteTag = m.toTag
        if (remoteTag == null) {
            candidate("dialog", "callid=$callId", "early-dialog", CandidateStatus.UNKNOWN,
                "响应无 to-tag，无法建立 dialog（100 Trying 不建 dialog）",
                "To 头缺少 tag 参数", confidence = 0.0, messages = listOf(m.id)); return
        }
        if (m.statusCode == 100) {
            candidate("dialog", "callid=$callId", "trying-no-dialog", CandidateStatus.REJECTED,
                "100 Trying 不创建 dialog（RFC3261 13.2.2.1）",
                rejectedReason = "100 是逐跳临时响应且通常无 to-tag", messages = listOf(m.id)); return
        }
        val isUas = tx.rec.role == TxRole.SERVER
        val dialogId = stableId("dlg", listOf(callId, localTag ?: "", remoteTag).joinToString("|"))
        val routeSet = if (isUas) m.recordRoute.reversed() else m.recordRoute.toList()
        val existingDialog = dialogs[dialogId]
        val parentFork = if (existingDialog == null) pickForkParent(callId, dialogId) else existingDialog.forkedFromDialogId
        val state = when {
            confirmed -> DialogState.CONFIRMED
            existingDialog?.state == DialogState.CONFIRMED -> DialogState.CONFIRMED
            else -> DialogState.EARLY
        }
        dialogs[dialogId] = DialogRecord(
            id = dialogId, callId = callId,
            localTag = localTag ?: "", remoteTag = remoteTag,
            state = state, forkedFromDialogId = parentFork,
            establishedByMessageId = m.id,
            routeSet = if (existingDialog != null && confirmed) existingDialog.routeSet.ifEmpty { routeSet } else routeSet,
            remoteTarget = m.contact.firstOrNull() ?: existingDialog?.remoteTarget,
            localSeq = if (isUas) existingDialog?.localSeq else m.cseqNumber ?: existingDialog?.localSeq,
            remoteSeq = if (isUas) m.cseqNumber ?: existingDialog?.remoteSeq else existingDialog?.remoteSeq,
            messageIds = (existingDialog?.messageIds ?: emptyList()) + m.id,
            algorithmVersion = p.algorithmVersion, paramsFingerprint = fp,
            sourceMessageIds = listOf(m.id), basisEventId = basisEventId,
        )
        candidate("dialog", dialogId, if (confirmed) "confirmed-dialog" else "early-dialog",
            CandidateStatus.ACCEPTED,
            if (confirmed) "to-tag=$remoteTag 的响应建立 confirmed dialog"
            else "forking：to-tag=$remoteTag 的 ${m.statusCode} 建立独立 early dialog（同一 INVITE 可有多个）",
            messages = listOf(m.id))
    }

    private fun pickForkParent(callId: String, selfId: String): String? =
        dialogs.values.firstOrNull { it.callId == callId && it.id != selfId }?.id

    /** tag 冲突：同一 to-tag 先后表示不同远端身份（Contact/route 不一致）时不给唯一结论。 */
    private fun checkTagConflict(tx: TxDynamic, m: MessageRecord) {
        val callId = m.callId ?: return; val tag = m.toTag ?: return
        val sameTag = dialogs.values.filter { it.callId == callId && it.remoteTag == tag }
        val contacts = sameTag.mapNotNull { it.remoteTarget }.toSet()
        if (contacts.size > 1) {
            val id = stableId("dlg", listOf(callId, m.fromTag ?: "", tag).joinToString("|"))
            val d = dialogs[id]
            if (d != null) dialogs[id] = d.copy(state = DialogState.AMBIGUOUS)
            candidate("dialog", id, "tag-conflict", CandidateStatus.UNKNOWN,
                "tag 冲突：同一 to-tag=$tag 对应了多个 Contact 身份 $contacts；保留全部合法候选，不作静默合并",
                "无法唯一决定远端身份，需要人工裁定", confidence = 0.0, messages = listOf(m.id))
        }
    }

    /** CANCEL 与被取消的 INVITE 是两个事务：通过 Call-ID + CSeq 序号（CSeq method 不同）关联。 */
    private fun linkCancelToInvite(cancelTx: TxDynamic, cancel: MessageRecord) {
        val callId = cancel.callId
        val candidatesList = inviteTxByCallId[callId].orEmpty().mapNotNull { txs[it] }
            .filter { it.rec.role == TxRole.CLIENT && it.rec.cseq == cancel.cseqNumber }
        when {
            candidatesList.size == 1 -> {
                val invite = candidatesList.first()
                cancelTx.linkedId = invite.rec.id
                cancelTx.linkReason = "CANCEL 与 INVITE 是不同 branch 的独立事务；按 Call-ID+同 CSeq 序号关联"
                candidate("transaction", cancelTx.rec.id, "cancel-link", CandidateStatus.ACCEPTED,
                    "CANCEL(${cancel.viaBranch}) 关联 INVITE 事务 ${invite.rec.id}(${invite.rec.branch})，两者事务独立",
                    messages = listOf(cancel.id))
                if (invite.state == "PROCEEDING" || invite.state == "CALLING") {
                    candidate("transaction", invite.rec.id, "cancel-race", CandidateStatus.ACCEPTED,
                        "CANCEL 竞态：INVITE 仍在 ${invite.state}，等待 487 Request Terminated（若 2xx 已在路上则 CANCEL 无法撤回）",
                        messages = listOf(cancel.id))
                }
            }
            candidatesList.isEmpty() -> candidate("transaction", cancelTx.rec.id, "cancel-link", CandidateStatus.UNKNOWN,
                "CANCEL 找不到同 Call-ID+CSeq 的客户端 INVITE 事务",
                "被取消的 INVITE 不在输入中", confidence = 0.2, messages = listOf(cancel.id))
            else -> candidate("transaction", cancelTx.rec.id, "cancel-link", CandidateStatus.UNKNOWN,
                "CANCEL 可关联多个 INVITE 事务：${candidatesList.map { it.rec.id }}，保留全部候选",
                "无法唯一决定被取消事务", confidence = 0.0, messages = listOf(cancel.id))
        }
    }

    /** ACK 归属：非 2xx ACK 与 INVITE 同事务（同 branch）；2xx ACK 是独立事务，靠 dialog 关联。 */
    private fun linkAckToInvite(ackTx: TxDynamic, ack: MessageRecord, twoXxAck: Boolean) {
        if (twoXxAck) {
            // 2xx ACK：branch 不同，按 Call-ID + to-tag 找 confirmed dialog
            val dlg = dialogs.values.filter {
                it.callId == ack.callId && (it.remoteTag == ack.toTag || ack.toTag == null)
            }
            val confirmed = dlg.filter { it.state == DialogState.CONFIRMED }
            when {
                confirmed.size == 1 -> candidate("transaction", ackTx.rec.id, "2xx-ack-dialog", CandidateStatus.ACCEPTED,
                    "2xx 的 ACK 是端到端独立事务（branch=${ack.viaBranch}），按 dialog ${confirmed.first().id} 关联",
                    messages = listOf(ack.id))
                confirmed.size > 1 -> candidate("transaction", ackTx.rec.id, "2xx-ack-dialog", CandidateStatus.UNKNOWN,
                    "ACK 的 to-tag 匹配多个 confirmed dialog，保留全部候选",
                    "forking 多 2xx 时 ACK 必须逐 dialog 发送", confidence = 0.0, messages = listOf(ack.id))
                else -> candidate("transaction", ackTx.rec.id, "2xx-ack-dialog", CandidateStatus.UNKNOWN,
                    "2xx ACK 找不到 confirmed dialog（可能 ACK 晚到或 to-tag 缺失）",
                    "无 dialog 可归属", confidence = 0.2, messages = listOf(ack.id))
            }
            return
        }
    }

    /** UDP 重传：同字节 + 同 branch + 时间窗口；连接复用（同代次/跨代次）不抹掉消息身份。 */
    private fun detectRetransmit(m: MessageRecord) {
        val t = now(m) ?: return
        val same = input.messages.filter { other ->
            other.seq < m.seq && other.rawSha256 == m.rawSha256 && other.id != m.id &&
                other.viaBranch == m.viaBranch && other.method == m.method
        }
        for (orig in same) {
            val ot = orig.receivedAt.value ?: continue
            val elapsed = (t - ot).toLong()
            val within = elapsed in 0..(p.timerBTimeoutMs)
            val linkId = stableId("rtx", listOf(orig.id, m.id).joinToString("|"))
            retransmits += RetransmitLink(
                id = linkId, originalMessageId = orig.id, retransmitMessageId = m.id,
                transport = m.transport, connectionGeneration = m.connectionGeneration,
                elapsedMs = elapsed,
                rationale = if (m.transport == Transport.UDP)
                    "UDP 同字节重复（虚拟时钟间隔 ${elapsed}ms，T1=${p.t1Ms}ms 倍增窗口内=$within）：判为重传，消息身份各自保留"
                else "面向流传输 ${m.transport} 连接代次 ${orig.connectionGeneration}->${m.connectionGeneration} 出现同字节：TCP 重连/复用不抹掉消息身份，记录为异常重复",
                algorithmVersion = p.algorithmVersion, paramsFingerprint = fp, basisEventId = basisEventId,
            )
            candidate("message", m.id, "retransmit",
                if (within || m.transport != Transport.UDP) CandidateStatus.ACCEPTED else CandidateStatus.UNKNOWN,
                if (m.transport == Transport.UDP)
                    "消息 ${m.id} 是 ${orig.id} 的 UDP 重传（虚拟时钟 +${elapsed}ms），状态机吸收但身份独立"
                else "消息 ${m.id} 与 ${orig.id} 字节相同但处于连接代次 ${m.connectionGeneration}（TCP 重连）：不合并身份",
                rejectedReason = if (!within && m.transport == Transport.UDP) "超出事务重传窗口，可能是重复捕获" else null,
                confidence = if (within) 1.0 else 0.4, messages = listOf(orig.id, m.id))
        }
    }

    private fun arm(tx: TxDynamic, name: String, atMs: Double, durationMs: Double, rationale: String, by: MessageRecord? = null) {
        val rt = TimerRuntime(name, atMs, atMs + durationMs, rationale = rationale)
        tx.timers.add(rt)
    }

    private fun cancelTimer(tx: TxDynamic, name: String, by: MessageRecord) {
        tx.timers.lastOrNull { it.name == name && !it.fired && it.canceledBy == null }?.canceledBy = by.id
    }

    /** 用虚拟时钟推进：任何在当前消息时刻之前到期的定时器标记 fired 并产生状态转移。 */
    fun fireDue(untilMs: Double) {
        for (tx in txs.values) {
            for (t in tx.timers) {
                if (!t.fired && t.canceledBy == null && t.deadline <= untilMs) {
                    t.fired = true
                    onTimerFire(tx, t)
                }
            }
        }
    }

    private fun onTimerFire(tx: TxDynamic, t: TimerRuntime) {
        when (t.name) {
            "Timer A" -> {
                tx.state = if (tx.state == "CALLING") "CALLING" else tx.state
                val next = (t.deadline - t.armedAtMs) * 2
                if (next <= p.t2Ms.toDouble()) arm(tx, "Timer A", t.deadline, next, "Timer A 倍增，上限 T2=${p.t2Ms}ms")
                candidate("transaction", tx.rec.id, "timerA", CandidateStatus.ACCEPTED,
                    "Timer A 于虚拟时钟 ${t.deadline.toLong()}ms 到期：重传 INVITE", messages = tx.messageIds.takeLast(1),
                    timerName = t.name, timerDeadline = UnitValue("${t.deadline.toLong()}ms", "ms", "relative-to-clock-origin", t.deadline))
            }
            "Timer B" -> { tx.state = "TERMINATED"; candidate("transaction", tx.rec.id, "timerB", CandidateStatus.ACCEPTED,
                "Timer B 到期：无任何响应，ICT 终止（随后晚到 2xx 走 late-2xx 判定）", messages = tx.messageIds) }
            "Timer D" -> { tx.state = "TERMINATED"; candidate("transaction", tx.rec.id, "timerD", CandidateStatus.ACCEPTED,
                "Timer D 到期：COMPLETED 吸收窗口结束，ICT TERMINATED", messages = tx.messageIds) }
            "Timer F" -> { tx.state = "TERMINATED"; candidate("transaction", tx.rec.id, "timerF", CandidateStatus.ACCEPTED,
                "Timer F 到期：非 INVITE 客户端事务终止", messages = tx.messageIds) }
            "Timer E" -> {
                val next = (t.deadline - t.armedAtMs) * 2
                if (next <= p.t2Ms.toDouble()) arm(tx, "Timer E", t.deadline, next, "Timer E 倍增到 T2")
            }
            "Timer K", "Timer J" -> { tx.state = "TERMINATED" }
            "Timer H" -> {
                if (tx.state == "COMPLETED") { tx.state = "TERMINATED"; candidate("transaction", tx.rec.id, "timerH",
                    CandidateStatus.ACCEPTED, "Timer H 到期：未收到 ACK，IST 终止（408 语义）", messages = tx.messageIds) }
            }
            "Timer G" -> {
                if (tx.state in setOf("PROCEEDING", "COMPLETED")) arm(tx, "Timer G", t.deadline,
                    minOf((t.deadline - t.armedAtMs) * 2, p.t2Ms.toDouble()), "Timer G 倍增，上限 T2")
            }
            "Timer I" -> tx.state = "TERMINATED"
        }
    }

    fun finish(): EngineOutput {
        // 虚拟时钟推进到最后一条消息时刻，触发到期定时器
        val maxT = input.messages.mapNotNull { it.receivedAt.value }.maxOrNull()
        if (maxT != null) {
            // 多次推进以保证倍增链全部结算
            repeat(8) { fireDue(maxT) }
        }
        val txRecords = txs.values.map { dyn ->
            dyn.rec.copy(
                state = dyn.state,
                messageIds = dyn.messageIds.distinct(),
                linkedTransactionId = dyn.linkedId,
                linkReason = dyn.linkReason,
            )
        }
        txs.values.forEach { tx ->
            tx.timers.forEachIndexed { idx, t ->
                timerSeq += 1
                timerRecords += TimerDecisionRecord(
                    id = stableId("tmr", listOf(tx.rec.id, t.name, idx.toString()).joinToString("|")),
                    transactionId = tx.rec.id, timerName = t.name,
                    armedAt = UnitValue("${t.armedAtMs.toLong()}ms", "ms", "relative-to-clock-origin", t.armedAtMs),
                    deadline = UnitValue("${t.deadline.toLong()}ms", "ms", "relative-to-clock-origin", t.deadline),
                    durationMs = (t.deadline - t.armedAtMs).toLong(), fired = t.fired,
                    canceledByMessageId = t.canceledBy, rationale = t.rationale,
                    algorithmVersion = p.algorithmVersion, paramsFingerprint = fp, basisEventId = basisEventId,
                )
            }
        }
        applyManualDecisions()
        return EngineOutput(
            transactions = txRecords,
            dialogs = dialogs.values.toList(),
            candidates = candidates.sortedBy { it.id },
            timers = timerRecords.sortedBy { it.id },
            retransmits = retransmits.sortedBy { it.id },
            diagnostics = diags,
        )
    }

    /** 人工决定/撤销：只改变带 decision 来源的结果，生成 DECISION_* 事件由仓储负责；此处施加覆盖并留下候选。 */
    private fun applyManualDecisions() {
        val active = input.decisions.filter { !it.revoked }
        for (d in active) {
            when (d.targetType) {
                "candidate" -> {
                    val idx = candidates.indexOfFirst { it.id == d.targetId }
                    if (idx >= 0) {
                        val old = candidates[idx]
                        val newStatus = when (d.decision) {
                            DecisionKind.ACCEPT -> CandidateStatus.ACCEPTED
                            DecisionKind.REJECT -> CandidateStatus.REJECTED
                            DecisionKind.ADJUST_PARAMS -> old.status
                        }
                        candidates[idx] = old.copy(status = newStatus,
                            rationale = old.rationale + " [人工裁定@event#${d.eventSeq}: ${d.reason}]")
                    }
                }
                "dialog" -> {
                    val dlg = dialogs[d.targetId]
                    if (dlg != null && d.decision == DecisionKind.REJECT) {
                        dialogs[d.targetId] = dlg.copy(state = DialogState.TERMINATED)
                    }
                }
            }
        }
    }
}
