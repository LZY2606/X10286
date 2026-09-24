package sipatlas

import java.sql.Connection as JdbcConnection
import java.sql.DriverManager

/**
 * SQLite-backed append-mostly store. Raw inputs are never overwritten;
 * corrections arrive as new versions linked by parent. Candidates carry
 * validity intervals so any historical event seq can be revisited with the
 * conclusions that were visible at that time.
 */
class Store(dbPath: String) {
    val conn: JdbcConnection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sources(
                  id TEXT PRIMARY KEY,
                  lineage TEXT NOT NULL,
                  version_no INTEGER NOT NULL,
                  parent_id TEXT,
                  direction TEXT,
                  kind TEXT NOT NULL,
                  raw TEXT NOT NULL,
                  received_at_ms INTEGER,
                  received_raw TEXT NOT NULL,
                  conn_id TEXT,
                  conn_generation INTEGER,
                  submitted_seq INTEGER NOT NULL
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS connections(
                  id TEXT NOT NULL,
                  generation INTEGER NOT NULL,
                  transport TEXT NOT NULL,
                  opened_seq INTEGER NOT NULL,
                  closed_seq INTEGER,
                  PRIMARY KEY(id, generation)
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS params(
                  id TEXT PRIMARY KEY,
                  parent_id TEXT,
                  values_json TEXT NOT NULL,
                  fingerprint TEXT NOT NULL,
                  seq INTEGER NOT NULL
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  algo_version TEXT,
                  param_fp TEXT,
                  vclock INTEGER NOT NULL
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS candidates(
                  id TEXT PRIMARY KEY,
                  run_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  natural_key TEXT NOT NULL,
                  status TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  algo_version TEXT NOT NULL,
                  param_fp TEXT NOT NULL,
                  source_ids TEXT NOT NULL,
                  rejection_reason TEXT,
                  valid_from_seq INTEGER NOT NULL,
                  valid_to_seq INTEGER
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS decisions(
                  id TEXT PRIMARY KEY,
                  target_id TEXT NOT NULL,
                  action TEXT NOT NULL,
                  rationale TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  conflicts_with TEXT,
                  undone_by TEXT
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meta(
                  key TEXT PRIMARY KEY,
                  value TEXT NOT NULL
                )"""
            )
        }
    }

    private var counters = mutableMapOf<String, Long>()

    @Synchronized
    fun nextId(prefix: String): String {
        val key = "seq:$prefix"
        val cur = counters.getOrPut(key) { getMeta(key)?.toLongOrNull() ?: 0L } + 1
        counters[key] = cur
        setMeta(key, cur.toString())
        return "$prefix-$cur"
    }

    fun nextEventSeq(): Long {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events").use { rs ->
                rs.next(); return rs.getLong(1) + 1
            }
        }
    }

    fun getMeta(key: String): String? {
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }

    fun insertSource(s: SourceVersion) {
        conn.prepareStatement(
            "INSERT INTO sources(id,lineage,version_no,parent_id,direction,kind,raw,received_at_ms,received_raw,conn_id,conn_generation,submitted_seq) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, s.id); ps.setString(2, s.lineage); ps.setInt(3, s.versionNo)
            ps.setString(4, s.parentId); ps.setString(5, s.direction?.name)
            ps.setString(6, s.kind.name); ps.setString(7, s.raw)
            if (s.receivedAtMs != null) ps.setLong(8, s.receivedAtMs) else ps.setNull(8, java.sql.Types.INTEGER)
            ps.setString(9, s.receivedRaw); ps.setString(10, s.connId)
            if (s.connGeneration != null) ps.setInt(11, s.connGeneration) else ps.setNull(11, java.sql.Types.INTEGER)
            ps.setLong(12, s.submittedSeq)
            ps.executeUpdate()
        }
    }

    fun allSources(): List<SourceVersion> {
        val out = mutableListOf<SourceVersion>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM sources ORDER BY submitted_seq").use { rs ->
                while (rs.next()) out.add(rowToSource(rs))
            }
        }
        return out
    }

    private fun rowToSource(rs: java.sql.ResultSet) = SourceVersion(
        id = rs.getString("id"),
        lineage = rs.getString("lineage"),
        versionNo = rs.getInt("version_no"),
        parentId = rs.getString("parent_id"),
        direction = rs.getString("direction")?.let { Direction.valueOf(it) },
        kind = SourceKind.valueOf(rs.getString("kind")),
        raw = rs.getString("raw"),
        receivedAtMs = rs.getObject("received_at_ms")?.let { (it as Number).toLong() },
        receivedRaw = rs.getString("received_raw"),
        connId = rs.getString("conn_id"),
        connGeneration = rs.getObject("conn_generation")?.let { (it as Number).toInt() },
        submittedSeq = rs.getLong("submitted_seq"),
    )

    /** Current (latest) version per lineage. */
    fun currentSources(): List<SourceVersion> {
        val byLineage = allSources().groupBy { it.lineage }
        return byLineage.values.map { versions -> versions.maxBy { it.versionNo } }
            .sortedBy { it.submittedSeq }
    }

    fun sourceLineageOf(versionIds: Collection<String>): Set<String> {
        if (versionIds.isEmpty()) return emptySet()
        val all = allSources().associateBy { it.id }
        return versionIds.mapNotNull { all[it]?.lineage }.toSet()
    }

    fun upsertConnection(c: Connection) {
        conn.prepareStatement(
            "INSERT INTO connections(id,generation,transport,opened_seq,closed_seq) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(id,generation) DO UPDATE SET closed_seq=excluded.closed_seq"
        ).use { ps ->
            ps.setString(1, c.id); ps.setInt(2, c.generation); ps.setString(3, c.transport)
            ps.setLong(4, c.openedSeq)
            if (c.closedSeq != null) ps.setLong(5, c.closedSeq) else ps.setNull(5, java.sql.Types.INTEGER)
            ps.executeUpdate()
        }
    }

    fun allConnections(): List<Connection> {
        val out = mutableListOf<Connection>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM connections ORDER BY opened_seq").use { rs ->
                while (rs.next()) out.add(
                    Connection(
                        rs.getString("id"), rs.getInt("generation"), rs.getString("transport"),
                        rs.getLong("opened_seq"),
                        rs.getObject("closed_seq")?.let { (it as Number).toLong() },
                    )
                )
            }
        }
        return out
    }

    fun insertParam(id: String, parentId: String?, p: AlgoParams, seq: Long) {
        conn.prepareStatement("INSERT INTO params(id,parent_id,values_json,fingerprint,seq) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, parentId)
            ps.setString(3, json.encodeToString(AlgoParams.serializer(), p))
            ps.setString(4, p.fingerprint); ps.setLong(5, seq)
            ps.executeUpdate()
        }
    }

    fun currentParams(): Pair<String, AlgoParams>? {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM params ORDER BY seq DESC LIMIT 1").use { rs ->
                if (!rs.next()) return null
                return rs.getString("id") to json.decodeFromString(AlgoParams.serializer(), rs.getString("values_json"))
            }
        }
    }

    fun allParams(): List<Triple<String, String?, AlgoParams>> {
        val out = mutableListOf<Triple<String, String?, AlgoParams>>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM params ORDER BY seq").use { rs ->
                while (rs.next()) out.add(
                    Triple(rs.getString("id"), rs.getString("parent_id"),
                        json.decodeFromString(AlgoParams.serializer(), rs.getString("values_json")))
                )
            }
        }
        return out
    }

    fun appendEvent(kind: String, payload: String, algoVersion: String?, paramFp: String?, vclock: Long): Event {
        val id = nextId("evt")
        conn.prepareStatement("INSERT INTO events(id,kind,payload,algo_version,param_fp,vclock) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, kind); ps.setString(3, payload)
            ps.setString(4, algoVersion); ps.setString(5, paramFp); ps.setLong(6, vclock)
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq FROM events WHERE id='$id'").use { rs ->
                rs.next()
                return Event(rs.getLong(1), id, kind, payload, algoVersion, paramFp, vclock)
            }
        }
    }

    fun events(upToSeq: Long? = null): List<Event> {
        val out = mutableListOf<Event>()
        val sql = if (upToSeq == null) "SELECT * FROM events ORDER BY seq"
        else "SELECT * FROM events WHERE seq <= $upToSeq ORDER BY seq"
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) out.add(
                    Event(rs.getLong("seq"), rs.getString("id"), rs.getString("kind"),
                        rs.getString("payload"), rs.getString("algo_version"),
                        rs.getString("param_fp"), rs.getLong("vclock"))
                )
            }
        }
        return out
    }

    fun insertCandidate(c: Candidate) {
        conn.prepareStatement(
            "INSERT INTO candidates(id,run_id,kind,natural_key,status,payload,algo_version,param_fp,source_ids,rejection_reason,valid_from_seq,valid_to_seq) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, c.id); ps.setString(2, c.runId); ps.setString(3, c.kind.name)
            ps.setString(4, c.naturalKey); ps.setString(5, c.status.name); ps.setString(6, c.payload)
            ps.setString(7, c.algoVersion); ps.setString(8, c.paramFp)
            ps.setString(9, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), c.sourceIds))
            ps.setString(10, c.rejectionReason); ps.setLong(11, c.validFromSeq)
            if (c.validToSeq != null) ps.setLong(12, c.validToSeq) else ps.setNull(12, java.sql.Types.INTEGER)
            ps.executeUpdate()
        }
    }

    fun updateCandidateStatus(id: String, status: CandidateStatus, reason: String? = null, validToSeq: Long? = null) {
        conn.prepareStatement("UPDATE candidates SET status=?, rejection_reason=COALESCE(?,rejection_reason), valid_to_seq=COALESCE(?,valid_to_seq) WHERE id=?").use { ps ->
            ps.setString(1, status.name); ps.setString(2, reason)
            if (validToSeq != null) ps.setLong(3, validToSeq) else ps.setNull(3, java.sql.Types.INTEGER)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    fun candidates(atSeq: Long? = null): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val sql = StringBuilder("SELECT * FROM candidates")
        if (atSeq != null) sql.append(" WHERE valid_from_seq <= $atSeq AND (valid_to_seq IS NULL OR valid_to_seq > $atSeq)")
        sql.append(" ORDER BY valid_from_seq, id")
        conn.createStatement().use { st ->
            st.executeQuery(sql.toString()).use { rs ->
                while (rs.next()) out.add(rowToCandidate(rs))
            }
        }
        return out
    }

    fun allCandidatesEver(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM candidates ORDER BY valid_from_seq, id").use { rs ->
                while (rs.next()) out.add(rowToCandidate(rs))
            }
        }
        return out
    }

    private fun rowToCandidate(rs: java.sql.ResultSet) = Candidate(
        id = rs.getString("id"),
        runId = rs.getString("run_id"),
        kind = CandidateKind.valueOf(rs.getString("kind")),
        naturalKey = rs.getString("natural_key"),
        status = CandidateStatus.valueOf(rs.getString("status")),
        payload = rs.getString("payload"),
        algoVersion = rs.getString("algo_version"),
        paramFp = rs.getString("param_fp"),
        sourceIds = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()), rs.getString("source_ids")),
        rejectionReason = rs.getString("rejection_reason"),
        validFromSeq = rs.getLong("valid_from_seq"),
        validToSeq = rs.getObject("valid_to_seq")?.let { (it as Number).toLong() },
    )

    fun insertDecision(d: Decision) {
        conn.prepareStatement("INSERT INTO decisions(id,target_id,action,rationale,payload,seq,conflicts_with,undone_by) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, d.id); ps.setString(2, d.targetId); ps.setString(3, d.action.name)
            ps.setString(4, d.rationale); ps.setString(5, d.payload); ps.setLong(6, d.seq)
            ps.setString(7, d.conflictsWith); ps.setString(8, d.undoneBy)
            ps.executeUpdate()
        }
    }

    fun markDecisionUndone(id: String, undoneBy: String) {
        conn.prepareStatement("UPDATE decisions SET undone_by=? WHERE id=?").use { ps ->
            ps.setString(1, undoneBy); ps.setString(2, id); ps.executeUpdate()
        }
    }

    fun decisions(): List<Decision> {
        val out = mutableListOf<Decision>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM decisions ORDER BY seq").use { rs ->
                while (rs.next()) out.add(
                    Decision(
                        rs.getString("id"), rs.getString("target_id"),
                        DecisionAction.valueOf(rs.getString("action")), rs.getString("rationale"),
                        rs.getString("payload"), rs.getLong("seq"),
                        rs.getString("conflicts_with"), rs.getString("undone_by"),
                    )
                )
            }
        }
        return out
    }

    fun close() = conn.close()
}
