#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CLUSTER_NAME=platform-local
KUBE_CONTEXT=kind-platform-local
export PATH="$HOME/.local/bin:$HOME/.docker/cli-plugins:$PATH"

fail() { echo "ERROR: $*" >&2; exit 1; }
load_env() { set -a; source "$1"; set +a; }
verify_sha() { local expected=$1 file=$2; printf '%s  %s\n' "$expected" "$file" | sha256sum --check --strict >/dev/null; }
require_command() { command -v "$1" >/dev/null || fail "required command not found: $1"; }
require_exact_line() { local actual=$1 expected=$2 label=$3; [[ "$actual" == *"$expected"* ]] || fail "$label mismatch: expected $expected, found $actual"; }
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }
h() { helm --kube-context "$KUBE_CONTEXT" "$@"; }
