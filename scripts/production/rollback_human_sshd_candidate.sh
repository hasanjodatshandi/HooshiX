#!/usr/bin/env bash
# Root-owned copy is invoked by the independent systemd rollback timer.
set -euo pipefail

state=/root/hooshix-ssh-pr158
live=/etc/ssh/sshd_config
before_sha=01c79f1385e2ec9b5b09e4995cfcbd2f6b21e6e05c7906a8a2b1f1b18a5c5fbb
candidate_sha=41076da5cb933c3be885c912a6b26ade7169c9c1f4dcba9a2e048d3cd08b9656
uid=$(id -u)
[[ ${uid} == 0 && ! -L ${live} ]] || exit 1
printf '%s  %s\n' "${before_sha}" "${state}/sshd_config.before" |
  sha256sum --check --status
current=$(sha256sum "${live}")
current=${current%% *}
if [[ ${current} != "${before_sha}" && ${current} != "${candidate_sha}" ]]; then
  echo 'FAILED: later SSH change detected; refusing to overwrite it' >&2
  exit 1
fi
/usr/sbin/sshd -t -f "${state}/sshd_config.before"
if [[ ${current} == "${candidate_sha}" ]]; then
  staged=$(mktemp /etc/ssh/.hooshix-pr158-rollback.XXXXXX)
  install -o root -g root -m 0644 "${state}/sshd_config.before" "${staged}"
  mv -T "${staged}" "${live}"
fi
/usr/sbin/sshd -t
/usr/bin/systemctl reload ssh.service
echo 'ROLLBACK=Passed'
