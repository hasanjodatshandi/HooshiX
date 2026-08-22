#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
state="$ROOT/.platform-runtime/staging/images.env"
[[ -f "$state" ]] || fail "staging image provenance state is missing; run staging-build"
source "$state"
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$BUILD_GIT_REVISION" --source-state "$BUILD_SOURCE_STATE" --worktree-sha256 "$BUILD_WORKTREE_SHA256" >/dev/null
k apply -f "$ROOT/infrastructure/istio/platform-apps-waypoint.yaml"
k apply -f "$ROOT/infrastructure/istio/platform-apps-waypoint-networkpolicy.yaml"
k wait --for=condition=Programmed gateway/platform-apps-waypoint -n platform-apps --timeout=60s
for service in compromised-password-service notification-service authorization-service identity-service web-bff; do "$ROOT/scripts/platform/staging_deploy_service.sh" "$service"; done
