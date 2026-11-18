# PLATFORM_FACTS: ops-agent

Taken from the code at commit `588f29e` on 2026-11-13. Each fact names the file it came from. **UNVERIFIED** means the
code does not show it. **NOT IN THIS REPO** means nothing in `src/`, `deploy/`, `pom.xml` or CI implements it.

**UPDATE 2026-11-18 (ADR-0002, `docs/adr/0002-jwt-and-tenant.md`): JWT resource server, roles and a tenant model now
exist; sections 1 to 3 and 6 below were updated, the rest of this file is as of commit `588f29e`. The paragraph
below is historical for JWT and tenants.**

Scope: this repository has one business endpoint, `POST /alerts` (plus, since ADR-0002, read-only `GET /api/v1/runs`).
It has **no** `/api/v1/chat`, `/chat/stream`, Idempotency-Key, consumer registration, budgets, task classes, stub-private mode, sensitivity routing,
Kafka, Bedrock or AWS code. A search of `src/` for `api/v1/chat|Idempotency|jwt|tenant|kafka|bedrock|aws` finds only
two places, both test data: a PromQL label string in `src/test/java/.../GuardrailIT.java:259` and the ai-gateway
runbook text in `src/test/resources/fixtures/RUNBOOK.md`. Those features belong to the ai-gateway repo (`../ai-gateway`,
referenced in `README.md` and `deploy/compose.yaml`), which was not read for this file.

## 1. Endpoints

| Method | Path | Auth / roles | Source |
|---|---|---|---|
| POST | `/alerts` | JWT with role `OPS_ALERT_SENDER`, or (while `opsagent.webhook.legacy-enabled`, default true) the shared token `Authorization: Bearer <OPSAGENT_WEBHOOK_TOKEN>`, constant-time compare | `security/SecurityConfig.java`, `security/LegacyWebhookToken.java`, `alert/AlertController.java` |
| GET | `/api/v1/runs?limit=` (1-100, default 20) | JWT with role `OPS_RUN_VIEWER`; the legacy token is not accepted | `runs/RunController.java` |
| GET | `/api/v1/runs/{id}` | same; run plus its `tool_audit` rows (tool, outcome, result size, argument shape) | `runs/RunController.java` |
| GET | `/actuator/health` (plus Boot's sub-paths) | No auth | `security/SecurityConfig.java` |

Anything else is denied (`anyRequest().denyAll()`). Source of ADR-0002: `docs/adr/0002-jwt-and-tenant.md`.

### POST /alerts request (Alertmanager webhook v4)
Source: `alert/AlertmanagerWebhook.java` and `alert/AlertValidation.java`. Unknown JSON fields are ignored (`@JsonIgnoreProperties`).

| Field | Type | Required | Limits |
|---|---|---|---|
| `version` | string | yes | must equal `"4"` |
| `groupKey`, `status`, `receiver`, `externalURL` | string | no | not validated |
| `groupLabels`, `commonLabels`, `commonAnnotations` | map<string,string> | no | not validated |
| `alerts` | array | yes | 1 to `opsagent.webhook.max-alerts` (default 20, config bounds 1–100) |
| `alerts[].labels` | map | yes | at most 32 entries. Key `[A-Za-z_][A-Za-z0-9_]{0,127}`. Value `[A-Za-z0-9._:/@=+-]{0,128}` |
| `alerts[].labels.alertname` | string | yes | `[A-Za-z_][A-Za-z0-9_]{0,127}` |
| `alerts[].labels.owner` | string | no | used as the mail recipient if it matches `[a-z0-9][a-z0-9-]{0,62}`, else falls back (`triage/TriageMailer.java:26,39-45`) |
| `alerts[].status` | string | yes | `firing` or `resolved` |
| `alerts[].fingerprint` | string | yes | `[0-9a-f]{1,32}` |
| `alerts[].startsAt` | ISO-8601 instant | yes | none |
| `alerts[].endsAt`, `generatorURL` | instant / string | no | none |
| `alerts[].annotations` | map | no | key matches the name pattern, value at most 512 chars |

### Responses (`AlertController.java:37-45`)
Plain JSON maps. **Not** `application/problem+json`: there is no ProblemDetail code and
`spring.mvc.problemdetails` is not set in `application.yml`.

| Status | Body |
|---|---|
| 202 | `{"intake": ["STARTED" \| "DUPLICATE" \| "RESOLVED_IGNORED" \| "BUSY", ...]}`, one entry per alert, in order (`triage/TriageService.java:42,68-97`) |
| 401 | `{"code":"UNAUTHORIZED"}`: no token, bad JWT (signature, issuer, audience, expiry, missing `tenant_id`), or wrong shared token (`SecurityConfig`) |
| 403 | `{"code":"FORBIDDEN"}`: valid token without the endpoint's role |
| 404 (reads) | `{"code":"NOT_FOUND"}`: no such run **or** a run of another tenant |
| 400 | `{"code":"INVALID_ALERT","problems":["alerts[0].fingerprint: not a fingerprint", ...]}`. Problems name fields, never values |
| 400 (malformed JSON) | `{"code":"INVALID_ALERT","problems":["body is not valid Alertmanager JSON"]}`, no echo of input (`AlertController.unreadable`, tested in `GuardrailIT` and `TenantSecurityIT`) |

Authentication runs in the filter chain before the body is read, so a bad or missing token with a bad body gets 401.

### Idempotency and async behaviour
- No `Idempotency-Key` header. Deduplication uses the DB constraint `UNIQUE (tenant_id, fingerprint, starts_at)` (V3;
  it was `(fingerprint, starts_at)` in V1). A repeat for the same tenant returns `DUPLICATE`; another tenant's identical
  alert is its own run (`TriageService.accept`).
- 202 is returned at once. The run executes on a pool with 2 threads and a queue of `queue-capacity` (default 8).
  When the queue is full, that alert gets `BUSY` and its row is set to `FAILED/BUSY` (`config/AgentWiring.java:87-92`,
  `TriageService.java:90-93`).
- Run states: `RECEIVED`, `RUNNING`, `DONE`, `STOPPED`, `FAILED` (`V2__run_recovery.sql`). `stop_reason` is one of
  `STEPS|TOKENS|DEADLINE` (`agent/GuardedChatModel.java`), `BUSY`, `OUTCOME_UNKNOWN`, `RECOVERY_LIMIT`
  (`triage/RunRecovery.java`), or for a failure the root exception's simple class name, cut to 32 chars
  (`TriageService.java`).
- Recovery after restart (ADR-0001, `docs/adr/0001-run-recovery.md`): rows carry `lease_owner`, `heartbeat_at`,
  `attempts`, `side_effect` (`NONE|MODEL|MAIL`, written before the call), `outcome_unknown`, `notice_sent`, and
  `alert_payload` (set to NULL when the run ends). `RunRecovery` sweeps at startup and every `sweep-every`. A stale
  run at `NONE` is resubmitted (up to `max-attempts`). Otherwise it is closed `FAILED/OUTCOME_UNKNOWN` with a notice
  email to the owner.
- **Polling path (new, ADR-0002).** The result is still delivered by email, and `GET /api/v1/runs/{id}` now shows the
  run's status for the owning tenant. It does not return the findings (they exist only in the email).

### /api/v1/chat and /chat/stream
NOT IN THIS REPO. Their request/response/error shapes, status enum, `Idempotency-Key`, roles, what a Spring AI or
Python client must send, stub-private behaviour and sensitivity behaviour are all **UNVERIFIED** here. Read them from
ai-gateway's code. ops-agent does not call the gateway's chat API; it calls OpenAI directly (`AgentWiring.java:27-29,42-59`).

## 2. Auth (ADR-0002, 2026-11-18)
- JWT resource server, `security/SecurityConfig.java`: RS256 only; issuer `opsagent.security.issuer` (default
  `http://localhost:8190/realms/platform`, the demo platform Keycloak realm); audience `opsagent.security.audience`
  (default `ops-agent`); `exp` required and checked with 30 s skew; claim `tenant_id` must match
  `[a-z0-9][a-z0-9-]{0,62}`. Keys: `opsagent.security.jwk-set-uri` (Keycloak certs endpoint) or, for tests,
  `public-key-location`. Neither set: legacy-only, every JWT refused. Both set: startup fails.
- Roles: the top-level `roles` claim becomes `ROLE_<name>`. Used: `OPS_ALERT_SENDER`, `OPS_RUN_VIEWER`.
- **Not in the platform realm yet.** `platform/keycloak/realm-platform.json` has no audience `ops-agent`, no roles
  `OPS_*` and no ops-agent client. Verified only with locally minted tokens (`TestJwt`, throwaway keys). The JWKS fetch
  from a real Keycloak is **UNVERIFIED**.
- Legacy shared token: `opsagent.webhook.token` = `${OPSAGENT_WEBHOOK_TOKEN:}`, honoured only on `POST /alerts` and
  only while `opsagent.webhook.legacy-enabled` (`OPSAGENT_LEGACY_WEBHOOK_ENABLED`, default true). It acts as role
  `OPS_ALERT_SENDER` in tenant `opsagent.webhook.tenant` (default `legacy`). Startup fails if it is enabled with an
  empty token, or if it is off and no JWT key source is set.
- Service-to-service: Alertmanager can use either the shared token (`credentials_file`, `deploy/alertmanager.yml`) or
  `http_config.oauth2`. The OAuth2 setup is **NOT BUILT** in `deploy/`.
- Caller identity for RBAC: runs from any alert sender are recorded under `opsagent.webhook.caller` (default
  `alertmanager`), which is the RBAC key for tools (`Caller.runCaller`). JWT `sub` is not stored.
- RBAC is per tool, configured in `opsagent.rbac.<caller>: [tools]`. An unknown caller gets no tools. An unknown tool
  name stops startup (`tools/ToolPolicy.java`).
- Outbound: OpenAI key `OPENAI_API_KEY`. If it is blank, no `ChatModel` bean is created and startup fails
  (`AgentWiring.java:40-41`).

## 3. Tenant / owner identifier
- **Tenant:** claim `tenant_id` of the validated JWT, nothing else (a header or parameter cannot set it). Stored in
  `triage_run.tenant_id VARCHAR(64) NOT NULL` (`V3__tenant.sql`; old rows are `legacy`). `tool_audit` has no tenant
  column; it is reached through its run.
- Every read endpoint filters `WHERE tenant_id = ?` from the token. Another tenant's run is 404, same as missing.
  Tested: `TenantSecurityIT.tenantIsolation`.
- Owner (separate concept): the alert's `owner` label, `[a-z0-9][a-z0-9-]{0,62}`, else `opsagent.mail.fallback-owner`
  (default `platform-oncall`) (`TriageMailer.java:26,39-45`). Stored as `triage_run.owner VARCHAR(128)` = the
  recipient `<owner>@<owner-domain>`. It is the mail recipient, not a tenant.

## 4. Config flags (`application.yml`, `OpsAgentProperties.java`)

| Property (env) | Default | Effect |
|---|---|---|
| `opsagent.webhook.token` (`OPSAGENT_WEBHOOK_TOKEN`) | empty; required while legacy is enabled | legacy bearer token for `/alerts` |
| `opsagent.webhook.legacy-enabled` (`OPSAGENT_LEGACY_WEBHOOK_ENABLED`) | true | switch for the shared token |
| `opsagent.webhook.tenant` (`OPSAGENT_LEGACY_TENANT`) | `legacy` | tenant of legacy-token runs |
| `opsagent.security.issuer` (`OPSAGENT_JWT_ISSUER`) | `http://localhost:8190/realms/platform` | JWT issuer |
| `opsagent.security.jwk-set-uri` (`OPSAGENT_JWT_JWK_SET_URI`) | empty | JWKS; empty (and no key location) = legacy-only |
| `opsagent.security.public-key-location` (`OPSAGENT_JWT_PUBLIC_KEY_LOCATION`) | empty | PEM public key, tests |
| `opsagent.security.audience` (`OPSAGENT_JWT_AUDIENCE`) / `clock-skew` | `ops-agent` / 30s | required audience, skew |
| `opsagent.webhook.caller` | `alertmanager` | identity and RBAC key of webhook runs |
| `opsagent.webhook.max-alerts` | 20 (1–100) | above this, 400 |
| `opsagent.webhook.queue-capacity` | 8 (1–64) | above this, `BUSY` |
| `opsagent.guardrails.max-steps` | 8 (1–50) | model calls per run |
| `opsagent.guardrails.max-tokens` | 24000 (≥100) | provider-reported in+out tokens per run |
| `opsagent.guardrails.max-tool-calls` | 8 (1–50) | after this, tool calls are refused and audited as `LIMIT` |
| `opsagent.guardrails.timeout` | 90s | run deadline |
| `opsagent.model.api-key` (`OPENAI_API_KEY`) | empty, so startup fails | OpenAI key |
| `opsagent.model.name` (`OPSAGENT_MODEL`) | `gpt-4o-mini` | model |
| `opsagent.model.base-url` (`OPSAGENT_MODEL_BASE_URL`) | empty | overrides the OpenAI base URL |
| `opsagent.model.timeout` / `max-output-tokens` | 30s / 600 | per call |
| `opsagent.sources.prometheus-url` (`OPSAGENT_PROMETHEUS_URL`) | `http://localhost:9090` | `queryPrometheus` |
| `opsagent.sources.log-dir` (`OPSAGENT_LOG_DIR`) | `./logs` | `*.log` / `*.jsonl` ECS JSON files |
| `opsagent.sources.runbook` (`OPSAGENT_RUNBOOK`) | `../ai-gateway/docs/RUNBOOK.md` | sections are `## <alertname>` |
| `opsagent.sources.health.gateway` (`OPSAGENT_GATEWAY_READINESS`) | `http://localhost:9464/actuator/health/readiness` | allow-list for `checkHealth` |
| `opsagent.sources.dashboard-url` (`OPSAGENT_DASHBOARD_URL`) | `http://localhost:3000/d/ai-gateway` | link in the email |
| `opsagent.sources.http-timeout` / `max-tool-result-chars` | 5s / 4000 | tool HTTP timeout and result clip |
| `opsagent.recovery.instance-id` (`OPSAGENT_INSTANCE_ID`) | empty: random per process | `lease_owner` value |
| `opsagent.recovery.heartbeat-every` / `stale-after` / `sweep-every` | 10s / 60s / 30s | heartbeat, staleness, sweep period |
| `opsagent.recovery.max-attempts` / `sweep-batch` | 3 (1–10) / 20 (1–500) | total attempts per run; rows claimed per sweep |
| `opsagent.mail.from` / `owner-domain` / `fallback-owner` | `ops-agent@gateway.invalid` / `gateway.invalid` / `platform-oncall` | recipient |
| `opsagent.rbac.alertmanager` | all 4 tools | tool grants |
| `OPSAGENT_DB_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5433/opsagent` / `opsagent` / empty | Postgres, Flyway |
| `OPSAGENT_SMTP_HOST` / `_PORT` | `localhost` / `1025` | SMTP, no auth or TLS configured |
| `OPSAGENT_PORT` | 8095 | HTTP port |
| `OPSAGENT_LOG_FORMAT` | `ecs` | structured console logs |
| `it.excludedGroups` (Maven) | `openai` | skips `LiveTriageIT` (`pom.xml:27,115`) |

## 5. Events / queues / topics
- No broker, topics, keys or message headers. Kafka is NOT IN THIS REPO.
- The only queue is the in-memory `ArrayBlockingQueue` of the triage pool (`AgentWiring.java`). Queued and running
  runs die with the process. Their rows stay `RECEIVED`/`RUNNING` until their heartbeat is stale, and then any
  instance's `RunRecovery` takes them (ADR-0001).
- Recovery notice email: subject `[TRIAGE OUTCOME UNKNOWN] <alertname> owner=<owner>` or
  `[TRIAGE NOT COMPLETED] ...` (`TriageMailer.sendRecoveryNotice`).
- Outbound email (`TriageMailer.java`): subject `[TRIAGE] <alertname> (<severity>) owner=<owner>`. Body sections:
  WHAT FIRED, FINDINGS, LIKELY CAUSE (+ confidence `LOW|MEDIUM|HIGH`, model-provided and unchecked), RUNBOOK (quoted
  from the file), EVIDENCE, RUN (id, status, stop reason, steps, tool calls, tokens).
- Persistence: `triage_run` and `tool_audit`. `tool_audit` outcome is `OK|DENIED|LIMIT|ERROR`. It is append-only by
  Postgres rules that turn UPDATE and DELETE into no-ops (`V1__triage_audit.sql:39-41`).

## 6. Test counts and CI
**Update 2026-11-18 (D1.2.7, local `verify`): surefire 11, failsafe 23 (`GuardrailIT` 9, `RunRecoveryIT` 6,
`TenantSecurityIT` 7, `LegacyDisabledIT` 1). Default `verify` total: 34. Not yet in CI. The older figures below are
historical.**

From the latest report files in `target/` (local run, 2026-11-12):
- Unit (surefire, 17:28): `AlertContractTest` 6, `ToolRegistryTest` 5. **11 pass, 0 failures.**
- Integration (failsafe, 17:29): `GuardrailIT` 8 pass (`failsafe-summary.xml`: completed 8, 0 errors and failures).
- Live (opt-in, 16:08): `LiveTriageIT` 1 pass. Its report is left over from an earlier run and not part of the last
  `verify`. Output is in `docs/evidence/live-triage.md`.
- UI tests: none exist.
- Default `verify` total: **19** (11 + 8).
- Update 2026-11-18 (local `verify`, not yet in CI): `RunRecoveryIT` adds 6 integration tests. Failsafe has 14
  (8 + 6), surefire still 11, so the default `verify` total is **25**.
- CI (`.github/workflows/ci.yml`): `./mvnw -B -ntp verify` then `docker build`. From `gh run list`, the last green run
  is **43332473456** (2026-11-12T23:52Z, commit `588f29e`). The two runs before it failed (43331765873,
  43330928394). Whether CI ran the same 19 tests is **UNVERIFIED**: the CI logs were not read.

## 7. Built / not built / built but not run live

| Item | State | Source |
|---|---|---|
| Webhook, validation, dedupe, BUSY | built, tested | `AlertContractTest`, `GuardrailIT` |
| 4 read-only tools, RBAC, audit shape | built, tested | `ToolRegistryTest`, `GuardrailIT` |
| Step/token/deadline/tool-call guardrails | built, tested with a scripted model | `GuardrailIT`, `ScriptedChatModel` |
| OpenAI live run | run once, 2026-11-12 | `docs/evidence/live-triage.md` |
| SMTP | Mailpit only (Testcontainers / compose). Real SMTP is not configured and has not been run live | `application.yml`, `deploy/compose.yaml`, README |
| Docker image | built in CI | `Dockerfile`, `ci.yml` |
| Compose demo | defined. A live run alongside the gateway is **UNVERIFIED**; the recorded fixtures say it ran | `fixtures/alerts/README.md` |
| AWS (any service), Terraform, EKS/Fargate | NOT BUILT | none |
| Bedrock | NOT BUILT | none |
| JWT resource server, roles, tenant on runs, tenant-scoped reads | built, tested with minted tokens (2026-11-18, local) | `TenantSecurityIT`, `LegacyDisabledIT`; ADR-0002 |
| Keycloak client/roles/audience for ops-agent, Alertmanager OAuth2, real JWKS fetch | NOT BUILT / UNVERIFIED | platform realm has none |
| Kafka, chat API, SSE | NOT BUILT here | none |
| Run recovery after restart | built, tested (2026-11-18, local `verify`) | `RunRecoveryIT`: `staleRunIsResubmittedOnce`, `unknownOutcomeIsNotRedone`, `attemptsAreBounded`, `duplicateDeliveriesMakeOneRun`, `concurrentSweepersDoNotDoubleProcess`, `liveRunIsNotStolen`; ADR-0001 |

## 8. README / docs vs code
1. `README.md` said "`verify` # 17 tests". Fixed on 2026-11-18: it now says 25, which includes `RunRecoveryIT`.
2. `README.md` lists the stop reason `TOOL_CALLS`. No code sets it. A spent tool-call budget makes each further tool
   return a refusal and writes an audit row with `LIMIT` (`OpsTools.java:166-168`). The run ends `DONE` unless
   another limit hits. Stop reasons in code: `STEPS`, `TOKENS`, `DEADLINE`, `BUSY`, or an exception class name.
3. `README.md` says "a full queue answers `BUSY`". It does, but only in the 202 `intake` list, not as a separate HTTP
   status.
4. `CLAUDE.md` says "Mail goes to `<owner>@gateway.invalid`". The domain is configurable (`opsagent.mail.owner-domain`),
   and an invalid owner goes to `platform-oncall`.
5. `TriageService` javadoc says each run ends "in exactly one email". When the send fails, the code sets `mailed=false`
   and makes no retry, so the run has no email (`TriageService.java:146-152`).
6. The `CLAUDE.md` rule "no payload in logs" holds for `TriageService`. But `TriageMailer` puts label values and the
   `summary` annotation in the email by design, and `OpsTools` returns a refusal string that lists the configured
   health service names to the model (`OpsTools.java:130`).
