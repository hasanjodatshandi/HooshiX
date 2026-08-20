#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/istio/pins.env"
require_exact_line "$(helm version --short)" "v4.2.4" helm
require_exact_line "$(istioctl version --remote=false | head -1)" "$ISTIO_VERSION" istioctl
for chart in base istiod cni ztunnel; do
  file="$ROOT/infrastructure/istio/chart/$ISTIO_VERSION/${chart}-$ISTIO_VERSION.tgz"
  key=$(echo "$chart" | tr '[:lower:]-' '[:upper:]_')
  var="ISTIO_${key}_CHART_SHA256"
  verify_sha "${!var}" "$file"
done
k apply -f "$ROOT/infrastructure/istio/namespaces.yaml"
h upgrade --install istio-base "$ROOT/infrastructure/istio/chart/$ISTIO_VERSION/base-$ISTIO_VERSION.tgz" -n istio-system --wait --timeout 3m
h upgrade --install istiod "$ROOT/infrastructure/istio/chart/$ISTIO_VERSION/istiod-$ISTIO_VERSION.tgz" -n istio-system --set profile=ambient --set global.hub="$ISTIO_LOCAL_IMAGE_HUB" --set global.tag="$ISTIO_VERSION" --set meshConfig.trustDomain="$ISTIO_TRUST_DOMAIN" --set autoscaleEnabled=false --set replicaCount=1 --wait --timeout 4m
h upgrade --install istio-cni "$ROOT/infrastructure/istio/chart/$ISTIO_VERSION/cni-$ISTIO_VERSION.tgz" -n istio-system --set profile=ambient --set global.hub="$ISTIO_LOCAL_IMAGE_HUB" --set global.tag="$ISTIO_VERSION" --set global.trustDomain="$ISTIO_TRUST_DOMAIN" --wait --timeout 4m
h upgrade --install ztunnel "$ROOT/infrastructure/istio/chart/$ISTIO_VERSION/ztunnel-$ISTIO_VERSION.tgz" -n istio-system --set global.hub="$ISTIO_LOCAL_IMAGE_HUB" --set global.tag="$ISTIO_VERSION" --set global.trustDomain="$ISTIO_TRUST_DOMAIN" --wait --timeout 4m
"$ROOT/scripts/platform/istio_foundation_verify.sh"
