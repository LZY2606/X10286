package sipreview.engine

import sipreview.domain.*

const val ALGO_VERSION = "sip-engine-1.0.0"

/**
 * 纯函数式重放：给定 [0..n] 的历史事件，确定性地重建全部派生对象。
 * 回放到任意旧事件即调用本函数，得到当时的依赖版本与结论；历史不会被最新结果覆盖。
 */
class ReplayEngine(private val algoVersion: String = ALGO_VERSION) {

    fun replay(projectId: String, name: String, seed: Long, events: List<HistoryEvent>): ProjectView {
        val ctx = Ctx(projectId, seed, events)
        for (ev in events) ctx.apply(ev)
        ctx.buildView(name, events, algoVersion)
        return ctx.view
    }
}

private class Ctx(val projectId: String, val seed: Long, val allEvents: List<HistoryEvent>) {
    val versions = LinkedHashMap<String, MutableList<RawVersion>>() // logicalId -> 版本链
    val commits = LinkedHashMap<String, EventCommit>()
    val conns = LinkedHashMap<String, MutableList<ConnVersion>>()
    val clocks = mutableListOf<RawClockAnchor>()
    var params = EngineParams()
    var paramsEventId: String? = null

    data class RawVersion(val logicalId: String, val version: Int, val parentUnitId: String?,
                          val batchId: String, val eventId: String, val seq: Int, val input: RawMessageInput,
                          val rawBytes: ByteArray)
    data class EventCommit(val eventId: String, val seq: Int, val payload: InputCommitPayload)
    data class ConnVersion(val view: ConnEventView)

    fun apply(ev: HistoryEvent) {
        when (ev) {
            is ProjectCreated -> {}
            is InputCommitted -> {
                val seq = commits.size
                commits[ev.eventId] = EventCommit(ev.eventId, seq, ev.commit)
                ev.commit.messages.forEach { msg ->
                    val logicalId = msg.id ?: Hashing.stableId(
                        "msg", ev.commit.batchId, msg.direction, msg.receivedAt,
                        msg.connectionId ?: "-", (versions.size).toString()
                    )
                    val chain = versions.getOrPut(logicalId) { mutableListOf() }
                    val bytes = when (msg.encoding) {
                        "hex" -> Hex.decode(msg.raw)
                        else -> msg.raw.toByteArray(Charsets.UTF_8)
                    }
                    val parentUnit = chain.lastOrNull()?.let { "$logicalId#v${it.version}" }
                    chain += RawVersion(
                        logicalId, chain.size + 1, parentUnit, ev.commit.batchId,
                        ev.eventId, chain.size, msg, bytes
                    )
                }
                ev.commit.connections.forEach { c ->
                    val logicalId = c.id ?: Hashing.stableId(
                        "conn", ev.commit.batchId, c.connectionId, c.generation.toString(), c.at
                    )
                    val (vms, _) = TimeNorm.toVirtualMs(c.at, c.tsUnit)
                    val view = ConnEventView(
                        unitId = "$logicalId#v1", version = 1,
                        connectionId = c.connectionId, generation = c.generation, kind = c.kind,
                        transport = c.transport, atRaw = c.at, vtimeMs = vms,
                        local = c.local, remote = c.remote,
                        sources = listOf(DirectSource("event", ev.eventId, field = "connection"))
                    )
                    conns.getOrPut(logicalId) { mutableListOf() }.add(ConnVersion(view))
                }
                clocks += ev.commit.clocks
            }
            is ParamsRevised -> { params = ev.params; paramsEventId = ev.eventId }
            is DecisionRecorded, is DecisionUndone -> {} // 视图阶段统一处理
        }
    }

    lateinit var view: ProjectView

    fun buildView(name: String, events: List<HistoryEvent>, algoVersion: String) {
        // 当前有效输入：每个逻辑 id 取最高版本；旧版本保留，供历史回放与来源追溯
        val effective = versions.values.mapNotNull { it.lastOrNull() }
        val supersedeEvent = HashMap<String, String?>()
        versions.values.forEach { chain ->
            chain.dropLast(1).forEach { v -> supersedeEvent[v.logicalId] = chain.last().eventId }
        }

        val messages = effective.map { rv -> buildMessage(rv, supersedeEvent[rv.logicalId]) }
            .sortedWith(compareBy(nullsLast()) { it.vtimeMs }.thenBy { it.unitId })

        val connViews = conns.values.map { it.last().view }
        val pfp = params.fingerprint()

        val state = EngineState(messages, connViews, params, pfp, algoVersion)
        state.run()

        // 人工决定与撤销
        val decisions = mutableListOf<DecisionView>()
        val active = HashMap<String, DecisionRecorded>()
        events.forEachIndexed { idx, ev ->
            when (ev) {
                is DecisionRecorded -> {
                    active[ev.candidateId] = ev
                    decisions += DecisionView(
                        eventId = ev.eventId, candidateId = ev.candidateId,
                        verdict = ev.verdict, note = ev.note, atEventSeq = idx,
                        active = true, stale = false, undoneBy = null
                    )
                }
                is DecisionUndone -> {
                    val target = decisions.indexOfLast { it.eventId == ev.undoneEventId }
                    if (target >= 0) {
                        val old = decisions[target]
                        decisions[target] = old.copy(active = false, undoneBy = ev.eventId)
                        active.remove(old.candidateId)
                    }
                }
                else -> {}
            }
        }
        // 冲突的人工决定：同一候选先后被互斥裁定；记录冲突判据
        events.filterIsInstance<DecisionRecorded>().groupBy { it.candidateId }.forEach { (cid, ds) ->
            val opposing = ds.map { it.verdict }.distinct().size > 1
            if (opposing) state.judgement(
                code = "DECISION-CONFLICT", severity = "ambiguous",
                title = "互相冲突的人工决定",
                detail = "候选 $cid 存在互斥裁定：${ds.joinToString { it.verdict }}；当前以最新未撤销裁定为准，历史裁定均可回看",
                ruleId = "review.decision.conflict",
                about = listOf(DirectSource("candidate", cid)),
                evidence = ds.map { DirectSource("event", it.eventId) },
                candidateIds = listOf(cid), atMs = null
            )
        }

        // 决定作用到候选；若决定基于的候选指纹已变，则标记过期（旧决定仍可查看）
        val candidates = state.candidates.map { c ->
            val dec = active[c.id]
            when {
                dec == null -> c
                dec.targetFingerprint != c.sourceFingerprint -> c.copy(
                    status = dec.verdict.toStatus(),
                    decidedByEvent = dec.eventId,
                    rejectReason = c.rejectReason
                ).let {
                    it // 过期状态在 DecisionView.stale 中表达
                }
                else -> c.copy(status = dec.verdict.toStatus(), decidedByEvent = dec.eventId)
            }
        }
        decisions.forEachIndexed { i, d ->
            val fpNow = state.candidates.firstOrNull { it.id == d.candidateId }?.sourceFingerprint
            val recorded = events.filterIsInstance<DecisionRecorded>().firstOrNull { it.eventId == d.eventId }
            if (d.active && recorded != null && fpNow != null && recorded.targetFingerprint != fpNow) {
                decisions[i] = d.copy(stale = true)
            }
        }

        val history = events.mapIndexed { idx, ev ->
            EventSummary(
                eventId = ev.eventId, seq = idx,
                type = ev::class.simpleName!!.replace("Recorded", "").replace("Committed", ""),
                parentEventId = ev.parentEventId, at = ev.at,
                label = labelOf(ev)
            )
        }
        val atEvent = events.lastOrNull()
        view = ProjectView(
            projectId = projectId, name = name, seed = seed,
            algoVersion = algoVersion, paramFingerprint = pfp, params = params,
            atEventId = atEvent?.eventId ?: "none", atEventSeq = events.lastIndex,
            headEventId = atEvent?.eventId ?: "none",
            messages = messages, connections = connViews, clocks = clocks,
            transactions = state.txs.map { it.toView() },
            dialogs = state.dialogs.map { it.toView() },
            routeSets = state.routeSets,
            timers = state.timers.map { it.toView() },
            candidates = candidates,
            judgements = state.judgements,
            decisions = decisions,
            history = history
        )
    }

    private fun labelOf(ev: HistoryEvent): String = when (ev) {
        is ProjectCreated -> "创建项目 ${ev.name}"
        is InputCommitted -> "导入批次 ${ev.commit.batchId}（消息 ${ev.commit.messages.size} 条，连接事件 ${ev.commit.connections.size} 条）"
        is ParamsRevised -> "参数修订 T1=${ev.params.t1Ms}ms T2=${ev.params.t2Ms}ms T4=${ev.params.t4Ms}ms"
        is DecisionRecorded -> "人工裁定 ${ev.verdict} 候选 ${ev.candidateId.take(18)}…"
        is DecisionUndone -> "撤销裁定 ${ev.undoneEventId.take(18)}…"
    }

    private fun String.toStatus() = when (this) {
        "accept", "choose" -> "accepted"; "reject" -> "rejected"; else -> "open"
    }

    private fun buildMessage(rv: RawVersion, supersededBy: String?): MessageView {
        val m = rv.input
        val rawText = when (m.encoding) {
            "hex" -> runCatching { String(rv.rawBytes, Charsets.UTF_8) }.getOrDefault("")
            else -> m.raw
        }
        val (vms, timeDiag) = TimeNorm.toVirtualMs(m.receivedAt, m.tsUnit)
        val parsed = if (rawText.isNotEmpty()) SipParser.parse("${rv.logicalId}#v${rv.version}", rawText) else null
        val ok = parsed != null && parsed.kind in setOf("request", "response")
        return MessageView(
            unitId = "${rv.logicalId}#v${rv.version}",
            version = rv.version,
            parentUnitId = rv.parentUnitId,
            batchId = rv.batchId,
            direction = m.direction, transport = m.transport.uppercase(),
            local = m.local, remote = m.remote,
            connectionId = m.connectionId, connectionGeneration = m.connectionGeneration,
            receivedAtRaw = m.receivedAt, tsUnit = m.tsUnit, vtimeMs = vms,
            timeDiagnostic = timeDiag, encoding = m.encoding,
            rawOriginal = m.raw, rawHex = Hex.encode(rv.rawBytes), byteLength = rv.rawBytes.size,
            note = m.note,
            parseStatus = if (ok) "parsed" else "unparseable",
            parsed = parsed?.takeIf { ok },
            sources = listOf(
                DirectSource("event", rv.eventId, field = "commit"),
                DirectSource("unit", rv.logicalId, rv.version)
            ),
            supersededByEvent = supersededBy
        )
    }
}
