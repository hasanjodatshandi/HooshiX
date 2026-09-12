#!/usr/bin/env python3
"""Derive private staging Google OIDC files from a downloaded Web client JSON."""

from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
from pathlib import Path

CLIENT_ID = re.compile(r"[0-9A-Za-z._-]{1,220}[.]apps[.]googleusercontent[.]com")


def _replace_private(path: Path, content: str) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def derive(source: Path, secret_path: Path, values_path: Path) -> None:
    if source.is_symlink() or not source.is_file():
        raise ValueError("staging Google OIDC client JSON must be a regular non-symlink file")
    if secret_path.parent != source.parent or values_path.parent != source.parent:
        raise ValueError("derived Google OIDC files must remain in the private source directory")
    try:
        document = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("staging Google OIDC client JSON is unreadable or invalid") from error
    web = document.get("web") if isinstance(document, dict) else None
    client_id = web.get("client_id") if isinstance(web, dict) else None
    client_secret = web.get("client_secret") if isinstance(web, dict) else None
    if not isinstance(client_id, str) or CLIENT_ID.fullmatch(client_id) is None:
        raise ValueError("staging Google OIDC client JSON has an invalid web.client_id")
    if (
        not isinstance(client_secret, str)
        or not 1 <= len(client_secret) <= 512
        or any(character.isspace() for character in client_secret)
    ):
        raise ValueError("staging Google OIDC client JSON has an invalid web.client_secret")
    _replace_private(secret_path, client_secret)
    _replace_private(
        values_path,
        json.dumps(
            {"googleOidc": {"enabled": True, "clientId": client_id}}, separators=(",", ":")
        )
        + "\n",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--secret-output", type=Path, required=True)
    parser.add_argument("--values-output", type=Path, required=True)
    arguments = parser.parse_args()
    os.umask(0o077)
    try:
        derive(arguments.source, arguments.secret_output, arguments.values_output)
    except ValueError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
