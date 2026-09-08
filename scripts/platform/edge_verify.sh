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
code=$(curl -sk --resolve hooshix.local:8443:127.0.0.1 -o /dev/null -w '%{http_code}' \
  "https://hooshix.local:8443/?canary=$secret_canary" \
  -H 'X-HooshiX-WAF-Test: block' -H "Cookie: canary=$secret_canary" \
  -H "Authorization: Bearer $secret_canary")
[[ "$code" == 403 ]] || fail "blocked-request privacy canary expected 403, got $code"
if k logs -n traefik-system deploy/traefik --since=2m 2>/dev/null | grep -Fq "$secret_canary"; then fail "Traefik logs exposed the secret canary"; fi
if k logs -n platform-edge deploy/edge-waf --since=2m 2>/dev/null | grep -Fq "$secret_canary"; then fail "WAF logs exposed the secret canary"; fi
k logs -n traefik-system deploy/traefik --since=2m | python3 -c '
import json, sys
events = [json.loads(line) for line in sys.stdin if line.strip()]
access = [e for e in events if "DownstreamStatus" in e]
safe = {"level", "time", "msg", "DownstreamStatus", "Duration", "OriginStatus", "OriginDuration", "Overhead", "RetryAttempts", "StartUTC", "RouterName", "ServiceName", "entryPointName"}
assert access and all(set(e) <= safe for e in access), "Traefik access-log fields are not allowlisted"
'
k logs -n platform-edge deploy/edge-waf --since=2m | python3 -c '
import json, sys
events = [json.loads(line) for line in sys.stdin if line.strip()]
safe = {"level", "ts", "logger", "msg", "rule_id", "severity", "status"}
waf = [e for e in events if e.get("logger") == "http.handlers.waf"]
assert waf and all(set(e) <= safe for e in waf), "WAF log fields are not allowlisted"
assert any(e.get("msg") == "waf_rule_match" and e.get("rule_id") == 1000001 for e in waf), "safe controlled-rule evidence missing"
assert any(e.get("msg") == "waf_request_blocked" and e.get("status") == 403 for e in waf), "safe block evidence missing"
'
"$ROOT/scripts/platform/edge_restart_verify.sh"
echo "Traefik/Gateway/WAF full integration verification PASSED"
