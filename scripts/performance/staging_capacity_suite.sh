#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
output_root=${1:-"${root}/.platform-runtime/stage7/capacity"}
base_url=${HOOSHIX_CAPACITY_BASE_URL:-https://hooshix.local:8443}
connect_host=${HOOSHIX_CAPACITY_CONNECT_HOST:-127.0.0.1}
runner="${root}/scripts/performance/stack_capacity.py"

mkdir -p "${output_root}"
python3 "${runner}" run \
  --base-url "${base_url}" \
  --connect-host "${connect_host}" \
  --insecure-local-staging \
  --profile staging-single-server \
  --mode load \
  --scenario invalid-login \
  --duration-seconds 60 \
  --concurrency 16 \
  --p99-limit-ms 1000 \
  --max-consecutive-swap-active-samples 5 \
  --output "${output_root}/load-invalid-login.json"
python3 "${runner}" run \
  --base-url "${base_url}" \
  --connect-host "${connect_host}" \
  --insecure-local-staging \
  --profile staging-single-server \
  --mode soak \
  --scenario session-bootstrap \
  --duration-seconds 1800 \
  --concurrency 8 \
  --p99-limit-ms 750 \
  --max-consecutive-swap-active-samples 5 \
  --output "${output_root}/soak-session-bootstrap.json"
python3 "${runner}" verify "${output_root}/load-invalid-login.json"
python3 "${runner}" verify "${output_root}/soak-session-bootstrap.json"
echo "Staging load and soak capacity evidence PASSED"
