#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
for service in compromised-password-service notification-service authorization-service identity-service web-bff; do "$ROOT/scripts/platform/staging_build_image.sh" "$service"; done
