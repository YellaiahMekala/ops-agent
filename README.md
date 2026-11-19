# ops-agent

An incident-triage agent for the [ai-gateway](../ai-gateway) demonstration stack. Alertmanager posts an alert; the agent
looks at Prometheus, the gateway's logs, its readiness endpoint and the runbook with four **read-only** tools, and emails
the alert's owner a summary. It changes nothing.

Separate repository on purpose (ai-gateway ADR-18): the gateway does not offer tool calling, and this agent's loop is
tool calling. LangChain4j is used here only.

## What it does

```
Alertmanager --webhook (JWT, or legacy token)--> /alerts --> one run per (tenant, fingerprint, startsAt)
                                                      |
                     model (OpenAI, direct) <--> 4 read-only tools --> tool_audit rows
                                                      |
                              email to <owner>@gateway.invalid: findings, likely cause,
                              the runbook section quoted verbatim, link to the evidence
```

- **Tools:** `queryPrometheus`, `searchLogs`, `readRunbook`, `checkHealth`. HTTP is GET only (`ReadOnlyHttp`). Each tool
  is checked against a role map (`ToolPolicy`) and bounded in its arguments. Tool results reach the model wrapped as data.
- **Audit:** one `tool_audit` row per call, append-only: tool, outcome, result size and the argument *shape*
  (`string(23)`), never a value.
- **Guardrails per run:** steps, tokens, tool calls, wall-clock deadline. Whichever is reached first stops the run; the
  email still goes and names the limit (`STEPS`, `TOKENS`, `TOOL_CALLS`, `DEADLINE`).
- **Auth and tenants** ([ADR-0002](docs/adr/0002-jwt-and-tenant.md)): JWT resource server (platform Keycloak realm, RS256,
  issuer, audience `ops-agent`, claim `tenant_id`). Roles `OPS_ALERT_SENDER` (`POST /alerts`) and `OPS_RUN_VIEWER`
  (`GET /api/v1/runs`, `/api/v1/runs/{id}`). Every run has a tenant and every read is scoped to the caller's; another
  tenant's run is 404. The old shared Alertmanager token still works for `POST /alerts` while
  `OPSAGENT_LEGACY_WEBHOOK_ENABLED` is true (default), as tenant `legacy`.
- **Intake:** no or wrong token 401, wrong role 403; malformed body 400 naming fields, never values; a repeat of the same incident
  `DUPLICATE`; resolved alerts ignored; a full queue answers `BUSY`.
- **Recovery after restart** ([ADR-0001](docs/adr/0001-run-recovery.md)): each run is a Postgres row
  (`RECEIVED`, `RUNNING`, then terminal) with the holding instance and a heartbeat. A sweep at startup and every 30s
  claims runs whose heartbeat is over 60s old (`FOR UPDATE SKIP LOCKED`, so two instances never take the same one).
  A run that had made no model call is resubmitted, at most 3 attempts. A run whose model call or email may already
  have happened is not redone: it ends `FAILED/OUTCOME_UNKNOWN`, flagged, and the owner gets a notice email.
- **Model:** OpenAI called directly, `maxRetries(0)`, `store(false)`. With no `OPENAI_API_KEY` the application does not
  start.

## Tests

```
./mvnw -B -ntp verify                                       # 34 tests (11 unit + 23 IT), no key, Docker needed
OPENAI_API_KEY=... ./mvnw -B -ntp verify -Dit.excludedGroups= -Dit.test=LiveTriageIT   # one real run, opt-in
```

- `AlertContractTest` (fixtures, including `recorded-*.json` from a real Alertmanager), `ToolRegistryTest`
  (release-blocking: the registry stays read-only), `GuardrailIT` (Postgres and Mailpit in Testcontainers, a scripted model),
  `RunRecoveryIT` (stale run resubmitted once, unknown outcome not redone, bounded attempts, dedupe, two concurrent
  sweepers, a live run not stolen).
- `GuardrailIT` also covers intake: wrong token 401, malformed JSON 400 `INVALID_ALERT` (no echo of the input).
- `TenantSecurityIT` and `LegacyDisabledIT` (ADR-0002): 401, 403, tenant A cannot read tenant B, legacy token, tenant-scoped
  dedupe. Tokens are minted per run with throwaway in-memory keys. The JWKS fetch from a real Keycloak is not tested.
- `LiveTriageIT` is tagged `openai` and excluded from the default build and CI. Evidence: `docs/evidence/live-triage.md`.

## Test evidence

Last result: 2026-11-18, `./mvnw -B -ntp verify`, 11 unit + 23 IT, 0 failures. Last CI run checked: `ci` run
44296513415 (11 unit + 14 IT, success; later tests are local only until pushed). Detail per feature:
[docs/testing/FEATURE_TESTING_GUIDE.md](docs/testing/FEATURE_TESTING_GUIDE.md).

| Feature | Tests | How to run | Last result | Where |
|---|---|---|---|---|
| Alertmanager contract, recorded fixtures | `AlertContractTest` (6 cases) | `./mvnw -B -ntp verify` | pass, 2026-11-18 | CI + local |
| Read-only tool registry | `ToolRegistryTest` (5) | same | pass, 2026-11-18 | CI + local |
| Triage run, audit, email, guardrails | `GuardrailIT` (9 tests) | same, Docker | pass, 2026-11-18 | CI + local |
| Intake: 401, 400 (fields, malformed JSON), DUPLICATE | `GuardrailIT` intake, malformedJson | same | pass, 2026-11-18 | local (malformedJson pending CI) |
| Run recovery (ADR-0001) | `RunRecoveryIT` (6) | same, Docker | pass, 2026-11-18 | CI + local |
| JWT, roles, tenant isolation, legacy token (ADR-0002) | `TenantSecurityIT` (7), `LegacyDisabledIT` (1) | same, Docker | pass, 2026-11-18 | local (pending CI) |
| JWT against a real Keycloak (clients, audience, roles, tenants) | manual, `deploy/keycloak/import-ops-agent.sh` | see the proof | pass, 11 checks (202, 401 x3, 403 x2, cross-tenant 404), stub model, no paid call, `docs/proof/jwt-keycloak-local-2026-11-19.md` | local only |
| Live model run | `LiveTriageIT` (opt-in, paid) | see Tests above | `docs/evidence/live-triage.md` | local only |
| Live end to end: Alertmanager to triage email, dedupe (PQ1.2.2) | manual, gateway demo + compose | see the proof | pass for ops-agent path, 3 runs, about 0.0025 USD; gateway ledger 0 rows (direct to OpenAI by design), `docs/proof/pq1-2-2-live-e2e-2026-11-18.md` | local only |

## Demo

With the ai-gateway demo running (`demo/up.sh` there):

```
./mvnw -B -ntp package -DskipTests && docker build -t ops-agent:local .
OPSAGENT_WEBHOOK_TOKEN=<random> OPENAI_API_KEY=<key> docker compose -f deploy/compose.yaml up -d
```

Mail is captured by Mailpit at http://127.0.0.1:8026. `deploy/record-webhooks.sh` replays a firing and a resolved alert
through the real Alertmanager to refresh the recorded fixtures.

## Honest scope

- Read-only by construction and by test, not by trust in the model. Prompt injection is *signalled* (tool output is
  quoted as data), not prevented; there is simply no tool that could act on it.
- The logs the demo reads are a fixture file; the gateway demo logs to stdout.
- Email goes to a capture container. Real SMTP is not configured.
- JWT validation is tested with locally minted tokens in the build, and was proven once against a real Keycloak using
  the clients in `deploy/keycloak/ops-agent-partial-import.json` (`docs/proof/jwt-keycloak-local-2026-11-19.md`; stub
  model, local only). The clients are not yet in the platform repo's realm file, and Alertmanager's own oauth2 login
  was not run.
- The agent's answer is a summary to a human, not an evaluated diagnosis.
