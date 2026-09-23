#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/staging_prepare.py" >/dev/null
F="$ROOT/.platform-runtime/staging/files"
secret_dir() {
  local ns=$1 name=$2 dir=$3
  local args=(); while IFS= read -r -d '' f; do args+=(--from-file="$(basename "$f")=$f"); done < <(find "$dir" -maxdepth 1 -type f -print0 | sort -z)
  k -n "$ns" create secret generic "$name" "${args[@]}" --dry-run=client -o yaml | k apply -f - >/dev/null
}
verify_private_file() {
  local path=$1 label=$2 parent
  parent=$(dirname "$path")
  [[ -d "$parent" && ! -L "$parent" ]] || fail "$label parent must be a regular directory"
  [[ "$(stat -c '%u' "$parent")" == "$(id -u)" && "$(stat -c '%a' "$parent")" == "700" ]] || fail "$label parent must be user-owned mode 0700"
  [[ -f "$path" && ! -L "$path" ]] || fail "$label must be a regular non-symlink file"
  [[ "$(stat -c '%u' "$path")" == "$(id -u)" ]] || fail "$label must be owned by the invoking user"
  [[ "$(stat -c '%a' "$path")" == "600" ]] || fail "$label must have mode 0600"
}
for n in postgres-admin redis-health redis-verify redis-acl; do secret_dir platform-data "$n" "$F/$n"; done
secret_dir platform-observability grafana-admin "$F/grafana-admin"
for n in authorization-db-migration authorization-db-runtime conversation-db-migration conversation-db-runtime identity-db-migration identity-db-runtime notification-db-migration notification-db-runtime web-bff-db-migration web-bff-db-runtime; do secret_dir platform-data "$n" "$F/$n"; done
for n in authorization-db-migration authorization-db-runtime conversation-db-migration conversation-db-runtime identity-db-migration identity-db-runtime notification-db-migration notification-db-runtime web-bff-db-migration web-bff-db-runtime authorization-quota-redis identity-quota-redis web-bff-redis authorization-kafka conversation-kafka identity-kafka notification-kafka web-bff-kafka authorization-fingerprint authorization-quota-key conversation-content identity-fingerprint identity-challenge identity-handoff identity-mfa identity-quota identity-refresh identity-jwt-private notification-fingerprint notification-delivery web-bff-locator web-bff-csrf web-bff-refresh web-bff-quota; do secret_dir platform-apps "$n" "$F/$n"; done
conversation_provider="$ROOT/.platform-runtime/staging/private/openai-api-key"
if [[ -e "$conversation_provider" || -L "$conversation_provider" ]]; then
  verify_private_file "$conversation_provider" "staging Conversation provider key"
  k -n platform-apps create secret generic conversation-provider --from-file="api-key=$conversation_provider" --dry-run=client -o yaml | k apply -f - >/dev/null
  echo 'staging Conversation provider secret created without printing its value'
else
  echo 'staging Conversation provider secret skipped because no local credential file is present'
fi
provider_file="$ROOT/.platform-runtime/staging/private/notification-providers.properties"
if [[ -e "$provider_file" || -L "$provider_file" ]]; then
  verify_private_file "$provider_file" "staging provider configuration"
  k -n platform-apps create secret generic notification-providers --from-file="providers.properties=$provider_file" --dry-run=client -o yaml | k apply -f - >/dev/null
  echo 'staging notification provider secret created without printing secret values'
else
  echo 'staging notification provider secret skipped because no local credential file is present'
fi
google_oidc_json="$ROOT/.platform-runtime/staging/private/google-oidc-client.json"
google_oidc_secret="$ROOT/.platform-runtime/staging/private/web-bff-google-client-secret"
google_oidc_values="$ROOT/.platform-runtime/staging/private/web-bff-google-values.yaml"
if [[ -e "$google_oidc_json" || -L "$google_oidc_json" ]]; then
  verify_private_file "$google_oidc_json" "staging Google OIDC client JSON"
  python3 "$ROOT/scripts/platform/google_oidc_staging.py" --source "$google_oidc_json" --secret-output "$google_oidc_secret" --values-output "$google_oidc_values"
  echo 'staging Google OIDC client files derived without printing credential values'
fi
if [[ -e "$google_oidc_secret" || -L "$google_oidc_secret" ]]; then
  verify_private_file "$google_oidc_secret" "staging Google OIDC client secret"
  verify_private_file "$google_oidc_values" "staging Google OIDC values"
  k -n platform-apps create secret generic web-bff-google-client --from-file="client-secret=$google_oidc_secret" --dry-run=client -o yaml | k apply -f - >/dev/null
  echo 'staging Google OIDC client secret created without printing its value'
else
  echo 'staging Google OIDC client secret skipped because no local credential file is present'
fi
k -n platform-apps create configmap identity-jwt-public --from-file=verifier.properties="$F/identity-jwt-public/verifier.properties" --dry-run=client -o yaml | k apply -f - >/dev/null
k -n platform-apps create configmap authorization-identity-jwt --from-file=verifier.properties="$F/identity-jwt-public/verifier.properties" --dry-run=client -o yaml | k apply -f - >/dev/null
for n in authorization identity web-bff; do k -n platform-apps create configmap "$n-host-time" --from-literal=host-time-synchronized=synchronized --dry-run=client -o yaml | k apply -f - >/dev/null; done
echo 'staging secrets/config created without printing secret values'
