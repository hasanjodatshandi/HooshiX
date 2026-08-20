#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/kind_verify.sh"
"$ROOT/scripts/platform/istio_foundation_verify.sh"
"$ROOT/scripts/platform/kyverno_verify.sh"
"$ROOT/scripts/platform/staging_data_verify.sh"
"$ROOT/scripts/platform/staging_verify.sh"
"$ROOT/scripts/platform/edge_verify.sh"
"$ROOT/scripts/platform/observability_verify.sh"
nonready=$(k get pods -A -o json | python3 -c 'import json,sys
d=json.load(sys.stdin); bad=[]
for pod in d["items"]:
 phase=pod.get("status",{}).get("phase")
 if phase in ("Succeeded",): continue
 cs=pod.get("status",{}).get("containerStatuses") or []
 if phase != "Running" or any(not item.get("ready",False) for item in cs):
  bad.append("{}/{}:{}".format(pod["metadata"]["namespace"],pod["metadata"]["name"],phase))
print("\n".join(bad))')
[[ -z "$nonready" ]] || { echo "$nonready" >&2; fail "non-ready platform pods remain"; }
echo "HooshiX production-fidelity staging lane verification PASSED"
