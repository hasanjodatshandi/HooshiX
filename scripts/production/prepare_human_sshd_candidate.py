"""Prepare a private, uninstalled host SSH candidate from reviewed inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import tempfile
from pathlib import Path

HOST_CONFIG = Path("/etc/ssh/sshd_config")
EXPECTED_LAST_MATCH = "Match User hooshixtunnel"
EXPECTED_TAIL = [
    "Match User hooshixadmin", "DisableForwarding yes", "AllowAgentForwarding no",
    "AllowTcpForwarding no", "AllowStreamLocalForwarding no", "X11Forwarding no",
    "PermitTunnel no", "GatewayPorts no",
]


def prepare(source: Path, tail: Path, output_dir: Path, expected_hash: str) -> dict:
    if os.geteuid() == 0:
        raise ValueError("candidate preparation must use the unprivileged operator")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
        raise ValueError("expected source hash is invalid")
    source_bytes = source.read_bytes()
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    if source_hash != expected_hash:
        raise ValueError("source config changed; inspect again before preparing")
    source_lines = source_bytes.decode("utf-8").splitlines()
    matches = [line.strip() for line in source_lines
               if re.match(r"^\s*Match\s+", line, re.IGNORECASE)]
    if not matches or matches[-1] != EXPECTED_LAST_MATCH:
        raise ValueError("last Match block changed; inspect before preparing")
    if any(re.match(r"^\s*Match\s+User\s+hooshixadmin(?:\s|$)", line, re.IGNORECASE)
           for line in source_lines):
        raise ValueError("human Match already exists; do not append twice")
    tail_bytes = tail.read_bytes()
    active_tail = [line.strip() for line in tail_bytes.decode("utf-8").splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
    if active_tail != EXPECTED_TAIL:
        raise ValueError("human Match template drifted")
    if output_dir.is_symlink() or not output_dir.is_dir():
        raise ValueError("private output directory is unavailable")
    directory = output_dir.stat()
    if directory.st_uid != os.geteuid() or stat.S_IMODE(directory.st_mode) & 0o077:
        raise ValueError("output directory must be operator-owned and mode 0700")
    candidate = source_bytes.rstrip(b"\n") + b"\n\n" + tail_bytes.rstrip(b"\n") + b"\n"
    with tempfile.NamedTemporaryFile(dir=output_dir, prefix="hooshix-sshd-candidate-",
                                     suffix=".conf", delete=False) as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(candidate)
        output.flush()
        os.fsync(output.fileno())
        candidate_path = output.name
    return {"candidate_path": candidate_path,
            "candidate_sha256": hashlib.sha256(candidate).hexdigest(),
            "source_sha256": source_hash,
            "installed": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-source-sha256", required=True)
    parser.add_argument("--tail", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(HOST_CONFIG, args.tail, args.output_dir,
                             args.expected_source_sha256), sort_keys=True))


if __name__ == "__main__":
    main()
