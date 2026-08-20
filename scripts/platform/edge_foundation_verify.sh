#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/traefik/pins.env"
load_env "$ROOT/infrastructure/waf/pins.env"
status=$(h status traefik -n traefik-system -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["info"]["status"])')
[[ "$status" == deployed ]] || fail "Traefik release status: $status"
chart=$(h list -n traefik-system -o json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["chart"] for x in d if x["name"]=="traefik"))')
[[ "$chart" == "traefik-$TRAEFIK_CHART_VERSION" ]] || fail "Traefik chart mismatch: $chart"
k rollout status deployment/traefik -n traefik-system --timeout=30s >/dev/null
traefik_id=$(k get pods -n traefik-system -l app.kubernetes.io/name=traefik -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
[[ "$traefik_id" == *"$TRAEFIK_INDEX_DIGEST" || "$traefik_id" == *"$TRAEFIK_AMD64_DIGEST" ]] || fail "Traefik digest mismatch: $traefik_id"
[[ "$(k get pod -n traefik-system -l app.kubernetes.io/name=traefik -o jsonpath='{.items[0].spec.serviceAccountName}')" == traefik ]] || fail "Traefik SA mismatch"
[[ "$(k get pod -n traefik-system -l app.kubernetes.io/name=traefik -o jsonpath='{.items[0].spec.nodeName}')" == platform-local-control-plane ]] || fail "Traefik not on ingress-ready node"
args=$(k get deployment traefik -n traefik-system -o jsonpath='{.spec.template.spec.containers[0].args}')
[[ "$args" == *"--providers.kubernetesgateway"* ]] || fail "Gateway provider not enabled"
[[ "$args" != *"--providers.kubernetesingress"* ]] || fail "Ingress provider must be disabled"
[[ "$args" != *"--providers.kubernetescrd"* ]] || fail "CRD provider must be disabled"
[[ "$args" == *"--api.dashboard=false"* ]] || fail "Traefik dashboard must be disabled"
[[ "$args" == *"--api.insecure=false"* ]] || fail "Traefik insecure API must be disabled"
if grep -Eq '^[[:space:]]*logs:|providers:[[:space:]]*$.*file:' "$ROOT/infrastructure/traefik/values-local.yaml"; then fail "stale Traefik chart-40 logging/file-provider key detected"; fi
k rollout status deployment/edge-waf -n platform-edge --timeout=30s >/dev/null
[[ "$(k get pod -n platform-edge -l app.kubernetes.io/name=edge-waf -o jsonpath='{.items[0].spec.serviceAccountName}')" == edge-waf ]] || fail "WAF SA mismatch"
[[ "$(k get deployment edge-waf -n platform-edge -o jsonpath='{.spec.replicas}')" == 1 ]] || fail "WAF single-server profile must have one replica"
k get networkpolicy edge-waf -n platform-edge >/dev/null
waf_image=$(k get deployment edge-waf -n platform-edge -o jsonpath='{.spec.template.spec.containers[0].image}')
[[ "$waf_image" == *@sha256:* ]] || fail "WAF image is not immutable: $waf_image"
waf_version=$(k exec -n platform-edge deploy/edge-waf -- caddy version)
[[ "$waf_version" == v2.11.4* ]] || fail "Caddy version mismatch: $waf_version"
build_info=$(k exec -n platform-edge deploy/edge-waf -- caddy build-info)
[[ "$build_info" == *$'github.com/corazawaf/coraza-caddy/v2\tv2.5.0'* ]] || fail "coraza-caddy 2.5.0 missing"
[[ "$build_info" == *$'github.com/corazawaf/coraza/v3\tv3.7.0'* ]] || fail "Coraza 3.7.0 missing"
k exec -n platform-edge deploy/edge-waf -- grep -q 'OWASP CRS ver.4.25.1' /etc/coraza/crs/crs-setup.conf.example
for i in $(seq 1 30); do
  gc=$(k get gatewayclass hooshix-traefik -o jsonpath='{.status.conditions[?(@.type=="Accepted")].status}' 2>/dev/null || true)
  gw=$(k get gateway hooshix-public -n traefik-system -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}' 2>/dev/null || true)
  rt=$(k get httproute hooshix-public -n platform-edge -o jsonpath='{.status.parents[0].conditions[?(@.type=="Accepted")].status}' 2>/dev/null || true)
  [[ "$gc/$gw/$rt" == "True/True/True" ]] && break
  sleep 1
done
[[ "$gc/$gw/$rt" == "True/True/True" ]] || fail "Gateway status not ready: $gc/$gw/$rt"
route_json=$(k get httproute hooshix-public -n platform-edge -o json)
ROUTE_JSON="$route_json" python3 - <<'PY'
import json,os,sys
r=json.loads(os.environ['ROUTE_JSON'])
h=r['spec'].get('hostnames') or []
if h != ['hooshix.local']: raise SystemExit('public HTTPRoute hostname is not exact hooshix.local')
refs=[b.get('name') for rule in r['spec'].get('rules',[]) for b in rule.get('backendRefs',[])]
if refs != ['edge-waf'] or 'web-bff' in refs: raise SystemExit('public HTTPRoute must point only to edge-waf')
PY
code=$(curl -sk --resolve hooshix.local:8443:127.0.0.1 -o /tmp/hooshix-waf-block.body -D /tmp/hooshix-waf-block.headers -w '%{http_code}' https://hooshix.local:8443/ -H 'X-HooshiX-WAF-Test: block')
[[ "$code" == 403 ]] || fail "controlled WAF request expected 403, got $code"
grep -qi '^X-HooshiX-WAF-Blocked: true' /tmp/hooshix-waf-block.headers || fail "controlled WAF marker missing"
echo "Traefik/Gateway/WAF foundation verification PASSED"
