#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/edge_foundation_verify.sh"
"$ROOT/scripts/platform/mesh_identity_verify.sh"
code=$(curl -sk --resolve hooshix.local:8443:127.0.0.1 -o /dev/null -w '%{http_code}' -X POST https://hooshix.local:8443/api/v1/auth/session/bootstrap -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty')
[[ "$code" == 201 ]] || fail "public Traefik -> WAF -> BFF bootstrap expected 201, got $code"
http_code=$(curl -sS --resolve hooshix.local:8080:127.0.0.1 -o /dev/null -w '%{http_code}' http://hooshix.local:8080/ || true)
[[ "$http_code" == 404 ]] || fail "local HTTP listener must not expose the application route; expected 404, got $http_code"
secret_canary=$(openssl rand -hex 24)
curl -sk --resolve hooshix.local:8443:127.0.0.1 -o /dev/null -X POST https://hooshix.local:8443/api/v1/auth/session/bootstrap -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' -H "Authorization: Bearer $secret_canary" -H "X-HooshiX-Secret-Canary: $secret_canary" || true
if k logs -n traefik-system deploy/traefik --since=2m 2>/dev/null | grep -Fq "$secret_canary"; then fail "Traefik logs exposed the secret canary"; fi
if k logs -n platform-edge deploy/edge-waf --since=2m 2>/dev/null | grep -Fq "$secret_canary"; then fail "WAF logs exposed the secret canary"; fi
echo "Traefik/Gateway/WAF full integration verification PASSED"
