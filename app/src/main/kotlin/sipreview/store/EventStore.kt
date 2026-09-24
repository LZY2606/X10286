package sipreview.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sipreview.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class EventStore private constructor(private val dbPath: Path, val conn: Connection) {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val projectId: String get() = meta("project_id")!!
    val seed: Long get() = meta("seed")!!.toLong()

    private fun meta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    private fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
            .use { it.setString(1, key); it.setString(2, value); it.execute() }
    }

    // -- append ---------------------------------------------------------------

    fun appendCapture(input: CaptureInput, diagnostics: List<String>): EventEnvelope {
        require(input.clientId.isNotBlank()) { "clientId must not be blank" }
        val existing = conn.prepareStatement("SELECT 1 FROM events WHERE kind='capture' AND subject=?").use { ps ->
            ps.setString(1, input.clientId)
            ps.executeQuery().use { it.next() }
        }
        require(!existing) { "capture clientId already submitted (immutable; submit a new revision): ${input.clientId}" }
        val event = CaptureEvent("cap:${input.clientId}:r${input.rev}", input, diagnostics)
        return insert("capture", json.encodeToString(event), subject = input.clientId) { it.copy(capture = event) }
    }

    fun appendParamRevision(paramSetId: String, parentId: String?, values: TimerParams, note: String, vclock: Long? = null): EventEnvelope {
        val event = ParamRevisionEvent("placeholder", paramSetId, parentId, values, note)
        return insert("param", json.encodeToString(event), forcedVclock = vclock, subject = paramSetId) { env ->
            val fixed = event.copy(eventId = "params:${env.seq}")
            env.copy(paramRevision = fixed)
        }
    }

    fun appendDecision(
        targetKind: String, targetId: String, verdict: String, rationale: String,
        supersedes: String? = null, adjustments: Map<String, String> = emptyMap(), vclock: Long? = null,
    ): EventEnvelope {
        val event = DecisionEvent("placeholder", "placeholder", targetKind, targetId, verdict, rationale, supersedes, adjustments)
        return insert("decision", json.encodeToString(event), forcedVclock = vclock, subject = targetId) { env ->
            val fixed = event.copy(eventId = "dec:${env.seq}", decisionId = "dec:${env.seq}")
            env.copy(decision = fixed)
        }
    }

    fun appendNote(targetKind: String, targetId: String, text: String, vclock: Long? = null): EventEnvelope {
        val event = NoteEvent("placeholder", targetKind, targetId, text)
        return insert("note", json.encodeToString(event), forcedVclock = vclock, subject = targetId) { env ->
            env.copy(note = event.copy(eventId = "note:${env.seq}"))
        }
    }

    private fun insert(kind: String, payload: String, forcedVclock: Long? = null, subject: String = "", fix: (EventEnvelope) -> EventEnvelope): EventEnvelope {
        val seq = conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM events").use {
            it.executeQuery().use { rs -> rs.getLong(1) }
        }
        val tentative = EventEnvelope(
            seq = seq,
            eventId = "pending",
            recordedAt = Instant.now().toString(),
            vclockMs = forcedVclock ?: nextVclock(),
            kind = kind,
        )
        val fixed = fix(tentative)
        val canonical = json.encodeToString(fixed)
        val eventId = "ev-" + FingerprintLike.of("$seed|$seq|$kind|$canonical")
        val withId = copyWithEventId(fixed, eventId)
        conn.prepareStatement("INSERT INTO events(seq,event_id,kind,subject,recorded_at,vclock_ms,payload) VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setLong(1, seq)
            ps.setString(2, eventId)
            ps.setString(3, kind)
            ps.setString(4, subject)
            ps.setString(5, withId.recordedAt)
            ps.setLong(6, withId.vclockMs)
            ps.setString(7, json.encodeToString(withId))
            ps.execute()
        }
        conn.commit()
        return withId
    }

    private fun copyWithEventId(env: EventEnvelope, id: String): EventEnvelope {
        return env.copy(
            eventId = id,
            capture = env.capture?.copy(eventId = id),
            paramRevision = env.paramRevision?.copy(eventId = id),
            decision = env.decision?.copy(eventId = id),
            note = env.note?.copy(eventId = id),
        )
    }

    private fun nextVclock(): Long =
        conn.prepareStatement("SELECT COALESCE(MAX(vclock_ms),0)+1 FROM events").use {
            it.executeQuery().use { rs -> rs.getLong(1) }
        }

    // -- read -----------------------------------------------------------------

    fun events(): List<EventEnvelope> =
        conn.prepareStatement("SELECT payload FROM events ORDER BY seq ASC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(json.decodeFromString<EventEnvelope>(rs.getString(1)))
                }
            }
        }

    fun eventsAsOf(seq: Long): List<EventEnvelope> =
        conn.prepareStatement("SELECT payload FROM events WHERE seq<=? ORDER BY seq ASC").use { ps ->
            ps.setLong(1, seq)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(json.decodeFromString<EventEnvelope>(rs.getString(1)))
                }
            }
        }

    fun addInvalidInput(view: InvalidInputView) {
        conn.prepareStatement(
            "INSERT INTO invalid_inputs(client_id, raw_b64, raw_len, received_raw, errors_json) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(client_id) DO NOTHING"
        ).use { ps ->
            ps.setString(1, view.clientId)
            ps.setString(2, view.rawBytesB64)
            ps.setInt(3, view.rawByteLength)
            ps.setString(4, view.receivedRaw)
            ps.setString(5, json.encodeToString(view.errors))
            ps.execute()
        }
        conn.commit()
    }

    fun invalidInputs(): List<InvalidInputView> =
        conn.prepareStatement("SELECT client_id, raw_b64, raw_len, received_raw, errors_json FROM invalid_inputs ORDER BY rowid")
            .use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                InvalidInputView(
                                    clientId = rs.getString(1),
                                    rawByteLength = rs.getInt(3),
                                    rawBytesB64 = rs.getString(2),
                                    receivedRaw = rs.getString(4),
                                    errors = json.decodeFromString(rs.getString(5)),
                                )
                            )
                        }
                    }
                }
            }

    fun export(): ExportBundle = ExportBundle(
        projectId = projectId,
        exportedAt = Instant.now().toString(),
        seed = seed,
        events = events(),
        invalidInputs = invalidInputs(),
    )

    fun close() = conn.close()

    companion object {
        fun open(dbPath: Path, seed: Long = SeededRandom.DEFAULT_SEED): EventStore {
            Files.createDirectories(dbPath.toAbsolutePath().parent)
            val fresh = Files.notExists(dbPath)
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
            conn.autoCommit = false
            schema(conn)
            val store = EventStore(dbPath, conn)
            if (fresh) {
                store.setMeta("project_id", "proj-" + FingerprintLike.of("project|$seed|${dbPath.fileName}|${Instant.now()}").take(10))
                store.setMeta("seed", seed.toString())
                conn.commit()
            }
            return store
        }

        fun importInto(dbPath: Path, bundle: ExportBundle): EventStore {
            require(Files.notExists(dbPath) || Files.size(dbPath) == 0L) { "import target must be an empty/new database: $dbPath"
            }
            Files.createDirectories(dbPath.toAbsolutePath().parent)
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
            conn.autoCommit = false
            schema(conn)
            val store = EventStore(dbPath, conn)
            store.setMeta("project_id", bundle.projectId)
            store.setMeta("seed", bundle.seed.toString())
            for (env in bundle.events.sortedBy { it.seq }) {
                val subject = when {
                    env.capture != null -> env.capture.capture.clientId
                    env.paramRevision != null -> env.paramRevision.paramSetId
                    env.decision != null -> env.decision.targetId
                    env.note != null -> env.note.targetId
                    else -> ""
                }
                conn.prepareStatement("INSERT INTO events(seq,event_id,kind,subject,recorded_at,vclock_ms,payload) VALUES(?,?,?,?,?,?,?)").use { ps ->
                    ps.setLong(1, env.seq)
                    ps.setString(2, env.eventId)
                    ps.setString(3, env.kind)
                    ps.setString(4, subject)
                    ps.setString(5, env.recordedAt)
                    ps.setLong(6, env.vclockMs)
                    ps.setString(7, store.json.encodeToString(env))
                    ps.execute()
                }
            }
            for (bad in bundle.invalidInputs) {
                store.addInvalidInput(bad)
            }
            conn.commit()
            return store
        }

        private fun schema(conn: Connection) {
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS events(
                        seq INTEGER PRIMARY KEY,
                        event_id TEXT UNIQUE NOT NULL,
                        kind TEXT NOT NULL,
                        subject TEXT NOT NULL DEFAULT '',
                        recorded_at TEXT NOT NULL,
                        vclock_ms INTEGER NOT NULL,
                        payload TEXT NOT NULL)"""
                )
                st.executeUpdate("CREATE TABLE IF NOT EXISTS invalid_inputs(client_id TEXT PRIMARY KEY, raw_b64 TEXT, raw_len INTEGER, received_raw TEXT, errors_json TEXT)")
            }
            conn.commit()
        }
    }
}

private object FingerprintLike {
    fun of(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

object SeededRandom {
    const val DEFAULT_SEED: Long = 20260924L
}
