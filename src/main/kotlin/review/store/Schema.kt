package review.store

object Schema {
    const val VERSION = 1
    const val DDL = """
    CREATE TABLE IF NOT EXISTS meta(
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS submissions(
        id TEXT PRIMARY KEY,
        seq INTEGER NOT NULL,
        parent_submission_id TEXT,
        submitted_at TEXT NOT NULL,
        payload_sha256 TEXT NOT NULL,
        payload_text TEXT NOT NULL,
        kind TEXT NOT NULL,
        note TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS events(
        id TEXT PRIMARY KEY,
        seq INTEGER NOT NULL UNIQUE,
        at TEXT NOT NULL,
        type TEXT NOT NULL,
        submission_id TEXT,
        basis_submission_ids TEXT NOT NULL,
        params_fingerprint TEXT,
        algorithm_version TEXT,
        payload TEXT NOT NULL,
        supersedes_event_id TEXT,
        decision TEXT,
        target_type TEXT,
        target_id TEXT,
        reason TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS messages(
        id TEXT PRIMARY KEY,
        seq INTEGER NOT NULL,
        direction TEXT NOT NULL,
        transport TEXT NOT NULL,
        connection_id TEXT NOT NULL,
        connection_generation INTEGER NOT NULL,
        received_at_json TEXT NOT NULL,
        raw_bytes TEXT NOT NULL,
        raw_sha256 TEXT NOT NULL,
        source_event_id TEXT NOT NULL,
        parse_diagnostics_json TEXT NOT NULL,
        record_json TEXT NOT NULL,
        introduced_by_submission TEXT NOT NULL,
        superseded_by_submission TEXT
    );
    CREATE TABLE IF NOT EXISTS connections(
        id TEXT PRIMARY KEY,
        record_json TEXT NOT NULL,
        introduced_by_submission TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS params_revisions(
        event_id TEXT PRIMARY KEY,
        params_json TEXT NOT NULL,
        fingerprint TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS derived_snapshots(
        id TEXT PRIMARY KEY,
        event_id TEXT NOT NULL,
        basis_event_id TEXT NOT NULL,
        stale INTEGER NOT NULL,
        transactions_json TEXT NOT NULL,
        dialogs_json NOT NULL,
        candidates_json TEXT NOT NULL,
        timers_json TEXT NOT NULL,
        retransmits_json TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS decision_log(
        event_id TEXT PRIMARY KEY,
        target_type TEXT NOT NULL,
        target_id TEXT NOT NULL,
        decision TEXT NOT NULL,
        reason TEXT NOT NULL,
        revoked_event_id TEXT
    );
    """
}
