#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/registry/pins.env"
docker rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
