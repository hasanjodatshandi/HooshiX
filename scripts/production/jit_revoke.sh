#!/usr/bin/env bash
set -Eeuo pipefail
GRANT_ID="${1:-}"
[[ "$GRANT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$ ]] || exit 2
rm -f -- "/etc/sudoers.d/hooshix-jit-$GRANT_ID" "/run/hooshix/jit/$GRANT_ID.sudoers"
logger -t hooshix-jit "grant revoked grant_id=$GRANT_ID"
