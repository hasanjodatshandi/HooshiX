#!/usr/bin/env bash
set -euo pipefail

readonly actionlint_version='1.7.12'
readonly actionlint_sha256='8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8'
readonly shellcheck_version='0.11.0'
readonly shellcheck_sha256='8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198'
readonly ruff_version='0.16.5'
readonly ruff_sha256='65b8bae7e43f12a91b71036a52176012b3aefb725d5ae263e2771474110a0983'

repository_root="$(git rev-parse --show-toplevel)"
readonly repository_root
tool_root="$(mktemp -d)"
readonly tool_root
trap 'rm -rf "${tool_root}"' EXIT

download_verified() {
  local url="$1"
  local sha256="$2"
  local destination="$3"
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    --retry 3 --retry-all-errors --connect-timeout 15 --max-time 120 \
    --output "${destination}" "${url}"
  printf '%s  %s\n' "${sha256}" "${destination}" | sha256sum --check --strict -
}

actionlint_archive="${tool_root}/actionlint.tar.gz"
download_verified \
  "https://github.com/rhysd/actionlint/releases/download/v${actionlint_version}/actionlint_${actionlint_version}_linux_amd64.tar.gz" \
  "${actionlint_sha256}" \
  "${actionlint_archive}"
mkdir "${tool_root}/actionlint"
tar -xzf "${actionlint_archive}" -C "${tool_root}/actionlint"
readonly actionlint="${tool_root}/actionlint/actionlint"
"${actionlint}" -version | grep -F "${actionlint_version}"

shellcheck_archive="${tool_root}/shellcheck.tar.xz"
download_verified \
  "https://github.com/koalaman/shellcheck/releases/download/v${shellcheck_version}/shellcheck-v${shellcheck_version}.linux.x86_64.tar.xz" \
  "${shellcheck_sha256}" \
  "${shellcheck_archive}"
tar -xJf "${shellcheck_archive}" -C "${tool_root}"
readonly shellcheck="${tool_root}/shellcheck-v${shellcheck_version}/shellcheck"
"${shellcheck}" --version | grep -F "version: ${shellcheck_version}"

ruff_archive="${tool_root}/ruff.tar.gz"
download_verified \
  "https://github.com/astral-sh/ruff/releases/download/${ruff_version}/ruff-x86_64-unknown-linux-gnu.tar.gz" \
  "${ruff_sha256}" \
  "${ruff_archive}"
tar -xzf "${ruff_archive}" -C "${tool_root}"
readonly ruff="${tool_root}/ruff-x86_64-unknown-linux-gnu/ruff"
"${ruff}" --version | grep -F "ruff ${ruff_version}"

cd "${repository_root}"
mapfile -d '' shell_sources < <(git ls-files -z 'scripts/**/*.sh')
if [[ "${#shell_sources[@]}" -eq 0 ]]; then
  echo 'No tracked shell sources were found.' >&2
  exit 1
fi
"${shellcheck}" \
  --severity=warning \
  --exclude=SC1090,SC2034,SC2154 \
  --external-sources \
  --source-path=SCRIPTDIR \
  "${shell_sources[@]}"
"${ruff}" check --no-cache --select E9,F63,F7,F82 scripts
SHELLCHECK_OPTS='--severity=warning --exclude=SC1090,SC2034,SC2154' \
  "${actionlint}" -shellcheck="${shellcheck}"
