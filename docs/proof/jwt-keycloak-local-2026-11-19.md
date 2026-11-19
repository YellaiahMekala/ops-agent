# JWT mode against a real Keycloak, local (2026-11-19)

Status: **run 2026-11-19, local, free** (no OpenAI call, no cloud). Closes the gap left by PQ1.2.2, which only used the
legacy shared token because no ops-agent client existed in the realm.

## 1. Setup

- Keycloak 26.3 (`start-dev --import-realm`, port 8190, `KC_HOSTNAME=http://localhost:8190`) loading the platform realm
  `platform/keycloak/realm-platform.json` read-only, with throwaway admin and service secrets (shell only).
  Issuer: `http://localhost:8190/realms/platform`.
- `deploy/keycloak/import-ops-agent.sh` posts `deploy/keycloak/ops-agent-partial-import.json` to
  `/admin/realms/platform/partialImport`. It adds realm roles `OPS_ALERT_SENDER` and `OPS_RUN_VIEWER`, and three
  confidential client-credentials clients with service-account users:

  | Client | Tenant (hard-coded claim) | Role |
  |---|---|---|
  | `ops-alertmanager-tenant-a` | tenant-a | `OPS_ALERT_SENDER` |
  | `ops-viewer-tenant-a` | tenant-a | `OPS_RUN_VIEWER` |
  | `ops-viewer-tenant-b` | tenant-b | `OPS_RUN_VIEWER` |

  Each has mappers: audience `ops-agent`, realm roles into the top-level `roles` claim, hard-coded `tenant_id`, `sub`.
  No secret is in the JSON: Keycloak generates it at import and the script writes it to a file (`.kc-secrets/`,
  git-ignored), never to the terminal.
- ops-agent: `target/ops-agent-0.1.0-SNAPSHOT.jar` on the host, Postgres 16 and Mailpit in throwaway containers.
  `OPSAGENT_JWT_JWK_SET_URI=http://localhost:8190/realms/platform/protocol/openid-connect/certs`,
  `OPSAGENT_LEGACY_WEBHOOK_ENABLED=false`, `OPENAI_API_KEY=stub-not-a-real-key`,
  `OPSAGENT_MODEL_BASE_URL=http://127.0.0.1:8199/v1`. That base URL is `deploy/keycloak/stub_model.py`, a local server
  returning one fixed report and no tool calls, so nothing reaches OpenAI.

## 2. Token claims (decoded payload; `sub`, `jti`, signature omitted)

```
ops-alertmanager-tenant-a: {"iss":"http://localhost:8190/realms/platform","aud":"ops-agent","azp":"ops-alertmanager-tenant-a","tenant_id":"tenant-a","roles":["OPS_ALERT_SENDER"]}
ops-viewer-tenant-b:       {"iss":"...","aud":"ops-agent","azp":"ops-viewer-tenant-b","tenant_id":"tenant-b","roles":["OPS_RUN_VIEWER"]}
wrong audience (platform client svc-reconcile-tenant-a, scope aud-ai-gateway): {"aud":"ai-gateway","tenant_id":"tenant-a","roles":["RERO_CALLER","GATEWAY_CONSUMER"]}
```

Tokens were fetched with `grant_type=client_credentials` and sent as `Authorization: Bearer <token>`. None is printed here.

## 3. Results

`POST /alerts` with `src/test/resources/fixtures/alerts/provider-error-ratio.json`; `U=http://localhost:8095`.

| # | Request | Expected | Got |
|---|---|---|---|
| 1 | `POST /alerts`, sender token | 202 accepted | **202** `{"intake":["STARTED"]}` |
| 1b | same alert again | duplicate | **202** `{"intake":["DUPLICATE"]}` |
| 2 | `POST /alerts`, no token | 401 | **401** `{"code":"UNAUTHORIZED"}` |
| 3 | `POST /alerts`, token with audience `ai-gateway` | 401 | **401** `{"code":"UNAUTHORIZED"}` |
| 4 | `POST /alerts`, viewer token | 403 | **403** `{"code":"FORBIDDEN"}` |
| 5 | `POST /alerts`, garbage bearer (legacy off) | 401 | **401** |
| 6 | `GET /api/v1/runs`, sender token | 403 | **403** `{"code":"FORBIDDEN"}` |
| 7 | `GET /api/v1/runs`, viewer tenant-a | the run | **200**, one run `DONE`, `mailed:true`, 1 step, 0 tool calls |
| 8 | `GET /api/v1/runs/{id}`, viewer tenant-a | 200 | **200** |
| 9 | `GET /api/v1/runs/{id}`, viewer tenant-b (same id) | 404 | **404** `{"code":"NOT_FOUND"}` |
| 10 | `GET /api/v1/runs`, viewer tenant-b | empty | **200** `{"runs":[]}` |
| 11 | `GET /api/v1/runs/{id}`, no token | 401 | **401** |

Command shape (`$SND`, `$VA`, `$VB` come from the realm token endpoint with each client's generated secret):

```
curl -X POST $U/alerts -H "Authorization: Bearer $SND" -H 'Content-Type: application/json' --data @provider-error-ratio.json
curl $U/api/v1/runs/$ID -H "Authorization: Bearer $VB"      # 404: another tenant's run
```

Mailpit held one message (the triage mail), none for the duplicate. The JWKS was fetched from the real Keycloak, which
`TenantSecurityIT` does not cover.

## 4. Limits

- The model was a stub: this proves authentication, roles, tenant scoping and the JWKS path, not triage quality.
- Alertmanager's own `http_config.oauth2` login was not run; the token was fetched with curl.
- The platform realm file was not changed. The client definitions live in this repo; importing them into a shared realm
  is a manual step (`import-ops-agent.sh`).
- Gotcha: on Windows the runbook default (contains `:`) fails to bind as a `Path` when running the jar on the host; set
  `OPSAGENT_RUNBOOK` and `OPSAGENT_LOG_DIR`.
- All containers and the stub were removed afterwards and the secrets file deleted.
