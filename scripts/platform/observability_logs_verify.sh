#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
CURL_IMAGE='docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6'
cleanup(){ kill ${pf:-} 2>/dev/null || true; k delete pod telemetry-log-canary telemetry-privacy-canary -n platform-apps --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT
k port-forward -n platform-observability service/loki 13100:3100 >/tmp/hooshix-loki-pf.log 2>&1 & pf=$!
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:13100/ready >/dev/null 2>&1 && break; sleep .2; done
query_loki(){ local text=$1; local q; q=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote("{service_name=\"hooshix-kubernetes-pod-log\"} |= \""+sys.argv[1]+"\""))' "$text"); curl -fsS "http://127.0.0.1:13100/loki/api/v1/query_range?query=$q&limit=20"; }
log_canary="HOOSHIX_SAFE_LOG_CANARY_${RANDOM}_$(date +%s)"
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: {name: telemetry-log-canary, namespace: platform-apps}
spec:
  serviceAccountName: web-bff
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: log
      image: $CURL_IMAGE
      command: [sh, -c, 'sleep 2; echo $log_canary; sleep 4']
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=jsonpath='{.status.phase}'=Succeeded pod/telemetry-log-canary -n platform-apps --timeout=60s >/dev/null
found=0
for i in $(seq 1 30); do
  query_loki "$log_canary" >/tmp/hooshix-loki-query.json || true
  if python3 - <<'PY'
import json,sys
try:r=json.load(open('/tmp/hooshix-loki-query.json')).get('data',{}).get('result',[])
except Exception:sys.exit(1)
sys.exit(0 if r else 1)
PY
  then found=1; break; fi
  sleep 1
done
[[ "$found" == 1 ]] || fail "Loki did not return the safe pod-log canary"
privacy_canary="HOOSHIX_PII_CANARY_${RANDOM}_$(date +%s)"
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: {name: telemetry-privacy-canary, namespace: platform-apps}
spec:
  serviceAccountName: web-bff
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: log
      image: $CURL_IMAGE
      command: [sh, -c, 'sleep 2; echo $privacy_canary; sleep 4']
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=jsonpath='{.status.phase}'=Succeeded pod/telemetry-privacy-canary -n platform-apps --timeout=60s >/dev/null
sleep 5
query_loki "$privacy_canary" >/tmp/hooshix-loki-privacy.json || true
python3 - <<'PY'
import json
r=json.load(open('/tmp/hooshix-loki-privacy.json')).get('data',{}).get('result',[])
if r: raise SystemExit('privacy canary reached Loki; Collector filter failed')
PY
echo "Collector -> Loki safe-log and privacy-filter verification PASSED"
