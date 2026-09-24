package sipreview.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * 重放引擎：按虚拟时钟顺序消费“当前生效版本”的原始记录，
 * 重建 RFC3261 client/server transaction、early/confirmed dialog、route set 与定时器。
 * 每个派生对象携带稳定身份、候选与拒绝原因；输入不足以唯一决定时保留全部合法候选。
 */
class ReplayEngine(private val clock: VirtualClock, private val idGen: IdFactory) {

    companion object { const val ALGO_VERSION = "replay-engine-v1" }

    @Serializable
    data class Tx(
        val id: String,
        val branch: String,
        val method: String,
        val isInvite: Boolean,
        var role: String = "client",
        var states: MutableList<Pair<String, Long>> = mutableListOf(),
        val sources: MutableList<String> = mutableListOf(),
        var branchPoint: Long? = null,
        val notes: MutableList<String> = mutableListOf(),
        var cancelledAt: Long? = null,
        var ackedAt: Long? = null,
    ) {
        fun stateAt(t: Long): String {
            var cur = states.firstOrNull()?.first ?: "UNKNOWN"
            for ((s, at) in states) if (at <= t) cur = s else break
            return cur
        }
        val finalState get() = states.lastOrNull()?.first ?: "UNKNOWN"
    }

    fun fingerprint(params: EngineParams): String {
        val raw = listOf(
            params.timerT1Ms, params.timerT2Ms, params.timerT4Ms,
            params.nonInviteTimeoutMs, params.inviteCompletedMs, params.retransmitWindowMs,
            params.fingerprintAlgoVersion,
        ).joinToString("|")
        val md = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "sha256:" + md.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun run(
        projectId: String,
        paramRevision: ParamRevision,
        records: List<RawRecord>,
        decisions: List<Decision>,
        previousRun: EngineRun? = null,
    ): EngineRun {
        val p = paramRevision.params
        val fp = fingerprint(p)
        val ctx = Ctx(projectId, paramRevision.id, p, fp, decisions)
        val ordered = records.sortedWith(compareBy({ it.recvNanos }, { it.ordinal }))
        ordered.forEach { ctx.consume(it, clock) }
        ctx.closeConnections(clock.nowNanos())
        val runId = "run:" + idGen.deterministic(
            projectId, paramRevision.id, ordered.map { it.id }, decisions.map { it.id }
        )
        val stale = buildStaleness(previousRun, ctx.txs.values.map { it.id }, ctx.dialogs.map { it.id })
        return EngineRun(
            runId = runId, projectId = projectId, paramRevisionId = paramRevision.id,
            inputRecordIds = ordered.map { it.id },
            transactions = ctx.buildTransactionStates(decisions),
            dialogs = ctx.dialogs,
            timers = ctx.timers,
            retransmits = ctx.retransmits,
            ownership = ctx.ownership,
            connections = ctx.connections.values.toList(),
            candidates = ctx.globalCandidates,
            staleness = stale,
        )
    }

    private fun buildStaleness(prev: EngineRun?, txIds: List<String>, dlgIds: List<String>): JsonObject? {
        if (prev == null) return null
        val oldTx = prev.transactions.map { it.id }.toSet()
        val oldDlg = prev.dialogs.map { it.id }.toSet()
        return buildJsonObject {
            put("previousRunId", prev.runId)
            putJsonArray("staleTransactions") { (oldTx - txIds.toSet()).forEach { add(it) } }
            putJsonArray("staleDialogs") { (oldDlg - dlgIds.toSet()).forEach { add(it) } }
            put("note", "局部输入变化：仅依赖被改记录的对象过期；过期结果可查看但不冒充当前结果")
        }
    }
}
