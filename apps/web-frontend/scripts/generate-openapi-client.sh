#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(git rev-parse --show-toplevel)
CONTRACT="${ROOT_DIR}/services/web-bff/contracts/openapi.yaml"
OUTPUT="${ROOT_DIR}/apps/web-frontend/src/api/generated"

if ! command -v openapi-typescript >/dev/null 2>&1; then
  echo "openapi-typescript is required" >&2
  exit 1
fi

openapi-typescript "${CONTRACT}" -o "${OUTPUT}/schema.ts"
