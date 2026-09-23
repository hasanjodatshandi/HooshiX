#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/waf/pins.env"
repo=localhost:5001/hooshix/edge-waf
tag=local-$(git -C "$ROOT" rev-parse --short=12 HEAD)
[[ "$WAF_SOURCE_DATE_EPOCH" =~ ^[1-9][0-9]*$ ]] || fail "WAF_SOURCE_DATE_EPOCH must be a positive Unix timestamp"
SOURCE_DATE_EPOCH="$WAF_SOURCE_DATE_EPOCH" docker buildx build \
  --build-arg "SOURCE_DATE_EPOCH=$WAF_SOURCE_DATE_EPOCH" \
  --provenance=false \
  --pull=false \
  --output "type=image,name=$repo:$tag,push=true,rewrite-timestamp=true,unpack=false" \
  "$ROOT/infrastructure/waf"
docker pull "$repo:$tag" >/dev/null
ref=$(docker inspect "$repo:$tag" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep '^localhost:5001/hooshix/edge-waf@' | head -1)
digest=${ref##*@}
[[ "$digest" == "$WAF_IMAGE_DIGEST" ]] || fail "WAF image digest mismatch: expected $WAF_IMAGE_DIGEST, built $digest"
echo "WAF image reproducibility verification PASSED: $repo@$digest"
