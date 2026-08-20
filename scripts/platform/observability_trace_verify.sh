#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
CURL_IMAGE='docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6'
cleanup(){ kill ${pf:-} 2>/dev/null || true; k delete pod telemetry-trace-canary -n platform-apps --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT
canary="hooshix-observability-canary-$(date +%s)-$RANDOM"
trace_json=$(python3 - "$canary" <<'PY'
import json,sys,time,os
n=time.time_ns()
print(json.dumps({'resourceSpans':[{'resource':{'attributes':[{'key':'service.name','value':{'stringValue':sys.argv[1]}}]},'scopeSpans':[{'scope':{'name':'hooshix.verify'},'spans':[{'traceId':os.urandom(16).hex(),'spanId':os.urandom(8).hex(),'name':'observability-canary','kind':1,'startTimeUnixNano':str(n),'endTimeUnixNano':str(n+1000000)}]}]}]}))
PY
)
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: telemetry-trace-canary
  namespace: platform-apps
  labels: {app.kubernetes.io/name: web-bff, verification.hooshix.io/role: telemetry-trace}
spec:
  serviceAccountName: web-bff
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      command: [sleep, "300"]
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=condition=Ready pod/telemetry-trace-canary -n platform-apps --timeout=60s >/dev/null
k exec -n platform-apps telemetry-trace-canary -- curl -fsS -m 8 -H 'Content-Type: application/json' --data "$trace_json" http://otel-collector.platform-observability-node.svc.cluster.local:4318/v1/traces >/dev/null
k port-forward -n platform-observability service/tempo 13200:3200 >/tmp/hooshix-tempo-pf.log 2>&1 & pf=$!
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:13200/ready >/dev/null 2>&1 && break; sleep .2; done
found=0
for i in $(seq 1 30); do
  q=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote("{ resource.service.name = \""+sys.argv[1]+"\" }"))' "$canary")
  curl -fsS "http://127.0.0.1:13200/api/search?q=$q" >/tmp/hooshix-tempo-search.json || true
  if python3 - <<'PY'
import json,sys
try: d=json.load(open('/tmp/hooshix-tempo-search.json'))
except Exception: sys.exit(1)
sys.exit(0 if d.get('traces') else 1)
PY
  then found=1; break; fi
  sleep 1
done
[[ "$found" == 1 ]] || fail "Tempo did not return the OTLP trace canary"
echo "Collector -> Tempo trace canary verification PASSED"
