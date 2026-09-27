from __future__ import annotations

import copy
import datetime as dt
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import jit_grant


class JitGrantTest(unittest.TestCase):
    def setUp(self) -> None:
        self.now = dt.datetime(2026, 9, 27, 12, 0, tzinfo=dt.timezone.utc)
        self.grant = {
            "schema_version": 1,
            "grant_id": "grant-1",
            "subject": "operator-1",
            "scope": ["host.read", "k3s.read"],
            "reason": "incident-42",
            "ticket": "INC-42",
            "issued_at": "2026-09-27T11:55:00Z",
            "expires_at": "2026-09-27T12:20:00Z",
        }

    def test_canonical_payload_excludes_untrusted_extra_fields(self) -> None:
        altered = copy.deepcopy(self.grant)
        altered["secret"] = "must-not-be-signed"
        self.assertEqual(jit_grant.canonical_payload(self.grant), jit_grant.canonical_payload(altered))

    def test_expired_grant_rejected(self) -> None:
        expired = copy.deepcopy(self.grant)
        expired["expires_at"] = "2026-09-27T11:59:59Z"
        with self.assertRaisesRegex(ValueError, "expired"):
            jit_grant.validate_shape(expired, self.now)

    def test_lifetime_over_30_minutes_rejected(self) -> None:
        long_lived = copy.deepcopy(self.grant)
        long_lived["expires_at"] = "2026-09-27T12:26:00Z"
        with self.assertRaisesRegex(ValueError, "30-minute"):
            jit_grant.validate_shape(long_lived, self.now)

    def test_duplicate_approvers_rejected_before_signature_check(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            grant_path = root / "grant.json"
            grant_path.write_text(json.dumps(self.grant), encoding="utf-8")
            with patch.object(jit_grant, "verify_signature") as verify:
                with self.assertRaisesRegex(ValueError, "distinct"):
                    jit_grant.verify(grant_path, [("one", root / "a"), ("one", root / "b")], root / "allowed", "test", self.now)
                verify.assert_not_called()


if __name__ == "__main__":
    unittest.main()
