package sipreview.engine

import sipreview.domain.*

private data class SchedTimer(
    val name: String, val owner: TxState, val atMs: Double, val durationMs: Long,
    val basis: String, val sources: List<DirectSource>, val fp: String
)

internal class TxState(
    val id: String,
    val role: String,
    val kind: String,
    var state: String,
    val callId: String?,
    val branch: String?,
    var cseqNumber: Long?,
    val cseqMethod: String?,
    val createdAtMs: Double?,
    val sources: MutableList<DirectSource> = mutableListOf(),
    val members: MutableList<String> = mutableListOf(),
    val stateHistory: MutableList<StateStep> = mutableListOf(),
    val timers: MutableList<TimerView> = mutableListOf(),
    val candidateIds: MutableList<String> = mutableListOf(),
    val rejectedIds: MutableList<String> = mutableListOf(),
    val links: MutableMap<String, String> = mutableMapOf(),
    val diagnostics: MutableList<String> = mutableListOf(),
    var sourceFingerprint: String = "",
    var scheduled: MutableList<SchedTimer> = mutableListOf(),
    var provisionalTags: MutableList<String> = mutableListOf(),
    var seenProvisional: Boolean = false,
    var canceled: Boolean = false
) {
    var stepCounter = 0
    fun transition(newState: String, atMs: Double?, label: String, refs: List<String>) {
        state = newState
        stepCounter += 1
        stateHistory += StateStep("${id}#s$stepCounter", newState, atMs, label, refs)
    }
}

internal class DlgState(
    val id: String,
    var state: String,
    val callId: String,
    var localTag: String?,
    var remoteTag: String?,
    var localTarget: String?,
    var remoteTarget: String?,
    var routeSetId: String?,
    var localCseq: Long?,
    var remoteCseq: Long?,
    val forkGroupId: String?,
    val parentDialogId: String?,
    var bornFromMessage: String?,
    var confirmedByMessage: String?,
    var endedByMessage: String?,
    val candidateIds: MutableList<String> = mutableListOf(),
    val sources: MutableList<DirectSource> = mutableListOf(),
    var sourceFingerprint: String = ""
)

class EngineState(
    private val messages: List<MessageView>,
    private val connections: List<ConnEventView>,
    private val params: EngineParams,
    private val paramFp: String,
    private val algoVersion: String
) {
    val txs = mutableListOf<TxState>()
    val dialogs = mutableListOf<DlgState>()
    val routeSets = mutableListOf<RouteSetView>()
    val timers = mutableListOf<TimerView>()
    val candidates = mutableListOf<CandidateView>()
    val judgements = mutableListOf<JudgementView>()

    private val byKey = HashMap<String, TxState>()
    private var candSeq = 0
    private var judgeSeq = 0

    private fun src(m: MessageView, field: String? = null) =
        DirectSource("unit", m.unitId.substringBefore('#'), m.version, field)

    fun run() {
        // 先按连接代次建立连接生命周期视图（连接复用/重连不能抹掉消息身份）
        assessConnections()
        for (m in messages) processMessage(m)
        // 终止仍开着的定时器
        timers.forEach { t ->
            if (t.status == "scheduled") {
                timers[timers.indexOf(t)] = t.copy(status = "cancelled",
                    basis = t.basis + "；观测窗口结束仍未触发，标记为未触发（保留计划时刻）")
            }
        }
    }

    private fun assessConnections() {
        val groups = connections.groupBy { it.connectionId }
        groups.forEach { (cid, evs) ->
            val gens = evs.map { it.generation }.distinct().sorted()
            if (gens.size > 1 || evs.any { it.kind == "reset" } || evs.any { it.kind == "reuse" }) {
                val reused = evs.any { it.kind == "reuse" }
                judgement(
                    code = if (reused) "CONN-REUSE" else "CONN-RECONNECT",
                    severity = "info",
                    title = if (reused) "传输连接复用" else "传输连接重连（新代次）",
                    detail = "连接 $cid 出现代次 $gens；连接复用只影响 hop 可靠性，不参与事务/dialog 身份，消息身份仍由 branch+tag+CSeq 决定",
                    ruleId = "transport.connection-identity",
                    about = listOf(DirectSource("unit", "conn:$cid")),
                    evidence = evs.map { DirectSource("unit", it.unitId) },
                    candidateIds = emptyList(),
                    atMs = evs.mapNotNull { it.vtimeMs }.minOrNull()
                )
            }
        }
    }

    private fun processMessage(m: MessageView) {
        val p = m.parsed ?: return // 无法解析的报文保留在消息层并已带诊断
        if (p.kind == "request") processRequest(m, p) else processResponse(m, p)
    }

    private fun getOrCreateTx(
        key: String, role: String, kind: String, callId: String?,
        branch: String?, cseqNumber: Long?, cseqMethod: String?,
        atMs: Double?, source: DirectSource, fp: String
    ): TxState {
        return byKey.getOrPut(key) {
            val tx = TxState(
                id = Hashing.stableId("tx", key),
                role = role, kind = kind, state = "Trying",
                callId = callId, branch = branch,
                cseqNumber = cseqNumber, cseqMethod = cseqMethod,
                createdAtMs = atMs,
                sourceFingerprint = fp
            )
            tx.transition("Trying", atMs, "事务建立（$role/$kind）", emptyList())
            tx.sources += source
            txs += tx
            tx
        }
    }

    fun judgement(
        code: String, severity: String, title: String, detail: String,
        ruleId: String, about: List<DirectSource>, evidence: List<DirectSource>,
        candidateIds: List<String>, atMs: Double?
    ) {
        judgeSeq += 1
        judgements += JudgementView(
            id = Hashing.stableId("j", code, judgeSeq.toString(),
                about.joinToString("|") { it.id + ":" + (it.field ?: "") }, atMs?.toString() ?: "na"),
            code = code, severity = severity, title = title, detail = detail,
            ruleId = "$ruleId@$algoVersion",
            about = about, evidence = evidence, candidateIds = candidateIds, atMs = atMs,
            machine = buildMachine(code, about, evidence),
            sourceFingerprint = paramFp
        )
    }

    private fun buildMachine(code: String, about: List<DirectSource>, evidence: List<DirectSource>) =
        mapOf(
            "code" to code,
            "algoVersion" to algoVersion,
            "paramFingerprint" to paramFp,
            "about" to about.joinToString(",") { it.id + "#v" + (it.version ?: 0) },
            "evidence" to evidence.joinToString(",") { it.id }
        )

    fun candidate(
        kind: String, about: List<DirectSource>, proposal: String, rationale: String,
        confidence: String, sources: List<DirectSource>, fp: String, rejectReason: String? = null
    ): String {
        candSeq += 1
        val id = Hashing.stableId("cand", kind, candSeq.toString(),
            about.joinToString("|") { it.id + ":" + (it.field ?: "") }, fp)
        candidates += CandidateView(
            id = id, kind = kind, about = about, proposal = proposal,
            rationale = rationale, confidence = confidence,
            status = if (rejectReason != null) "rejected" else "open",
            sources = sources, sourceFingerprint = fp, rejectReason = rejectReason
        )
        return id
    }

    fun toView(t: TxState) = TxView(
        id = t.id, role = t.role, kind = t.kind, state = t.state,
        stateHistory = t.stateHistory, callId = t.callId, branch = t.branch,
        peerBranch = null, cseqNumber = t.cseqNumber, cseqMethod = t.cseqMethod,
        memberMessages = t.members, links = t.links,
        candidateIds = t.candidateIds, rejectedIds = t.rejectedIds,
        timerIds = t.timers.map { it.id }, diagnostics = t.diagnostics,
        sources = t.sources, sourceFingerprint = t.sourceFingerprint
    )

    fun toView(d: DlgState) = DialogView(
        id = d.id, state = d.state, callId = d.callId,
        localTag = d.localTag, remoteTag = d.remoteTag,
        localTarget = d.localTarget, remoteTarget = d.remoteTarget,
        routeSetId = d.routeSetId, localCseq = d.localCseq, remoteCseq = d.remoteCseq,
        forkGroupId = d.forkGroupId, parentDialogId = d.parentDialogId,
        bornFromMessage = d.bornFromMessage, confirmedByMessage = d.confirmedByMessage,
        endedByMessage = d.endedByMessage, candidateIds = d.candidateIds,
        sources = d.sources, sourceFingerprint = d.sourceFingerprint
    )

    internal fun addTimer(t: SchedTimer) {
        val tv = TimerView(
            id = Hashing.stableId("tmr", t.owner.id, t.name, t.atMs.toString()),
            ownerId = t.owner.id, name = t.name,
            scheduledAtMs = t.owner.createdAtMs ?: t.atMs,
            fireAtMs = t.atMs, durationMs = t.durationMs, durationUnit = "ms",
            basis = "${t.basis}（规则版本 $algoVersion，参数指纹 ${paramFp.take(16)}）",
            status = "scheduled", sources = t.sources, sourceFingerprint = t.fp
        )
        timers += tv
        t.owner.timers += tv
    }
}
