#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
restore(){ k scale deployment/tempo deployment/loki -n platform-observability --replicas=1 >/dev/null 2>&1 || true; }
trap restore EXIT
smoke(){ local code; code=$(curl -sk --resolve hooshix.local:8443:127.0.0.1 -o /dev/null -w '%{http_code}' -X POST https://hooshix.local:8443/api/v1/auth/session/bootstrap -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty'); [[ "$code" == 201 ]] || fail "application failed during telemetry backend outage: HTTP $code"; for d in compromised-password-service notification-service authorization-service identity-service web-bff; do k rollout status deployment/$d -n platform-apps --timeout=20s >/dev/null; done; }
k scale deployment/tempo -n platform-observability --replicas=0 >/dev/null
k wait --for=delete pod -n platform-observability -l app.kubernetes.io/name=tempo --timeout=45s >/dev/null || true
smoke
k scale deployment/tempo -n platform-observability --replicas=1 >/dev/null
k rollout status deployment/tempo -n platform-observability --timeout=60s >/dev/null
k scale deployment/loki -n platform-observability --replicas=0 >/dev/null
k wait --for=delete pod -n platform-observability -l app.kubernetes.io/name=loki --timeout=45s >/dev/null || true
smoke
k scale deployment/loki -n platform-observability --replicas=1 >/dev/null
k rollout status deployment/loki -n platform-observability --timeout=60s >/dev/null
trap - EXIT
echo "Telemetry backend outage non-authority verification PASSED"
