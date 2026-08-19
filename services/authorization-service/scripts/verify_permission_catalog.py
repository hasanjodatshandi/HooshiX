#!/usr/bin/env python3
from pathlib import Path
import re, sys
CATALOG = Path(__file__).resolve().parents[1] / "contracts" / "permissions" / "permission-catalog.yaml"
KEY = re.compile(r"^  - key: ((?:[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+)|platform\.legal_hold\.manage)$")
SCOPE = re.compile(r"^    scope: (TENANT|PLATFORM)$")
LIFE = re.compile(r"^    lifecycle: (ACTIVE|DEPRECATED|RETIRED)$")
REQUIRED = {"tenant.read","tenant.delete","role.read","role.create","role.update","role.archive","role.permission.manage","membership.read","membership.role.assign","membership.permission.manage","membership.owner.assign","platform.tenant.create","platform.tenant.suspend","platform.tenant.resume","platform.tenant.restore","platform.legal_hold.manage"}
def main():
    lines = CATALOG.read_text(encoding="utf-8-sig").splitlines()
    if lines[:2] != ["version: 1", "permissions:"]:
        print("invalid catalog header", file=sys.stderr); return 1
    rows=[]
    for i in range(2, len(lines), 3):
        if i+2 >= len(lines): print("truncated permission row", file=sys.stderr); return 1
        k,s,l=KEY.match(lines[i]),SCOPE.match(lines[i+1]),LIFE.match(lines[i+2])
        if not (k and s and l): print(f"invalid permission row at line {i+1}", file=sys.stderr); return 1
        if len(k.group(1))>128: print("permission key too long", file=sys.stderr); return 1
        rows.append(k.group(1))
    if len(rows)!=len(set(rows)) or set(rows)!=REQUIRED:
        print("permission catalog differs from current ADR set", file=sys.stderr); return 1
    print(f"Permission catalog verification PASSED ({len(rows)} permissions).")
    return 0
if __name__ == "__main__": raise SystemExit(main())
