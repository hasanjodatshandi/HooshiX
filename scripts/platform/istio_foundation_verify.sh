#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/istio/pins.env"
for release in istio-base istiod istio-cni ztunnel; do
  status=$(h status "$release" -n istio-system -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["info"]["status"])')
  [[ "$status" == deployed ]] || fail "$release is not deployed: $status"
done
k rollout status deployment/istiod -n istio-system --timeout=30s >/dev/null
k rollout status daemonset/istio-cni-node -n istio-system --timeout=30s >/dev/null
k rollout status daemonset/ztunnel -n istio-system --timeout=30s >/dev/null
[[ "$(k get daemonset istio-cni-node -n istio-system -o jsonpath='{.status.numberReady}')" -eq 3 ]] || fail "Istio CNI must be ready on all 3 nodes"
[[ "$(k get daemonset ztunnel -n istio-system -o jsonpath='{.status.numberReady}')" -eq 3 ]] || fail "ztunnel must be ready on all 3 nodes"
pilot_id=$(k get pods -n istio-system -l app=istiod -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
cni_id=$(k get pods -n istio-system -l k8s-app=istio-cni-node -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
ztunnel_id=$(k get pods -n istio-system -l app=ztunnel -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
[[ "$pilot_id" == *"${ISTIO_PILOT_DISTROLESS_INDEX_DIGEST}" || "$pilot_id" == *"${ISTIO_PILOT_DISTROLESS_AMD64_DIGEST}" ]] || fail "istiod imageID mismatch: $pilot_id"
[[ "$cni_id" == *"${ISTIO_CNI_DISTROLESS_INDEX_DIGEST}" || "$cni_id" == *"${ISTIO_CNI_DISTROLESS_AMD64_DIGEST}" ]] || fail "Istio CNI imageID mismatch: $cni_id"
[[ "$ztunnel_id" == *"${ISTIO_ZTUNNEL_INDEX_DIGEST}" || "$ztunnel_id" == *"${ISTIO_ZTUNNEL_AMD64_DIGEST}" || "$ztunnel_id" == *"${ISTIO_ZTUNNEL_DISTROLESS_INDEX_DIGEST}" || "$ztunnel_id" == *"${ISTIO_ZTUNNEL_DISTROLESS_AMD64_DIGEST}" ]] || fail "ztunnel imageID mismatch: $ztunnel_id"
trust_domain=$(k get configmap istio -n istio-system -o jsonpath='{.data.mesh}' | awk '$1=="trustDomain:" {gsub(/"/,"",$2); print $2; exit}')
[[ "$trust_domain" == "$ISTIO_TRUST_DOMAIN" ]] || fail "Istio trust domain mismatch: $trust_domain"
for ns in traefik-system platform-edge platform-apps platform-observability platform-observability-node; do
  [[ "$(k get ns "$ns" -o jsonpath='{.metadata.labels.istio\.io/dataplane-mode}')" == ambient ]] || fail "$ns not Ambient-enrolled"
done
[[ -z "$(k get ns platform-data -o jsonpath='{.metadata.labels.istio\.io/dataplane-mode}')" ]] || fail "platform-data must use selective rather than namespace-wide Ambient enrollment"
[[ "$(k get ns platform-observability-node -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')" == privileged ]] || fail "node observability namespace uses PSS privileged only for the exact Kyverno-governed read-only log hostPath exception"
if k get hpa istiod -n istio-system >/dev/null 2>&1; then fail "istiod HPA must be disabled in local single-server profile"; fi
analysis=$(timeout 30s istioctl analyze --context "$KUBE_CONTEXT" --all-namespaces -o json 2>&1) || { rc=$?; echo "$analysis" >&2; fail "istioctl analyze failed or timed out (rc=$rc)"; }
if ! ANALYSIS_JSON="$analysis" python3 -c 'import json,os,sys; items=json.loads(os.environ["ANALYSIS_JSON"]); sys.exit(1 if any(str(x.get("level", "")).lower()=="error" for x in items) else 0)'; then
  echo "$analysis" >&2
  fail "istioctl analyze reported Error diagnostics"
fi
if [[ "$analysis" != "[]" ]]; then echo "istioctl analyze non-blocking diagnostics: $analysis"; fi
echo "Istio Ambient foundation verification PASSED"
