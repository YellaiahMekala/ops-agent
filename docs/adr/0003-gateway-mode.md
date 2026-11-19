# ADR-0003: The model is reached through the gateway

Status: accepted, 2026-11-19

## Context

ops-agent called OpenAI directly with the LangChain4j `OpenAiChatModel`. That was a deliberate bypass: the ai-gateway
had no tool calling, and this agent's loop is tool calling (ai-gateway ADR-18, and the PQ1.2.2 proof,
`docs/proof/pq1-2-2-live-e2e-2026-11-18.md`, records a run whose gateway ledger had 0 rows). The bypass meant the
agent held a provider key, escaped the gateway's identity, per-run budgets, guardrails and ledger, and had to be kept
off the gateway's governance by convention only.

The gateway now offers tool calling as a pass-through: contract v1, `platform/contracts/gateway/tool-calling.v1.md`
(D1.3.1). The gateway forwards tool definitions to the model and returns the model's tool calls. It never executes a
tool. The caller runs its own read-only, RBAC-checked, audited tools and sends the results back as `TOOL` messages.

## Decision

1. **Default mode is `gateway`.** `opsagent.model.mode` is `gateway` or `direct`; the default is `gateway`.
   `GatewayChatModel` implements LangChain4j `ChatModel` and posts to `{gateway}/api/v1/chat` with `taskClass
   AGENT_TOOL_STEP`. It maps messages (`SYSTEM`, `USER`, `ASSISTANT` with `toolCalls`, `TOOL` with `toolCallId`), tool
   specifications (to JSON-schema `parameters`), and the response (`toolCalls`, `finishReason`, `usage`). Spring
   `RestClient` over the JDK HTTP client, no WebFlux. `GuardedChatModel` wraps it unchanged: steps, tokens and the
   deadline are still enforced here, in addition to the gateway's own per-run limits.
2. **Headers.** `Authorization: Bearer <jwt>`; `Idempotency-Key: <runId>:<step>`; `X-Agent-Run-Id: <runId>`. The step is
   the number of assistant messages already in the request plus one, so the key is a function of the conversation
   position, needs no counter, and is the same on a replay of the same position.
3. **Identity.** A client-credentials token from the platform Keycloak realm, client `ops-agent-gateway`, secret from
   `OPSAGENT_GATEWAY_CLIENT_SECRET` only. Cached and replaced 30 s before expiry; dropped on a gateway 401. The client,
   its hard-coded `tenant_id` and its tool allow-list are platform-side configuration, not in this repository.
4. **Errors, in terms of the run.**
   - 400, 401, 403 (`TOOL_NOT_ALLOWED`), 422 and other 4xx: the request itself is refused, retrying cannot help. The
     run ends `FAILED`, `stop_reason GATEWAY_<code>`, and the email still goes.
   - 429 `AGENT_RUN_LIMIT`: the gateway's per-run step or token budget is spent. The run ends `STOPPED`,
     `GATEWAY_RUN_LIMIT`, the same status class as our own `STEPS`/`TOKENS`/`DEADLINE`, and the email names it.
   - 5xx, any other 429, timeout, connection or token-endpoint failure: `FAILED`, `GATEWAY_UNAVAILABLE`.
   - **No retry anywhere.** This is ADR-0001: a call that may have happened is never redone. The run already records
     `side_effect = MODEL` before every model call, so a crash during a gateway call is closed by recovery as
     `OUTCOME_UNKNOWN` and not re-run. The gateway's idempotent replay would make a same-key retry safe on its side, but
     the agent does not rely on that; it keeps one rule.
   - Gateway behaviour to know: a missing `X-Agent-Run-Id` is a 400 `INVALID_TOOLS`. An idempotent replay returns no
     `toolCalls`, so after a lost response the step is re-run under a new key, never the same one. Errors are JSON
     bodies with a `code`, not problem+json.
   - Error text carries the status and the problem `code` only, never a response body, prompt or token.
5. **Bypass removal.** `application.yml` no longer has a model key, a model base URL or any provider name. The `direct`
   path moved to `application-direct.yml`, active only with `SPRING_PROFILES_ACTIVE=direct`, for local development.
   `deploy/compose.yaml` no longer passes a provider key to ops-agent. `GatewayModeConfigTest` fails the build if the
   default configuration or the compose file reintroduces a provider URL or key reference.
6. **Egress enforcement (example).** `deploy/k8s/ops-agent-networkpolicy.yaml` allows ops-agent egress only to the
   gateway, Keycloak, Postgres, Prometheus, Loki, an SMTP relay and cluster DNS. A provider is unreachable from the pod
   even if `direct` mode were switched on with a key. It is a template with placeholder namespaces and labels.

## Consequences

- Provider choice, keys, budgets, guardrails, masking and the ledger belong to the gateway. The ledger now sees the
  agent's steps, grouped by `agent_run_id`. Tool arguments and results are not stored there (contract).
- A new failure source: if the gateway or Keycloak is down, runs fail as `GATEWAY_UNAVAILABLE` and the owner still gets
  the email with the runbook (the runbook is quoted by code, not by the model).
- The gateway runs injection and masking on `TOOL` message content like any user input; ops-agent still wraps tool
  results as data and has no write tool, so neither side is relied on alone.
- `responseFormat` (structured output) is not sent: the contract has no field for it. LangChain4j falls back to
  instructing the model in the prompt and parsing the answer, as it did for OpenAI before.
- Existing ITs run in `direct` mode with a scripted model (`src/test/resources/application.properties`), so they stay
  independent of the gateway; `GatewayModeIT` switches to `gateway`.

## Not done / limits

- Run once against the real gateway and a real Keycloak token endpoint with the stub provider
  (`docs/proof/d1-3-1-gateway-mode-e2e-2026-11-19.md`); that run found the `status` mismatch below. Not run against a
  real model. `GatewayModeIT` uses a fake
  gateway written from the contract; a shared misreading would not be caught.
- The NetworkPolicy has not been applied to a cluster and only holds on a CNI that enforces it.
- Ambiguities in the contract, handled conservatively: the `status` field of the response is checked against the gateway's success statuses (`OK`, `FALLBACK_OK`, ... see `GatewayChatModel.SUCCESS_STATUSES`; the first real run found the gateway says `OK`, not the contract example's `SUCCEEDED`);
  a 429 with a code other than `AGENT_RUN_LIMIT` is treated as unavailable; the contract does not say what the gateway
  returns for a duplicate in-flight `Idempotency-Key`, so that case surfaces as its HTTP status and fails the run.
