# Alert fixtures

- `provider-error-ratio.json` and `resolved.json` follow the documented Alertmanager webhook format (version 4).
  Their labels and annotations come from ai-gateway's rule `GatewayProviderErrorRatio`.
- `recorded-*.json` were captured from the real Alertmanager in ai-gateway's demo stack during the Phase 12 live run.
  They are replayed by `AlertContractTest`.
