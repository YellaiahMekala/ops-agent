# ADR-0002: JWT callers and a tenant on every run

Status: accepted, 2026-11-18

## Context

ops-agent had one inbound credential: a shared bearer token that Alertmanager sends to `POST /alerts`. The token
carries no identity, no role and no tenant. Every run was recorded as caller `alertmanager`, and `triage_run` was not
readable over HTTP at all. The other services on the platform (ai-gateway, EKA, reconcile-ai) take a JWT from the
platform Keycloak realm and take the tenant only from its `tenant_id` claim (platform spikes S-1 and S-3). ops-agent
should follow the same model before anyone else calls it, and before runs become readable.

Platform facts this relies on (`platform/keycloak/realm-platform.json`, spike S-1):

- Realm `platform`, issuer `http://localhost:8190/realms/platform` in the demo, RS256, token lifetime 300 s.
- Per-tenant service clients (`svc-reconcile-tenant-a`, `-b`) with a hard-coded `tenant_id` claim the caller cannot
  change.
- A top-level `roles` claim (realm roles) and one audience per service, chosen per call by client scope.
- ops-agent has **no** client, audience or roles in that realm yet. Adding them is a change in the platform repo, not
  here (see Consequences).

## Decision

### 1. Who calls ops-agent

| Caller | Today | Credential | Role |
|---|---|---|---|
| Alertmanager | `POST /alerts` | legacy shared token, or a JWT from its `http_config.oauth2` (client credentials) | `OPS_ALERT_SENDER` |
| A person or service reading runs | `GET /api/v1/runs`, `GET /api/v1/runs/{id}` | JWT only | `OPS_RUN_VIEWER` |

Roles are separate on purpose: whoever can raise an alert (and so spend model tokens and send mail) cannot read run
history, and a viewer cannot raise alerts. Anything else is denied (`anyRequest().denyAll()`); health is open.

### 2. How the tenant is derived

- From the validated JWT claim `tenant_id`, nothing else. A header, query parameter or body field cannot set it, and
  a test proves a spoofed `X-Tenant-Id` and `?tenant_id=` change nothing.
- The decoder refuses a token with a missing or malformed `tenant_id` (`[a-z0-9][a-z0-9-]{0,62}`), so no request
  reaches a handler without a tenant.
- Validation: RS256 only, issuer, audience `ops-agent` (configurable), `exp` present and not past (30 s skew),
  tenant claim. Keys from `opsagent.security.jwk-set-uri` (Keycloak certs endpoint). A PEM public key location exists
  for tests.
- Roles come from the top-level `roles` claim, as `ROLE_<name>`.
- The legacy shared token has no claim. Its runs belong to one configured tenant, `opsagent.webhook.tenant`
  (default `legacy`).

### 3. Data

`V3__tenant.sql`: `triage_run.tenant_id VARCHAR(64) NOT NULL`. Existing rows become tenant `legacy`.
The dedupe constraint becomes `UNIQUE (tenant_id, fingerprint, starts_at)`: the same incident twice for one tenant is
`DUPLICATE`; two tenants with the same fingerprint and start time are two runs. Index `(tenant_id, created_at DESC)`.

Every read is `WHERE tenant_id = ?` with the tenant from the token. A run of another tenant is **404 `NOT_FOUND`**,
the same answer as a run that does not exist, so ids cannot be probed. `tool_audit` is read only through a run already
proven to belong to the tenant. The view never selects the alert payload, lease or heartbeat columns.

`caller` stays the RBAC key for tools and stays `opsagent.webhook.caller`: both the legacy token and the
`OPS_ALERT_SENDER` role mean "the alert source". The tenant does not change tool grants.

### 4. Migration path (webhook shared token, behind config)

1. **Now (default):** `opsagent.webhook.legacy-enabled=true`. The shared token works exactly as before, for
   `POST /alerts` only (it opens nothing else), as tenant `legacy`. JWT is accepted alongside it once
   `jwk-set-uri` is set. With no JWT key source, every JWT is refused (legacy-only deployments keep starting).
2. Configure Alertmanager with `http_config.oauth2` against a client holding `OPS_ALERT_SENDER` and the `tenant_id`
   claim. Both modes work in parallel; compare the `tenant_id` column to see which senders moved.
3. Set `OPSAGENT_LEGACY_WEBHOOK_ENABLED=false`. The shared token is then just a wrong bearer value (401).
4. Later: delete the token property and the filter.

Startup fails fast when nothing can authenticate (legacy off, no JWT source) or when legacy is on with an empty token.

### 5. Errors

`401 {"code":"UNAUTHORIZED"}` (no reason given), `403 {"code":"FORBIDDEN"}`, `404 {"code":"NOT_FOUND"}`. The
malformed-JSON 400 `INVALID_ALERT` is unchanged. Authentication now runs before body parsing, so an unauthenticated
malformed body is 401, not 400 (this closes the limit noted in the testing guide, F4).

## Consequences

- Needs a platform change before it can be used live: a client scope with audience `ops-agent`, realm roles
  `OPS_ALERT_SENDER` and `OPS_RUN_VIEWER`, and a service client per tenant for Alertmanager. Not built, not run
  against a real Keycloak. The tests use locally minted RS256 tokens with throwaway keys; the JWKS fetch path
  (`jwk-set-uri`) is configuration only and was not exercised.
- Runs from the legacy token share tenant `legacy`: no per-sender separation until step 2 is done.
- Tenant is not in the email or in logs. Logs still carry run ids, counts and outcomes.
- The JWT `sub`/`azp` is not stored on the run. If an audit of which sender raised which run is needed, add a
  column then.
- No rate limit per tenant on `POST /alerts`; the bounded queue (`BUSY`) is global.
