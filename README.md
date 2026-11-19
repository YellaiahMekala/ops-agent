# ops-agent

An incident-triage agent for the [ai-gateway](../ai-gateway) demonstration stack. Alertmanager posts an alert; the agent
looks at Prometheus, the gateway's logs, its readiness endpoint and the runbook with four **read-only** tools, and emails
the alert's owner a summary. It changes nothing.

Separate repository on purpose (ai-gateway ADR-18): this agent's loop is tool calling, and the gateway never runs tools.
LangChain4j is used here only. Since [ADR-0003](docs/adr/0003-gateway-mode.md) the model is reached **through the
gateway** (tool-calling contract v1, `taskClass AGENT_TOOL_STEP`); the tools still execute here.

## What it does

```
Alertmanager --webhook (JWT, or legacy token)--> /alerts --> one run per (tenant, fingerprint, startsAt)
                                                      |
                     model (via ai-gateway) <--> 4 read-only tools --> tool_audit rows
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
- **Model** ([ADR-0003](docs/adr/0003-gateway-mode.md)): `opsagent.model.mode` is `gateway` (default) or `direct`.
  - `gateway`: `GatewayChatModel` posts to `{gateway}/api/v1/chat` with a Keycloak client-credentials token (client
    `ops-agent-gateway`, secret from `OPSAGENT_GATEWAY_CLIENT_SECRET`), `Idempotency-Key <runId>:<step>` and
    `X-Agent-Run-Id`. No provider key, no provider URL, no retries. A gateway 403/400/422 fails the run
    (`GATEWAY_TOOL_NOT_ALLOWED` and so on), 429 `AGENT_RUN_LIMIT` stops it (`STOPPED/GATEWAY_RUN_LIMIT`), a 5xx or
    timeout fails it as `GATEWAY_UNAVAILABLE` and the step is not redone. Without the gateway settings the application
    does not start. Gateway facts: a missing `X-Agent-Run-Id` is a 400 `INVALID_TOOLS`; an idempotent replay returns
    no `toolCalls`, so after a lost response the step is re-run under a new key; errors are plain JSON, not problem+json.
  - `direct`: the old OpenAI client, local development only: `SPRING_PROFILES_ACTIVE=direct OPENAI_API_KEY=...`. It
    bypasses the gateway's identity, budgets, guardrails and ledger. Do not use it in a shared environment.

## Tests

```
./mvnw -B -ntp verify                                       # 52 tests (24 unit + 28 IT), no key, Docker needed
OPENAI_API_KEY=... ./mvnw -B -ntp verify -Dit.excludedGroups= -Dit.test=LiveTriageIT   # one real run, direct mode, opt-in
```

- `AlertContractTest` (fixtures, including `recorded-*.json` from a real Alertmanager), `ToolRegistryTest`
  (release-blocking: the registry stays read-only), `GuardrailIT` (Postgres and Mailpit in Testcontainers, a scripted model),
  `RunRecoveryIT` (stale run resubmitted once, unknown outcome not redone, bounded attempts, dedupe, two concurrent
  sweepers, a live run not stolen).
- `GuardrailIT` also covers intake: wrong token 401, malformed JSON 400 `INVALID_ALERT` (no echo of the input).
- `TenantSecurityIT` and `LegacyDisabledIT` (ADR-0002): 401, 403, tenant A cannot read tenant B, legacy token, tenant-scoped
  dedupe. Tokens are minted per run with throwaway in-memory keys. The JWKS fetch from a real Keycloak is not tested.
- `GatewayChatModelTest` (request and response mapping against the contract, error mapping, token cache),
  `GatewayModeConfigTest` (release-blocking: `application.yml` names no provider URL or key) and `GatewayModeIT`
  (ADR-0003: a fake gateway HTTP server scripts a tool-call round trip, 403, 429 and 503; real Postgres, Mailpit and
  tools; asserts roles, `toolCallId`, `X-Agent-Run-Id`, `Idempotency-Key` steps). The fake is the gateway only. The real
  gateway, a real Keycloak token endpoint and a real model are **not** exercised by these tests.
- `LiveTriageIT` is tagged `openai` and excluded from the default build and CI. Evidence: `docs/evidence/live-triage.md`.

## Test evidence

Last result: 2026-11-19, `./mvnw -B -ntp verify`, 24 unit + 28 IT, 0 failures. Last CI run checked: `ci` run
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
| Gateway mode: tool-call round trip, 403, 429, 5xx, mapping, token cache (ADR-0003) | `GatewayChatModelTest` (10), `GatewayModeConfigTest` (3), `GatewayModeIT` (5) | same, Docker | pass, 2026-11-19, against a fake gateway only | local only (pending CI) |
| Live model run (direct mode) | `LiveTriageIT` (opt-in, paid) | see Tests above | `docs/evidence/live-triage.md` | local only |
| Live end to end: Alertmanager to triage email, dedupe (PQ1.2.2) | manual, gateway demo + compose | see the proof | pass for ops-agent path, 3 runs, about 0.0025 USD; gateway ledger 0 rows (direct to OpenAI, true at that date; replaced by gateway mode, ADR-0003), `docs/proof/pq1-2-2-live-e2e-2026-11-18.md` | local only |

## Demo

With the ai-gateway demo running (`demo/up.sh` there):

```
./mvnw -B -ntp package -DskipTests && docker build -t ops-agent:local .
OPSAGENT_WEBHOOK_TOKEN=<random> OPSAGENT_GATEWAY_TOKEN_URI=<keycloak token endpoint> \
  OPSAGENT_GATEWAY_CLIENT_SECRET=<secret of client ops-agent-gateway> docker compose -f deploy/compose.yaml up -d
```

This needs a gateway build with the tool-calling API, and the `ops-agent-gateway` client in the Keycloak realm with the
four tool names on its allow-list. Both are platform-side and not in this repository. `deploy/k8s/` holds an example
NetworkPolicy (egress only to the gateway, Keycloak, Postgres, Prometheus/Loki, SMTP, DNS); it has not been applied to
a cluster.

Mail is captured by Mailpit at http://127.0.0.1:8026. `deploy/record-webhooks.sh` replays a firing and a resolved alert
through the real Alertmanager to refresh the recorded fixtures.

## Honest scope

- Gateway mode is tested against a fake gateway that plays the contract, not against the real gateway. The first run
  with the real gateway, Keycloak and a model is still to do (a deploy step, and paid if the model is real).
- The NetworkPolicy is an example, unapplied and untested; it only holds on a CNI that enforces NetworkPolicy.
- Read-only by construction and by test, not by trust in the model. Prompt injection is *signalled* (tool output is
  quoted as data), not prevented; there is simply no tool that could act on it.
- The logs the demo reads are a fixture file; the gateway demo logs to stdout.
- Email goes to a capture container. Real SMTP is not configured.
- JWT validation is tested with locally minted tokens in the build, and was proven once against a real Keycloak using
  the clients in `deploy/keycloak/ops-agent-partial-import.json` (`docs/proof/jwt-keycloak-local-2026-11-19.md`; stub
  model, local only). The clients are not yet in the platform repo's realm file, and Alertmanager's own oauth2 login
  was not run.
- The agent's answer is a summary to a human, not an evaluated diagnosis.

## License and AI use

No open-source license is granted: all rights reserved. Use of this repository for AI or ML training, AI-based analysis or reproduction, or inclusion in datasets is not permitted without written permission: see [AI_USE_POLICY.md](AI_USE_POLICY.md).
