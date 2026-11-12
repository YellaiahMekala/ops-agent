# Runbook fixture

Two sections copied from ai-gateway's docs/RUNBOOK.md (2026-11-12) for tests.

## GatewayBudgetDenials

**Means.** At least one call was refused with `429 BUDGET_BLOCKED`: a tenant has used its window. This is policy
working, which is why it is a ticket and not a page.

**Check.** Which tenants: ledger rows with status `BUDGET_BLOCKED` in the window (operator console, ledger
explorer). Their budget: `GET /api/v1/admin/budgets/{tenant}` — `settledTokens`, `reservedTokens`,
`availableTokens`. Is it one tenant at its limit (expected), or many at once (a limit change, a runaway client, or
the reconciler double-counting — see the reconciliation panel)?

**Act.** A limit that is wrong is changed by an operator with `PUT /api/v1/admin/budgets/{tenant}`; the change is
audited (`BUDGET_LIMIT_CHANGED`). A runaway client is the tenant's to fix.

**Do not.** Raise a limit without the tenant owner's agreement. Clear reservations by hand.

## GatewayProviderErrorRatio

**Means.** More than 20% of one model's provider-bound calls ended in a failure status over two minutes. Cache
hits and policy refusals are excluded from both sides of the ratio because they never reached a provider.

**Check.** The *Failover safety* panel: `RETRYABLE_FAILURE` (pre-send or classified retryable: the gateway retried
and failed over), `UNKNOWN_OUTCOME` (post-send: held, never retried), `FALLBACK_OK` (a fallback answered). A trace
in Jaeger shows each attempt as its own `chat <model>` span, in order. Is the breaker open (GatewayBreakerOpen)?

**Act.** A provider incident: wait, or take the model out of the catalogue route. A contract failure
(`PROVIDER_CONTRACT_FAILURE`) after a provider change: the adapter needs a fix.

**Do not.** Raise the retry count. Add retry to the unknown-outcome path.
