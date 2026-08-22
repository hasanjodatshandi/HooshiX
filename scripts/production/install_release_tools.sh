#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
source "$ROOT/infrastructure/production/release-tools.env"
DEST=${1:?destination directory is required}
mkdir -p "$DEST"
chmod 0755 "$DEST"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl_flags=(--fail --silent --show-error --location --proto '=https' --tlsv1.2)
syft_archive="$tmp/syft.tar.gz"
grype_archive="$tmp/grype.tar.gz"
cosign_bin="$tmp/cosign"
curl "${curl_flags[@]}" -o "$syft_archive" "https://github.com/anchore/syft/releases/download/v${SYFT_VERSION}/syft_${SYFT_VERSION}_linux_amd64.tar.gz"
curl "${curl_flags[@]}" -o "$grype_archive" "https://github.com/anchore/grype/releases/download/v${GRYPE_VERSION}/grype_${GRYPE_VERSION}_linux_amd64.tar.gz"
curl "${curl_flags[@]}" -o "$cosign_bin" "https://github.com/sigstore/cosign/releases/download/v${COSIGN_VERSION}/cosign-linux-amd64"
printf '%s  %s\n' "$SYFT_LINUX_AMD64_SHA256" "$syft_archive" | sha256sum --check --strict -
printf '%s  %s\n' "$GRYPE_LINUX_AMD64_SHA256" "$grype_archive" | sha256sum --check --strict -
printf '%s  %s\n' "$COSIGN_LINUX_AMD64_SHA256" "$cosign_bin" | sha256sum --check --strict -
tar -xzf "$syft_archive" -C "$tmp" syft
tar -xzf "$grype_archive" -C "$tmp" grype
install -m 0755 "$tmp/syft" "$DEST/syft"
install -m 0755 "$tmp/grype" "$DEST/grype"
install -m 0755 "$cosign_bin" "$DEST/cosign"
"$DEST/syft" version | grep -E "^Version:[[:space:]]+$SYFT_VERSION$" >/dev/null
"$DEST/grype" version | grep -E "^Version:[[:space:]]+$GRYPE_VERSION$" >/dev/null
"$DEST/cosign" version | grep -E "^GitVersion:[[:space:]]+v$COSIGN_VERSION$" >/dev/null
