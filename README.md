# ops-agent

An incident-triage agent for the [ai-gateway](../ai-gateway) demonstration stack. Alertmanager posts an alert; the agent
looks at Prometheus, the gateway's logs, its readiness endpoint and the runbook with four **read-only** tools, and emails
the alert's owner a summary. It changes nothing.

Separate repository on purpose (ai-gateway ADR-18): the gateway does not offer tool calling, and this agent's loop is
tool calling. LangChain4j is used here only.

## What it does

```
Alertmanager --webhook (bearer token)--> /alerts --> one run per (fingerprint, startsAt)
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
- **Intake:** wrong token 401; malformed body 400 naming fields, never values; a repeat of the same incident
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
./mvnw -B -ntp verify                                       # 25 tests, no key, Docker needed
OPENAI_API_KEY=... ./mvnw -B -ntp verify -Dit.excludedGroups= -Dit.test=LiveTriageIT   # one real run, opt-in
```

- `AlertContractTest` (fixtures, including `recorded-*.json` from a real Alertmanager), `ToolRegistryTest`
  (release-blocking: the registry stays read-only), `GuardrailIT` (Postgres and Mailpit in Testcontainers, a scripted model),
  `RunRecoveryIT` (stale run resubmitted once, unknown outcome not redone, bounded attempts, dedupe, two concurrent
  sweepers, a live run not stolen).
- `LiveTriageIT` is tagged `openai` and excluded from the default build and CI. Evidence: `docs/evidence/live-triage.md`.

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
- The agent's answer is a summary to a human, not an evaluated diagnosis.
