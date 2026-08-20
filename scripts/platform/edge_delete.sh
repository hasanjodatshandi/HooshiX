#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
k delete httproute hooshix-public -n platform-edge --ignore-not-found
k delete -f "$ROOT/infrastructure/waf/kubernetes.yaml" --ignore-not-found || true
k delete gateway hooshix-public -n traefik-system --ignore-not-found
k delete gatewayclass hooshix-traefik --ignore-not-found
h uninstall traefik -n traefik-system --ignore-not-found || true
k delete secret hooshix-local-tls -n traefik-system --ignore-not-found
