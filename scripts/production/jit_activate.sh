#!/usr/bin/env bash
set -Eeuo pipefail

# Grants only command IDs already present in the root-owned command map. The
# map is environment-specific and must be provisioned outside Git.
GRANT="${1:-}"
ALLOWED_SIGNERS="${2:-/etc/hooshix/jit/allowed_signers}"
COMMAND_ID="${3:-}"
MAP="/etc/hooshix/jit/command-map.json"
RUNTIME="/run/hooshix/jit"

[[ $EUID -eq 0 ]] || { echo "JIT_ACTIVATE_REJECTED: root_required" >&2; exit 1; }
[[ -f "$GRANT" && -f "$ALLOWED_SIGNERS" && -f "$MAP" ]] || {
  echo "JIT_ACTIVATE_REJECTED: required_policy_file_missing" >&2; exit 1;
}
[[ "$COMMAND_ID" =~ ^[a-z][a-z0-9_.-]{0,63}$ ]] || {
  echo "JIT_ACTIVATE_REJECTED: command_id_invalid" >&2; exit 1;
}

RECEIPT="$(mktemp)"
trap 'rm -f -- "$RECEIPT"' EXIT
/usr/bin/python3 /usr/local/libexec/hooshix-jit-grant.py "$GRANT" \
  --allowed-signers "$ALLOWED_SIGNERS" \
  --approval "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["approvals"][0]["identity"]+"="+json.load(open(sys.argv[1]))["approvals"][0]["signature"])' "$GRANT")" \
  --approval "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["approvals"][1]["identity"]+"="+json.load(open(sys.argv[1]))["approvals"][1]["signature"])' "$GRANT")" >"$RECEIPT"

IFS=$'\t' read -r SUBJECT EXPIRES SCOPE <<EOF
$(python3 -c 'import json,sys; g=json.load(open(sys.argv[1])); print(g["subject"],g["expires_at"]," ".join(g["scope"]))' "$GRANT")
EOF
python3 - "$COMMAND_ID" "$SCOPE" <<'PY' || {
import sys
if sys.argv[1] not in sys.argv[2].split():
    raise SystemExit(1)
PY
  echo "JIT_ACTIVATE_REJECTED: command_scope_not_granted" >&2; exit 1;
}
COMMAND="$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(m[sys.argv[2]])' "$MAP" "$COMMAND_ID" 2>/dev/null)" || {
  echo "JIT_ACTIVATE_REJECTED: command_not_in_root_owned_map" >&2; exit 1;
}
[[ "$COMMAND" =~ ^/[^[:cntrl:]]+$ && "$COMMAND" != *$'\n'* && "$COMMAND" != *';'* ]] || {
  echo "JIT_ACTIVATE_REJECTED: command_map_value_invalid" >&2; exit 1;
}
getent passwd "$SUBJECT" >/dev/null || { echo "JIT_ACTIVATE_REJECTED: subject_not_local_user" >&2; exit 1; }
mkdir -p -m 0700 "$RUNTIME"
GRANT_ID="$(python3 -c 'import json,sys,re; v=json.load(open(sys.argv[1]))["grant_id"]; print(v)' "$GRANT")"
[[ "$GRANT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$ ]] || { echo "JIT_ACTIVATE_REJECTED: grant_id_invalid" >&2; exit 1; }
POLICY="$RUNTIME/$GRANT_ID.sudoers"
printf '%s ALL=(root) NOPASSWD: %s\n' "$SUBJECT" "$COMMAND" >"$POLICY"
chown root:root "$POLICY"
chmod 0440 "$POLICY"
visudo -cf "$POLICY" >/dev/null
mkdir -p -m 0755 /etc/sudoers.d
ln -sfn "$POLICY" "/etc/sudoers.d/hooshix-jit-$GRANT_ID"
EXPIRES_EPOCH="$(date -d "$EXPIRES" +%s)"
NOW_EPOCH="$(date +%s)"
DELAY=$((EXPIRES_EPOCH - NOW_EPOCH))
(( DELAY > 0 )) || { rm -f -- "$POLICY" "/etc/sudoers.d/hooshix-jit-$GRANT_ID"; echo "JIT_ACTIVATE_REJECTED: grant_expired" >&2; exit 1; }
systemd-run --quiet --unit="hooshix-jit-expire-$GRANT_ID" --on-active="${DELAY}s" --collect /usr/local/libexec/hooshix-jit-revoke.sh "$GRANT_ID"
printf 'JIT_ACTIVATED grant_id=%s subject=%s expires_at=%s command_id=%s\n' "$GRANT_ID" "$SUBJECT" "$EXPIRES" "$COMMAND_ID"
