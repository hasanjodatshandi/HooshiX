#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
for release in ztunnel istio-cni istiod istio-base; do h uninstall "$release" -n istio-system --ignore-not-found || true; done
