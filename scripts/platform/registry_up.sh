#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/registry/pins.env"
if ! docker inspect "$REGISTRY_NAME" >/dev/null 2>&1; then
  docker run -d --restart=always -p "127.0.0.1:${REGISTRY_HOST_PORT}:5000" --name "$REGISTRY_NAME" "$REGISTRY_IMAGE" >/dev/null
else
  docker start "$REGISTRY_NAME" >/dev/null || true
fi
docker network connect kind "$REGISTRY_NAME" 2>/dev/null || true
for node in $(kind get nodes --name "$CLUSTER_NAME"); do
  docker exec "$node" mkdir -p "/etc/containerd/certs.d/${REGISTRY_NODE_HOST}"
  cat <<HOSTS | docker exec -i "$node" sh -c "cat > /etc/containerd/certs.d/${REGISTRY_NODE_HOST}/hosts.toml"
[host."http://${REGISTRY_NAME}:5000"]
  capabilities = ["pull", "resolve", "push"]
HOSTS
  docker exec "$node" systemctl restart containerd
 done
for i in $(seq 1 20); do curl -fsS "http://127.0.0.1:${REGISTRY_HOST_PORT}/v2/" >/dev/null && break; sleep 1; done
curl -fsS "http://127.0.0.1:${REGISTRY_HOST_PORT}/v2/" >/dev/null
echo "kind local registry ready on 127.0.0.1:${REGISTRY_HOST_PORT}"
