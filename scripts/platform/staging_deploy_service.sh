#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
state="$ROOT/.platform-runtime/staging/images.env"
[[ -f "$state" ]] || fail "staging image provenance state is missing; run staging-build"
source "$state"
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$BUILD_GIT_REVISION" --source-state "$BUILD_SOURCE_STATE" --worktree-sha256 "$BUILD_WORKTREE_SHA256" >/dev/null
service=${1:?service required}
case "$service" in authorization-service|compromised-password-service|identity-service|notification-service|web-bff) ;; *) fail "unsupported service: $service";; esac
key=$(echo "$service" | tr '[:lower:]-' '[:upper:]_')
repo_var="${key}_REPOSITORY"; digest_var="${key}_DIGEST"; repo=${!repo_var}; digest=${!digest_var}
chart="$ROOT/services/$service/deploy/helm/$service"
values="$ROOT/deploy/staging/$service.yaml"
extra=()
helm_timeout=70s
if [[ "$service" == web-bff ]]; then
  google_values="$ROOT/.platform-runtime/staging/private/web-bff-google-values.yaml"
  if [[ -e "$google_values" || -L "$google_values" ]]; then
    [[ -f "$google_values" && ! -L "$google_values" ]] || fail "staging Google OIDC values must be a regular non-symlink file"
    [[ "$(stat -c '%u' "$google_values")" == "$(id -u)" && "$(stat -c '%a' "$google_values")" == 600 ]] || fail "staging Google OIDC values must be user-owned mode 0600"
    extra+=(-f "$google_values")
  fi
fi
if [[ "$service" == compromised-password-service ]]; then
  dataset_state="$ROOT/.platform-runtime/staging/dataset.env"
  [[ -f "$dataset_state" ]] || fail "generated staging dataset state is missing; run staging-data-install first"
  line=$(cat "$dataset_state")
  [[ "$line" =~ ^COMPROMISED_PASSWORD_MANIFEST_SHA256=([0-9a-f]{64})$ ]] || fail "generated staging dataset state is invalid"
  extra+=(--set-string "dataset.expectedManifestSha256=${BASH_REMATCH[1]}")
  hibp_values="$ROOT/.platform-runtime/staging/private/compromised-password-hibp-values.yaml"
  if [[ -e "$hibp_values" || -L "$hibp_values" ]]; then
    [[ -f "$hibp_values" && ! -L "$hibp_values" ]] || fail "staging HIBP values must be a regular non-symlink file"
    [[ "$(stat -c '%u' "$hibp_values")" == "$(id -u)" && "$(stat -c '%a' "$hibp_values")" == 600 ]] || fail "staging HIBP values must be user-owned mode 0600"
    extra+=(-f "$hibp_values")
    helm_timeout=7500s
  fi
fi
helm lint "$chart" -f "$values" --set "image.repository=$repo" --set "image.digest=$digest" "${extra[@]}" >/dev/null
h upgrade --install "$service" "$chart" -n platform-apps -f "$values" --set "image.repository=$repo" --set "image.digest=$digest" "${extra[@]}" --wait --timeout "$helm_timeout"
k rollout status deployment/$service -n platform-apps --timeout=30s >/dev/null
echo "$service staging release PASSED"
