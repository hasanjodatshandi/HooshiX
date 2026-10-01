#!/usr/bin/env bash
# Read-only VPS preflight. Run as the operator; sudo executes only pinned sshd.
set -euo pipefail

source_config=/etc/ssh/sshd_config
candidate=/home/hooshixadmin/.cache/hooshix-ssh-pr158/hooshix-sshd-candidate-jjjjc_d8.conf
source_sha=01c79f1385e2ec9b5b09e4995cfcbd2f6b21e6e05c7906a8a2b1f1b18a5c5fbb
candidate_sha=41076da5cb933c3be885c912a6b26ade7169c9c1f4dcba9a2e048d3cd08b9656

operator_uid=$(id -u)
operator_name=$(id -un)
if [[ ${operator_uid} == 0 ]]; then
  echo 'FAILED: run as the operator, not root' >&2
  exit 1
fi
candidate_owner_mode=$(stat -c '%a:%U' "${candidate}")
if [[ ${candidate_owner_mode} != "600:${operator_name}" ]]; then
  echo 'FAILED: candidate owner or mode changed' >&2
  exit 1
fi
printf '%s  %s\n%s  %s\n' \
  "${source_sha}" "${source_config}" "${candidate_sha}" "${candidate}" |
  sha256sum --check --status || { echo 'FAILED: source or candidate changed' >&2; exit 1; }
echo 'HASHES_AND_MODE=Passed'

sudo -v
sudo -n /usr/sbin/sshd -t -f "${candidate}"
echo 'CANDIDATE_SYNTAX=Passed'

for port in 22 22022; do
  connection="host=10.77.47.2,addr=10.77.47.2,laddr=10.77.47.1,lport=${port}"
  human=$(sudo -n /usr/sbin/sshd -T -f "${candidate}" -C "user=hooshixadmin,${connection}")
  for check in \
    permitrootlogin:no passwordauthentication:no kbdinteractiveauthentication:no \
    pubkeyauthentication:yes disableforwarding:yes allowagentforwarding:no \
    allowtcpforwarding:no allowstreamlocalforwarding:no x11forwarding:no \
    permittunnel:no gatewayports:no; do
    key=${check%%:*}
    expected=${check#*:}
    actual=$(awk -v key="${key}" '$1 == key {print $2}' <<<"${human}")
    if [[ ${actual} != "${expected}" ]]; then
      echo "FAILED: human policy ${key} on port ${port}" >&2
      exit 1
    fi
  done
  echo "HUMAN_PORT_${port}=Passed"

  tunnel_before=$(sudo -n /usr/sbin/sshd -T -C "user=hooshixtunnel,${connection}")
  tunnel_after=$(sudo -n /usr/sbin/sshd -T -f "${candidate}" -C "user=hooshixtunnel,${connection}")
  if [[ -z ${tunnel_before} || ${tunnel_before} != "${tunnel_after}" ]]; then
    echo "FAILED: tunnel account policy changed on port ${port}" >&2
    exit 1
  fi
  echo "TUNNEL_POLICY_PORT_${port}=Unchanged"
done

echo 'PREFLIGHT=Passed'
