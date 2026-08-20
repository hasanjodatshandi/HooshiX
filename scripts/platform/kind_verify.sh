#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/kind/pins.env"
load_env "$ROOT/infrastructure/calico/pins.env"
load_env "$ROOT/infrastructure/gateway-api/pins.env"
[[ "$(kind get clusters | grep -Fx "$CLUSTER_NAME" | wc -l)" -eq 1 ]] || fail "kind cluster missing"
mapfile -t nodes < <(k get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"|"}{.status.nodeInfo.kubeletVersion}{"|"}{range .status.conditions[?(@.type=="Ready")]}{.status}{end}{"\n"}{end}')
[[ ${#nodes[@]} -eq 3 ]] || fail "expected 3 nodes, found ${#nodes[@]}"
for row in "${nodes[@]}"; do [[ "$row" == *"|v${KUBERNETES_VERSION}|True" ]] || fail "node version/readiness mismatch: $row"; done
[[ "$(k get nodes -l ingress-ready=true --no-headers | wc -l)" -eq 1 ]] || fail "expected one ingress-ready node"
if k get daemonset -n kube-system kindnet >/dev/null 2>&1; then fail "kindnet must not be installed"; fi
k rollout status daemonset/calico-node -n kube-system --timeout=30s >/dev/null
k rollout status deployment/calico-kube-controllers -n kube-system --timeout=30s >/dev/null
actual_cni=$(k get daemonset calico-node -n kube-system -o jsonpath='{.spec.template.spec.initContainers[?(@.name=="install-cni")].image}')
actual_node=$(k get daemonset calico-node -n kube-system -o jsonpath='{.spec.template.spec.containers[?(@.name=="calico-node")].image}')
actual_ctl=$(k get deployment calico-kube-controllers -n kube-system -o jsonpath='{.spec.template.spec.containers[0].image}')
[[ "$actual_cni" == "${CALICO_IMAGE_REGISTRY}/cni@${CALICO_CNI_AMD64_DIGEST}" ]] || fail "Calico CNI local image mismatch: $actual_cni"
[[ "$actual_node" == "${CALICO_IMAGE_REGISTRY}/node@${CALICO_NODE_AMD64_DIGEST}" ]] || fail "Calico node local image mismatch: $actual_node"
[[ "$actual_ctl" == "${CALICO_IMAGE_REGISTRY}/kube-controllers@${CALICO_CONTROLLERS_AMD64_DIGEST}" ]] || fail "Calico controllers local image mismatch: $actual_ctl"
for crd in gatewayclasses.gateway.networking.k8s.io gateways.gateway.networking.k8s.io httproutes.gateway.networking.k8s.io referencegrants.gateway.networking.k8s.io; do k get "crd/$crd" >/dev/null; done
[[ "$(sysctl -n fs.inotify.max_user_watches)" -ge 524288 ]] || fail "inotify watches too low"
[[ "$(sysctl -n fs.inotify.max_user_instances)" -ge 512 ]] || fail "inotify instances too low"
echo "kind/Calico/Gateway API foundation verification PASSED"
