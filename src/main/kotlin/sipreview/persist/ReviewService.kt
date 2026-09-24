package sipreview.persist

import kotlinx.serialization.Serializable
import sipreview.domain.*

/** 应用服务：编排“提交不可变输入 → 参数版本 → 重放 → 人工决定 → 历史快照”。 */
class ReviewService(val db: Database, private val clock: VirtualClock, private val realNanos: () -> Long = { System.nanoTime() }) {

    fun createProject(name: String, seed: Long, note: String = ""): Project {
        val ids = IdFactory(seed)
        val p = Project(ids.scoped("proj", name), name, seed, realNanos(), note)
        db.createProject(p)
        val pr0 = ParamRevision(
            id = ids.scoped("params", p.id, 0), projectId = p.id, parentId = null, seq = 0,
            note = "初始参数", params = EngineParams(), createdAtNanos = realNanos(),
        )
        db.insertParamRevision(pr0)
        db.audit(p.id, realNanos(), "system", "project_created", """{"name":"$name","seed":$seed}""")
        return p
    }

    fun importJsonl(projectId: String, jsonl: String, revisionOf: String? = null): ImportResult {
        val project = db.getProject(projectId) ?: error("project not found")
        val ids = IdFactory(project.seed)
        val start = db.allRecords(projectId).maxOfOrNull { it.ordinal + 1 } ?: 0
        val mode = RevisionMode(revisionOf = revisionOf, supersedes = revisionOf)
        val result = Importer.importJsonl(projectId, jsonl, start, mode, ids, realNanos())
        db.tx { result.accepted.forEach { db.insertRaw(it) } }
        if (result.accepted.isNotEmpty()) {
            db.audit(projectId, realNanos(), "importer", "records_committed",
                """{"count":${result.accepted.size},"revisionOf":${revisionOf?.let { "\"$it\"" } ?: "null"}}""")
        }
        return result
    }

    fun reviseParams(projectId: String, params: EngineParams, note: String): ParamRevision {
        val project = db.getProject(projectId) ?: error("project not found")
        val prev = db.latestParamRevision(projectId)!!
        val ids = IdFactory(project.seed)
        val pr = ParamRevision(
            id = ids.scoped("params", projectId, prev.seq + 1, note, params.toString()),
            projectId = projectId, parentId = prev.id, seq = prev.seq + 1,
            note = note, params = params, createdAtNanos = realNanos(),
        )
        db.insertParamRevision(pr)
        return pr
    }

    fun decide(projectId: String, req: DecideRequest): Decision {
        val project = db.getProject(projectId) ?: error("project not found")
        val run = currentRun(projectId) ?: error("尚无重放结果")
        val ids = IdFactory(project.seed)
        val d = Decision(
            id = ids.scoped("dec", projectId, realNanos(), req.kind, req.targetRef, req.choice ?: ""),
            projectId = projectId, eventId = run.runId, atNanos = realNanos(),
            kind = req.kind, targetRef = req.targetRef, choice = req.choice,
            rejectedCandidates = req.rejectedCandidates, reason = req.reason, undoOf = req.undoOf,
        )
        db.insertDecision(d)
        return d
    }

    fun currentRun(projectId: String, paramSeq: Long? = null): EngineRun? {
        val project = db.getProject(projectId) ?: return null
        val revisions = db.listParamRevisions(projectId)
        val rev = if (paramSeq == null) revisions.lastOrNull() else revisions.firstOrNull { it.seq == paramSeq }
            ?: return null
        val records = db.activeRecords(projectId)
        val decisions = db.listDecisions(projectId)
        val engine = ReplayEngine(clock, IdFactory(project.seed))
        val run = engine.run(projectId, rev, records, decisions, db.listRuns(projectId).lastOrNull())
        db.saveRun(run, realNanos())
        return run
    }

    /** 回到任意旧事件：用截至该决定时刻的输入/参数/决定重建当时的可见结论。 */
    fun replayAt(projectId: String, decisionId: String?): EngineRun? {
        val project = db.getProject(projectId) ?: return null
        val allDecisions = db.listDecisions(projectId)
        val upto = if (decisionId == null) allDecisions else allDecisions.takeWhile { it.id != decisionId } +
            allDecisions.filter { it.id == decisionId }
        val rev = db.latestParamRevision(projectId) ?: return null
        val engine = ReplayEngine(clock, IdFactory(project.seed))
        return engine.run(projectId, rev, db.activeRecords(projectId), upto)
    }

    fun exportJson(): String = db.json.encodeToString(db.exportArchive())

    fun importArchiveJson(text: String): ImportArchiveSummary {
        val archive = db.json.decodeFromString<ExportArchive>(text)
        val before = db.listProjects().map { it.id }.toSet()
        db.importArchive(archive)
        return ImportArchiveSummary(
            projects = archive.projects.size,
            newProjects = archive.projects.count { it.id !in before },
            records = archive.records.size,
            paramRevisions = archive.paramRevisions.size,
            decisions = archive.decisions.size,
            runs = archive.runs.size,
        )
    }
}

@Serializable
data class DecideRequest(
    val kind: String,
    val targetRef: String,
    val choice: String? = null,
    val rejectedCandidates: List<String> = emptyList(),
    val reason: String = "",
    val undoOf: String? = null,
)

@Serializable
data class ImportArchiveSummary(
    val projects: Int, val newProjects: Int, val records: Int,
    val paramRevisions: Int, val decisions: Int, val runs: Int,
)
