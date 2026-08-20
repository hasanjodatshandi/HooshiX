#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/observability/pins.env"
for d in loki tempo prometheus alertmanager grafana; do k rollout status deployment/$d -n platform-observability --timeout=45s >/dev/null; done
k rollout status daemonset/otel-collector -n platform-observability-node --timeout=45s >/dev/null
[[ "$(k get daemonset otel-collector -n platform-observability-node -o jsonpath='{.status.numberReady}')" -eq 3 ]] || fail "OTel Collector must be ready on all 3 nodes"
check_image() { local kind=$1 name=$2 ns=$3 digest=$4; local img; img=$(k get "$kind" "$name" -n "$ns" -o jsonpath='{.spec.template.spec.containers[0].image}'); [[ "$img" == *"@$digest" ]] || fail "$name image digest mismatch: $img"; }
check_image daemonset otel-collector platform-observability-node "$OTEL_COLLECTOR_INDEX_DIGEST"
check_image deployment prometheus platform-observability "$PROMETHEUS_INDEX_DIGEST"
check_image deployment loki platform-observability "$LOKI_INDEX_DIGEST"
check_image deployment tempo platform-observability "$TEMPO_INDEX_DIGEST"
check_image deployment grafana platform-observability "$GRAFANA_INDEX_DIGEST"
check_image deployment alertmanager platform-observability "$ALERTMANAGER_INDEX_DIGEST"
for pair in 'otel-collector platform-observability-node' 'prometheus platform-observability' 'loki platform-observability' 'tempo platform-observability' 'grafana platform-observability' 'alertmanager platform-observability'; do set -- $pair; name=$1; ns=$2; sa=$(k get pod -n "$ns" -l "app.kubernetes.io/name=$name" -o jsonpath='{.items[0].spec.serviceAccountName}'); [[ "$sa" == "$name" ]] || fail "$name ServiceAccount mismatch: $sa"; done
[[ "$(k get ns platform-observability -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')" == restricted ]] || fail "platform-observability PSS must be restricted"
[[ "$(k get ns platform-observability-node -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')" == privileged ]] || fail "platform-observability-node PSS enforce must be privileged only for the exact Kyverno-governed log hostPath exception"
for ns in platform-observability platform-observability-node; do k get networkpolicy default-deny -n "$ns" >/dev/null; done
k get validatingpolicy platform-observability-node-hardening >/dev/null
collector=$(k get daemonset otel-collector -n platform-observability-node -o json)
COLLECTOR_JSON="$collector" python3 - <<'PY'
import json,os,sys
d=json.loads(os.environ['COLLECTOR_JSON'])
s=d['spec']['template']['spec']; c=s['containers'][0]
h=[v for v in s.get('volumes',[]) if 'hostPath' in v]
if len(h)!=1 or h[0]['name']!='pod-logs' or h[0]['hostPath'].get('path')!='/var/log/pods': raise SystemExit('Collector hostPath contract mismatch')
m=[x for x in c.get('volumeMounts',[]) if x['name']=='pod-logs']
if len(m)!=1 or m[0].get('readOnly') is not True or m[0].get('mountPath')!='/var/log/pods': raise SystemExit('Collector pod-log mount must be exact and read-only')
PY
config=$(k get configmap otel-collector-config -n platform-observability-node -o jsonpath='{.data.config\.yaml}')
for token in 'memory_limiter' 'queue_size: 512' 'max_elapsed_time: 30s' 'filter/privacy' 'transform/privacy' 'filelog/application' 'otlp/tempo' 'otlphttp/loki'; do [[ "$config" == *"$token"* ]] || fail "Collector config missing bounded/privacy token: $token"; done
grafana_env=$(k get deployment grafana -n platform-observability -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}')
for pair in 'GF_ANALYTICS_REPORTING_ENABLED=false' 'GF_ANALYTICS_CHECK_FOR_UPDATES=false' 'GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false' 'GF_PLUGINS_PLUGIN_ADMIN_ENABLED=false' 'GF_PLUGINS_PREINSTALL_DISABLED=true' 'GF_PLUGINS_PREINSTALL_AUTO_UPDATE=false'; do [[ "$grafana_env" == *"$pair"* ]] || fail "Grafana hardening missing: $pair"; done
if k logs -n platform-observability deploy/grafana --since=10m 2>/dev/null | grep -Eqi 'installing plugin|plugin.*download|check.*update'; then fail "Grafana startup attempted plugin/update network activity"; fi
echo "Observability foundation verification PASSED"
