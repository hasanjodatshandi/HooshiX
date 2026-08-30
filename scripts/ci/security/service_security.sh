#!/usr/bin/env bash
set -euo pipefail

readonly mode="${1:-}"
fixture_root=''
git_stderr=''

cleanup() {
  if [[ -n "${fixture_root}" ]]; then
    rm -rf -- "${fixture_root}"
  fi
  if [[ -n "${git_stderr}" ]]; then
    rm -f -- "${git_stderr}"
  fi
  rm -f /tmp/gitleaks-*-control.log /tmp/gitleaks-*-git.stderr
}

trap cleanup EXIT

require_environment() {
  local name
  for name in "$@"; do
    if [[ -z "${!name:-}" ]]; then
      printf 'Required CI environment variable is missing: %s\n' "${name}" >&2
      exit 1
    fi
  done
}

gitleaks_common_arguments() {
  printf '%s\0' \
    --rm \
    --network none \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --cap-drop ALL \
    --security-opt no-new-privileges \
    --volume "${GITHUB_WORKSPACE}/.gitleaks.toml:/config/.gitleaks.toml:ro"
}

verify_gitleaks_fixtures() {
  require_environment GITHUB_WORKSPACE GITLEAKS_IMAGE GITLEAKS_VERSION
  local -a common=()
  mapfile -d '' common < <(gitleaks_common_arguments)
  docker run "${common[@]}" "${GITLEAKS_IMAGE}" version | grep -F "${GITLEAKS_VERSION}"

  fixture_root="$(mktemp -d)"

  mkdir "${fixture_root}/negative"
  git -C "${fixture_root}/negative" init --quiet
  git -C "${fixture_root}/negative" config user.name 'HooshiX CI Fixture'
  git -C "${fixture_root}/negative" config user.email 'ci-fixture@invalid.example'
  printf '%s\n' 'ordinary test content without credentials' > "${fixture_root}/negative/input.txt"
  git -C "${fixture_root}/negative" add input.txt
  git -C "${fixture_root}/negative" commit --quiet -m 'fixture benign'
  docker run "${common[@]}" \
    --volume "${fixture_root}/negative:/fixture:ro" \
    "${GITLEAKS_IMAGE}" \
    dir --config /config/.gitleaks.toml --no-banner --redact=100 /fixture \
    >/tmp/gitleaks-negative-control.log 2>&1
  docker run "${common[@]}" \
    --volume "${fixture_root}/negative:/fixture:ro" \
    --entrypoint git \
    "${GITLEAKS_IMAGE}" \
    -C /fixture log -p --all --no-ext-diff --no-textconv \
    >/dev/null 2>/tmp/gitleaks-negative-git.stderr
  if [[ -s /tmp/gitleaks-negative-git.stderr ]]; then
    echo 'Gitleaks negative-history Git preflight wrote stderr.' >&2
    exit 1
  fi
  docker run "${common[@]}" \
    --volume "${fixture_root}/negative:/fixture:ro" \
    --workdir /fixture \
    "${GITLEAKS_IMAGE}" \
    git --config /config/.gitleaks.toml --no-banner --redact=100 --log-opts='--all' /fixture \
    >/tmp/gitleaks-negative-history-control.log 2>&1

  mkdir "${fixture_root}/positive"
  printf 'token = "ghp_%s%s"\n' \
    '9Kq7mX2vP4sN8cR1tY6a' \
    'B3dF5hJ7L9wZ0uE2iO4p' > "${fixture_root}/positive/input.txt"
  set +e
  docker run "${common[@]}" \
    --volume "${fixture_root}/positive:/fixture:ro" \
    "${GITLEAKS_IMAGE}" \
    dir --config /config/.gitleaks.toml --no-banner --redact=100 --exit-code 42 /fixture \
    >/tmp/gitleaks-positive-control.log 2>&1
  local status=$?
  set -e
  if [[ "${status}" -ne 42 ]]; then
    echo 'Gitleaks current-tree positive detection control failed.' >&2
    exit 1
  fi

  mkdir "${fixture_root}/history"
  git -C "${fixture_root}/history" init --quiet
  git -C "${fixture_root}/history" config user.name 'HooshiX CI Fixture'
  git -C "${fixture_root}/history" config user.email 'ci-fixture@invalid.example'
  printf 'token = "ghp_%s%s"\n' \
    '9Kq7mX2vP4sN8cR1tY6a' \
    'B3dF5hJ7L9wZ0uE2iO4p' > "${fixture_root}/history/committed-secret.txt"
  git -C "${fixture_root}/history" add committed-secret.txt
  git -C "${fixture_root}/history" commit --quiet -m 'fixture add'
  git -C "${fixture_root}/history" rm --quiet committed-secret.txt
  git -C "${fixture_root}/history" commit --quiet -m 'fixture delete'
  docker run "${common[@]}" \
    --volume "${fixture_root}/history:/fixture:ro" \
    --entrypoint git \
    "${GITLEAKS_IMAGE}" \
    -C /fixture log -p --all --no-ext-diff --no-textconv \
    >/dev/null 2>/tmp/gitleaks-history-git.stderr
  if [[ -s /tmp/gitleaks-history-git.stderr ]]; then
    echo 'Gitleaks positive-history Git preflight wrote stderr.' >&2
    exit 1
  fi
  set +e
  docker run "${common[@]}" \
    --volume "${fixture_root}/history:/fixture:ro" \
    --workdir /fixture \
    "${GITLEAKS_IMAGE}" \
    git --config /config/.gitleaks.toml --no-banner --redact=100 --exit-code 42 --log-opts='--all' /fixture \
    >/tmp/gitleaks-history-control.log 2>&1
  status=$?
  set -e
  if [[ "${status}" -ne 42 ]]; then
    echo 'Gitleaks commit-then-delete history detection control failed.' >&2
    exit 1
  fi
}

scan_gitleaks() {
  require_environment GITHUB_WORKSPACE GITLEAKS_IMAGE
  local -a common=()
  mapfile -d '' common < <(gitleaks_common_arguments)
  docker run "${common[@]}" \
    --volume "${GITHUB_WORKSPACE}:/src:ro" \
    --workdir /src \
    "${GITLEAKS_IMAGE}" \
    dir --config /config/.gitleaks.toml --no-banner --redact=100 /src

  git_stderr="$(mktemp)"
  docker run "${common[@]}" \
    --volume "${GITHUB_WORKSPACE}:/src:ro" \
    --entrypoint git \
    "${GITLEAKS_IMAGE}" \
    -C /src log -p --all --no-ext-diff --no-textconv \
    >/dev/null 2>"${git_stderr}"
  if [[ -s "${git_stderr}" ]]; then
    echo 'Gitleaks history Git preflight wrote stderr.' >&2
    exit 1
  fi
  docker run "${common[@]}" \
    --volume "${GITHUB_WORKSPACE}:/src:ro" \
    --workdir /src \
    "${GITLEAKS_IMAGE}" \
    git --config /config/.gitleaks.toml --no-banner --redact=100 --log-opts='--all' /src
}

install_osv_scanner() {
  require_environment OSV_SCANNER_VERSION OSV_SCANNER_SHA256
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    --output /tmp/osv-scanner \
    "https://github.com/google/osv-scanner/releases/download/v${OSV_SCANNER_VERSION}/osv-scanner_linux_amd64"
  printf '%s  %s\n' "${OSV_SCANNER_SHA256}" /tmp/osv-scanner | sha256sum --check --strict -
  chmod 0755 /tmp/osv-scanner
  /tmp/osv-scanner --version | grep -F "${OSV_SCANNER_VERSION}"
}

scan_osv_lockfile() {
  require_environment SERVICE_DIR
  /tmp/osv-scanner scan source --lockfile="${SERVICE_DIR}/gradle.lockfile" --format=vertical
}

case "${mode}" in
  gitleaks-fixtures) verify_gitleaks_fixtures ;;
  gitleaks-scan) scan_gitleaks ;;
  osv-install) install_osv_scanner ;;
  osv-scan) scan_osv_lockfile ;;
  *)
    echo 'Usage: service_security.sh {gitleaks-fixtures|gitleaks-scan|osv-install|osv-scan}' >&2
    exit 2
    ;;
esac
