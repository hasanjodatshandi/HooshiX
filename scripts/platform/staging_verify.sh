#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
state="$ROOT/.platform-runtime/staging/images.env"
[[ -f "$state" ]] || fail "staging image provenance state is missing; run staging-build"
source "$state"
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$BUILD_GIT_REVISION" --source-state "$BUILD_SOURCE_STATE" --worktree-sha256 "$BUILD_WORKTREE_SHA256" >/dev/null
for service in compromised-password-service notification-service authorization-service identity-service conversation-service web-bff; do
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
for policy in authorization-service-waypoint authorization-service-ztunnel conversation-service-waypoint conversation-service-ztunnel identity-service-waypoint identity-service-ztunnel web-bff-waypoint web-bff-ztunnel; do
  if [[ "$policy" == *-waypoint ]]; then condition=WaypointAccepted; else condition=ZtunnelAccepted; fi
  accepted=$(k get authorizationpolicy "$policy" -n platform-apps -o "jsonpath={.status.conditions[?(@.type==\"$condition\")].status}:{.status.conditions[?(@.type==\"$condition\")].reason}" 2>/dev/null || true)
  [[ "$accepted" == True:Accepted ]] || fail "$policy is not accepted: $accepted"
done
google_values="$ROOT/.platform-runtime/staging/private/web-bff-google-values.yaml"
if [[ -f "$google_values" ]]; then
  google_enabled=$(k get deployment web-bff -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="WEB_BFF_GOOGLE_OIDC_ENABLED")].value}')
  [[ "$google_enabled" == true ]] || fail "staging Google OIDC local configuration is present but not enabled"
  k get secret web-bff-google-client -n platform-apps >/dev/null
  k get serviceentry web-bff-google-oidc -n platform-apps >/dev/null
fi
k rollout status deployment/platform-apps-waypoint -n platform-apps --timeout=30s >/dev/null
[[ "$(k get gateway platform-apps-waypoint -n platform-apps -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}')" == True ]] || fail "platform-apps waypoint is not Programmed"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
api=$(curl -skS --resolve localhost:8443:127.0.0.1 -o "$tmp/api" -w '%{http_code}' https://localhost:8443/api/v1/does-not-exist)
api_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("code","MISSING"))' "$tmp/api")
[[ "$api/$api_code" == 404/NOT_FOUND ]] || fail "BFF bounded unknown API mismatch: $api/$api_code"
curl -skS --resolve localhost:8443:127.0.0.1 -c "$tmp/cookies" -D "$tmp/h" -o "$tmp/bootstrap" -X POST https://localhost:8443/api/v1/auth/session/bootstrap -H 'Origin: https://localhost:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' -H 'Content-Length: 0'
status=$(awk 'NR==1{print $2}' "$tmp/h"); mode=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("mode","MISSING"))' "$tmp/bootstrap"); csrf=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("csrfToken",""))' "$tmp/bootstrap")
[[ "$status/$mode" == 201/PREAUTH && -n "$csrf" ]] || fail "browser bootstrap mismatch: $status/$mode"
login=$(curl -skS --resolve localhost:8443:127.0.0.1 -b "$tmp/cookies" -o "$tmp/login" -w '%{http_code}' -X POST https://localhost:8443/api/v1/auth/local -H 'Origin: https://localhost:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' -H "X-CSRF-Token: $csrf" -H 'X-Request-Id: 4af88179-f470-4bb8-a834-4fd8f62037d4' -H 'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440001' -H 'Content-Type: application/json' --data '{"channel":"EMAIL","contact":"staging-unknown@example.invalid","password":"StagingSmokeOnly-NotARealCredential-123!"}')
login_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("code","MISSING"))' "$tmp/login")
[[ "$login/$login_code" == 401/AUTHENTICATION_FAILED ]] || fail "BFF -> Identity negative smoke mismatch: $login/$login_code"
pg=$(k get pod -n platform-data -l app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].metadata.name}')
for spec in 'authorization 4 authorization_migration' 'conversation 6 conversation_migration' 'identity 14 identity_migration' 'notification 7 notification_migration' 'web_bff 1 web_bff_migration'; do set -- $spec; db=$1; expected=$2; owner=$3; n=$(k exec -n platform-data "$pg" -- psql -U postgres -d "$db" -Atc 'select count(*) from flyway_schema_history where success'); [[ "$n" == "$expected" ]] || fail "$db Flyway count mismatch: $n"; actual=$(k exec -n platform-data "$pg" -- psql -U postgres -d postgres -Atc "select pg_get_userbyid(datdba) from pg_database where datname='$db'"); [[ "$actual" == "$owner" ]] || fail "$db owner mismatch: $actual"; done
matrix=$(k exec -n platform-data "$pg" -- psql -U postgres -d postgres -Atc "select rolname||':'||has_database_privilege(rolname,'authorization','CONNECT')||':'||has_database_privilege(rolname,'conversation','CONNECT')||':'||has_database_privilege(rolname,'identity','CONNECT')||':'||has_database_privilege(rolname,'notification','CONNECT')||':'||has_database_privilege(rolname,'web_bff','CONNECT') from pg_roles where rolname in ('authorization_runtime','conversation_runtime','identity_runtime','notification_runtime','web_bff_runtime') order by rolname")
expected_matrix=$'authorization_runtime:true:false:false:false:false\nconversation_runtime:false:true:false:false:false\nidentity_runtime:false:false:true:false:false\nnotification_runtime:false:false:false:true:false\nweb_bff_runtime:false:false:false:false:true'
[[ "$matrix" == "$expected_matrix" ]] || fail "runtime database CONNECT isolation mismatch"
provider_enabled=$(k get deployment conversation-service -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="CONVERSATION_PROVIDER_RUNTIME_ENABLED")].value}')
provider_canary=$(k get deployment conversation-service -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="CONVERSATION_PROVIDER_CANARY_PERCENT")].value}')
[[ "$provider_enabled/$provider_canary" == true/1 ]] || fail "Conversation staging canary mismatch: $provider_enabled/$provider_canary"
k get secret conversation-provider -n platform-apps >/dev/null
authorization_tenant_rls=$(k exec -n platform-data "$pg" -- psql -U postgres -d authorization -Atc "SELECT relrowsecurity||':'||relforcerowsecurity FROM pg_class WHERE oid='authorization_tenant_projection'::regclass")
[[ "$authorization_tenant_rls" == true:true ]] || fail "authorization tenant projection forced RLS mismatch: $authorization_tenant_rls"
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
mounted_dataset_profile=$(docker exec platform-local-worker cat /var/local/hooshix/compromised-password/release-manifest.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print("{}:{}:{}".format(d["source_kind"], d["prefix_cardinality_bound"], d["serialized_response_bytes_bound"]))')
deployed_dataset_kind=$(k get deployment compromised-password-service -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="HOOSHIX_COMPROMISED_PASSWORD_DATASET_REQUIRED_SOURCE_KIND")].value}')
deployed_prefix_bound=$(k get deployment compromised-password-service -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="HOOSHIX_COMPROMISED_PASSWORD_DATASET_MAX_PREFIX_CARDINALITY")].value}')
deployed_response_bound=$(k get deployment compromised-password-service -n platform-apps -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="HOOSHIX_COMPROMISED_PASSWORD_DATASET_MAX_SERIALIZED_RESPONSE_BYTES")].value}')
[[ "$mounted_dataset_profile" == "$deployed_dataset_kind:$deployed_prefix_bound:$deployed_response_bound" ]] || fail "Compromised Password deployed source/compatibility profile does not match mounted manifest"
ready=$(k get --raw='/readyz' | tail -1); [[ "$ready" == ok ]] || fail "Kubernetes API readyz is not ok"
mount_source=$(docker inspect platform-local-control-plane --format '{{range .Mounts}}{{if eq .Destination "/var/lib/etcd"}}{{.Source}}{{end}}{{end}}')
[[ "$mount_source" == '/dev/shm/hooshix-kind/etcd' ]] || fail "kind etcd is not bind-mounted from the reviewed WSL tmpfs path: $mount_source"
[[ "$(findmnt -n -o FSTYPE /dev/shm)" == tmpfs ]] || fail "/dev/shm is not tmpfs on the WSL host"
echo "Six-service staging and persistence verification PASSED"
