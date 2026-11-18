# ADR-0001: Run recovery after restart

Status: accepted, 2026-11-18

## Context

A triage run was a `triage_run` row set to `RUNNING` at intake, then queued on an in-memory pool. If the process died,
queued and running runs were lost and their rows stayed `RUNNING` forever. Nothing told the owner. The run makes two
external calls that cost money or reach a person: the model (OpenAI, paid) and the triage email.

The requirement: a run in progress when the process dies is neither lost nor duplicated, and duplicate Alertmanager
deliveries never create a second run.

## Decision

1. **Store: the existing Postgres, via Flyway (`V2__run_recovery.sql`).** The repository already persists runs there,
   so no new store is added. Columns added to `triage_run`: `lease_owner`, `heartbeat_at`, `attempts`, `side_effect`
   (`NONE|MODEL|MAIL`), `outcome_unknown`, `notice_sent`, `alert_payload` (JSONB). New status `RECEIVED`.
2. **States.** `RECEIVED` at intake, `RUNNING` once a worker takes it with a conditional update
   (`WHERE lease_owner = me AND status = 'RECEIVED'`), then `DONE|STOPPED|FAILED`. Every write after intake is fenced
   on `lease_owner`.
3. **Heartbeat.** Each instance renews `heartbeat_at` for the runs it holds in memory, queued or running, with one
   `UPDATE ... WHERE id = ANY(?)` every `heartbeat-every` (10s).
4. **Side effects are recorded before they happen.** Before each model call (`GuardedChatModel`'s `beforeCall` hook)
   and before the email, the run sets `side_effect` while it still holds the lease. If it no longer holds the lease
   (another instance took it), the call is not made. A crash between the mark and the call leaves the mark
   pessimistic. That costs a notice instead of a rerun; it never causes a duplicate call.
5. **Sweep, at startup and every `sweep-every` (30s), on every instance.** It claims non-terminal runs whose heartbeat
   is older than `stale-after` (60s) in one statement:
   `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) AND heartbeat_at < cutoff RETURNING ...`. Two sweepers
   cannot claim the same row. A claimed row has a fresh heartbeat, so it is no longer stale for anyone else.
   - `side_effect = NONE`: resubmitted on the sweeping instance with `attempts + 1`. Above `max-attempts` (3) the run
     is closed `FAILED/RECOVERY_LIMIT`. This bounds a poison alert that kills the process every time.
   - `MODEL` or `MAIL`: not redone. Closed `FAILED/OUTCOME_UNKNOWN` with `outcome_unknown = true`. A notice email goes
     to the owner and `notice_sent` records whether it was sent.
6. **Dedupe key unchanged:** `UNIQUE (fingerprint, starts_at)` from V1. It holds in every state, so a redelivery
   during a live, dead or finished run is `DUPLICATE`.
7. **Instance id:** `opsagent.recovery.instance-id` (`OPSAGENT_INSTANCE_ID`), else a fresh random id per process. A
   restarted process is a new holder. Its previous life's runs are recovered when their heartbeat goes stale, not
   instantly.

## Consequences

- `alert_payload` stores the validated alert (labels, annotations, at most 512 chars per value) while a run is live,
  because a resubmission needs it. It is set to NULL when the run ends. This is a deliberate exception to the
  "identifiers only" rule for `triage_run`, bounded to live runs.
- A crashed run is recovered after up to `stale-after + sweep-every` (about 90s with the defaults), not at once.
- The notice is sent at most once: only the instance whose conditional close succeeded sends it. If that instance
  dies between the close and the send, there is no notice and `notice_sent = false` shows it.
- A model call is read-only and only costs money. Re-running it would be safe for the systems it reads. It is still
  treated as an unknown outcome, as the requirement asks, to avoid paying twice and mailing twice.
- Migration V2 closes `RUNNING` rows from before V2 as `FAILED/OUTCOME_UNKNOWN`, because they have no payload to
  resubmit.
- Not addressed: a stalled instance (long GC pause) whose lease was taken loses the run at its next fenced write. A
  model call already in flight at that moment completes and is discarded.
