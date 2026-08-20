#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
k apply -f "$ROOT/infrastructure/istio/platform-apps-waypoint.yaml"
k apply -f "$ROOT/infrastructure/istio/platform-apps-waypoint-networkpolicy.yaml"
k wait --for=condition=Programmed gateway/platform-apps-waypoint -n platform-apps --timeout=60s
for service in compromised-password-service notification-service authorization-service identity-service web-bff; do "$ROOT/scripts/platform/staging_deploy_service.sh" "$service"; done
