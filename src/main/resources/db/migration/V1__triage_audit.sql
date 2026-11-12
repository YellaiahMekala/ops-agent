-- ops-agent's own records. Identifiers, counts and outcomes only: no alert text, no model text, no tool arguments or
-- results. The email is the only place findings exist.

CREATE TABLE triage_run (
    id            UUID         PRIMARY KEY,
    alert_name    VARCHAR(128) NOT NULL,
    fingerprint   VARCHAR(32)  NOT NULL,
    starts_at     TIMESTAMPTZ  NOT NULL,
    owner         VARCHAR(128) NOT NULL,
    caller        VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    stop_reason   VARCHAR(32),
    steps         INTEGER      NOT NULL DEFAULT 0,
    input_tokens  INTEGER      NOT NULL DEFAULT 0,
    output_tokens INTEGER      NOT NULL DEFAULT 0,
    tool_calls    INTEGER      NOT NULL DEFAULT 0,
    mailed        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ,
    -- Alertmanager re-sends a firing alert every repeat_interval; one incident is one run.
    CONSTRAINT uq_triage_incident UNIQUE (fingerprint, starts_at),
    CONSTRAINT ck_triage_status CHECK (status IN ('RUNNING', 'DONE', 'STOPPED', 'FAILED'))
);

CREATE TABLE tool_audit (
    id             UUID         PRIMARY KEY,
    run_id         UUID         NOT NULL REFERENCES triage_run (id),
    tool           VARCHAR(64)  NOT NULL,
    argument_shape JSONB        NOT NULL,
    result_chars   INTEGER      NOT NULL,
    outcome        VARCHAR(16)  NOT NULL,
    caller         VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_tool_outcome CHECK (outcome IN ('OK', 'DENIED', 'LIMIT', 'ERROR'))
);

CREATE INDEX idx_tool_audit_run ON tool_audit (run_id);

-- Append-only, as the gateway's ledger: audit rows are evidence.
CREATE RULE tool_audit_no_update AS ON UPDATE TO tool_audit DO INSTEAD NOTHING;
CREATE RULE tool_audit_no_delete AS ON DELETE TO tool_audit DO INSTEAD NOTHING;
