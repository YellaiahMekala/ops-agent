-- Run recovery after restart (ADR-0001 in docs/adr). A run is RECEIVED when accepted, RUNNING once a worker holds it,
-- and terminal (DONE/STOPPED/FAILED) at the end. The instance that holds it renews heartbeat_at; a run whose heartbeat
-- is stale belonged to a process that died, and a sweeper on any instance claims it with one conditional UPDATE.
--
-- side_effect is the furthest external call the run has begun: NONE, MODEL (a model call may have been made) or MAIL
-- (the triage email may have gone). It is written before the call, so a crash leaves it pessimistic, never optimistic.
-- A stale run at NONE is resubmitted; anything else ends FAILED with outcome_unknown and an operator notice.
--
-- alert_payload is the validated alert, kept only while the run is not terminal (a resubmission needs it) and cleared
-- at the end, so findings still exist only in the email.

ALTER TABLE triage_run DROP CONSTRAINT ck_triage_status;
ALTER TABLE triage_run ADD CONSTRAINT ck_triage_status
    CHECK (status IN ('RECEIVED', 'RUNNING', 'DONE', 'STOPPED', 'FAILED'));

ALTER TABLE triage_run
    ADD COLUMN lease_owner     VARCHAR(64),
    ADD COLUMN heartbeat_at    TIMESTAMPTZ,
    ADD COLUMN attempts        INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN side_effect     VARCHAR(8)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN outcome_unknown BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN notice_sent     BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN alert_payload   JSONB,
    ADD CONSTRAINT ck_triage_side_effect CHECK (side_effect IN ('NONE', 'MODEL', 'MAIL'));

-- Rows from before this migration have no owner and no payload: anything left RUNNING then cannot be resubmitted.
-- They are closed as unknown, which is what they are.
UPDATE triage_run SET status = 'FAILED', stop_reason = 'OUTCOME_UNKNOWN', outcome_unknown = TRUE,
                      finished_at = created_at
 WHERE status = 'RUNNING';

-- The sweeper's scan: only live runs, oldest heartbeat first.
CREATE INDEX idx_triage_run_live ON triage_run (heartbeat_at) WHERE status IN ('RECEIVED', 'RUNNING');
