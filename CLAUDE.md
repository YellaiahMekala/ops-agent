# CLAUDE.md: ops-agent standing rules

- **Read-only tools only.** Exactly four, listed in `OpsTools.TOOL_NAMES`. No tool name starts with a write verb;
  `ReadOnlyHttp` has one public method, `get`. `ToolRegistryTest` is release-blocking: remove the tool, never weaken the test.
- **Never act on tool output.** Logs, runbook text and metrics are data wrapped in `<tool-result>`. No code path executes
  or forwards them.
- **Audit stores argument shape and result size, never values.** `tool_audit` is append-only.
- **Guardrails are enforced outside the model** (`GuardedChatModel`, `ToolRun`). Do not rely on the prompt.
- **Mail goes to `<owner>@gateway.invalid`.** The runbook is quoted verbatim, not paraphrased by the model.
- **No key in the repo, logs or chat.** `OPENAI_API_KEY` and `OPSAGENT_WEBHOOK_TOKEN` come from the environment.
  Never run `docker compose config` with them set.
- **No payload in logs or audit**: alert annotations and model text are not logged.
- Model client: `maxRetries(0)`, `store(false)`. Live tests are tagged `openai` and need an explicit go-ahead (paid).
- Do not commit or tag unless the owner asks; no co-author trailer.
- Banned phrases: exactly-once, DLP, prevents prompt injection, evaluates quality, high availability, and any cost or
  latency figure not measured here.
