# ops-agent: feature testing guide

Scope: all tests in this repository as of 2026-11-18 (26 default tests: 11 unit with surefire, 15 integration with
failsafe on Testcontainers). Last full local run 2026-11-18: 0 failures. Last CI run checked: `ci` run 44296513415
(11 unit + 14 IT, success; the 15th IT was added after it).

Run types:

- **Default build**: `./mvnw -B -ntp verify`. Free, no key, needs Docker. Runs in CI.
- **Opt-in, paid**: `LiveTriageIT`, tag `openai`. Needs `OPENAI_API_KEY` (environment only). Never in CI.

## Index

| ID | Feature | Tests | Run type |
|---|---|---|---|
| F1 | Alertmanager contract | `AlertContractTest` | default |
| F2 | Read-only tool registry | `ToolRegistryTest` | default |
| F3 | Triage run, audit, guardrails, email | `GuardrailIT` | default |
| F4 | Webhook intake | `GuardrailIT` (intake, malformedJson) | default |
| F5 | Run recovery after restart (D1.1.7) | `RunRecoveryIT` | default |
| F6 | The CI race in `liveRunIsNotStolen` | `RunRecoveryIT` | default |
| F7 | Live triage | `LiveTriageIT` | opt-in, paid |

## F1 Alertmanager contract

**Proves.** Every fixture, including `recorded-*.json` captured from a real Alertmanager, parses and validates; the
firing fixture carries what triage needs (name, owner, fingerprint, start, runbook, expression); refusals name the
field and never echo the value.
**Run.** `./mvnw -B -ntp verify`, or `-Dtest=AlertContractTest`.
**Expected.** `Tests run: 6, Failures: 0` for the class.
**Failure modes.** A recorded fixture no longer validates after an Alertmanager upgrade: refresh with
`deploy/record-webhooks.sh`. A refusal that quotes a value is a leak: fix `AlertValidation`.
**Talking points.** Contract tests use recorded real payloads, not only hand-written ones, so the schema is what the
sender really sends.

## F2 Read-only tool registry

**Proves.** Exactly four tools exist; no name is a write verb; the tools' only HTTP client has one public method
(`get`); the tools hold no other client; the role map names only real tools and an unknown name stops startup.
**Run.** `-Dtest=ToolRegistryTest`. **Expected.** 5 tests pass.
**Failure modes.** Adding a tool or a client field fails the test on purpose; that is the release block. Change the
test only after a reviewed decision.
**Talking points.** Read-only is enforced by structure and tested by reflection, not by trusting the prompt. Prompt
injection is signalled, and there is no tool that could act on it.

## F3 Triage run, audit, guardrails, email

**Proves.** With a scripted model, fixture Prometheus, readiness and logs, and real Postgres and Mailpit: a run makes
tool calls, writes one append-only `tool_audit` row per call holding the argument shape (`string(23)`) and no values,
and ends in exactly one email. Each limit (steps, tokens, tool calls, deadline) stops the run and the email names it.
Tool arguments are bounded and refused with a reason. An instruction inside tool output is treated as data.
**Run.** `-Dit.test=GuardrailIT` (Docker). **Expected.** `Tests run: 9, Failures: 0` (includes the intake tests).
**Failure modes.** Docker not running: Testcontainers fails at startup.
**Talking points.** The model is scripted, so guardrail tests are deterministic and cost nothing; the one live run is
separate evidence (F7).

## F4 Webhook intake

**Proves.** Wrong or missing token gives 401; a body that parses but fails validation gives 400 naming fields; a body
that is not JSON gives 400 `{"code":"INVALID_ALERT","problems":["body is not valid Alertmanager JSON"]}` with nothing
from the input and no parser text; a repeat of the same incident is `DUPLICATE`; resolved alerts are `RESOLVED_IGNORED`.
**Run.** `-Dit.test=GuardrailIT`. **Expected.** `intake` and `malformedJson` pass.
**Failure modes.** Before the fix, a malformed body got Spring Boot's default error body (a different shape; parser
messages can quote input). `AlertController` now handles `HttpMessageNotReadableException`.
**Known limit.** Body parsing happens before the token check, so an unauthenticated malformed body gets 400, not 401.
It leaks nothing, but the token check should move earlier if the endpoint is ever exposed beyond Alertmanager.
**Talking points.** Error bodies are part of the security surface: fixed shape, field names only, never values.

## F5 Run recovery after restart (D1.1.7, ADR-0001)

**Proves.** Six scenarios on real Postgres with a controllable clock and a scripted model:

1. Crash before any model call: the stale run is resubmitted once, runs, and mails once.
2. Unknown outcome (a model call or email may have happened): not redone; `FAILED/OUTCOME_UNKNOWN`, flagged, owner
   notice sent.
3. A run that keeps dying before its model call is retried up to max attempts (3), then closed with a notice.
4. Dedupe: a re-delivered alert during a live run, a dead run, or concurrently makes no second run.
5. Two instances sweeping at once: each stale run is claimed by exactly one (`FOR UPDATE SKIP LOCKED`).
6. A live run's heartbeat keeps sweepers off it (F6).

**Run.** `-Dit.test=RunRecoveryIT` (Docker). **Expected.** `Tests run: 6, Failures: 0` (about 15 to 25 s).
**Failure modes.** A duplicate email or a second attempt means the claim is not atomic. A run stuck `RUNNING` means the
heartbeat or sweep interval is wrong.
**Talking points.** Why not redo an unknown outcome: a second model call or email is a visible side effect, so the safe
choice is to flag it and tell the owner. Why Postgres: the row is the lock, with no extra coordinator.

## F6 The CI race in `liveRunIsNotStolen`

**What happened.** `ci` run 44284561001 failed once while the local run passed. The test advanced the fake clock as
soon as the run was `RUNNING`. The guard checks the run deadline before each model call, so on a slow runner the clock
jumped before the first call and the run was stopped by the deadline instead of finishing `DONE`.
**Fix** (run 44296513415, green). The test now waits until `model.calls() >= 1` (the slow call is in flight), then
advances the clock, heartbeats and sweeps. Test-only; no production change.
**Talking points.** I read the failing log to decide whether the race was in the test or the code, fixed the test, and
kept every assertion (0 claimed, attempts 1, one model call, one mail).

## F7 Live triage (opt-in, paid)

**Proves.** One real model run end to end. Evidence: `docs/evidence/live-triage.md`.
**Run.** `OPENAI_API_KEY=... ./mvnw -B -ntp verify -Dit.excludedGroups= -Dit.test=LiveTriageIT`.
**Failure modes.** No key: the application does not start. Model output varies, so assertions are on structure.
**Talking points.** Deterministic tests carry the guarantees; the live run only shows the real model fits them.
