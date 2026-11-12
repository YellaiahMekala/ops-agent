#!/usr/bin/env bash
# Records what Alertmanager really sends, for the contract fixtures (plan 12.1).
#
# Needs: the ai-gateway demo network, this repo's demo stack started with OPS_ALERTMANAGER_CONFIG=alertmanager.record.yml,
# and deploy/capture_webhook.py running on the host with an output directory.
# Posts one firing alert to ops-alertmanager's API, waits for the webhook, then resolves it and waits again.
# The alert's labels and annotations copy the gateway's GatewayProviderErrorRatio rule; nothing here is personal data.
set -euo pipefail

AM=${AM:-http://127.0.0.1:9094}
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

alert() {
  local ends="$1"
  cat <<JSON
[{"labels":{"alertname":"GatewayProviderErrorRatio","severity":"page","owner":"gateway-oncall","service":"ai-gateway"},
  "annotations":{"summary":"Provider failure ratio above 20% for 2 minutes",
                 "runbook":"docs/RUNBOOK.md#gatewayprovidererrorratio"},
  "startsAt":"$NOW"${ends:+,\"endsAt\":\"$ends\"},
  "generatorURL":"http://prometheus:9090/graph?g0.expr=gateway_provider_error_ratio"}]
JSON
}

echo "firing ..."
curl -fsS -X POST -H 'Content-Type: application/json' --data "$(alert '')" "$AM/api/v2/alerts"
sleep 15
echo "resolving ..."
curl -fsS -X POST -H 'Content-Type: application/json' --data "$(alert "$(date -u +%Y-%m-%dT%H:%M:%SZ)")" "$AM/api/v2/alerts"
sleep 75
echo "done"
