#!/usr/bin/env bash
set -Eeuo pipefail

# Run on the VPS only after copying this repository's production host package.
# It never accepts a password/secret argument and refuses incomplete external
# audit or JIT inputs. Use --dry-run first; --apply is intentionally explicit.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANAGEMENT_INTERFACE="wg-hooshix"
MANAGEMENT_ADDRESS="10.77.47.1"
AUDIT_SINK_CONFIG=""
ALLOWED_SIGNERS=""
APPLY=0
CLOSE_PUBLIC_SSH=0

usage() {
  cat <<'EOF'
Usage: commission_host.sh [--dry-run] [--apply] [--close-public-ssh]
  [--management-interface NAME] [--management-address IPv4]
  --audit-sink-config PATH --allowed-signers PATH
EOF
}

while (($#)); do
  case "$1" in
    --dry-run) APPLY=0; shift ;;
    --apply) APPLY=1; shift ;;
    --close-public-ssh) CLOSE_PUBLIC_SSH=1; shift ;;
    --management-interface) MANAGEMENT_INTERFACE="$2"; shift 2 ;;
    --management-address) MANAGEMENT_ADDRESS="$2"; shift 2 ;;
    --audit-sink-config) AUDIT_SINK_CONFIG="$2"; shift 2 ;;
    --allowed-signers) ALLOWED_SIGNERS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "COMMISSION_STOPPED: unknown option" >&2; usage >&2; exit 2 ;;
  esac
done

[[ $EUID -eq 0 ]] || { echo "COMMISSION_STOPPED: root_required" >&2; exit 1; }
command -v systemctl >/dev/null || { echo "COMMISSION_STOPPED: systemd_required" >&2; exit 1; }
command -v sshd >/dev/null || { echo "COMMISSION_STOPPED: openssh_server_required" >&2; exit 1; }
command -v ip >/dev/null || { echo "COMMISSION_STOPPED: iproute2_required" >&2; exit 1; }
ip -4 address show dev "$MANAGEMENT_INTERFACE" | grep -Fq "$MANAGEMENT_ADDRESS" || {
  echo "COMMISSION_STOPPED: management_address_not_present" >&2; exit 1;
}

if [[ -z "$AUDIT_SINK_CONFIG" || ! -f "$AUDIT_SINK_CONFIG" ]]; then
  echo "COMMISSION_STOPPED: external_audit_sink_config_required" >&2; exit 1
fi
if grep -Eq 'AUDIT_SINK_(HOST|PORT|NAME)_REQUIRED|TODO|TBD|CHANGE_ME' "$AUDIT_SINK_CONFIG"; then
  echo "COMMISSION_STOPPED: external_audit_sink_config_contains_placeholder" >&2; exit 1
fi
[[ -n "$ALLOWED_SIGNERS" && -f "$ALLOWED_SIGNERS" ]] || {
  echo "COMMISSION_STOPPED: jit_allowed_signers_required" >&2; exit 1;
}

sshd -t
install -d -m 0750 /var/backups/hooshix-commissioning
BACKUP="/var/backups/hooshix-commissioning/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 0700 "$BACKUP"
cp -a /etc/ssh "$BACKUP/ssh"
[[ -f /etc/audit/rules.d/hooshix.rules ]] && cp -a /etc/audit/rules.d/hooshix.rules "$BACKUP/" || true
[[ -d /etc/systemd/system/ssh.socket.d ]] && cp -a /etc/systemd/system/ssh.socket.d "$BACKUP/" || true

if (( ! APPLY )); then
  echo "COMMISSION_PREFLIGHT=PASSED"
  echo "BACKUP_PATH=$BACKUP"
  echo "APPLY_REQUIRED=--apply"
  exit 0
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends auditd audispd-plugins rsyslog-gnutls

install -D -m 0640 "$ROOT/infrastructure/production/host/audit.rules" /etc/audit/rules.d/hooshix.rules
install -D -m 0640 "$AUDIT_SINK_CONFIG" /etc/rsyslog.d/60-hooshix-audit-tls.conf
install -D -m 0644 "$ROOT/infrastructure/production/host/jit-policy.json" /etc/hooshix/jit/policy.json
install -D -m 0640 "$ALLOWED_SIGNERS" /etc/hooshix/jit/allowed_signers
install -D -m 0750 "$ROOT/scripts/production/jit_grant.py" /usr/local/libexec/hooshix-jit-grant.py
install -D -m 0750 "$ROOT/scripts/production/jit_activate.sh" /usr/local/libexec/hooshix-jit-activate.sh
install -D -m 0750 "$ROOT/scripts/production/jit_revoke.sh" /usr/local/libexec/hooshix-jit-revoke.sh
install -D -m 0644 "$ROOT/infrastructure/production/host/hooshix-jit-expire@.service" /etc/systemd/system/hooshix-jit-expire@.service

install -d -m 0755 /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/99-hooshix-management.conf <<EOF
# Managed by HooshiX commissioning; SSH is reachable only on WireGuard.
ListenAddress ${MANAGEMENT_ADDRESS}:22
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AuthenticationMethods publickey
AllowAgentForwarding no
AllowTcpForwarding no
X11Forwarding no
PermitTunnel no
GatewayPorts no
PermitUserEnvironment no
EOF

sshd -t
systemctl daemon-reload
systemctl enable --now auditd
augenrules --load
systemctl restart rsyslog

if (( CLOSE_PUBLIC_SSH )); then
  install -d -m 0755 /etc/systemd/system/ssh.socket.d
  cat > /etc/systemd/system/ssh.socket.d/99-hooshix-management.conf <<EOF
[Socket]
ListenStream=
ListenStream=${MANAGEMENT_ADDRESS}:22
EOF
  systemctl daemon-reload
  systemctl restart ssh.socket
  command -v nft >/dev/null || { echo "COMMISSION_STOPPED: nft_required_for_public_ssh_denial" >&2; exit 1; }
  nft list chain inet filter input >/dev/null 2>&1 || {
    echo "COMMISSION_STOPPED: expected_nft_input_chain_missing" >&2; exit 1;
  }
  if ! nft list chain inet filter input | grep -Fq 'tcp dport { 22, 22022, 2222 }'; then
    nft insert rule inet filter input iifname != "${MANAGEMENT_INTERFACE}" tcp dport { 22, 22022, 2222 } counter drop
  fi
fi

sshd -t
echo "COMMISSION_APPLIED=LOCAL_AUDIT_AND_SSH_POLICY"
echo "AUDIT_EXTERNAL_DELIVERY=CONFIGURED_BUT_RECEIVE_TEST_REQUIRED"
echo "JIT_GRANT_VERIFIER=INSTALLED_APPROVAL_KEYS_EXTERNAL"
echo "BACKUP_PATH=$BACKUP"
