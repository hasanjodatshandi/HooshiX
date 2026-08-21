#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
source "$ROOT/.platform-runtime/staging/images.env"
for service in compromised-password-service notification-service authorization-service identity-service web-bff; do
  status=$(h status "$service" -n platform-apps -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["info"]["status"])')
  [[ "$status" == deployed ]] || fail "$service Helm release is not deployed: $status"
  k rollout status deployment/$service -n platform-apps --timeout=30s >/dev/null
  sa=$(k get pod -n platform-apps -l "app.kubernetes.io/name=$service" -o jsonpath='{.items[0].spec.serviceAccountName}')
  [[ "$sa" == "$service" ]] || fail "$service ServiceAccount mismatch: $sa"
  img=$(k get deployment "$service" -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].image}')
  key=$(echo "$service" | tr '[:lower:]-' '[:upper:]_'); repo_var="${key}_REPOSITORY"; digest_var="${key}_DIGEST"
  expected_image="${!repo_var}@${!digest_var}"
  [[ "$img" == "$expected_image" ]] || fail "$service exact image mismatch: $img"
done
for policy in authorization-service-waypoint authorization-service-ztunnel web-bff-waypoint web-bff-ztunnel; do
  accepted=$(k get authorizationpolicy "$policy" -n platform-apps -o jsonpath='{.status.conditions[?(@.type=="Accepted")].status}' 2>/dev/null || true)
  [[ -z "$accepted" || "$accepted" == True ]] || fail "$policy is not accepted: $accepted"
done
k rollout status deployment/platform-apps-waypoint -n platform-apps --timeout=30s >/dev/null
[[ "$(k get gateway platform-apps-waypoint -n platform-apps -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}')" == True ]] || fail "platform-apps waypoint is not Programmed"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
api=$(curl -skS --resolve hooshix.local:8443:127.0.0.1 -o "$tmp/api" -w '%{http_code}' https://hooshix.local:8443/api/v1/does-not-exist)
api_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("code","MISSING"))' "$tmp/api")
[[ "$api/$api_code" == 404/NOT_FOUND ]] || fail "BFF bounded unknown API mismatch: $api/$api_code"
curl -skS --resolve hooshix.local:8443:127.0.0.1 -c "$tmp/cookies" -D "$tmp/h" -o "$tmp/bootstrap" -X POST https://hooshix.local:8443/api/v1/auth/session/bootstrap -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' -H 'Content-Length: 0'
status=$(awk 'NR==1{print $2}' "$tmp/h"); mode=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("mode","MISSING"))' "$tmp/bootstrap"); csrf=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("csrfToken",""))' "$tmp/bootstrap")
[[ "$status/$mode" == 201/PREAUTH && -n "$csrf" ]] || fail "browser bootstrap mismatch: $status/$mode"
login=$(curl -skS --resolve hooshix.local:8443:127.0.0.1 -b "$tmp/cookies" -o "$tmp/login" -w '%{http_code}' -X POST https://hooshix.local:8443/api/v1/auth/local -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' -H "X-CSRF-Token: $csrf" -H 'X-Request-Id: 550e8400-e29b-41d4-a716-446655440001' -H 'Content-Type: application/json' --data '{"channel":"EMAIL","contact":"staging-unknown@example.invalid","password":"StagingSmokeOnly-NotARealCredential-123!"}')
login_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("code","MISSING"))' "$tmp/login")
[[ "$login/$login_code" == 401/AUTHENTICATION_FAILED ]] || fail "BFF -> Identity negative smoke mismatch: $login/$login_code"
pg=$(k get pod -n platform-data -l app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].metadata.name}')
for spec in 'authorization 2 authorization_migration' 'identity 4 identity_migration' 'notification 3 notification_migration'; do set -- $spec; db=$1; expected=$2; owner=$3; n=$(k exec -n platform-data "$pg" -- psql -U postgres -d "$db" -Atc 'select count(*) from flyway_schema_history where success'); [[ "$n" == "$expected" ]] || fail "$db Flyway count mismatch: $n"; actual=$(k exec -n platform-data "$pg" -- psql -U postgres -d postgres -Atc "select pg_get_userbyid(datdba) from pg_database where datname='$db'"); [[ "$actual" == "$owner" ]] || fail "$db owner mismatch: $actual"; done
matrix=$(k exec -n platform-data "$pg" -- psql -U postgres -d postgres -Atc "select rolname||':'||has_database_privilege(rolname,'authorization','CONNECT')||':'||has_database_privilege(rolname,'identity','CONNECT')||':'||has_database_privilege(rolname,'notification','CONNECT') from pg_roles where rolname in ('authorization_runtime','identity_runtime','notification_runtime') order by rolname")
expected_matrix=$'authorization_runtime:true:false:false\nidentity_runtime:false:true:false\nnotification_runtime:false:false:true'
[[ "$matrix" == "$expected_matrix" ]] || fail "runtime database CONNECT isolation mismatch"
template_privileges=$(k exec -n platform-data "$pg" -- psql -U postgres -d notification -Atc "SELECT table_name||':'||has_table_privilege('notification_runtime','public.'||table_name,'SELECT')||':'||has_table_privilege('notification_runtime','public.'||table_name,'INSERT')||':'||has_table_privilege('notification_runtime','public.'||table_name,'UPDATE')||':'||has_table_privilege('notification_runtime','public.'||table_name,'DELETE') FROM (VALUES ('notification_template_activation'),('notification_template_audit'),('notification_template_definition'),('notification_template_version')) AS t(table_name) ORDER BY table_name")
expected_template_privileges=$'notification_template_activation:true:false:false:false
notification_template_audit:true:false:false:false
notification_template_definition:true:false:false:false
notification_template_version:true:false:false:false'
[[ "$template_privileges" == "$expected_template_privileges" ]] || fail "notification runtime template privileges mismatch: $template_privileges"
dataset_state="$ROOT/.platform-runtime/staging/dataset.env"
[[ -f "$dataset_state" ]] || fail "generated staging dataset state is missing"
dataset_line=$(cat "$dataset_state"); [[ "$dataset_line" =~ ^COMPROMISED_PASSWORD_MANIFEST_SHA256=([0-9a-f]{64})$ ]] || fail "generated staging dataset state is invalid"
dataset_sha=${BASH_REMATCH[1]}
deployed_dataset_sha=$(k get deployment compromised-password-service -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="HOOSHIX_COMPROMISED_PASSWORD_DATASET_EXPECTED_MANIFEST_SHA256")].value}')
[[ "$deployed_dataset_sha" == "$dataset_sha" ]] || fail "Compromised Password deployed manifest digest does not match generated staging state"
mounted_dataset_sha=$(docker exec platform-local-worker sha256sum /var/local/hooshix/compromised-password/release-manifest.json | awk '{print $1}')
[[ "$mounted_dataset_sha" == "$dataset_sha" ]] || fail "Compromised Password mounted manifest digest mismatch"
ready=$(k get --raw='/readyz' | tail -1); [[ "$ready" == ok ]] || fail "Kubernetes API readyz is not ok"
mount_source=$(docker inspect platform-local-control-plane --format '{{range .Mounts}}{{if eq .Destination "/var/lib/etcd"}}{{.Source}}{{end}}{{end}}')
[[ "$mount_source" == '/dev/shm/hooshix-kind/etcd' ]] || fail "kind etcd is not bind-mounted from the reviewed WSL tmpfs path: $mount_source"
[[ "$(findmnt -n -o FSTYPE /dev/shm)" == tmpfs ]] || fail "/dev/shm is not tmpfs on the WSL host"
echo "Five-service staging and persistence verification PASSED"
