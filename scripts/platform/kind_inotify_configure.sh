#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_command sudo
file=/etc/sysctl.d/99-hooshix-kind.conf
printf '%s\n' 'fs.inotify.max_user_watches=524288' 'fs.inotify.max_user_instances=512' | sudo tee "$file" >/dev/null
sudo sysctl --system >/dev/null
"$ROOT/scripts/platform/kind_inotify_verify.sh"
