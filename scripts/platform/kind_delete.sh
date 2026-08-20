#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/kind/pins.env"
ETCD_TMPFS_DIR=/dev/shm/hooshix-kind-etcd
if kind get clusters | grep -Fxq "$CLUSTER_NAME"; then kind delete cluster --name "$CLUSTER_NAME"; fi
if [[ -d "$ETCD_TMPFS_DIR" ]]; then
  docker run --rm --pull=never --entrypoint sh -v "$ETCD_TMPFS_DIR:/etcd" "$KIND_NODE_IMAGE" -c 'find /etcd -mindepth 1 -delete'
  rm -rf "$ETCD_TMPFS_DIR"
fi
