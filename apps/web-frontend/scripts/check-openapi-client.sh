#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(git rev-parse --show-toplevel)
CONTRACT="${ROOT_DIR}/services/web-bff/contracts/openapi.yaml"
COMMITTED="${ROOT_DIR}/apps/web-frontend/src/api/generated/schema.ts"

if ! command -v openapi-typescript >/dev/null 2>&1; then
  echo "openapi-typescript is required" >&2
  exit 1
fi

CHECK_DIR=$(mktemp -d)
trap 'rm -rf "${CHECK_DIR}"' EXIT

openapi-typescript "${CONTRACT}" -o "${CHECK_DIR}/schema.ts"
if ! cmp --silent "${COMMITTED}" "${CHECK_DIR}/schema.ts"; then
  echo "Generated BFF OpenAPI types are stale; run npm run generate:api." >&2
  diff --unified "${COMMITTED}" "${CHECK_DIR}/schema.ts" || true
  exit 1
fi
