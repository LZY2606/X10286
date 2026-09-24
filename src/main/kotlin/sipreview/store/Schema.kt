package sipreview.store

object Schema {
    const val VERSION = 1

    val DDL = """
PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS projects (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    created_at_millis INTEGER NOT NULL,
    head_param_id TEXT NOT NULL,
    head_raw_version_id TEXT,
    head_decision_id TEXT
);

CREATE TABLE IF NOT EXISTS batches (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id),
    name TEXT NOT NULL,
    format_version TEXT NOT NULL,
    submitted_at_millis INTEGER NOT NULL,
    item_count INTEGER NOT NULL,
    diagnostics_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS raw_versions (
    id TEXT PRIMARY KEY,
    ordinal INTEGER NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id),
    batch_id TEXT NOT NULL REFERENCES batches(id),
    direction TEXT NOT NULL,
    transport TEXT NOT NULL,
    connection_id TEXT NOT NULL,
    connection_generation INTEGER NOT NULL,
    local_address TEXT NOT NULL,
    remote_address TEXT NOT NULL,
    received_at_millis INTEGER NOT NULL,
    clock_source TEXT NOT NULL,
    raw_bytes BLOB NOT NULL,
    raw_text TEXT NOT NULL,
    label TEXT,
    note TEXT,
    parent_version_id TEXT REFERENCES raw_versions(id),
    superseded INTEGER NOT NULL DEFAULT 0,
    submitted_at_millis INTEGER NOT NULL,
    UNIQUE(project_id, ordinal)
);

CREATE TABLE IF NOT EXISTS parameters (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id),
    name TEXT NOT NULL,
    values_json TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    parent_id TEXT REFERENCES parameters(id),
    created_at_millis INTEGER NOT NULL,
    note TEXT
);

CREATE TABLE IF NOT EXISTS decisions (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id),
    target_id TEXT NOT NULL,
    target_kind TEXT NOT NULL,
    verdict TEXT NOT NULL,
    rationale TEXT NOT NULL,
    conflicts_with TEXT,
    created_at_millis INTEGER NOT NULL,
    superseded_by TEXT
);

CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id),
    started_at_millis INTEGER NOT NULL,
    finished_at_millis INTEGER NOT NULL,
    raw_head_version TEXT,
    param_id TEXT NOT NULL,
    decision_head_id TEXT,
    event_count INTEGER NOT NULL,
    note TEXT
);

CREATE TABLE IF NOT EXISTS derived_rows (
    id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES runs(id),
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    label TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    direct_sources_json TEXT NOT NULL,
    parent_derived_id TEXT,
    algorithm_version TEXT NOT NULL,
    param_fingerprint TEXT NOT NULL,
    created_at_millis INTEGER NOT NULL,
    stale INTEGER NOT NULL DEFAULT 0,
    stale_reason TEXT,
    PRIMARY KEY (id, run_id)
);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES runs(id),
    seq INTEGER NOT NULL,
    at_millis INTEGER NOT NULL,
    type TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    sources_json TEXT NOT NULL,
    decision_ref TEXT
);

CREATE TABLE IF NOT EXISTS archive_entries (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id),
    scenario TEXT NOT NULL,
    title TEXT NOT NULL,
    baseline_id TEXT NOT NULL,
    mutated_source_id TEXT NOT NULL,
    mutation_json TEXT NOT NULL,
    adopted_json TEXT NOT NULL,
    rejected_json TEXT NOT NULL,
    judgement_events_json TEXT NOT NULL,
    blast_radius_json TEXT NOT NULL,
    created_at_millis INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_raw_project ON raw_versions(project_id);
CREATE INDEX IF NOT EXISTS idx_events_run ON events(run_id, seq);
CREATE INDEX IF NOT EXISTS idx_derived_project ON derived_rows(project_id);
CREATE INDEX IF NOT EXISTS idx_decisions_project ON decisions(project_id);
"""
}
