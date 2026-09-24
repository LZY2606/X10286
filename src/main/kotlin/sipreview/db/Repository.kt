package sipreview.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import sipreview.model.*
import java.sql.ResultSet
import java.sql.Types

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

inline fun <reified T> T.toJsonText(): String = JSON.encodeToString(kotlinx.serialization.serializer(), this)
inline fun <reified T> String?.decode(): T = JSON.decodeFromString(this ?: "null")

fun strList(s: String?): List<String> {
    if (s.isNullOrBlank()) return emptyList()
    return JSON.parseToJsonElement(s).jsonArray.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
}

fun List<String>.toStrList(): String = JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), this)

class Repository(val db: Database) {

    private fun nextCounter(projectId: String, name: String): Int = db.tx { c ->
        c.prepareStatement("""
            INSERT INTO counters(project_id,name,value) VALUES(?,?,1)
            ON CONFLICT(project_id,name) DO UPDATE SET value=value+1
            RETURNING value
        """.trimIndent()).apply {
            setString(1, projectId); setString(2, name)
        }.executeQuery().use { if (it.next()) it.getInt(1) else error("counter failed") }
    }

    fun currentCounter(projectId: String, name: String): Int = db.tx { c ->
        c.prepareStatement("SELECT value FROM counters WHERE project_id=? AND name=?").apply {
            setString(1, projectId); setString(2, name)
        }.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
    }

    fun createProject(id: String, name: String, createdAtMs: Long, casePackId: String?) = db.tx { c ->
        c.prepareStatement("INSERT INTO projects(id,name,created_at_ms,case_pack_id) VALUES(?,?,?,?)").apply {
            setString(1, id); setString(2, name); setLong(3, createdAtMs); setString(4, casePackId)
        }.executeUpdate()
        Unit
    }

    fun project(id: String): ProjectInfo? = db.tx { c ->
        c.prepareStatement("SELECT * FROM projects WHERE id=?").apply { setString(1, id) }
            .executeQuery().use { if (it.next()) ProjectInfo(it.getString(1), it.getString(2), it.getLong(3), it.getString(4)) else null }
    }

    fun listProjects(): List<ProjectInfo> = db.tx { c ->
        c.createStatement().executeQuery("SELECT id,name,created_at_ms,case_pack_id FROM projects ORDER BY created_at_ms").readAll {
            ProjectInfo(getString(1), getString(2), getLong(3), getString(4))
        }
    }

    fun insertMaterial(m: MaterialRecord) = db.tx { c ->
        c.prepareStatement("""INSERT INTO materials(uid,project_id,seq,kind,direction,raw_bytes_b64,raw_text,raw_hash,
            |received_at_ms,received_at_original,time_unit,connection_id,connection_generation,parent_uid,revision_reason,payload,committed_at_seq)
            |VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()).apply {
            setString(1, m.uid); setString(2, m.projectId); setInt(3, m.seq); setString(4, m.kind.name)
            setString(5, m.direction); setString(6, m.rawBytesBase64); setString(7, m.rawText); setString(8, m.rawHash)
            if (m.receivedAtMs == null) setNull(9, Types.INTEGER) else setLong(9, m.receivedAtMs)
            setString(10, m.receivedAtOriginal); setString(11, m.timeUnit)
            setString(12, m.connectionId); if (m.connectionGeneration == null) setNull(13, Types.INTEGER) else setInt(13, m.connectionGeneration)
            setString(14, m.parentUid); setString(15, m.revisionReason)
            setString(16, JSON.encodeToString(JsonObject.serializer(), m.payload)); setInt(17, m.committedAtSeq)
        }.executeUpdate(); Unit
    }

    fun replaceAllForImport(projects: List<ProjectInfo>, materials: List<MaterialRecord>,
                            events: List<EventRecord>, decisions: List<DecisionRecord>,
                            archives: List<ArchiveRun>, counters: List<Triple<String, String, Int>>) = db.tx { c ->
        listOf("archive_runs", "decisions", "derived_events", "materials", "counters", "projects").forEach {
            c.createStatement().execute("DELETE FROM $it")
        }
        projects.forEach { p ->
            c.prepareStatement("INSERT INTO projects(id,name,created_at_ms,case_pack_id) VALUES(?,?,?,?)").apply {
                setString(1, p.id); setString(2, p.name); setLong(3, p.createdAtMs); setString(4, p.casePackId)
            }.executeUpdate()
        }
        materials.forEach { m ->
            c.prepareStatement("""INSERT INTO materials(uid,project_id,seq,kind,direction,raw_bytes_b64,raw_text,raw_hash,
                |received_at_ms,received_at_original,time_unit,connection_id,connection_generation,parent_uid,revision_reason,payload,committed_at_seq)
                |VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()).apply {
                setString(1, m.uid); setString(2, m.projectId); setInt(3, m.seq); setString(4, m.kind.name)
                setString(5, m.direction); setString(6, m.rawBytesBase64); setString(7, m.rawText); setString(8, m.rawHash)
                if (m.receivedAtMs == null) setNull(9, Types.INTEGER) else setLong(9, m.receivedAtMs)
                setString(10, m.receivedAtOriginal); setString(11, m.timeUnit)
                setString(12, m.connectionId); if (m.connectionGeneration == null) setNull(13, Types.INTEGER) else setInt(13, m.connectionGeneration)
                setString(14, m.parentUid); setString(15, m.revisionReason)
                setString(16, JSON.encodeToString(JsonObject.serializer(), m.payload)); setInt(17, m.committedAtSeq)
            }.executeUpdate()
        }
        events.forEach { insertEventStatement(c, it).executeUpdate() }
        decisions.forEach { insertDecisionStatement(c, it).executeUpdate() }
        archives.forEach { insertArchiveStatement(c, it).executeUpdate() }
        counters.forEach { (p, n, v) ->
            c.prepareStatement("INSERT INTO counters(project_id,name,value) VALUES(?,?,?)").apply {
                setString(1, p); setString(2, n); setInt(3, v)
            }.executeUpdate()
        }
        Unit
    }

    fun materials(projectId: String): List<MaterialRecord> = db.tx { c ->
        c.prepareStatement("SELECT * FROM materials WHERE project_id=? ORDER BY seq").apply { setString(1, projectId) }
            .executeQuery().readAll { readMaterial() }
    }

    private fun ResultSet.readMaterial() = MaterialRecord(
        uid = getString("uid"), projectId = getString("project_id"), seq = getInt("seq"),
        kind = MaterialKind.valueOf(getString("kind")), direction = getString("direction"),
        rawBytesBase64 = getString("raw_bytes_b64"), rawText = getString("raw_text"), rawHash = getString("raw_hash"),
        receivedAtMs = getObject("received_at_ms") as? Long, receivedAtOriginal = getString("received_at_original"),
        timeUnit = getString("time_unit"), connectionId = getString("connection_id"),
        connectionGeneration = getObject("connection_generation") as? Int,
        parentUid = getString("parent_uid"), revisionReason = getString("revision_reason"),
        payload = JSON.parseToJsonElement(getString("payload")).jsonObject, committedAtSeq = getInt("committed_at_seq")
    )

    fun insertEvent(e: EventRecord) = db.tx { c -> insertEventStatement(c, e).executeUpdate(); Unit }

    fun insertEvents(es: List<EventRecord>) = db.tx { c ->
        es.forEach { insertEventStatement(c, it).executeUpdate() }; Unit
    }

    private fun insertEventStatement(c: java.sql.Connection, e: EventRecord) =
        c.prepareStatement("""INSERT INTO derived_events(uid,project_id,seq,kind,at_ms,subject_uid,subject_kind,
            |generation_seq,basis_uids,source_uids,algorithm_version,param_fingerprint,payload,superseded_by_uid,decision_target_uid)
            |VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()).apply {
            setString(1, e.uid); setString(2, e.projectId); setInt(3, e.seq); setString(4, e.kind.name)
            if (e.atMs == null) setNull(5, Types.INTEGER) else setLong(5, e.atMs)
            setString(6, e.subjectUid); setString(7, e.subjectKind)
            if (e.generationSeq == null) setNull(8, Types.INTEGER) else setInt(8, e.generationSeq)
            setString(9, e.basisUids.toStrList()); setString(10, e.sourceUids.toStrList())
            setString(11, e.algorithmVersion); setString(12, e.paramFingerprint)
            setString(13, JSON.encodeToString(JsonObject.serializer(), e.payload))
            setString(14, e.supersededByUid); setString(15, e.decisionTargetUid)
        }

    fun events(projectId: String): List<EventRecord> = db.tx { c ->
        c.prepareStatement("SELECT * FROM derived_events WHERE project_id=? ORDER BY seq").apply { setString(1, projectId) }
            .executeQuery().readAll { readEvent() }
    }

    fun markSuperseded(uidsToNew: Map<String, String>) = db.tx { c ->
        uidsToNew.forEach { (old, new) ->
            c.prepareStatement("UPDATE derived_events SET superseded_by_uid=? WHERE uid=?").apply {
                setString(1, new); setString(2, old)
            }.executeUpdate()
        }
        Unit
    }

    private fun ResultSet.readEvent() = EventRecord(
        uid = getString("uid"), projectId = getString("project_id"), seq = getInt("seq"),
        kind = EventKind.valueOf(getString("kind")), atMs = getObject("at_ms") as? Long,
        subjectUid = getString("subject_uid"), subjectKind = getString("subject_kind"),
        generationSeq = getObject("generation_seq") as? Int,
        basisUids = strList(getString("basis_uids")), sourceUids = strList(getString("source_uids")),
        algorithmVersion = getString("algorithm_version"), paramFingerprint = getString("param_fingerprint"),
        payload = JSON.parseToJsonElement(getString("payload")).jsonObject,
        supersededByUid = getString("superseded_by_uid"), decisionTargetUid = getString("decision_target_uid")
    )

    fun insertDecision(d: DecisionRecord) = db.tx { c -> insertDecisionStatement(c, d).executeUpdate(); Unit }
    private fun insertDecisionStatement(c: java.sql.Connection, d: DecisionRecord) =
        c.prepareStatement("""INSERT INTO decisions(uid,project_id,seq,target_uid,action,rationale,param_set_version,
            |undone_by_uid,conflicts_with_uid,conflict_resolution,payload)
            |VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()).apply {
            setString(1, d.uid); setString(2, d.projectId); setInt(3, d.seq); setString(4, d.targetUid)
            setString(5, d.action); setString(6, d.rationale); setString(7, d.paramSetVersion)
            setString(8, d.undoneByUid); setString(9, d.conflictsWithUid); setString(10, d.conflictResolution)
            setString(11, JSON.encodeToString(JsonObject.serializer(), d.payload))
        }

    fun markUndone(uid: String, undoUid: String) = db.tx { c ->
        c.prepareStatement("UPDATE decisions SET undone_by_uid=? WHERE uid=?").apply {
            setString(1, undoUid); setString(2, uid)
        }.executeUpdate(); Unit
    }

    fun decisions(projectId: String): List<DecisionRecord> = db.tx { c ->
        c.prepareStatement("SELECT * FROM decisions WHERE project_id=? ORDER BY seq").apply { setString(1, projectId) }
            .executeQuery().readAll {
                DecisionRecord(getString("uid"), getString("project_id"), getInt("seq"), getString("target_uid"),
                    getString("action"), getString("rationale"), getString("param_set_version"),
                    getString("undone_by_uid"), getString("conflicts_with_uid"), getString("conflict_resolution"),
                    JSON.parseToJsonElement(getString("payload")).jsonObject)
            }

    fun insertArchive(a: ArchiveRun) = db.tx { c -> insertArchiveStatement(c, a).executeUpdate(); Unit }
    private fun insertArchiveStatement(c: java.sql.Connection, a: ArchiveRun) =
        c.prepareStatement("""INSERT INTO archive_runs(uid,project_id,seq,scenario,mutation,direct_source_uid,
            |baseline_fingerprint,mutated_fingerprint,affected_uids,unaffected_uids,expected_scope_uids,scope_correct,
            |accepted,rationale,basis_event_uids) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()).apply {
            setString(1, a.uid); setString(2, a.projectId); setInt(3, a.seq); setString(4, a.scenario)
            setString(5, a.mutation); setString(6, a.directSourceUid); setString(7, a.baselineFingerprint)
            setString(8, a.mutatedFingerprint); setString(9, a.affectedSubjectUids.toStrList())
            setString(10, a.unaffectedSubjectUids.toStrList()); setString(11, a.expectedScopeUids.toStrList())
            setInt(12, if (a.scopeCorrect) 1 else 0); setInt(13, if (a.accepted) 1 else 0)
            setString(14, a.rationale); setString(15, a.basisEventUids.toStrList())
        }

    fun archives(projectId: String): List<ArchiveRun> = db.tx { c ->
        c.prepareStatement("SELECT * FROM archive_runs WHERE project_id=? ORDER BY seq").apply { setString(1, projectId) }
            .executeQuery().readAll {
                ArchiveRun(getString("uid"), getString("project_id"), getInt("seq"), getString("scenario"),
                    getString("mutation"), getString("direct_source_uid"), getString("baseline_fingerprint"),
                    getString("mutated_fingerprint"), strList(getString("affected_uids")),
                    strList(getString("unaffected_uids")), strList(getString("expected_scope_uids")),
                    getInt("scope_correct") == 1, getInt("accepted") == 1, getString("rationale"),
                    strList(getString("basis_event_uids")))
            }

    fun counters(): List<Triple<String, String, Int>> = db.tx { c ->
        c.createStatement().executeQuery("SELECT project_id,name,value FROM counters").readAll {
            Triple(getString(1), getString(2), getInt(3))
        }
    }

    private fun <T> ResultSet.readAll(mapper: ResultSet.() -> T): List<T> {
        val out = mutableListOf<T>()
        while (next()) out += mapper()
        close()
        return out
    }
}
