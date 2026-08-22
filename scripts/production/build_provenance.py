#!/usr/bin/env python3
"""Build a bounded SLSA provenance v1 predicate for one reviewed production image."""
from __future__ import annotations
import argparse,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import verify_release
BUILD_TYPE="https://github.com/hasanjodatshandi/HooshiX/.github/workflows/production-release.yml"
SOURCE_URI="git+https://github.com/hasanjodatshandi/HooshiX.git"
def build(manifest:dict,image:str,invocation_id:str)->dict:
    if image not in manifest["images"].values(): raise ValueError("image is not part of the reviewed release manifest")
    revision=manifest["git_revision"]
    return {"buildDefinition":{"buildType":BUILD_TYPE,"externalParameters":{"gitRevision":revision,"image":image},"internalParameters":{},"resolvedDependencies":[{"uri":f"{SOURCE_URI}@{revision}","digest":{"gitCommit":revision}}]},"runDetails":{"builder":{"id":verify_release.EXPECTED_CERTIFICATE_IDENTITY},"metadata":{"invocationId":invocation_id}}}
def main()->int:
    p=argparse.ArgumentParser(); p.add_argument("--manifest",required=True,type=Path); p.add_argument("--image",required=True); p.add_argument("--invocation-id",required=True); p.add_argument("--output",required=True,type=Path); a=p.parse_args()
    try:
        manifest=verify_release.load_manifest(a.manifest); errors=verify_release.validate_manifest(manifest)
        if errors: raise ValueError("; ".join(errors))
        if not a.invocation_id or len(a.invocation_id)>256: raise ValueError("invocation-id is invalid")
        data=build(manifest,a.image,a.invocation_id)
        a.output.write_text(json.dumps(data,sort_keys=True,separators=(",",":"))+"\n",encoding="utf-8")
    except (OSError,json.JSONDecodeError,ValueError) as exc:
        print(f"Production provenance build FAILED: {exc}",file=sys.stderr); return 1
    print(f"Production provenance build PASSED: {a.output}"); return 0
if __name__=="__main__": raise SystemExit(main())
