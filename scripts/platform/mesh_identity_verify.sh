#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
CURL_IMAGE='docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6'
cleanup() {
  k delete pod mesh-traefik-positive -n traefik-system --ignore-not-found --wait=false >/dev/null 2>&1 || true
  k delete pod mesh-waf-positive -n platform-edge --ignore-not-found --wait=false >/dev/null 2>&1 || true
  k delete pod mesh-unauthorized -n default --ignore-not-found --wait=false >/dev/null 2>&1 || true
  k delete pod authorization-identity-policy -n platform-apps --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
trap cleanup EXIT
for d in edge-waf:platform-edge web-bff:platform-apps authorization-service:platform-apps identity-service:platform-apps notification-service:platform-apps compromised-password-service:platform-apps; do
  name=${d%%:*}; ns=${d##*:}; k rollout status deployment/$name -n "$ns" --timeout=30s >/dev/null
done
for name in web-bff authorization-service identity-service notification-service compromised-password-service; do
  sa=$(k get pod -n platform-apps -l "app.kubernetes.io/name=$name" -o jsonpath='{.items[0].spec.serviceAccountName}')
  [[ "$sa" == "$name" ]] || fail "$name ServiceAccount mismatch: $sa"
  proxies=$(k get pod -n platform-apps -l "app.kubernetes.io/name=$name" -o jsonpath='{range .items[0].spec.containers[*]}{.name}{"\n"}{end}' | grep -c '^istio-proxy$' || true)
  [[ "$proxies" -eq 0 ]] || fail "$name unexpectedly has a sidecar in Ambient mode"
  [[ "$(k get peerauthentication "$name" -n platform-apps -o jsonpath='{.spec.mtls.mode}')" == STRICT ]] || fail "$name PeerAuthentication is not STRICT"
done
[[ "$(k get peerauthentication edge-waf -n platform-edge -o jsonpath='{.spec.mtls.mode}')" == STRICT ]] || fail "edge-waf PeerAuthentication is not STRICT"
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: mesh-traefik-positive
  namespace: traefik-system
  labels: {app.kubernetes.io/name: traefik}
spec:
  serviceAccountName: traefik
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      command: [sleep, "300"]
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=condition=Ready pod/mesh-traefik-positive -n traefik-system --timeout=60s >/dev/null
headers=$(k exec -n traefik-system mesh-traefik-positive -- curl -sS -m 8 -D - -o /dev/null http://edge-waf.platform-edge.svc.cluster.local:8080/ -H 'X-HooshiX-WAF-Test: block')
echo "$headers" | grep -qi '^X-HooshiX-WAF-Blocked: true' || fail "Traefik ServiceAccount did not reach WAF through approved mTLS identity path"
set +e
code=$(k exec -n traefik-system mesh-traefik-positive -- curl -sS -m 5 -o /dev/null -w '%{http_code}' -X POST http://web-bff.platform-apps.svc.cluster.local:8080/api/v1/auth/session/bootstrap -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' 2>/dev/null)
rc=$?
set -e
[[ "$code" == 000 || "$code" == 403 || $rc -ne 0 ]] || fail "Traefik ServiceAccount bypassed WAF and reached Web BFF directly (HTTP $code)"
k delete pod mesh-traefik-positive -n traefik-system --wait=true >/dev/null
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: mesh-waf-positive
  namespace: platform-edge
  labels: {app.kubernetes.io/name: edge-waf, verification.hooshix.io/role: mesh-positive}
spec:
  serviceAccountName: edge-waf
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      command: [sleep, "300"]
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=condition=Ready pod/mesh-waf-positive -n platform-edge --timeout=60s >/dev/null
code=$(k exec -n platform-edge mesh-waf-positive -- curl -sS -m 8 -o /dev/null -w '%{http_code}' -X POST http://web-bff.platform-apps.svc.cluster.local:8080/api/v1/auth/session/bootstrap -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty')
[[ "$code" == 201 ]] || fail "WAF ServiceAccount -> Web BFF approved identity path expected 201, got $code"
k delete pod mesh-waf-positive -n platform-edge --wait=true >/dev/null
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: authorization-identity-policy
  namespace: platform-apps
  labels: {app.kubernetes.io/name: identity-service}
spec:
  serviceAccountName: identity-service
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      command: [sleep, "300"]
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=condition=Ready pod/authorization-identity-policy -n platform-apps --timeout=60s >/dev/null
authorization_grpc='http://authorization-service.platform-apps.svc.cluster.local:9090/hooshix.authorization.v1.AuthorizationService/CheckPermission'
grpc_probe() {
  local caller_header=$1
  k exec -n platform-apps authorization-identity-policy -- sh -c "printf '\000\000\000\000\000' | curl --http2-prior-knowledge -sS -m 8 -o /dev/null -w '%{http_code}' -X POST '$authorization_grpc' -H 'content-type: application/grpc' -H 'te: trailers' $caller_header --data-binary @-"
}
allowed_code=$(grpc_probe "-H 'x-hooshix-authorization-caller: identity-service'")
[[ "$allowed_code" == 200 ]] || fail "Identity principal + bound caller header did not reach Authorization CheckPermission (HTTP $allowed_code)"
wrong_code=$(grpc_probe "-H 'x-hooshix-authorization-caller: workflow-service'")
[[ "$wrong_code" == 403 ]] || fail "Identity principal spoofed another Authorization caller class (HTTP $wrong_code)"
missing_code=$(grpc_probe "")
[[ "$missing_code" == 403 ]] || fail "Identity principal reached Authorization CheckPermission without bound caller header (HTTP $missing_code)"
k delete pod authorization-identity-policy -n platform-apps --wait=true >/dev/null
cat <<POD | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: {name: mesh-unauthorized, namespace: default}
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $CURL_IMAGE
      command: [sleep, "300"]
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
POD
k wait --for=condition=Ready pod/mesh-unauthorized -n default --timeout=60s >/dev/null
for target in 'http://edge-waf.platform-edge.svc.cluster.local:8080/' 'http://web-bff.platform-apps.svc.cluster.local:8080/api/v1/auth/session/bootstrap'; do
  set +e
  code=$(k exec -n default mesh-unauthorized -- curl -sS -m 5 -o /dev/null -w '%{http_code}' -X POST "$target" -H 'Origin: https://hooshix.local:8443' -H 'Sec-Fetch-Site: same-origin' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Dest: empty' 2>/dev/null)
  rc=$?
  set -e
  [[ "$code" == 000 || "$code" == 403 || $rc -ne 0 ]] || fail "non-enrolled unauthorized workload reached protected target $target (HTTP $code)"
done
echo "Ambient STRICT mTLS and workload-identity positive/negative verification PASSED"
