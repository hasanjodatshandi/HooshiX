#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/traefik/pins.env"
load_env "$ROOT/infrastructure/waf/pins.env"
RUNTIME="$ROOT/.platform-runtime"
mkdir -p "$RUNTIME/tls"; chmod 700 "$RUNTIME" "$RUNTIME/tls"
verify_sha "$TRAEFIK_CHART_SHA256" "$ROOT/infrastructure/traefik/chart/$TRAEFIK_CHART_VERSION/traefik-$TRAEFIK_CHART_VERSION.tgz"
verify_sha "$CRS_ARCHIVE_SHA256" "$ROOT/infrastructure/waf/vendor/$CRS_VERSION/coreruleset-$CRS_VERSION.tar.gz"
helm lint "$ROOT/infrastructure/traefik/chart/$TRAEFIK_CHART_VERSION/traefik-$TRAEFIK_CHART_VERSION.tgz" -f "$ROOT/infrastructure/traefik/values-local.yaml" >/dev/null
if [[ ! -f "$RUNTIME/tls/hooshix.local.crt" || ! -f "$RUNTIME/tls/hooshix.local.key" ]]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 30 -subj '/CN=hooshix.local' -addext 'subjectAltName=DNS:hooshix.local' -keyout "$RUNTIME/tls/hooshix.local.key" -out "$RUNTIME/tls/hooshix.local.crt" >/dev/null 2>&1
  chmod 600 "$RUNTIME/tls/hooshix.local.key"; chmod 644 "$RUNTIME/tls/hooshix.local.crt"
fi
k apply -f "$ROOT/infrastructure/traefik/service-account.yaml"
k -n traefik-system create secret tls hooshix-local-tls --cert="$RUNTIME/tls/hooshix.local.crt" --key="$RUNTIME/tls/hooshix.local.key" --dry-run=client -o yaml | k apply -f -
h upgrade --install traefik "$ROOT/infrastructure/traefik/chart/$TRAEFIK_CHART_VERSION/traefik-$TRAEFIK_CHART_VERSION.tgz" -n traefik-system -f "$ROOT/infrastructure/traefik/values-local.yaml" --wait --timeout 80s
k apply -f "$ROOT/infrastructure/traefik/networkpolicy.yaml"
k apply -f "$ROOT/infrastructure/traefik/gateway.yaml"
k apply -f "$ROOT/infrastructure/waf/kubernetes.yaml"
k rollout status deployment/edge-waf -n platform-edge --timeout=80s
"$ROOT/scripts/platform/edge_foundation_verify.sh"
