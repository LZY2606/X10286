package sipreview.persist

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sipreview.domain.*
import java.sql.Connection
import java.sql.DriverManager
import java.io.File

class Database private constructor(private val conn: Connection) {

    val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val SCHEMA_VERSION = 1
        fun open(path: String): Database {
            Class.forName("org.sqlite.JDBC")
            val url = if (path == ":memory:") "jdbc:sqlite::memory:"
            else "jdbc:sqlite:${File(path).absolutePath}"
            val c = DriverManager.getConnection(url)
            c.createStatement().execute("PRAGMA foreign_keys = ON")
            Database(c).also { it.migrate() }
        }
    }

    private fun migrate() {
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, seed INTEGER NOT NULL,
                created_at_nanos INTEGER NOT NULL, note TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS raw_records (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
                direction TEXT NOT NULL, bytes_b64 TEXT NOT NULL,
                connection_id TEXT NOT NULL, connection_generation INTEGER NOT NULL,
                transport TEXT NOT NULL, src_addr TEXT, dst_addr TEXT,
                recv_orig TEXT NOT NULL, recv_basis TEXT NOT NULL, recv_nanos INTEGER NOT NULL,
                parent_record_id TEXT, revision_of TEXT, supersedes TEXT,
                parser_version TEXT NOT NULL, param_fingerprint TEXT NOT NULL,
                parsed_json TEXT NOT NULL, committed_at_nanos INTEGER NOT NULL,
                UNIQUE(project_id, ordinal, revision_of),
                FOREIGN KEY(project_id) REFERENCES projects(id)
            );
            CREATE TABLE IF NOT EXISTS param_revisions (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, parent_id TEXT,
                seq INTEGER NOT NULL, note TEXT NOT NULL, params_json TEXT NOT NULL,
                created_at_nanos INTEGER NOT NULL,
                FOREIGN KEY(project_id) REFERENCES projects(id)
            );
            CREATE TABLE IF NOT EXISTS decisions (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, event_id TEXT NOT NULL,
                at_nanos INTEGER NOT NULL, kind TEXT NOT NULL, target_ref TEXT NOT NULL,
                choice TEXT, rejected_json TEXT NOT NULL, reason TEXT NOT NULL, undo_of TEXT,
                FOREIGN KEY(project_id) REFERENCES projects(id)
            );
            CREATE TABLE IF NOT EXISTS engine_runs (
                run_id TEXT NOT NULL, project_id TEXT NOT NULL, param_revision_id TEXT NOT NULL,
                created_at_nanos INTEGER NOT NULL, run_json TEXT NOT NULL,
                PRIMARY KEY(run_id, project_id)
            );
            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL,
                at_nanos INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL,
                detail_json TEXT NOT NULL
            );
            CREATE TRIGGER IF NOT EXISTS trg_raw_no_update AFTER UPDATE ON raw_records
            BEGIN
                SELECT RAISE(ABORT, 'raw_records 不可覆盖：修订请以新版本+父版本表达');
            END;
            CREATE TRIGGER IF NOT EXISTS trg_raw_no_delete AFTER DELETE ON raw_records
            BEGIN
                SELECT RAISE(ABORT, 'raw_records 一经提交不可删除');
            END;
            """.trimIndent()
        )
        conn.createStatement().executeUpdate(
            "INSERT OR IGNORE INTO meta(key,value) VALUES('schema_version','$SCHEMA_VERSION')"
        )
    }

    fun tx(block: () -> Unit) {
        val old = conn.autoCommit
        conn.autoCommit = false
        try { block(); conn.commit() } catch (e: Exception) { conn.rollback(); throw e }
        finally { conn.autoCommit = old }
    }

    // ---- projects ----
    fun createProject(p: Project) {
        conn.prepareStatement(
            "INSERT INTO projects(id,name,seed,created_at_nanos,note) VALUES(?,?,?,?,?)"
        ).apply {
            setString(1, p.id); setString(2, p.name); setLong(3, p.seed)
            setLong(4, p.createdAtNanos); setString(5, p.note); executeUpdate()
        }
    }

    fun getProject(id: String): Project? = conn.prepareStatement(
        "SELECT id,name,seed,created_at_nanos,note FROM projects WHERE id=?"
    ).apply { setString(1, id) }.executeQuery().use { q ->
        if (q.next()) Project(q.getString(1), q.getString(2), q.getLong(3), q.getLong(4), q.getString(5)) else null
    }

    fun listProjects(): List<Project> {
        val out = mutableListOf<Project>()
        conn.createStatement().executeQuery(
            "SELECT id,name,seed,created_at_nanos,note FROM projects ORDER BY created_at_nanos"
        ).use { q -> while (q.next()) out += Project(q.getString(1), q.getString(2), q.getLong(3), q.getLong(4), q.getString(5)) }
        return out
    }

    // ---- raw records ----
    fun insertRaw(r: RawRecord) {
        conn.prepareStatement(
            """INSERT INTO raw_records(id,project_id,ordinal,direction,bytes_b64,connection_id,
              connection_generation,transport,src_addr,dst_addr,recv_orig,recv_basis,recv_nanos,
              parent_record_id,revision_of,supersedes,parser_version,param_fingerprint,parsed_json,
              committed_at_nanos) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).apply {
            setString(1, r.id); setString(2, r.projectId); setLong(3, r.ordinal)
            setString(4, r.direction.name); setString(5, r.bytesBase64)
            setString(6, r.connectionId); setLong(7, r.connectionGeneration)
            setString(8, r.transport); setString(9, r.srcAddr); setString(10, r.dstAddr)
            setString(11, r.recvOrig); setString(12, r.recvBasis); setLong(13, r.recvNanos)
            setString(14, r.parentRecordId); setString(15, r.revisionOf); setString(16, r.supersedes)
            setString(17, r.parserVersion); setString(18, r.paramFingerprint)
            setString(19, json.encodeToString(r.parsed)); setLong(20, r.committedAtNanos)
            executeUpdate()
        }
    }

    /** 当前生效版本：同一 revisionOf 取 supersedes 链末端；无修订者直接取。 */
    fun activeRecords(projectId: String): List<RawRecord> {
        val all = allRecords(projectId)
        val latest = HashMap<String, RawRecord>()
        val rootOf = HashMap<String, String>()
        all.forEach { r ->
            val root = r.revisionOf ?: r.id
            rootOf[r.id] = root
        }
        all.forEach { r ->
            val root = rootOf[r.id]!!
            val prev = latest[root]
            if (prev == null) latest[root] = r
            else if (r.ordinal >= prev.ordinal) latest[root] = r
        }
        return latest.values.sortedWith(compareBy({ it.recvNanos }, { it.ordinal }))
    }

    fun allRecords(projectId: String): List<RawRecord> {
        val out = mutableListOf<RawRecord>()
        conn.prepareStatement("SELECT * FROM raw_records WHERE project_id=? ORDER BY ordinal").apply {
            setString(1, projectId)
        }.executeQuery().use { q ->
            while (q.next()) out += readRecord(q)
        }
        return out
    }

    private fun readRecord(q: java.sql.ResultSet) = RawRecord(
        id = q.getString("id"), projectId = q.getString("project_id"),
        ordinal = q.getLong("ordinal"), direction = Direction.valueOf(q.getString("direction")),
        bytesBase64 = q.getString("bytes_b64"), connectionId = q.getString("connection_id"),
        connectionGeneration = q.getLong("connection_generation"),
        transport = q.getString("transport"), srcAddr = q.getString("src_addr"),
        dstAddr = q.getString("dst_addr"), recvOrig = q.getString("recv_orig"),
        recvBasis = q.getString("recv_basis"), recvNanos = q.getLong("recv_nanos"),
        parentRecordId = q.getString("parent_record_id"), revisionOf = q.getString("revision_of"),
        supersedes = q.getString("supersedes"), parserVersion = q.getString("parser_version"),
        paramFingerprint = q.getString("param_fingerprint"),
        parsed = json.decodeFromString(q.getString("parsed_json")),
        committedAtNanos = q.getLong("committed_at_nanos"),
    )

    // ---- param revisions ----
    fun insertParamRevision(pr: ParamRevision) {
        conn.prepareStatement(
            """INSERT INTO param_revisions(id,project_id,parent_id,seq,note,params_json,created_at_nanos)
              VALUES(?,?,?,?,?,?,?)"""
        ).apply {
            setString(1, pr.id); setString(2, pr.projectId); setString(3, pr.parentId)
            setLong(4, pr.seq); setString(5, pr.note)
            setString(6, json.encodeToString(pr.params)); setLong(7, pr.createdAtNanos)
            executeUpdate()
        }
    }

    fun latestParamRevision(projectId: String): ParamRevision? =
        listParamRevisions(projectId).maxByOrNull { it.seq }

    fun listParamRevisions(projectId: String): List<ParamRevision> {
        val out = mutableListOf<ParamRevision>()
        conn.prepareStatement(
            "SELECT id,project_id,parent_id,seq,note,params_json,created_at_nanos FROM param_revisions WHERE project_id=? ORDER BY seq"
        ).apply { setString(1, projectId) }.executeQuery().use { q ->
            while (q.next()) out += ParamRevision(
                q.getString(1), q.getString(2), q.getString(3), q.getLong(4),
                q.getString(5), json.decodeFromString(q.getString(6)), q.getLong(7),
            )
        }
        return out
    }

    // ---- decisions ----
    fun insertDecision(d: Decision) {
        conn.prepareStatement(
            """INSERT INTO decisions(id,project_id,event_id,at_nanos,kind,target_ref,choice,
              rejected_json,reason,undo_of) VALUES(?,?,?,?,?,?,?,?,?,?)"""
        ).apply {
            setString(1, d.id); setString(2, d.projectId); setString(3, d.eventId)
            setLong(4, d.atNanos); setString(5, d.kind); setString(6, d.targetRef)
            setString(7, d.choice); setString(8, json.encodeToString(d.rejectedCandidates))
            setString(9, d.reason); setString(10, d.undoOf); executeUpdate()
        }
        audit(d.projectId, d.atNanos, "reviewer", "decision",
            """{"id":"${d.id}","kind":"${d.kind}","target":"${d.targetRef}","choice":${d.choice?.let { "\"$it\"" }}}""")
    }

    fun listDecisions(projectId: String): List<Decision> {
        val out = mutableListOf<Decision>()
        conn.prepareStatement(
            """SELECT id,project_id,event_id,at_nanos,kind,target_ref,choice,rejected_json,reason,undo_of
              FROM decisions WHERE project_id=? ORDER BY at_nanos"""
        ).apply { setString(1, projectId) }.executeQuery().use { q ->
            while (q.next()) out += Decision(
                id = q.getString(1), projectId = q.getString(2), eventId = q.getString(3),
                atNanos = q.getLong(4), kind = q.getString(5), targetRef = q.getString(6),
                choice = q.getString(7),
                rejectedCandidates = json.decodeFromString(q.getString(8)),
                reason = q.getString(9), undoOf = q.getString(10),
            )
        }
        return out
    }

    fun audit(projectId: String, atNanos: Long, actor: String, action: String, detailJson: String) {
        conn.prepareStatement(
            "INSERT INTO audit_log(project_id,at_nanos,actor,action,detail_json) VALUES(?,?,?,?,?)"
        ).apply {
            setString(1, projectId); setLong(2, atNanos); setString(3, actor)
            setString(4, action); setString(5, detailJson); executeUpdate()
        }
    }

    // ---- runs ----
    fun saveRun(run: EngineRun, atNanos: Long) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO engine_runs(run_id,project_id,param_revision_id,created_at_nanos,run_json) VALUES(?,?,?,?,?)"
        ).apply {
            setString(1, run.runId); setString(2, run.projectId)
            setString(3, run.paramRevisionId); setLong(4, atNanos)
            setString(5, json.encodeToString(run)); executeUpdate()
        }
    }

    fun listRuns(projectId: String): List<EngineRun> {
        val out = mutableListOf<EngineRun>()
        conn.prepareStatement("SELECT run_json FROM engine_runs WHERE project_id=? ORDER BY created_at_nanos").apply {
            setString(1, projectId)
        }.executeQuery().use { q -> while (q.next()) out += json.decodeFromString(q.getString(1)) }
        return out
    }

    fun getRun(runId: String): EngineRun? =
        conn.prepareStatement("SELECT run_json FROM engine_runs WHERE run_id=?").apply {
            setString(1, runId)
        }.executeQuery().use { if (it.next()) json.decodeFromString<EngineRun>(it.getString(1)) else null }

    // ---- export / import ----
    fun exportArchive(): ExportArchive {
        val projects = listProjects()
        return ExportArchive(
            schemaVersion = SCHEMA_VERSION,
            projects = projects,
            records = projects.flatMap { allRecords(it.id) },
            paramRevisions = projects.flatMap { listParamRevisions(it.id) },
            decisions = projects.flatMap { listDecisions(it.id) },
            runs = projects.flatMap { listRuns(it.id) },
        )
    }

    fun importArchive(a: ExportArchive) = tx {
        a.projects.forEach { p ->
            conn.prepareStatement("INSERT OR IGNORE INTO projects(id,name,seed,created_at_nanos,note) VALUES(?,?,?,?,?)").apply {
                setString(1, p.id); setString(2, p.name); setLong(3, p.seed)
                setLong(4, p.createdAtNanos); setString(5, p.note); executeUpdate()
            }
        }
        a.records.forEach { r ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO raw_records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).apply {
                setString(1, r.id); setString(2, r.projectId); setLong(3, r.ordinal)
                setString(4, r.direction.name); setString(5, r.bytesBase64)
                setString(6, r.connectionId); setLong(7, r.connectionGeneration)
                setString(8, r.transport); setString(9, r.srcAddr); setString(10, r.dstAddr)
                setString(11, r.recvOrig); setString(12, r.recvBasis); setLong(13, r.recvNanos)
                setString(14, r.parentRecordId); setString(15, r.revisionOf); setString(16, r.supersedes)
                setString(17, r.parserVersion); setString(18, r.paramFingerprint)
                setString(19, json.encodeToString(r.parsed)); setLong(20, r.committedAtNanos)
                executeUpdate()
            }
        }
        a.paramRevisions.forEach { insertParamRevision(it) }
        a.decisions.forEach {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO decisions VALUES(?,?,?,?,?,?,?,?,?,?)"
            ).apply {
                setString(1, it.id); setString(2, it.projectId); setString(3, it.eventId)
                setLong(4, it.atNanos); setString(5, it.kind); setString(6, it.targetRef)
                setString(7, it.choice); setString(8, json.encodeToString(it.rejectedCandidates))
                setString(9, it.reason); setString(10, it.undoOf); executeUpdate()
            }
        }
        a.runs.forEach { saveRun(it, System.nanoTime()) }
    }
}

@kotlinx.serialization.Serializable
data class ExportArchive(
    val schemaVersion: Int,
    val projects: List<Project>,
    val records: List<RawRecord>,
    val paramRevisions: List<ParamRevision>,
    val decisions: List<Decision>,
    val runs: List<EngineRun>,
)
