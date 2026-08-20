#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
RUNTIME="$ROOT/.platform-runtime/staging"
k port-forward -n platform-observability service/grafana 13000:3000 >/tmp/hooshix-grafana-pf.log 2>&1 & pf=$!
trap 'kill $pf 2>/dev/null || true' EXIT
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:13000/api/health >/dev/null 2>&1 && break; sleep .2; done
password=$(cat "$RUNTIME/files/grafana-admin/password")
for uid in prometheus loki tempo; do
  code=$(curl -sS -u "admin:$password" -o /tmp/hooshix-grafana-ds.json -w '%{http_code}' "http://127.0.0.1:13000/api/datasources/uid/$uid/health")
  [[ "$code" == 200 ]] || fail "Grafana datasource $uid health returned HTTP $code"
done
unset password
if k logs -n platform-observability deploy/grafana --since=10m 2>/dev/null | grep -Eqi 'installing plugin|plugin.*download|check.*update'; then fail "Grafana attempted plugin/update network activity"; fi
echo "Grafana hardened datasource verification PASSED"
