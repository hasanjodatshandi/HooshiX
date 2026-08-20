#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
[[ "$(sysctl -n fs.inotify.max_user_watches)" -ge 524288 ]] || fail "fs.inotify.max_user_watches must be at least 524288"
[[ "$(sysctl -n fs.inotify.max_user_instances)" -ge 512 ]] || fail "fs.inotify.max_user_instances must be at least 512"
echo "kind/WSL inotify prerequisite verification PASSED"
