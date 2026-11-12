# Live triage run (plan 12.5), 2026-11-12

Command: `./mvnw -B -ntp verify -Dit.excludedGroups= -Dit.test=LiveTriageIT` with OPENAI_API_KEY from the environment.
Model: gpt-4o-mini (config default), maxRetries 0, store false. Fixture alert, logs, Prometheus and readiness.

```
LIVE status=DONE stop=null steps=5 toolCalls=4 inputTokens=4809 outputTokens=213 mailed=true tools=[{tool=queryPrometheus, outcome=OK}, {tool=readRunbook, outcome=OK}, {tool=searchLogs, outcome=OK}, {tool=checkHealth, outcome=OK}]
```

Spend: 4809 input + 213 output tokens. At OpenAI's published gpt-4o-mini list price (0.15 / 0.60 USD per 1M) that is about 0.00085 USD; computed from the run's own token counts, not from an invoice.
