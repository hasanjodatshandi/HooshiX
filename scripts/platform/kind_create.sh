#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/kind/pins.env"
load_env "$ROOT/infrastructure/calico/pins.env"
load_env "$ROOT/infrastructure/gateway-api/pins.env"
require_command docker; require_command kind; require_command kubectl; require_command timeout
ETCD_TMPFS_DIR=/dev/shm/hooshix-kind-etcd
if kind get clusters | grep -Fxq "$CLUSTER_NAME"; then kind delete cluster --name "$CLUSTER_NAME"; fi
if [[ -d "$ETCD_TMPFS_DIR" ]]; then
  docker run --rm --pull=never --entrypoint sh -v "$ETCD_TMPFS_DIR:/etcd" "$KIND_NODE_IMAGE" -c 'find /etcd -mindepth 1 -delete'
  rm -rf "$ETCD_TMPFS_DIR"
fi
install -d -m 0700 "$ETCD_TMPFS_DIR"
require_exact_line "$(kind version)" "v${KIND_VERSION}" kind
require_exact_line "$(kubectl version --client -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["clientVersion"]["gitVersion"])')" "v${KUBECTL_VERSION}" kubectl
verify_sha "$CALICO_MANIFEST_SHA256" "$ROOT/infrastructure/calico/vendor/${CALICO_VERSION}/calico.yaml"
verify_sha "$GATEWAY_STANDARD_MANIFEST_SHA256" "$ROOT/infrastructure/gateway-api/vendor/${GATEWAY_API_VERSION}/standard-install.yaml"
kind create cluster --name "$CLUSTER_NAME" --image "$KIND_NODE_IMAGE" --config "$ROOT/infrastructure/kind/cluster.yaml" --wait 60s
sideload_calico() {
  local name=$1 digest=$2
  local ref="${CALICO_IMAGE_REGISTRY}/${name}@${digest}"
  if ! docker image inspect "$ref" >/dev/null 2>&1; then
    local pulled=0
    for delay in 0 2 5; do
      [[ "$delay" -eq 0 ]] || sleep "$delay"
      if timeout 120s docker pull --platform linux/amd64 "$ref" >/dev/null; then pulled=1; break; fi
    done
    [[ "$pulled" -eq 1 ]] || fail "failed to pull pinned Calico image: $ref"
  fi
  kind load docker-image "$ref" --name "$CLUSTER_NAME" >/dev/null
  for node in $(kind get nodes --name "$CLUSTER_NAME"); do
    if docker exec "$node" ctr -n k8s.io images list -q | grep -Fxq "$ref"; then continue; fi
    local src
    src=$(docker exec "$node" ctr -n k8s.io images list -q | grep -E "@${digest}$" | head -1 || true)
    [[ -n "$src" ]] || fail "sideloaded Calico content missing on $node: $digest"
    docker exec "$node" ctr -n k8s.io images tag "$src" "$ref" >/dev/null
  done
}
sideload_calico cni "$CALICO_CNI_AMD64_DIGEST"
sideload_calico node "$CALICO_NODE_AMD64_DIGEST"
sideload_calico kube-controllers "$CALICO_CONTROLLERS_AMD64_DIGEST"
python3 - "$ROOT/infrastructure/calico/vendor/${CALICO_VERSION}/calico.yaml" <<'PY' | k apply -f -
from pathlib import Path
import os,sys
p=Path(sys.argv[1]); s=p.read_text(encoding='utf-8')
registry=os.environ['CALICO_IMAGE_REGISTRY']
replacements={
    f"quay.io/calico/cni@{os.environ['CALICO_CNI_INDEX_DIGEST']}": f"{registry}/cni@{os.environ['CALICO_CNI_AMD64_DIGEST']}",
    f"quay.io/calico/node@{os.environ['CALICO_NODE_INDEX_DIGEST']}": f"{registry}/node@{os.environ['CALICO_NODE_AMD64_DIGEST']}",
    f"quay.io/calico/kube-controllers@{os.environ['CALICO_CONTROLLERS_INDEX_DIGEST']}": f"{registry}/kube-controllers@{os.environ['CALICO_CONTROLLERS_AMD64_DIGEST']}",
}
for old,new in replacements.items():
    if old not in s: raise SystemExit('expected pinned Calico vendor image missing: '+old)
    s=s.replace(old,new)
if 'quay.io/calico/' in s: raise SystemExit('unreviewed quay.io Calico image remains after local render')
print(s,end='')
PY
k rollout status daemonset/calico-node -n kube-system --timeout=180s
k rollout status deployment/calico-kube-controllers -n kube-system --timeout=180s
k rollout status deployment/coredns -n kube-system --timeout=180s
k apply -f "$ROOT/infrastructure/gateway-api/vendor/${GATEWAY_API_VERSION}/standard-install.yaml"
for crd in gatewayclasses.gateway.networking.k8s.io gateways.gateway.networking.k8s.io httproutes.gateway.networking.k8s.io referencegrants.gateway.networking.k8s.io; do
  k wait --for=condition=Established "crd/$crd" --timeout=60s
done
k wait --for=condition=Ready nodes --all --timeout=120s
"$ROOT/scripts/platform/kind_verify.sh"
