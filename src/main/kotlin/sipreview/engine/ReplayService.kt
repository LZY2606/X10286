package sipreview.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import sipreview.domain.*
import sipreview.store.SqliteStore

/**
 * Orchestrates one replay against the current project snapshot and persists everything:
 * derived rows carry algorithm version + parameter fingerprint + direct source ids,
 * so every on-screen value can be traced back and reconstructed on reopen.
 */
class ReplayService(private val store: SqliteStore) {
    private val json = jacksonObjectMapper()

    fun replayCurrent(projectId: String, nowMillis: Long, note: String? = null): RunView {
        val prj = store.getProject(projectId) ?: error("no project $projectId")
        val raw = store.listRaw(projectId, currentOnly = true)
        val params = store.currentParameters(projectId) ?: error("no parameters")
        val decisions = store.listDecisions(projectId)
        val origin = raw.minOfOrNull { it.receivedAtMillis } ?: nowMillis
        val timerParams = TimerParams.fromParameterSet(params, origin)
        val runId = store.startRun(projectId, nowMillis, note)
        val snapshot = SnapshotInputs(projectId, runId, raw, params.fingerprint, timerParams,
            decisions.filter { it.supersededBy == null })
        val result = ReplayEngine().replay(snapshot)

        val rows = mutableListOf<DerivedRow>()
        var created = nowMillis
        fun row(kind: String, status: String, subject: String, label: String,
                payload: Map<String, Any?>, sources: List<String>,
                parent: String? = null): String {
            val id = Ids.prefixed("der:", runId, kind, subject,
                json.writeValueAsString(payload).take(200))
            rows += DerivedRow(id, projectId, runId, kind, status, subject, label,
                json.writeValueAsString(payload), sources, parent,
                ParameterSet.ALGORITHM_VERSION, params.fingerprint, created++, false, null)
            return id
        }

        for (tx in result.transactions) {
            row("transaction", (tx["state"] as? String) ?: "?", tx["id"] as String,
                "${tx["role"]} ${tx["method"]} tx", tx,
                (tx["sources"] as? List<*>)?.map { it.toString() } ?: emptyList())
        }
        for (d in result.dialogs) {
            row("dialog", d["state"] as String, d["id"] as String,
                "dialog ${d["callId"]}", d,
                (d["sources"] as? List<*>)?.map { it.toString() } ?: emptyList())
        }
        for (t in result.timers) {
            row("timer", "fired", t["id"] as String,
                "timer ${t["timer"]} fired @${t["atMs"]}ms", t,
                listOf(t["subjectId"] as String))
        }
        for (c in result.connections) {
            @Suppress("UNCHECKED_CAST")
            val gens = c["generations"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val sources = (c["sources"] as? List<*>)?.map { it.toString() } ?: emptyList()
            row("connection", "observed", c["id"] as String,
                "connection ${c["connectionId"]} (${gens.size} generations)", c, sources)
        }
        for (c in result.candidates) {
            val derId = row("candidate", c.status, c.subjectId, c.label,
                c.payload + mapOf("candidateId" to c.id, "atMs" to c.atMs,
                    "kind" to c.kind, "rejectedReason" to c.rejectedReason),
                c.sources)
            row("judgement", c.status, c.id,
                if (c.status == "adopted") "adopted: ${c.label}" else "not adopted: ${c.label}",
                mapOf("candidateId" to c.id, "verdict" to c.status,
                    "rejectedReason" to c.rejectedReason,
                    "algorithmVersion" to ParameterSet.ALGORITHM_VERSION,
                    "paramFingerprint" to params.fingerprint),
                c.sources, parent = derId)
        }
        for (u in result.unknowns) {
            @Suppress("UNCHECKED_CAST")
            row("unknown", "unknown", u["id"] as String,
                u["label"] as String, u,
                (u["sources"] as? List<*>)?.map { it.toString() } ?: emptyList())
        }
        // Timer parameter provenance row: explicit note about Timer G = T1/2 choice.
        row("parameter-provenance", params.fingerprint, params.id,
            "timer parameter set ${params.name}", mapOf(
                "values" to timerParams.asMap(),
                "fingerprint" to params.fingerprint,
                "notes" to listOf(
                    "Timer G initial = T1/2 per RFC 3261 17.2.1",
                    "all delays evaluated on the virtual clock",
                    "Timer G doubles up to T2")), emptyList())

        val events = result.events.mapIndexed { i, e ->
            @Suppress("UNCHECKED_CAST")
            DomainEvent((i + 1).toLong(), projectId, runId, (e["seq"] as Number).toLong(),
                (e["atMs"] as Number).toLong(), e["type"] as String,
                e["subjectId"] as String,
                json.writeValueAsString(e["payload"]),
                (e["sources"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                e["decisionRef"] as String?)
        }
        store.insertDerived(rows)
        store.insertEvents(events)
        store.finishRun(runId, nowMillis, events.size.toLong())
        return RunView(store.getRun(runId)!!, result, params, rows)
    }

    fun reviseAndReplay(projectId: String, parentRawId: String, item: PreparedItem,
                        nowMillis: Long, note: String): RunView {
        val parent = store.getRaw(parentRawId) ?: error("unknown raw $parentRawId")
        val revised = store.reviseRaw(projectId, parentRawId, item, nowMillis)
        store.markDerivedStaleForSource(projectId, setOf(parent.id, revised.id),
            "direct source revised: $parentRawId -> ${revised.id}")
        return replayCurrent(projectId, nowMillis, note)
    }
}

data class RunView(
    val run: RunRecord,
    val result: ReplayResult,
    val params: ParameterSet,
    val derived: List<DerivedRow>
)
