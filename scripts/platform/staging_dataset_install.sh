#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
python3 - <<'PY'
import importlib.util
from pathlib import Path
root=Path.cwd(); spec=importlib.util.spec_from_file_location('local_runtime',root/'scripts/local/runtime.py'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.RUNTIME=root/'.platform-runtime'/'staging'; m.DATASET=m.RUNTIME/'compromised-password'; m.DATASET.mkdir(parents=True,exist_ok=True)
r=m.build_compromised_password_dataset()
import hashlib,json,re,subprocess
manifest_path=Path(r['manifest'])
manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
manifest_sha=hashlib.sha256(manifest_path.read_bytes()).hexdigest()
if manifest_sha != r['manifest_sha'] or re.fullmatch(r'[0-9a-f]{64}', manifest_sha) is None:
    raise SystemExit('generated dataset manifest digest is invalid')
if manifest.get('source_kind') != 'GENERATED_TEST_FIXTURE' or manifest.get('hash_mode') != 'SHA1':
    raise SystemExit('generated staging dataset source/hash contract mismatch')
if manifest.get('prefix_cardinality_bound') != 16 or manifest.get('serialized_response_bytes_bound') != 4096:
    raise SystemExit('generated staging dataset compatibility bounds mismatch')
if manifest.get('builder_git_revision') != subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip():
    raise SystemExit('generated staging dataset builder revision does not match current HEAD')
state=m.RUNTIME/'dataset.env'
state.write_text(f'COMPROMISED_PASSWORD_MANIFEST_SHA256={manifest_sha}\n', encoding='ascii')
state.chmod(0o600)
PY
docker exec platform-local-worker mkdir -p /var/local/hooshix/compromised-password
docker cp "$ROOT/.platform-runtime/staging/compromised-password/compromised-password.sqlite" platform-local-worker:/var/local/hooshix/compromised-password/corpus.sqlite
docker cp "$ROOT/.platform-runtime/staging/compromised-password/compromised-password.manifest.json" platform-local-worker:/var/local/hooshix/compromised-password/release-manifest.json
docker exec platform-local-worker chmod 0444 /var/local/hooshix/compromised-password/corpus.sqlite /var/local/hooshix/compromised-password/release-manifest.json
k apply -f "$ROOT/infrastructure/staging/compromised-password-pv.yaml"
k wait --for=jsonpath='{.status.phase}'=Bound pvc/compromised-password-dataset -n platform-apps --timeout=30s
echo "Compromised Password generated staging fixture verification PASSED"
