package sipreview.db

import sipreview.db.Database

object Schema {
    const val VERSION = 3

    private val TABLES = listOf("""
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )""", """
        CREATE TABLE IF NOT EXISTS projects (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,
            case_pack_id TEXT
        )""", """
        CREATE TABLE IF NOT EXISTS materials (
            uid TEXT PRIMARY KEY,
            project_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            kind TEXT NOT NULL,
            direction TEXT,
            raw_bytes_b64 TEXT NOT NULL,
            raw_text TEXT NOT NULL,
            raw_hash TEXT NOT NULL,
            received_at_ms INTEGER,
            received_at_original TEXT,
            time_unit TEXT,
            connection_id TEXT,
            connection_generation INTEGER,
            parent_uid TEXT,
            revision_reason TEXT,
            payload TEXT NOT NULL,
            committed_at_seq INTEGER NOT NULL,
            FOREIGN KEY(project_id) REFERENCES projects(id)
        )""", """
        CREATE TABLE IF NOT EXISTS derived_events (
            uid TEXT PRIMARY KEY,
            project_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            kind TEXT NOT NULL,
            at_ms INTEGER,
            subject_uid TEXT,
            subject_kind TEXT,
            generation_seq INTEGER,
            basis_uids TEXT NOT NULL,
            source_uids TEXT NOT NULL,
            algorithm_version TEXT NOT NULL,
            param_fingerprint TEXT NOT NULL,
            payload TEXT NOT NULL,
            superseded_by_uid TEXT,
            decision_target_uid TEXT
        )""", """
        CREATE TABLE IF NOT EXISTS decisions (
            uid TEXT PRIMARY KEY,
            project_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            target_uid TEXT NOT NULL,
            action TEXT NOT NULL,
            rationale TEXT NOT NULL,
            param_set_version TEXT,
            undone_by_uid TEXT,
            conflicts_with_uid TEXT,
            conflict_resolution TEXT,
            payload TEXT NOT NULL
        )""", """
        CREATE TABLE IF NOT EXISTS archive_runs (
            uid TEXT PRIMARY KEY,
            project_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            scenario TEXT NOT NULL,
            mutation TEXT NOT NULL,
            direct_source_uid TEXT NOT NULL,
            baseline_fingerprint TEXT NOT NULL,
            mutated_fingerprint TEXT NOT NULL,
            affected_uids TEXT NOT NULL,
            unaffected_uids TEXT NOT NULL,
            expected_scope_uids TEXT NOT NULL,
            scope_correct INTEGER NOT NULL,
            accepted INTEGER NOT NULL,
            rationale TEXT NOT NULL,
            basis_event_uids TEXT NOT NULL
        )""", """
        CREATE TABLE IF NOT EXISTS counters (
            project_id TEXT NOT NULL,
            name TEXT NOT NULL,
            value INTEGER NOT NULL,
            PRIMARY KEY(project_id, name)
        )""", """
        CREATE INDEX IF NOT EXISTS idx_mat_project ON materials(project_id, seq)
        """, """
        CREATE INDEX IF NOT EXISTS idx_evt_project ON derived_events(project_id, seq)
        """, """
        CREATE INDEX IF NOT EXISTS idx_evt_subject ON derived_events(subject_uid)
        """, """
        CREATE INDEX IF NOT EXISTS idx_dec_project ON decisions(project_id, seq)
        """, """
        CREATE INDEX IF NOT EXISTS idx_arc_project ON archive_runs(project_id, seq)
        """)

    fun create(db: Database) = db.tx { c ->
        c.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            TABLES.forEach { s.execute(it) }
            val rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")
            val existing = if (rs.next()) rs.getInt(1) else 0
            rs.close()
            if (existing == 0) {
                s.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','$VERSION')")
            } else if (existing != VERSION) {
                s.execute("UPDATE meta SET value='$VERSION' WHERE key='schema_version'")
            }
        }
        Unit
    }
}
