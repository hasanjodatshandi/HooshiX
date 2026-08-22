#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
state_dir="$ROOT/.platform-runtime/staging"
mkdir -p "$state_dir"
chmod 700 "$ROOT/.platform-runtime" "$state_dir"
snapshot=$(python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" snapshot)
revision=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["revision"])' <<<"$snapshot")
source_state=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["source_state"])' <<<"$snapshot")
worktree_sha=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["worktree_sha256"])' <<<"$snapshot")
state="$state_dir/images.env"
printf 'BUILD_GIT_REVISION=%s\nBUILD_SOURCE_STATE=%s\nBUILD_WORKTREE_SHA256=%s\n' "$revision" "$source_state" "$worktree_sha" > "$state"
chmod 600 "$state"
for service in compromised-password-service notification-service authorization-service identity-service web-bff; do
  "$ROOT/scripts/platform/staging_build_image.sh" "$service"
done
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$revision" --source-state "$source_state" --worktree-sha256 "$worktree_sha" >/dev/null
