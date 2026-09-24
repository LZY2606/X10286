package forensics.sip.db

import forensics.sip.engine.EngineParams
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class Database(private val conn: Connection) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        fun open(path: Path): Database {
            Files.createDirectories(path.toAbsolutePath().parent ?: Path.of("."))
            Class.forName("org.sqlite.JDBC")
            val c = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            c.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA foreign_keys=ON")
            }
            val db = Database(c)
            db.migrate()
            return db
        }
    }

    fun migrate() {
        conn.createStatement().use { s ->
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS project(
                    k TEXT PRIMARY KEY, v TEXT NOT NULL
                )""".trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS input_batch(
                    id TEXT PRIMARY KEY,
                    schema_version TEXT NOT NULL,
                    ordinal INTEGER NOT NULL UNIQUE,
                    parent_batch_id TEXT REFERENCES input_batch(id),
                    label TEXT NOT NULL,
                    submitted_at_ticks INTEGER NOT NULL,
                    raw_sha TEXT NOT NULL,
                    json TEXT NOT NULL
                )""".trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS message(
                    id TEXT NOT NULL,
                    batch_id TEXT NOT NULL REFERENCES input_batch(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    transport TEXT NOT NULL,
                    conn_gen INTEGER NOT NULL,
                    conn_id TEXT NOT NULL,
                    ticks INTEGER NOT NULL,
                    raw_text TEXT NOT NULL,
                    raw_b64 TEXT NOT NULL,
                    byte_count INTEGER NOT NULL,
                    source_addr TEXT,
                    source_port INTEGER,
                    declared INTEGER NOT NULL,
                    PRIMARY KEY(batch_id, id)
                )""".trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS connection_event(
                    id TEXT NOT NULL,
                    batch_id TEXT NOT NULL REFERENCES input_batch(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    transport TEXT NOT NULL,
                    gen INTEGER NOT NULL,
                    conn_id TEXT NOT NULL,
                    ticks INTEGER NOT NULL,
                    local_addr TEXT,
                    remote_addr TEXT,
                    note TEXT,
                    PRIMARY KEY(batch_id, id)
                )""".trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS import_diagnostic(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_id TEXT NOT NULL REFERENCES input_batch(id) ON DELETE CASCADE,
                    message_ordinal INTEGER,
                    severity TEXT NOT NULL,
                    code TEXT NOT NULL,
                    detail TEXT NOT NULL
                )""".trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS run(
                    ordinal INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL UNIQUE,
                    kind TEXT NOT NULL,
                    parent_run_id TEXT REFERENCES run(id),
                    batch_id TEXT REFERENCES input_batch(id),
                    params_json TEXT NOT NULL,
                    decision_json TEXT,
                    created_at_ticks INTEGER NOT NULL
                )""".trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS derived(
                    run_ordinal INTEGER NOT NULL REFERENCES run(ordinal) ON DELETE CASCADE,
                    kind TEXT NOT NULL,
                    object_id TEXT NOT NULL,
                    json TEXT NOT NULL,
                    PRIMARY KEY(run_ordinal, kind, object_id)
                )""".trimIndent()
            )
        }
    }

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val v = block()
            conn.commit()
            return v
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun getProject(k: String): String? =
        conn.prepareStatement("SELECT v FROM project WHERE k=?").use { ps ->
            ps.setString(1, k); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun setProject(k: String, v: String) {
        conn.prepareStatement("INSERT INTO project(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")
            .use { it.setString(1, k); it.setString(2, v); it.executeUpdate() }
    }

    fun nextBatchOrdinal(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(ordinal)+1,0) FROM input_batch").use { it.getLong(1) }

    fun nextRunOrdinal(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(ordinal)+1,0) FROM run").use { it.getLong(1) }

    fun batchExists(id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM input_batch WHERE id=?").use {
            it.setString(1, id); it.executeQuery().use { r -> r.next() }
        }

    fun insertBatch(
        id: String, schemaVersion: String, ordinal: Long, parentId: String?, label: String,
        submittedAt: Long, rawSha: String, json: String,
        messages: List<forensics.sip.sip.MessageRecord>,
        events: List<forensics.sip.sip.ConnectionEvent>,
        diagnostics: List<forensics.sip.sip.ImportDiagnostic>,
    ) {
        conn.prepareStatement(
            """INSERT INTO input_batch(id,schema_version,ordinal,parent_batch_id,label,submitted_at_ticks,raw_sha,json)
               VALUES(?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, schemaVersion); ps.setLong(3, ordinal)
            ps.setString(4, parentId); ps.setString(5, label); ps.setLong(6, submittedAt)
            ps.setString(7, rawSha); ps.setString(8, json); ps.executeUpdate()
        }
        conn.prepareStatement(
            """INSERT INTO message(id,batch_id,ordinal,direction,transport,conn_gen,conn_id,ticks,
               raw_text,raw_b64,byte_count,source_addr,source_port,declared)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            for ((i, m) in messages.withIndex()) {
                ps.setString(1, m.id); ps.setString(2, id); ps.setLong(3, i + 1L)
                ps.setString(4, m.direction.name); ps.setString(5, m.transport.name)
                ps.setLong(6, m.connectionGeneration); ps.setString(7, m.connectionId)
                ps.setLong(8, m.receivedAtTicksUtc); ps.setString(9, m.rawText)
                ps.setString(10, m.rawBytesBase64); ps.setInt(11, m.rawByteCount)
                ps.setString(12, m.sourceAddress); if (m.sourcePort == null) ps.setNull(13, java.sql.Types.INTEGER) else ps.setInt(13, m.sourcePort)
                ps.setBoolean(14, m.declaredByInput); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            """INSERT INTO connection_event(id,batch_id,ordinal,kind,transport,gen,conn_id,ticks,local_addr,remote_addr,note)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            for ((i, e) in events.withIndex()) {
                ps.setString(1, e.id); ps.setString(2, id); ps.setLong(3, i + 1L)
                ps.setString(4, e.kind); ps.setString(5, e.transport.name); ps.setLong(6, e.generation)
                ps.setString(7, e.connectionId); ps.setLong(8, e.atTicksUtc)
                ps.setString(9, e.localAddress); ps.setString(10, e.remoteAddress); ps.setString(11, e.note)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO import_diagnostic(batch_id,message_ordinal,severity,code,detail) VALUES(?,?,?,?,?)"
        ).use { ps ->
            for (d in diagnostics) {
                ps.setString(1, id)
                if (d.messageOrdinal == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setLong(2, d.messageOrdinal)
                ps.setString(3, d.severity); ps.setString(4, d.code); ps.setString(5, d.detail); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun insertRun(ordinal: Long, id: String, kind: String, parentRunId: String?, batchId: String?,
                  params: EngineParams, decisionJson: String?, createdAt: Long) {
        conn.prepareStatement(
            """INSERT INTO run(ordinal,id,kind,parent_run_id,batch_id,params_json,decision_json,created_at_ticks)
               VALUES(?,?,?,?,?,?,?,?)"""
        ).use {
            it.setLong(1, ordinal); it.setString(2, id); it.setString(3, kind); it.setString(4, parentRunId)
            it.setString(5, batchId); it.setString(6, json.encodeToString(EngineParams.serializer(), params))
            it.setString(7, decisionJson); it.setLong(8, createdAt); it.executeUpdate()
        }
    }

    fun putDerived(runOrdinal: Long, kind: String, objectId: String, jsonValue: String) {
        conn.prepareStatement(
            """INSERT INTO derived(run_ordinal,kind,object_id,json) VALUES(?,?,?,?)
               ON CONFLICT(run_ordinal,kind,object_id) DO UPDATE SET json=excluded.json"""
        ).use {
            it.setLong(1, runOrdinal); it.setString(2, kind); it.setString(3, objectId); it.setString(4, jsonValue)
            it.executeUpdate()
        }
    }

    fun allMessages(): List<forensics.sip.sip.MessageRecord> {
        val out = mutableListOf<forensics.sip.sip.MessageRecord>()
        conn.prepareStatement("SELECT * FROM message ORDER BY batch_id, ordinal").executeQuery().use { r ->
            while (r.next()) out += forensics.sip.sip.MessageRecord(
                r.getString("id"), r.getString("batch_id"), r.getLong("ordinal"),
                forensics.sip.sip.Direction.valueOf(r.getString("direction")),
                forensics.sip.sip.Transport.valueOf(r.getString("transport")),
                r.getLong("conn_gen"), r.getString("conn_id"), r.getLong("ticks"),
                "ns-utc-virtual", r.getString("raw_text"), r.getString("raw_b64"), r.getInt("byte_count"),
                r.getString("source_addr"), r.getObject("source_port") as? Int,
                "observer-socket", r.getBoolean("declared"),
            )
        }
        return out
    }

    fun allConnectionEvents(): List<forensics.sip.sip.ConnectionEvent> {
        val out = mutableListOf<forensics.sip.sip.ConnectionEvent>()
        conn.prepareStatement("SELECT * FROM connection_event ORDER BY batch_id, ordinal").executeQuery().use { r ->
            while (r.next()) out += forensics.sip.sip.ConnectionEvent(
                r.getString("id"), r.getString("batch_id"), r.getLong("ordinal"), r.getString("kind"),
                forensics.sip.sip.Transport.valueOf(r.getString("transport")), r.getLong("gen"),
                r.getString("conn_id"), r.getLong("ticks"), "ns-utc-virtual",
                r.getString("local_addr"), r.getString("remote_addr"), r.getString("note"),
            )
        }
        return out
    }

    fun runs(): List<RunRow> {
        val out = mutableListOf<RunRow>()
        conn.createStatement().executeQuery("SELECT * FROM run ORDER BY ordinal").use { r ->
            while (r.next()) out += RunRow(
                r.getLong("ordinal"), r.getString("id"), r.getString("kind"),
                r.getString("parent_run_id"), r.getString("batch_id"),
                r.getString("params_json"), r.getString("decision_json"), r.getLong("created_at_ticks"),
            )
        }
        return out
    }

    fun latestRun(): RunRow? = runs().lastOrNull()

    fun derivedAt(runOrdinal: Long, kind: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        conn.prepareStatement(
            """SELECT object_id, json FROM derived WHERE kind=? AND run_ordinal<=?
               ORDER BY run_ordinal"""
        ).use { ps ->
            ps.setString(1, kind); ps.setLong(2, runOrdinal)
            ps.executeQuery().use { r -> while (r.next()) out[r.getString(1)] = r.getString(2) }
        }
        return out
    }

    fun currentRunOrdinal(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(ordinal),0) FROM run").use { it.getLong(1) }
}

data class RunRow(
    val ordinal: Long,
    val id: String,
    val kind: String,
    val parentRunId: String?,
    val batchId: String?,
    val paramsJson: String,
    val decisionJson: String?,
    val createdAtTicks: Long,
)

fun <T> ResultSet.use(block: (ResultSet) -> T): T = try { block(this) } finally { close() }
