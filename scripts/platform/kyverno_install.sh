#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/kyverno/pins.env"
require_command docker; require_command timeout
file="$ROOT/infrastructure/kyverno/vendor/$KYVERNO_VERSION/install.yaml"
verify_sha "$KYVERNO_INSTALL_SHA256" "$file"
KYVERNO_LOCAL_REPOSITORY_PREFIX=localhost:5001/hooshix/vendor/kyverno
require_command curl
curl -fsS http://127.0.0.1:5001/v2/ >/dev/null || fail "local kind registry is not ready"
mirror_kyverno() {
  local name=$1 digest=$2
  local upstream="reg.kyverno.io/kyverno/${name}@${digest}"
  local repository="${KYVERNO_LOCAL_REPOSITORY_PREFIX}/${name}"
  local tag="${repository}:v${KYVERNO_VERSION}-amd64"
  if ! docker image inspect "$upstream" >/dev/null 2>&1; then
    local pulled=0
    for delay in 0 2 5; do
      [[ "$delay" -eq 0 ]] || sleep "$delay"
      if timeout 120s docker pull --platform linux/amd64 "$upstream" >/dev/null; then pulled=1; break; fi
    done
    [[ "$pulled" -eq 1 ]] || fail "failed to pull pinned Kyverno image: $upstream"
  fi
  docker tag "$upstream" "$tag"
  timeout 120s docker push "$tag" >/dev/null || fail "failed to mirror pinned Kyverno image: $name"
  local mirrored
  mirrored=$(docker inspect "$tag" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep -F "${repository}@" | tail -1 || true)
  [[ "$mirrored" == "${repository}@${digest}" ]] || fail "mirrored Kyverno digest mismatch for $name: $mirrored"
}
mirror_kyverno kyverno "$KYVERNO_ADMISSION_AMD64_DIGEST"
mirror_kyverno kyvernopre "$KYVERNO_PRE_AMD64_DIGEST"
mirror_kyverno background-controller "$KYVERNO_BACKGROUND_AMD64_DIGEST"
mirror_kyverno cleanup-controller "$KYVERNO_CLEANUP_AMD64_DIGEST"
mirror_kyverno reports-controller "$KYVERNO_REPORTS_AMD64_DIGEST"
export KYVERNO_LOCAL_REPOSITORY_PREFIX
python3 - "$file" <<'PY' | k apply --server-side --force-conflicts -f -
from pathlib import Path
import os,sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
repl={
 'reg.kyverno.io/kyverno/kyverno:v1.18.2':f"{os.environ['KYVERNO_LOCAL_REPOSITORY_PREFIX']}/kyverno@{os.environ['KYVERNO_ADMISSION_AMD64_DIGEST']}",
 'reg.kyverno.io/kyverno/kyvernopre:v1.18.2':f"{os.environ['KYVERNO_LOCAL_REPOSITORY_PREFIX']}/kyvernopre@{os.environ['KYVERNO_PRE_AMD64_DIGEST']}",
 'reg.kyverno.io/kyverno/background-controller:v1.18.2':f"{os.environ['KYVERNO_LOCAL_REPOSITORY_PREFIX']}/background-controller@{os.environ['KYVERNO_BACKGROUND_AMD64_DIGEST']}",
 'reg.kyverno.io/kyverno/cleanup-controller:v1.18.2':f"{os.environ['KYVERNO_LOCAL_REPOSITORY_PREFIX']}/cleanup-controller@{os.environ['KYVERNO_CLEANUP_AMD64_DIGEST']}",
 'reg.kyverno.io/kyverno/reports-controller:v1.18.2':f"{os.environ['KYVERNO_LOCAL_REPOSITORY_PREFIX']}/reports-controller@{os.environ['KYVERNO_REPORTS_AMD64_DIGEST']}",
}
for old,new in repl.items():
    if old not in s: raise SystemExit('missing expected Kyverno image '+old)
    s=s.replace(old,new)
needle='''    app.kubernetes.io/version: v1.18.2\n---\napiVersion: v1\nkind: ServiceAccount\n'''
labels='''    app.kubernetes.io/version: v1.18.2\n    pod-security.kubernetes.io/enforce: restricted\n    pod-security.kubernetes.io/audit: restricted\n    pod-security.kubernetes.io/warn: restricted\n---\napiVersion: v1\nkind: ServiceAccount\n'''
if needle not in s: raise SystemExit('Kyverno namespace marker missing')
s=s.replace(needle,labels,1)
print(s,end='')
PY
for d in kyverno-admission-controller kyverno-background-controller kyverno-cleanup-controller kyverno-reports-controller; do k rollout status deployment/$d -n kyverno --timeout=70s; done
policy_file="$ROOT/infrastructure/kyverno/policies/platform-workload-hardening.yaml"
webhook_ready=0
for attempt in $(seq 1 20); do
  endpoint_ready=$(k get endpointslice -n kyverno -l kubernetes.io/service-name=kyverno-svc -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' 2>/dev/null || true)
  if grep -Fxq true <<<"$endpoint_ready" && timeout 10s kubectl --context "$KUBE_CONTEXT" apply --server-side --dry-run=server --field-manager=hooshix-kyverno-readiness -f "$policy_file" >/dev/null 2>&1; then
    webhook_ready=1
    break
  fi
  [[ "$attempt" -eq 20 ]] || sleep 2
done
[[ "$webhook_ready" -eq 1 ]] || fail "Kyverno policy validation webhook did not become ready"
k apply -f "$policy_file"
"$ROOT/scripts/platform/kyverno_verify.sh"
