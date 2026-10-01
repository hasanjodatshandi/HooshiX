#!/usr/bin/env bash
# Operator-side application; sudo invokes host tools, never this writable script.
set -euo pipefail

if [[ $# != 1 || $1 != --rescue-and-second-session-ready ]]; then
  echo 'FAILED: keep VNC rescue and a second private SSH session open first' >&2
  exit 1
fi
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
state=/root/hooshix-ssh-pr158
live=/etc/ssh/sshd_config
candidate=/home/hooshixadmin/.cache/hooshix-ssh-pr158/hooshix-sshd-candidate-jjjjc_d8.conf
unit=hooshix-ssh-pr158-rollback
before_sha=01c79f1385e2ec9b5b09e4995cfcbd2f6b21e6e05c7906a8a2b1f1b18a5c5fbb
candidate_sha=41076da5cb933c3be885c912a6b26ade7169c9c1f4dcba9a2e048d3cd08b9656
rollback_sha=6ed728fca74841557f2138aae7748e8f5518d3f387e4bcb06d9a88df3f5ab3e7

[[ ! -L ${live} ]] || exit 1
live_metadata=$(stat -c '%a:%u:%g' "${live}")
[[ ${live_metadata} == 644:0:0 ]] || exit 1
printf '%s  %s\n' a967f8fc361ba374116630e81e36382e177e833a18f3316e21b557e1afd8a596 \
  "${script_dir}/check_human_sshd_candidate.sh" | sha256sum --check --status
bash "${script_dir}/check_human_sshd_candidate.sh"
# mkdir deliberately refuses a repeated/unreconciled rollout; no old backup is overwritten.
sudo -n /usr/bin/mkdir -m 0700 "${state}"
sudo -n /usr/bin/cp -p "${live}" "${state}/sshd_config.before"
sudo -n /usr/bin/install -o root -g root -m 0644 "${candidate}" "${state}/sshd_config.candidate"
sudo -n /usr/bin/install -o root -g root -m 0700 \
  "${script_dir}/rollback_human_sshd_candidate.sh" "${state}/rollback.sh"
printf '%s  %s\n%s  %s\n%s  %s\n' \
  "${before_sha}" "${state}/sshd_config.before" \
  "${candidate_sha}" "${state}/sshd_config.candidate" \
  "${rollback_sha}" "${state}/rollback.sh" |
  sudo -n /usr/bin/sha256sum --check --status
sudo -n /usr/sbin/sshd -t -f "${state}/sshd_config.candidate"

sudo -n /usr/bin/systemd-run --unit="${unit}" --on-active=10m \
  --timer-property=AccuracySec=1s --property=Type=oneshot \
  /bin/bash "${state}/rollback.sh"
sudo -n /usr/bin/systemctl is-active --quiet "${unit}.timer"
echo 'ROLLBACK_TIMER=Active'
# Recheck the live source immediately before the atomic replacement.
printf '%s  %s\n' "${before_sha}" "${live}" |
  sudo -n /usr/bin/sha256sum --check --status
staged=$(sudo -n /usr/bin/mktemp /etc/ssh/.hooshix-pr158-install.XXXXXX)
sudo -n /usr/bin/install -o root -g root -m 0644 "${state}/sshd_config.candidate" "${staged}"
sudo -n /usr/bin/mv -T "${staged}" "${live}"
if ! sudo -n /usr/sbin/sshd -t || ! sudo -n /usr/bin/systemctl reload ssh.service; then
  sudo -n /bin/bash "${state}/rollback.sh"
  echo 'FAILED: application failed; rollback executed' >&2
  exit 1
fi
echo 'SSH_CANDIDATE=Applied'
echo 'NEXT: verify fresh private SSH and forwarding denial within 10 minutes'
echo 'Do not reboot or cancel the rollback timer before independent verification'
