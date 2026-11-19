# D1.3.1 gateway mode, end to end (pointer, 2026-11-19)

Full proof, commands and ledger evidence: `ai-gateway/docs/proof/d1-3-1-tool-calling-e2e-2026-11-19.md`.

Local run, stub provider, real Keycloak (platform realm), real gateway, ops-agent in `opsagent.model.mode=gateway`.
No `OPENAI_API_KEY` in ops-agent's environment. Stub run: live check skipped (no key set). Live run later the same day: see "Live run (gpt-4o-mini)" below, USD 0.000392.

| Check | Result |
|---|---|
| Alert (sender token, 202) to `DONE`, 3 gateway steps, 2 tools executed here and audited (`tool_audit`: tool, outcome, size, argument shape only) | PASS |
| Gateway ledger: 3 rows `AGENT_TOOL_STEP`, one `agent_run_id`, tool names only, no arguments or results | PASS |
| Tool names ops-agent sends equal the allow-list (`checkHealth, queryPrometheus, readRunbook, searchLogs`) | PASS |
| Gateway 403, 400 (several), 429 AGENT_RUN_LIMIT, 422 TOOLS_NOT_SUPPORTED, 400 on `/chat/stream` (curl) | PASS, as documented |
| ops-agent maps a real 422 to `FAILED/GATEWAY_TOOLS_NOT_SUPPORTED`, email still sent | PASS |
| 403 and 429 mapping in ops-agent | not run live; `GatewayModeIT` (fake gateway) |
| Idempotent replay returns no `toolCalls` | ops-agent fails the run (`GATEWAY_UNAVAILABLE`), no redo; unit test |

**Bug found and fixed.** The first run failed `GATEWAY_UNAVAILABLE` after a good 200: `GatewayChatModel` required
`status` `SUCCEEDED` (the contract example) but the gateway answers `OK`. Fixed with `SUCCESS_STATUSES` (`OK`,
`FALLBACK_OK`, `CACHE_HIT_*`, `CACHE_DEGRADED`, `IDEMPOTENT_REPLAY`, `SUCCEEDED`), tests `successStatuses` and
`idempotentReplayWithoutToolCalls`, fake-gateway fixtures switched to `OK`, platform contract example corrected.
`./mvnw verify`: 26 unit + 28 IT, 0 failures. The fake gateway had shared the contract's misreading, which is the risk
ADR-0003 named and this run caught.

## Live run (gpt-4o-mini)

Later the same day, with the owner's approval: same setup, gateway on the `real-provider` profile routed to
`openai-gpt-4o-mini` (key loaded into the gateway process only; ops-agent's environment had no `OPENAI_API_KEY`).
Full evidence: `ai-gateway/docs/proof/d1-3-1-tool-calling-e2e-2026-11-19.md`, section 7.

| Item | Result |
|---|---|
| Model | `gpt-4o-mini` |
| Alert | one synthetic `GatewayProviderErrorRatio` alert (fixture), 202 |
| Steps | 2 |
| Tool calls | 3 in step 1 (`checkHealth`, `queryPrometheus`, `readRunbook`), executed here, each audited (`OK`, argument shape only) |
| Final status | `DONE`, report parsed, email sent |
| Tokens (provider-reported, from the gateway ledger) | input 1771, output 210 |
| Cost | USD 0.000392 (at 0.15 / 0.60 per 1M) |
| Gateway ledger | 2 rows `AGENT_TOOL_STEP`, tool names only, `PROVIDER_REPORTED` usage |
| Bugs | none found; no code change |

Report: three findings (alert claim, Prometheus ratio 0.87 from the stub, gateway health UP), likely cause provider
incident or contract failure, confidence HIGH, quoted runbook section, evidence links, run footer saying the agent
changed nothing.
