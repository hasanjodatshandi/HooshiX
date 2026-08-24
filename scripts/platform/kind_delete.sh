#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/kind/pins.env"
ETCD_TMPFS_PARENT=/dev/shm/hooshix-kind
ETCD_TMPFS_DIR=$ETCD_TMPFS_PARENT/etcd
if kind get clusters | grep -Fxq "$CLUSTER_NAME"; then kind delete cluster --name "$CLUSTER_NAME"; fi
if [[ -d "$ETCD_TMPFS_PARENT" ]]; then
  docker run --rm --pull=never --entrypoint sh \
    -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
    -v "$ETCD_TMPFS_PARENT:/hooshix-kind" "$KIND_NODE_IMAGE" \
    -c 'find /hooshix-kind -mindepth 1 -delete && chown "$HOST_UID:$HOST_GID" /hooshix-kind'
  rmdir "$ETCD_TMPFS_PARENT"
fi
