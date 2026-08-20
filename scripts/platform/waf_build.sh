#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/waf/pins.env"
repo=localhost:5001/hooshix/edge-waf
tag=local-$(git -C "$ROOT" rev-parse --short=12 HEAD)
docker build --provenance=false --pull=false -t "$repo:$tag" "$ROOT/infrastructure/waf"
docker push "$repo:$tag" >/tmp/hooshix-edge-waf-push.log
ref=$(docker inspect "$repo:$tag" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep '^localhost:5001/hooshix/edge-waf@' | head -1)
digest=${ref##*@}
[[ "$digest" == "$WAF_IMAGE_DIGEST" ]] || fail "WAF image digest mismatch: expected $WAF_IMAGE_DIGEST, built $digest"
echo "WAF image reproducibility verification PASSED: $repo@$digest"
