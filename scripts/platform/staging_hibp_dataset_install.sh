#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

sqlite=${1:?approved SQLite artifact path required}
manifest=${2:?approved release manifest path required}
overlay="$ROOT/.platform-runtime/staging/private/compromised-password-hibp-values.yaml"
state="$ROOT/.platform-runtime/staging/dataset.env"
overlay_next="$overlay.next"
state_next="$state.next"
for pending in "$overlay_next" "$state_next"; do
  if [[ -e "$pending" || -L "$pending" ]]; then unlink "$pending"; fi
done

revision=$(git rev-parse HEAD)
python3 "$ROOT/scripts/platform/hibp_dataset_staging.py" \
  --sqlite "$sqlite" \
  --manifest "$manifest" \
  --revision "$revision" \
  --overlay-output "$overlay_next" \
  --state-output "$state_next"

sqlite=$(realpath -e -- "$sqlite")
manifest=$(realpath -e -- "$manifest")

if k get deployment compromised-password-service -n platform-apps >/dev/null 2>&1; then
  k scale deployment/compromised-password-service -n platform-apps --replicas=0 >/dev/null
  k rollout status deployment/compromised-password-service -n platform-apps --timeout=60s >/dev/null
fi
docker exec platform-local-worker mkdir -p /var/local/hooshix/compromised-password
for target in corpus.sqlite.next release-manifest.json.next; do
  docker exec platform-local-worker sh -c "if [ -e '/var/local/hooshix/compromised-password/$target' ]; then unlink '/var/local/hooshix/compromised-password/$target'; fi"
done
docker cp "$sqlite" platform-local-worker:/var/local/hooshix/compromised-password/corpus.sqlite.next
docker cp "$manifest" platform-local-worker:/var/local/hooshix/compromised-password/release-manifest.json.next
expected_sqlite=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["sqlite_artifact_sha256"])' "$manifest")
actual_sqlite=$(docker exec platform-local-worker sha256sum /var/local/hooshix/compromised-password/corpus.sqlite.next | awk '{print $1}')
[[ "$actual_sqlite" == "$expected_sqlite" ]] || fail "copied HIBP SQLite artifact digest mismatch"
expected_manifest=$(sha256sum "$manifest" | awk '{print $1}')
actual_manifest=$(docker exec platform-local-worker sha256sum /var/local/hooshix/compromised-password/release-manifest.json.next | awk '{print $1}')
[[ "$actual_manifest" == "$expected_manifest" ]] || fail "copied HIBP release manifest digest mismatch"
k apply -f "$ROOT/infrastructure/staging/compromised-password-hibp-pv.yaml" >/dev/null
k wait --for=jsonpath='{.status.phase}'=Bound pvc/compromised-password-hibp-dataset -n platform-apps --timeout=30s >/dev/null
docker exec platform-local-worker sh -c "chmod 0444 /var/local/hooshix/compromised-password/corpus.sqlite.next /var/local/hooshix/compromised-password/release-manifest.json.next && mv /var/local/hooshix/compromised-password/corpus.sqlite.next /var/local/hooshix/compromised-password/corpus.sqlite && mv /var/local/hooshix/compromised-password/release-manifest.json.next /var/local/hooshix/compromised-password/release-manifest.json"
mv "$overlay_next" "$overlay"
mv "$state_next" "$state"
echo "Compromised Password complete HIBP staging dataset installation PASSED"
