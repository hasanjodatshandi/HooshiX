#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/kind_delete.sh"
"$ROOT/scripts/platform/registry_down.sh"
echo "production-fidelity local platform stopped"
