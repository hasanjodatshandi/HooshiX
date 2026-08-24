#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, os, stat, subprocess
from pathlib import Path

def git(root: Path,*args:str)->bytes:
    return subprocess.check_output(["git",*args],cwd=root)

def worktree_sha256(root: Path,revision:str,status:bytes)->str:
    digest=hashlib.sha256()
    digest.update(revision.encode("ascii")+b"\0"+status+b"\0")
    digest.update(git(root,"diff","--binary","--no-ext-diff","HEAD","--"))
    untracked=git(root,"ls-files","--others","--exclude-standard","-z").split(b"\0")
    for raw in sorted(x for x in untracked if x):
        path=root/raw.decode("utf-8",errors="strict")
        digest.update(raw+b"\0")
        metadata=path.lstat()
        digest.update(oct(stat.S_IMODE(metadata.st_mode)).encode("ascii")+b"\0")
        if path.is_symlink():
            digest.update(b"symlink\0"+os.readlink(path).encode("utf-8"))
        elif path.is_file():
            digest.update(b"file\0")
            with path.open("rb") as handle:
                for chunk in iter(lambda:handle.read(1024*1024),b""):
                    digest.update(chunk)
        else:
            digest.update(b"other\0")
        digest.update(b"\0")
    return digest.hexdigest()

def snapshot(root:Path)->dict[str,str]:
    root=root.resolve()
    revision=git(root,"rev-parse","HEAD").decode("ascii").strip()
    status=git(root,"status","--porcelain=v1","-z","--untracked-files=all")
    return {"revision":revision,"source_state":"clean" if not status else "dirty","worktree_sha256":worktree_sha256(root,revision,status)}

def verify(root:Path,expected:dict[str,str])->dict[str,str]:
    actual=snapshot(root)
    if actual!=expected:
        raise SystemExit("repository source changed after staging provenance capture: "+json.dumps({"expected":expected,"actual":actual},sort_keys=True))
    return actual

def main():
    parser=argparse.ArgumentParser(description="Capture and verify staging Git/worktree provenance")
    parser.add_argument("--root",type=Path,required=True)
    sub=parser.add_subparsers(dest="command",required=True)
    sub.add_parser("snapshot")
    verify_parser=sub.add_parser("verify")
    verify_parser.add_argument("--revision",required=True)
    verify_parser.add_argument("--source-state",choices=("clean","dirty"),required=True)
    verify_parser.add_argument("--worktree-sha256",required=True)
    args=parser.parse_args()
    if args.command=="snapshot":
        print(json.dumps(snapshot(args.root),sort_keys=True)); return
    expected={"revision":args.revision,"source_state":args.source_state,"worktree_sha256":args.worktree_sha256}
    verify(args.root,expected)
    print(json.dumps(expected,sort_keys=True))
if __name__=="__main__":
    main()
