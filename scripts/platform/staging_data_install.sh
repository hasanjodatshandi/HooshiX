#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/staging_secrets_apply.sh"
k apply -f "$ROOT/infrastructure/staging/data.yaml"
k apply -f "$ROOT/infrastructure/staging/networkpolicy.yaml"
k apply -f "$ROOT/infrastructure/staging/authorizationpolicy.yaml"
k rollout status deployment/postgresql -n platform-data --timeout=70s
k rollout status deployment/security-redis -n platform-data --timeout=70s
k delete job postgres-bootstrap -n platform-data --ignore-not-found >/dev/null
k apply -f "$ROOT/infrastructure/staging/postgres-bootstrap.yaml"
k wait --for=condition=complete job/postgres-bootstrap -n platform-data --timeout=70s
"$ROOT/scripts/platform/staging_data_verify.sh"
