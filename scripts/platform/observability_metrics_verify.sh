#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/observability_foundation_verify.sh"
k port-forward -n platform-observability service/prometheus 19090:9090 >/tmp/hooshix-prom-pf.log 2>&1 & pf=$!
trap 'kill $pf 2>/dev/null || true' EXIT
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:19090/-/ready >/dev/null 2>&1 && break; sleep .2; done
for job in authorization-service compromised-password-service identity-service notification-service web-bff; do
  q=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "up{job=\"$job\"}")
  curl -fsS "http://127.0.0.1:19090/api/v1/query?query=$q" >/tmp/hooshix-prom-query.json
  JOB="$job" python3 - <<'PY'
import json,os
r=json.load(open('/tmp/hooshix-prom-query.json')).get('data',{}).get('result',[])
if not r or not all(float(x['value'][1]) == 1 for x in r): raise SystemExit('Prometheus target not up: '+os.environ['JOB'])
PY
done
q=$(python3 -c 'import urllib.parse; print(urllib.parse.quote("up{job=\"otel-collector\"}"))')
curl -fsS "http://127.0.0.1:19090/api/v1/query?query=$q" >/tmp/hooshix-prom-query.json
python3 - <<'PY'
import json
r=json.load(open('/tmp/hooshix-prom-query.json')).get('data',{}).get('result',[])
if len(r) != 3 or not all(float(x['value'][1]) == 1 for x in r): raise SystemExit(f'expected 3 healthy Collector targets, found {len(r)}')
PY
echo "Prometheus application and Collector target verification PASSED"
