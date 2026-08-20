#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/kyverno/pins.env"
for d in kyverno-admission-controller kyverno-background-controller kyverno-cleanup-controller kyverno-reports-controller; do k rollout status deployment/$d -n kyverno --timeout=30s >/dev/null; done
[[ "$(k get ns kyverno -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')" == restricted ]] || fail "Kyverno namespace is not restricted PSS"
local_prefix=localhost:5001/hooshix/vendor/kyverno
for spec in \
  "kyverno-admission-controller:kyverno:$KYVERNO_ADMISSION_AMD64_DIGEST" \
  "kyverno-background-controller:background-controller:$KYVERNO_BACKGROUND_AMD64_DIGEST" \
  "kyverno-cleanup-controller:cleanup-controller:$KYVERNO_CLEANUP_AMD64_DIGEST" \
  "kyverno-reports-controller:reports-controller:$KYVERNO_REPORTS_AMD64_DIGEST"; do
  deployment=${spec%%:*}; rest=${spec#*:}; image_name=${rest%%:*}; digest=${rest#*:}
  images=$(k get deployment "$deployment" -n kyverno -o jsonpath='{range .spec.template.spec.containers[*]}{.image}{"\n"}{end}')
  [[ "$images" == *"${local_prefix}/${image_name}@${digest}"* ]] || fail "$deployment exact local mirror image mismatch"
done
pre_images=$(k get deployment kyverno-admission-controller -n kyverno -o jsonpath='{range .spec.template.spec.initContainers[*]}{.image}{"\n"}{end}')
[[ "$pre_images" == *"${local_prefix}/kyvernopre@${KYVERNO_PRE_AMD64_DIGEST}"* ]] || fail "Kyverno pre-init exact local mirror image mismatch"
for policy in platform-images-by-digest platform-pod-hardening platform-observability-node-hardening; do
  [[ "$(k get validatingpolicy "$policy" -o jsonpath='{.apiVersion}')" == policies.kyverno.io/v1 ]] || fail "$policy is not stable CEL policy v1"
done
[[ "$(k get mutatingpolicy pin-istio-waypoint-image -o jsonpath='{.apiVersion}')" == policies.kyverno.io/v1 ]] || fail "waypoint image pin is not stable MutatingPolicy v1"
waypoint_image=$(k get pod -n platform-apps -l gateway.networking.k8s.io/gateway-name=platform-apps-waypoint -o jsonpath='{.items[0].spec.containers[0].image}' 2>/dev/null || true)
if [[ -n "$waypoint_image" ]]; then
  [[ "$waypoint_image" == 'docker.io/istio/proxyv2@sha256:2f59eaf91e15213e50d91c7951c81073695872d6b17b82e146773695f24b1c6e' ]] || fail "waypoint pod image is not immutable: $waypoint_image"
fi
cat >/tmp/kyverno-positive.yaml <<'POD'
apiVersion: v1
kind: Pod
metadata: {name: kyverno-positive, namespace: platform-apps}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6
      command: [sleep, "1"]
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k apply --server-side --dry-run=server -f /tmp/kyverno-positive.yaml >/dev/null
sed 's#docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6#docker.io/curlimages/curl:8.16.0#' /tmp/kyverno-positive.yaml >/tmp/kyverno-tagged.yaml
if k apply --server-side --dry-run=server -f /tmp/kyverno-tagged.yaml >/tmp/kyverno-tagged.out 2>&1; then fail "Kyverno allowed tag-only platform image"; fi
grep -Eqi 'digest|denied|validation' /tmp/kyverno-tagged.out || fail "tag-only negative did not return Kyverno denial evidence"
sed 's/allowPrivilegeEscalation: false/allowPrivilegeEscalation: true/' /tmp/kyverno-positive.yaml >/tmp/kyverno-privileged.yaml
if k apply --server-side --dry-run=server -f /tmp/kyverno-privileged.yaml >/tmp/kyverno-privileged.out 2>&1; then fail "Kyverno allowed privilege escalation"; fi
grep -Eqi 'privilege|denied|validation' /tmp/kyverno-privileged.out || fail "hardening negative did not return Kyverno denial evidence"
legacy=$(find "$ROOT/infrastructure" "$ROOT/deploy" "$ROOT/services" -type f \( -name '*.yaml' -o -name '*.yml' \) ! -path '*/vendor/*' -print0 | xargs -0 grep -lE '^apiVersion: kyverno\.io/v(1|2)$' 2>/dev/null || true)
[[ -z "$legacy" ]] || fail "legacy Kyverno production policy API found: $legacy"
echo "Kyverno 1.18.2 CEL admission verification PASSED"
