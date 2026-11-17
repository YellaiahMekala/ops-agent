# PLATFORM_FACTS: ops-agent

Taken from the code at commit `588f29e` on 2026-11-13. Each fact names the file it came from. **UNVERIFIED** means the
code does not show it. **NOT IN THIS REPO** means nothing in `src/`, `deploy/`, `pom.xml` or CI implements it.

Scope: this repository has one business endpoint, `POST /alerts`. It has **no** `/api/v1/chat`, `/chat/stream`, JWT,
tenant model, Idempotency-Key, consumer registration, budgets, task classes, stub-private mode, sensitivity routing,
Kafka, Bedrock or AWS code. A search of `src/` for `api/v1/chat|Idempotency|jwt|tenant|kafka|bedrock|aws` finds only
two places, both test data: a PromQL label string in `src/test/java/.../GuardrailIT.java:259` and the ai-gateway
runbook text in `src/test/resources/fixtures/RUNBOOK.md`. Those features belong to the ai-gateway repo (`../ai-gateway`,
referenced in `README.md` and `deploy/compose.yaml`), which was not read for this file.

## 1. Endpoints

| Method | Path | Auth / roles | Source |
|---|---|---|---|
| POST | `/alerts` | `Authorization: Bearer <OPSAGENT_WEBHOOK_TOKEN>`, compared in constant time. No user roles. | `alert/AlertController.java:33-55` |
| GET | `/actuator/health` (plus Boot's sub-paths) | No auth. There is no Spring Security dependency. | `application.yml:19-23`, `pom.xml` |

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
| 401 | `{"code":"UNAUTHORIZED"}` when the header is missing, not `Bearer `, or wrong |
| 400 | `{"code":"INVALID_ALERT","problems":["alerts[0].fingerprint: not a fingerprint", ...]}`. Problems name fields, never values |
| 400 (malformed JSON) | Spring Boot's default error body (`timestamp,status,error,path`). Shape **UNVERIFIED** (no test covers it) |

The token check runs before validation, so a bad token with a bad body gets 401.

### Idempotency and async behaviour
- No `Idempotency-Key` header. Deduplication uses the DB constraint `UNIQUE (fingerprint, starts_at)`. A repeat
  returns `DUPLICATE` (`db/migration/V1__triage_audit.sql:21`, `TriageService.java:83-85`).
- 202 is returned at once. The run executes on a pool with 2 threads and a queue of `queue-capacity` (default 8).
  When the queue is full, that alert gets `BUSY` and its row is set to `FAILED/BUSY` (`config/AgentWiring.java:87-92`,
  `TriageService.java:90-93`).
- Run states: `RUNNING`, `DONE`, `STOPPED`, `FAILED` (`V1__triage_audit.sql:22`). `stop_reason` is one of
  `STEPS|TOKENS|DEADLINE` (`agent/GuardedChatModel.java:22`), `BUSY`, or for a failure the root exception's simple
  class name, cut to 32 chars (`TriageService.java:183-190`).
- **No polling path.** The result is delivered only as an email. `triage_run` is not exposed over HTTP.

### /api/v1/chat and /chat/stream
NOT IN THIS REPO. Their request/response/error shapes, status enum, `Idempotency-Key`, roles, what a Spring AI or
Python client must send, stub-private behaviour and sensitivity behaviour are all **UNVERIFIED** here. Read them from
ai-gateway's code. ops-agent does not call the gateway's chat API; it calls OpenAI directly (`AgentWiring.java:27-29,42-59`).

## 2. Auth
- Issuer, audience, algorithm, JWKS source, token lifetime: none. There is no JWT or OAuth2 dependency in `pom.xml`
  and no security config in `src/main`.
- The one inbound credential is a static shared bearer token, `opsagent.webhook.token` = `${OPSAGENT_WEBHOOK_TOKEN}`,
  with no default (`application.yml:28`; `@NotBlank` in `config/OpsAgentProperties.java:34`).
- Service-to-service today: Alertmanager reads the token from `credentials_file` (`deploy/alertmanager.yml:18-22`).
  Compose writes that file from the environment (`deploy/compose.yaml`, `ops-alertmanager.entrypoint`).
- Caller identity: every webhook run is recorded under the fixed `opsagent.webhook.caller` (default `alertmanager`).
  That is also its RBAC key (`OpsAgentProperties.java:31-35`, `TriageService.java:70`). The token does not carry
  identity, user or role claims.
- RBAC is per tool, configured in `opsagent.rbac.<caller>: [tools]`. An unknown caller gets no tools. An unknown tool
  name stops startup (`tools/ToolPolicy.java`).
- What a new internal caller needs: it must send Alertmanager v4 JSON to `POST /alerts` with the same shared token.
  It would be recorded as `alertmanager`, because there is one token and one caller per deployment. Separate
  identities would need code changes (**NOT BUILT**).
- Outbound: OpenAI key `OPENAI_API_KEY`. If it is blank, no `ChatModel` bean is created and startup fails
  (`AgentWiring.java:40-41`).

## 3. Tenant / owner identifier
- There is no tenant. The closest is the alert's `owner` label. It must match `[a-z0-9][a-z0-9-]{0,62}`, max 63
  chars; otherwise the code uses `opsagent.mail.fallback-owner` (default `platform-oncall`) (`TriageMailer.java:26,39-45`).
- Stored as `triage_run.owner VARCHAR(128)` = the full recipient `<owner>@<owner-domain>`. `caller VARCHAR(64)` is on
  both `triage_run` and `tool_audit` (`V1__triage_audit.sql`).
- Cross-tenant 404: not applicable. No read endpoint exists.

## 4. Config flags (`application.yml`, `OpsAgentProperties.java`)

| Property (env) | Default | Effect |
|---|---|---|
| `opsagent.webhook.token` (`OPSAGENT_WEBHOOK_TOKEN`) | none; required | bearer token for `/alerts` |
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
| `opsagent.mail.from` / `owner-domain` / `fallback-owner` | `ops-agent@gateway.invalid` / `gateway.invalid` / `platform-oncall` | recipient |
| `opsagent.rbac.alertmanager` | all 4 tools | tool grants |
| `OPSAGENT_DB_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5433/opsagent` / `opsagent` / empty | Postgres, Flyway |
| `OPSAGENT_SMTP_HOST` / `_PORT` | `localhost` / `1025` | SMTP, no auth or TLS configured |
| `OPSAGENT_PORT` | 8095 | HTTP port |
| `OPSAGENT_LOG_FORMAT` | `ecs` | structured console logs |
| `it.excludedGroups` (Maven) | `openai` | skips `LiveTriageIT` (`pom.xml:27,115`) |

## 5. Events / queues / topics
- No broker, topics, keys or message headers. Kafka is NOT IN THIS REPO.
- The only queue is the in-memory `ArrayBlockingQueue` of the triage pool (`AgentWiring.java:87-92`). Queued runs are
  lost on restart, and their rows stay `RUNNING` (inferred from the code; no recovery code exists).
- Outbound email (`TriageMailer.java`): subject `[TRIAGE] <alertname> (<severity>) owner=<owner>`. Body sections:
  WHAT FIRED, FINDINGS, LIKELY CAUSE (+ confidence `LOW|MEDIUM|HIGH`, model-provided and unchecked), RUNBOOK (quoted
  from the file), EVIDENCE, RUN (id, status, stop reason, steps, tool calls, tokens).
- Persistence: `triage_run` and `tool_audit`. `tool_audit` outcome is `OK|DENIED|LIMIT|ERROR`. It is append-only by
  Postgres rules that turn UPDATE and DELETE into no-ops (`V1__triage_audit.sql:39-41`).

## 6. Test counts and CI
From the latest report files in `target/` (local run, 2026-11-12):
- Unit (surefire, 17:28): `AlertContractTest` 6, `ToolRegistryTest` 5. **11 pass, 0 failures.**
- Integration (failsafe, 17:29): `GuardrailIT` 8 pass (`failsafe-summary.xml`: completed 8, 0 errors and failures).
- Live (opt-in, 16:08): `LiveTriageIT` 1 pass. Its report is left over from an earlier run and not part of the last
  `verify`. Output is in `docs/evidence/live-triage.md`.
- UI tests: none exist.
- Default `verify` total: **19** (11 + 8).
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
| JWT/OAuth2, tenants, Kafka, chat API, SSE | NOT BUILT here | none |
| Run recovery after restart | NOT BUILT | none |

## 8. README / docs vs code
1. `README.md` says "`verify` # 17 tests". The code and reports give **19** (6 + 5 + 8).
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
