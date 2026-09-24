package sipreview.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import sipreview.domain.*
import sipreview.engine.Ids
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64

class SqliteStore(private val dbPath: Path) : AutoCloseable {
    val mapper: ObjectMapper = jacksonObjectMapper()
    private val conn: Connection

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            Schema.DDL.trimIndent().split(";").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("PRAGMA") }
                .forEach { st.execute(it) }
            st.execute("PRAGMA foreign_keys=ON")
        }
        val version = conn.createStatement().executeQuery("PRAGMA user_version").use { it.getInt(1) }
        if (version == 0) {
            conn.createStatement().execute("PRAGMA user_version = ${Schema.VERSION}")
        }
    }

    // ---------- projects ----------

    fun createProject(name: String, description: String?, nowMillis: Long,
                      initialParams: Map<String, String>): Project {
        val id = Ids.prefixed("prj:", name, nowMillis.toString())
        val fp = fingerprintParams(initialParams)
        val paramId = Ids.prefixed("par:", id, fp)
        tx {
            prepare("""INSERT INTO projects(id,name,description,created_at_millis,head_param_id)
                VALUES(?,?,?,?,?)""") {
                setString(1, id); setString(2, name); setString(3, description)
                setLong(4, nowMillis); setString(5, paramId)
            }.executeUpdate()
            prepare("""INSERT INTO parameters(id,project_id,name,values_json,fingerprint,
                parent_id,created_at_millis,note) VALUES(?,?,?,?,?,?,?,?)""") {
                setString(1, paramId); setString(2, id); setString(3, "defaults")
                setString(4, mapper.writeValueAsString(initialParams))
                setString(5, fp); setString(6, null); setLong(7, nowMillis)
                setString(8, "RFC 3261 default timer parameters")
            }.executeUpdate()
        }
        return getProject(id)!!
    }

    fun getProject(id: String): Project? = conn.prepareStatement(
        "SELECT * FROM projects WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rowProject(rs) else null }
    }

    fun listProjects(): List<Project> = conn.createStatement().executeQuery(
        "SELECT * FROM projects ORDER BY created_at_millis").mapRows(::rowProject)

    // ---------- batches / raw (immutable, append-only) ----------

    fun importBatch(projectId: String, name: String, items: List<PreparedItem>,
                    diagnostics: List<String>, nowMillis: Long,
                    formatVersion: String = "sip-review-import/1"): SourceBatch {
        val batchId = Ids.prefixed("bat:", projectId, name, nowMillis.toString(),
            items.size.toString())
        var head: String? = null
        tx {
            prepare("""INSERT INTO batches(id,project_id,name,format_version,submitted_at_millis,
                item_count,diagnostics_json) VALUES(?,?,?,?,?,?,?)""") {
                setString(1, batchId); setString(2, projectId); setString(3, name)
                setString(4, formatVersion); setLong(5, nowMillis)
                setInt(6, items.size); setString(7, mapper.writeValueAsString(diagnostics))
            }.executeUpdate()
            val nextOrdinal = (maxOrdinal(projectId) ?: 0)
            items.forEachIndexed { i, it ->
                val ordinal = nextOrdinal + i + 1
                val id = Ids.prefixed("raw:", projectId, ordinal.toString(),
                    Base64.getEncoder().encodeToString(it.rawBytes),
                    it.direction.name, it.connectionId, it.connectionGeneration.toString(),
                    it.receivedAtMillis.toString())
                insertRaw(RawVersion(id, ordinal.toLong(), projectId, batchId,
                    it.direction, it.transport, it.connectionId, it.connectionGeneration,
                    it.localAddress, it.remoteAddress, it.receivedAtMillis, it.clockSource,
                    Base64.getEncoder().encodeToString(it.rawBytes), it.rawText,
                    it.label, it.note, null, false, nowMillis, null))
                head = id
            }
            prepare("UPDATE projects SET head_raw_version_id=? WHERE id=?") {
                setString(1, head); setString(2, projectId)
            }.executeUpdate()
        }
        return getBatch(batchId)!!
    }

    /** Append a corrected version of one raw observation; original stays but is superseded. */
    fun reviseRaw(projectId: String, parentId: String, item: PreparedItem,
                  nowMillis: Long): RawVersion {
        val parent = getRaw(parentId) ?: error("unknown parent raw version $parentId")
        require(parent.projectId == projectId) { "raw version belongs to another project" }
        val ordinal = (maxOrdinal(projectId) ?: 0) + 1
        val id = Ids.prefixed("raw:", projectId, ordinal.toString(),
            Base64.getEncoder().encodeToString(item.rawBytes),
            item.direction.name, item.connectionId, item.connectionGeneration.toString(),
            item.receivedAtMillis.toString())
        tx {
            insertRaw(RawVersion(id, ordinal, projectId, parent.batchId,
                item.direction, item.transport, item.connectionId, item.connectionGeneration,
                item.localAddress, item.remoteAddress, item.receivedAtMillis, item.clockSource,
                Base64.getEncoder().encodeToString(item.rawBytes), item.rawText,
                item.label, item.note, parentId, false, nowMillis,
                "revision of $parentId"))
            prepare("UPDATE raw_versions SET superseded=1 WHERE id=?") {
                setString(1, parentId)
            }.executeUpdate()
            prepare("UPDATE projects SET head_raw_version_id=? WHERE id=?") {
                setString(1, id); setString(2, projectId)
            }.executeUpdate()
        }
        return getRaw(id)!!
    }

    private fun insertRaw(r: RawVersion) {
        prepare("""INSERT INTO raw_versions(id,ordinal,project_id,batch_id,direction,transport,
            connection_id,connection_generation,local_address,remote_address,
            received_at_millis,clock_source,raw_bytes,raw_text,label,note,
            parent_version_id,superseded,submitted_at_millis)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""") {
            setString(1, r.id); setLong(2, r.ordinal); setString(3, r.projectId)
            setString(4, r.batchId); setString(5, r.direction.name); setString(6, r.transport.name)
            setString(7, r.connectionId); setInt(8, r.connectionGeneration)
            setString(9, r.localAddress); setString(10, r.remoteAddress)
            setLong(11, r.receivedAtMillis); setString(12, r.clockSource)
            setBytes(13, Base64.getDecoder().decode(r.rawBytesBase64))
            setString(14, r.rawText); setString(15, r.label); setString(16, r.note)
            setString(17, r.parentVersionId); setInt(18, if (r.superseded) 1 else 0)
            setLong(19, r.submittedAtMillis)
        }.executeUpdate()
    }

    fun listRaw(projectId: String, currentOnly: Boolean): List<RawVersion> =
        val sql = buildString {
            append("SELECT * FROM raw_versions WHERE project_id=?")
            if (currentOnly) append(" AND superseded=0")
            append(" ORDER BY ordinal")
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().mapRows(::rowRaw)
        }

    fun getRaw(id: String): RawVersion? = conn.prepareStatement(
        "SELECT * FROM raw_versions WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) rowRaw(it) else null }
    }

    fun listBatches(projectId: String): List<SourceBatch> = conn.prepareStatement(
        "SELECT * FROM batches WHERE project_id=? ORDER BY submitted_at_millis").use { ps ->
        ps.setString(1, projectId); ps.executeQuery().mapRows(::rowBatch)
    }

    fun getBatch(id: String): SourceBatch? = conn.prepareStatement(
        "SELECT * FROM batches WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) rowBatch(it) else null }
    }

    private fun maxOrdinal(projectId: String): Int? = conn.prepareStatement(
        "SELECT MAX(ordinal) FROM raw_versions WHERE project_id=?").use { ps ->
        ps.setString(1, projectId); ps.executeQuery().use {
            if (it.next()) it.getInt(1).takeIf { _ -> !it.wasNull() } else null
        }
    }

    // ---------- parameters (versioned) ----------

    fun addParameters(projectId: String, name: String, values: Map<String, String>,
                      note: String?, nowMillis: Long): ParameterSet {
        val parent = currentParameters(projectId)
        val fp = fingerprintParams(values)
        val id = Ids.prefixed("par:", projectId, fp, nowMillis.toString())
        tx {
            prepare("""INSERT INTO parameters(id,project_id,name,values_json,fingerprint,
                parent_id,created_at_millis,note) VALUES(?,?,?,?,?,?,?,?)""") {
                setString(1, id); setString(2, projectId); setString(3, name)
                setString(4, mapper.writeValueAsString(values)); setString(5, fp)
                setString(6, parent?.id); setLong(7, nowMillis); setString(8, note)
            }.executeUpdate()
            prepare("UPDATE projects SET head_param_id=? WHERE id=?") {
                setString(1, id); setString(2, projectId)
            }.executeUpdate()
        }
        return getParameters(id)!!
    }

    fun currentParameters(projectId: String): ParameterSet? {
        val p = getProject(projectId) ?: return null
        return getParameters(p.headParamId)
    }

    fun getParameters(id: String): ParameterSet? = conn.prepareStatement(
        "SELECT * FROM parameters WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) rowParam(it) else null }
    }

    fun listParameters(projectId: String): List<ParameterSet> = conn.prepareStatement(
        "SELECT * FROM parameters WHERE project_id=? ORDER BY created_at_millis").use { ps ->
        ps.setString(1, projectId); ps.executeQuery().mapRows(::rowParam)
    }

    // ---------- decisions (append-only, conflicts retained) ----------

    fun addDecision(projectId: String, targetId: String, targetKind: String,
                    verdict: String, rationale: String, nowMillis: Long): Decision {
        val previous = conn.prepareStatement(
            "SELECT * FROM decisions WHERE project_id=? AND target_id=? AND superseded_by IS NULL ORDER BY created_at_millis DESC LIMIT 1").use { ps ->
            ps.setString(1, projectId); ps.setString(2, targetId)
            ps.executeQuery().use { if (it.next()) rowDecision(it) else null }
        }
        val conflict = previous?.takeIf { it.verdict != verdict }
        val id = Ids.prefixed("dec:", projectId, targetId, verdict, nowMillis.toString())
        tx {
            prepare("""INSERT INTO decisions(id,project_id,target_id,target_kind,verdict,
                rationale,conflicts_with,created_at_millis,superseded_by)
                VALUES(?,?,?,?,?,?,?,?,?)""") {
                setString(1, id); setString(2, projectId); setString(3, targetId)
                setString(4, targetKind); setString(5, verdict); setString(6, rationale)
                setString(7, conflict?.id); setLong(8, nowMillis); setString(9, null)
            }.executeUpdate()
            if (previous != null) {
                prepare("UPDATE decisions SET superseded_by=? WHERE id=?") {
                    setString(1, id); setString(2, previous.id)
                }.executeUpdate()
            }
            prepare("UPDATE projects SET head_decision_id=? WHERE id=?") {
                setString(1, id); setString(2, projectId)
            }.executeUpdate()
        }
        return getDecision(id)!!
    }

    fun listDecisions(projectId: String): List<Decision> = conn.prepareStatement(
        "SELECT * FROM decisions WHERE project_id=? ORDER BY created_at_millis").use { ps ->
        ps.setString(1, projectId); ps.executeQuery().mapRows(::rowDecision)
    }

    private fun getDecision(id: String): Decision? = conn.prepareStatement(
        "SELECT * FROM decisions WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) rowDecision(it) else null }
    }

    // ---------- runs / derived / events ----------

    fun startRun(projectId: String, nowMillis: Long, note: String?): String {
        val prj = getProject(projectId)!!
        val id = Ids.prefixed("run:", projectId, nowMillis.toString(),
            prj.headRawVersionId ?: "empty", prj.headParamId)
        prepare("""INSERT INTO runs(id,project_id,started_at_millis,finished_at_millis,
            raw_head_version,param_id,decision_head_id,event_count,note)
            VALUES(?,?,?,?,?,?,?,?,?)""") {
            setString(1, id); setString(2, projectId); setLong(3, nowMillis)
            setLong(4, 0); setString(5, prj.headRawVersionId); setString(6, prj.headParamId)
            setString(7, prj.headDecisionId); setLong(8, 0); setString(9, note)
        }.executeUpdate()
        return id
    }

    fun finishRun(runId: String, finishedAt: Long, eventCount: Long) {
        prepare("UPDATE runs SET finished_at_millis=?, event_count=? WHERE id=?") {
            setLong(1, finishedAt); setLong(2, eventCount); setString(3, runId)
        }.executeUpdate()
    }

    fun insertDerived(rows: List<DerivedRow>) {
        if (rows.isEmpty()) return
        prepare("""INSERT INTO derived_rows(id,project_id,run_id,kind,status,subject_id,label,
            payload_json,direct_sources_json,parent_derived_id,algorithm_version,
            param_fingerprint,created_at_millis,stale,stale_reason)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""") {
            for (r in rows) {
                setString(1, r.id); setString(2, r.projectId); setString(3, r.runId)
                setString(4, r.kind); setString(5, r.status); setString(6, r.subjectId)
                setString(7, r.label); setString(8, r.payloadJson)
                setString(9, mapper.writeValueAsString(r.directSources))
                setString(10, r.parentDerivedId); setString(11, r.algorithmVersion)
                setString(12, r.paramFingerprint); setLong(13, r.createdAtMillis)
                setInt(14, if (r.stale) 1 else 0); setString(15, r.staleReason)
                addBatch()
            }
            executeBatch()
        }
    }

    fun markDerivedStaleForSource(projectId: String, changedRawIds: Set<String>, reason: String) {
        // Local invalidation: only derived rows whose direct provenance touches changed sources.
        val all = conn.prepareStatement(
            "SELECT id, run_id, direct_sources_json FROM derived_rows WHERE project_id=? AND stale=0").use { ps ->
            ps.setString(1, projectId); ps.executeQuery().mapRows { rs ->
                Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            }
        }
        prepare("UPDATE derived_rows SET stale=1, stale_reason=? WHERE id=? AND run_id=?") {
            for ((id, runId, sourcesJson) in all) {
                val sources = mapper.readValue(sourcesJson, List::class.java).map { it.toString() }.toSet()
                if (sources.any { it in changedRawIds }) {
                    setString(1, reason); setString(2, id); setString(3, runId); addBatch()
                }
            }
            executeBatch()
        }
    }

    fun listDerived(projectId: String, runId: String?): List<DerivedRow> {
        val sql = "SELECT * FROM derived_rows WHERE project_id=?" +
            (if (runId != null) " AND run_id=?" else "") + " ORDER BY created_at_millis,id"
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, projectId); if (runId != null) ps.setString(2, runId)
            ps.executeQuery().mapRows(::rowDerived)
        }
    }

    fun insertEvents(events: List<DomainEvent>) {
        if (events.isEmpty()) return
        prepare("""INSERT INTO events(project_id,run_id,seq,at_millis,type,subject_id,
            payload_json,sources_json,decision_ref) VALUES(?,?,?,?,?,?,?,?,?)""") {
            for (e in events) {
                setString(1, e.projectId); setString(2, e.runId); setLong(3, e.seq)
                setLong(4, e.atMillis); setString(5, e.type); setString(6, e.subjectId)
                setString(7, e.payloadJson)
                setString(8, mapper.writeValueAsString(e.sources))
                setString(9, e.decisionRef); addBatch()
            }
            executeBatch()
        }
    }

    fun listEvents(projectId: String, runId: String?): List<DomainEvent> {
        val sql = "SELECT * FROM events WHERE project_id=?" +
            (if (runId != null) " AND run_id=?" else "") + " ORDER BY seq,id"
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, projectId); if (runId != null) ps.setString(2, runId)
            ps.executeQuery().mapRows(::rowEvent)
        }
    }

    fun listRuns(projectId: String): List<RunRecord> = conn.prepareStatement(
        "SELECT * FROM runs WHERE project_id=? ORDER BY started_at_millis").use { ps ->
        ps.setString(1, projectId); ps.executeQuery().mapRows(::rowRun)
    }

    fun getRun(id: String): RunRecord? = conn.prepareStatement(
        "SELECT * FROM runs WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) rowRun(it) else null }
    }

    // ---------- counterexample archive ----------

    fun insertArchive(e: ArchiveEntry) {
        prepare("""INSERT OR REPLACE INTO archive_entries(id,project_id,scenario,title,baseline_id,
            mutated_source_id,mutation_json,adopted_json,rejected_json,judgement_events_json,
            blast_radius_json,created_at_millis) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""") {
            setString(1, e.id); setString(2, e.projectId); setString(3, e.scenario)
            setString(4, e.title); setString(5, e.baselineId); setString(6, e.mutatedSourceId)
            setString(7, e.mutation); setString(8, e.adoptedResultJson)
            setString(9, e.rejectedResultsJson); setString(10, e.judgementEventsJson)
            setString(11, e.blastRadiusJson); setLong(12, e.createdAtMillis)
        }.executeUpdate()
    }

    fun listArchive(projectId: String): List<ArchiveEntry> = conn.prepareStatement(
        "SELECT * FROM archive_entries WHERE project_id=? ORDER BY created_at_millis").use { ps ->
        ps.setString(1, projectId); ps.executeQuery().mapRows(::rowArchive)
    }

    // ---------- export / import (identity, order and provenance preserved) ----------

    fun exportProject(projectId: String): ProjectBundle {
        val prj = getProject(projectId) ?: error("no project $projectId")
        return ProjectBundle(
            format = "sip-review-project/1",
            project = prj,
            batches = listBatches(projectId),
            rawVersions = conn.prepareStatement(
                "SELECT * FROM raw_versions WHERE project_id=? ORDER BY ordinal").use { ps ->
                ps.setString(1, projectId); ps.executeQuery().mapRows(::rowRaw)
            },
            parameters = listParameters(projectId),
            decisions = listDecisions(projectId),
            runs = listRuns(projectId),
            derivedRows = listDerived(projectId, null),
            events = listEvents(projectId, null),
            archive = listArchive(projectId)
        )
    }

    fun importProject(bundle: ProjectBundle): Project {
        require(bundle.format == "sip-review-project/1") {
            "unsupported bundle format: ${bundle.format}"
        }
        val prj = bundle.project
        if (getProject(prj.id) != null) error("project already exists: ${prj.id}")
        tx {
            prepare("""INSERT INTO projects(id,name,description,created_at_millis,head_param_id,
                head_raw_version_id,head_decision_id) VALUES(?,?,?,?,?,?,?)""") {
                setString(1, prj.id); setString(2, prj.name); setString(3, prj.description)
                setLong(4, prj.createdAtMillis); setString(5, prj.headParamId)
                setString(6, prj.headRawVersionId); setString(7, prj.headDecisionId)
            }.executeUpdate()
            for (b in bundle.batches.sortedBy { it.submittedAtMillis }) {
                prepare("""INSERT INTO batches(id,project_id,name,format_version,submitted_at_millis,
                    item_count,diagnostics_json) VALUES(?,?,?,?,?,?,?)""") {
                    setString(1, b.id); setString(2, b.projectId); setString(3, b.name)
                    setString(4, b.formatVersion); setLong(5, b.submittedAtMillis)
                    setInt(6, b.itemCount)
                    setString(7, mapper.writeValueAsString(b.parseDiagnostics))
                }.executeUpdate()
            }
            for (r in bundle.rawVersions.sortedBy { it.ordinal }) insertRaw(r)
            for (p in bundle.parameters.sortedBy { it.createdAtMillis }) {
                prepare("""INSERT INTO parameters(id,project_id,name,values_json,fingerprint,
                    parent_id,created_at_millis,note) VALUES(?,?,?,?,?,?,?,?)""") {
                    setString(1, p.id); setString(2, p.projectId); setString(3, p.name)
                    setString(4, mapper.writeValueAsString(p.values)); setString(5, p.fingerprint)
                    setString(6, p.parentId); setLong(7, p.createdAtMillis); setString(8, p.note)
                }.executeUpdate()
            }
            for (d in bundle.decisions.sortedBy { it.createdAtMillis }) {
                prepare("""INSERT INTO decisions(id,project_id,target_id,target_kind,verdict,
                    rationale,conflicts_with,created_at_millis,superseded_by)
                    VALUES(?,?,?,?,?,?,?,?,?)""") {
                    setString(1, d.id); setString(2, d.projectId); setString(3, d.targetId)
                    setString(4, d.targetKind); setString(5, d.verdict); setString(6, d.rationale)
                    setString(7, d.conflictsWith); setLong(8, d.createdAtMillis)
                    setString(9, d.supersededBy)
                }.executeUpdate()
            }
            for (run in bundle.runs.sortedBy { it.startedAtMillis }) {
                prepare("""INSERT INTO runs(id,project_id,started_at_millis,finished_at_millis,
                    raw_head_version,param_id,decision_head_id,event_count,note)
                    VALUES(?,?,?,?,?,?,?,?,?)""") {
                    setString(1, run.id); setString(2, run.projectId)
                    setLong(3, run.startedAtMillis); setLong(4, run.finishedAtMillis)
                    setString(5, run.rawHeadVersion); setString(6, run.paramId)
                    setString(7, run.decisionHeadId); setLong(8, run.eventCount)
                    setString(9, run.note)
                }.executeUpdate()
            }
            insertDerived(bundle.derivedRows)
            insertEvents(bundle.events)
            for (a in bundle.archive) insertArchive(a)
        }
        return prj
    }

    // ---------- plumbing ----------

    fun fingerprintParams(values: Map<String, String>): String {
        val canonical = values.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
        return "${sipreview.engine.TimerParamsFpVersion.VALUE}:${Ids.sha1(listOf(canonical)).take(24)}"
    }

    private fun tx(block: Connection.() -> Unit) {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.block()
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = prev
        }
    }

    private fun prepare(sql: String, block: java.sql.PreparedStatement.() -> Unit) =
        conn.prepareStatement(sql).apply { block() }

    override fun close() = conn.close()

    private fun rowProject(rs: ResultSet) = Project(
        rs.getString("id"), rs.getString("name"), rs.getLong("created_at_millis"),
        rs.getString("head_param_id"), rs.getString("head_raw_version_id"),
        rs.getString("head_decision_id"), rs.getString("description"))

    private fun rowBatch(rs: ResultSet) = SourceBatch(
        rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
        rs.getString("format_version"), rs.getLong("submitted_at_millis"),
        rs.getInt("item_count"),
        mapper.readValue(rs.getString("diagnostics_json"),
            mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)))

    private fun rowRaw(rs: ResultSet) = RawVersion(
        id = rs.getString("id"), ordinal = rs.getLong("ordinal"),
        projectId = rs.getString("project_id"), batchId = rs.getString("batch_id"),
        direction = Direction.valueOf(rs.getString("direction")),
        transport = Transport.valueOf(rs.getString("transport")),
        connectionId = rs.getString("connection_id"),
        connectionGeneration = rs.getInt("connection_generation"),
        localAddress = rs.getString("local_address"),
        remoteAddress = rs.getString("remote_address"),
        receivedAtMillis = rs.getLong("received_at_millis"),
        clockSource = rs.getString("clock_source"),
        rawBytesBase64 = Base64.getEncoder().encodeToString(rs.getBytes("raw_bytes")),
        rawText = rs.getString("raw_text"), label = rs.getString("label"),
        note = rs.getString("note"), parentVersionId = rs.getString("parent_version_id"),
        superseded = rs.getInt("superseded") == 1,
        submittedAtMillis = rs.getLong("submitted_at_millis"))

    private fun rowParam(rs: ResultSet): ParameterSet {
        @Suppress("UNCHECKED_CAST")
        val values = mapper.readValue(rs.getString("values_json"), Map::class.java)
            as Map<String, String>
        return ParameterSet(rs.getString("id"), rs.getString("project_id"),
            rs.getString("name"), values, rs.getString("fingerprint"),
            rs.getString("parent_id"), rs.getLong("created_at_millis"), rs.getString("note"))
    }

    private fun rowDecision(rs: ResultSet) = Decision(
        rs.getString("id"), rs.getString("project_id"), rs.getString("target_id"),
        rs.getString("target_kind"), rs.getString("verdict"), rs.getString("rationale"),
        rs.getString("conflicts_with"), rs.getLong("created_at_millis"),
        rs.getString("superseded_by"))

    private fun rowRun(rs: ResultSet) = RunRecord(
        rs.getString("id"), rs.getString("project_id"), rs.getLong("started_at_millis"),
        rs.getLong("finished_at_millis"), rs.getString("raw_head_version"),
        rs.getString("param_id"), rs.getString("decision_head_id"),
        rs.getLong("event_count"), rs.getString("note"))

    private fun rowDerived(rs: ResultSet): DerivedRow {
        @Suppress("UNCHECKED_CAST")
        val sources = mapper.readValue(rs.getString("direct_sources_json"), List::class.java)
            as List<String>
        return DerivedRow(rs.getString("id"), rs.getString("project_id"), rs.getString("run_id"),
            rs.getString("kind"), rs.getString("status"), rs.getString("subject_id"),
            rs.getString("label"), rs.getString("payload_json"), sources,
            rs.getString("parent_derived_id"), rs.getString("algorithm_version"),
            rs.getString("param_fingerprint"), rs.getLong("created_at_millis"),
            rs.getInt("stale") == 1, rs.getString("stale_reason"))
    }

    private fun rowEvent(rs: ResultSet): DomainEvent {
        @Suppress("UNCHECKED_CAST")
        val sources = mapper.readValue(rs.getString("sources_json"), List::class.java)
            as List<String>
        return DomainEvent(rs.getLong("id"), rs.getString("project_id"), rs.getString("run_id"),
            rs.getLong("seq"), rs.getLong("at_millis"), rs.getString("type"),
            rs.getString("subject_id"), rs.getString("payload_json"), sources,
            rs.getString("decision_ref"))
    }

    private fun rowArchive(rs: ResultSet) = ArchiveEntry(
        rs.getString("id"), rs.getString("project_id"), rs.getString("scenario"),
        rs.getString("title"), rs.getString("baseline_id"), rs.getString("mutated_source_id"),
        rs.getString("mutation_json"), rs.getString("adopted_json"),
        rs.getString("rejected_json"), rs.getString("judgement_events_json"),
        rs.getString("blast_radius_json"), rs.getLong("created_at_millis"))

    private fun <T> ResultSet.mapRows(f: (ResultSet) -> T): List<T> {
        val out = mutableListOf<T>()
        while (next()) out += f(this)
        return out
    }
}

data class ProjectBundle(
    val format: String,
    val project: Project,
    val batches: List<SourceBatch>,
    val rawVersions: List<RawVersion>,
    val parameters: List<ParameterSet>,
    val decisions: List<Decision>,
    val runs: List<RunRecord>,
    val derivedRows: List<DerivedRow>,
    val events: List<DomainEvent>,
    val archive: List<ArchiveEntry>
)

