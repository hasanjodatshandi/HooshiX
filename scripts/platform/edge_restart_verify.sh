#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

before=$(k get pod -n traefik-system -l app.kubernetes.io/name=traefik -o jsonpath='{.items[0].metadata.uid}')
k rollout restart deployment/traefik -n traefik-system >/dev/null
k rollout status deployment/traefik -n traefik-system --timeout=80s >/dev/null
after=$(k get pod -n traefik-system -l app.kubernetes.io/name=traefik -o jsonpath='{.items[0].metadata.uid}')
[[ -n "$before" && -n "$after" && "$before" != "$after" ]] || fail "Traefik restart did not replace its pod"

code=000
for _ in $(seq 1 30); do
  code=$(curl -sk --resolve hooshix.local:8443:127.0.0.1 -o /dev/null -w '%{http_code}' \
    https://hooshix.local:8443/ -H 'X-HooshiX-WAF-Test: block' || true)
  [[ "$code" == 403 ]] && break
  sleep 1
done
[[ "$code" == 403 ]] || fail "Traefik did not restore the WAF route after restart; got $code"
"$ROOT/scripts/platform/edge_foundation_verify.sh"
echo "Traefik exact-API-egress restart recovery verification PASSED"
