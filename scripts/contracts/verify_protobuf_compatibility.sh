#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
  echo "usage: $0 <buf-binary> <baseline-image> [baseline-git-ref]" >&2
  exit 64
fi

buf_binary="$1"
baseline_image="$2"
baseline_ref="${3:-HEAD^}"
repository_root="$(git rev-parse --show-toplevel)"
current_proto="${repository_root}/contracts/protobuf-contracts/src/main/proto"
baseline_root="$(mktemp -d /tmp/hooshix-contract-baseline.XXXXXX)"
baseline_contract="${baseline_root}/contracts/protobuf-contracts"

cleanup() {
  rm -rf -- "${baseline_root}"
}
trap cleanup EXIT

if [[ ! -x "${buf_binary}" ]]; then
  echo "Buf binary is not executable: ${buf_binary}" >&2
  exit 64
fi

git -C "${repository_root}" archive "${baseline_ref}" -- contracts/protobuf-contracts \
  | tar -x -C "${baseline_root}"

if grep -Fq 'prepareBufDependencies' "${baseline_contract}/build.gradle.kts"; then
  "${repository_root}/services/identity-service/gradlew" \
    -p "${baseline_contract}" \
    prepareBufDependencies \
    --dependency-verification strict \
    --no-daemon \
    --no-configuration-cache
fi

(
  cd "${baseline_contract}"
  "${buf_binary}" build src/main/proto -o "${baseline_image}"
)

"${buf_binary}" breaking "${current_proto}" --against "${baseline_image}"
