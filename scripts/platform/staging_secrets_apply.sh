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
for n in postgres-admin redis-health redis-verify redis-acl; do secret_dir platform-data "$n" "$F/$n"; done
secret_dir platform-observability grafana-admin "$F/grafana-admin"
for n in authorization-db-migration authorization-db-runtime identity-db-migration identity-db-runtime notification-db-migration notification-db-runtime web-bff-db-migration web-bff-db-runtime; do secret_dir platform-data "$n" "$F/$n"; done
for n in authorization-db-migration authorization-db-runtime identity-db-migration identity-db-runtime notification-db-migration notification-db-runtime web-bff-db-migration web-bff-db-runtime authorization-quota-redis identity-quota-redis web-bff-redis authorization-fingerprint authorization-quota-key identity-fingerprint identity-challenge identity-handoff identity-mfa identity-quota identity-refresh identity-jwt-private notification-fingerprint notification-delivery web-bff-locator web-bff-csrf web-bff-refresh web-bff-quota; do secret_dir platform-apps "$n" "$F/$n"; done
k -n platform-apps create configmap identity-jwt-public --from-file=verifier.properties="$F/identity-jwt-public/verifier.properties" --dry-run=client -o yaml | k apply -f - >/dev/null
k -n platform-apps create configmap authorization-identity-jwt --from-file=verifier.properties="$F/identity-jwt-public/verifier.properties" --dry-run=client -o yaml | k apply -f - >/dev/null
for n in authorization identity web-bff; do k -n platform-apps create configmap "$n-host-time" --from-literal=host-time-synchronized=synchronized --dry-run=client -o yaml | k apply -f - >/dev/null; done
echo 'staging secrets/config created without printing secret values'
