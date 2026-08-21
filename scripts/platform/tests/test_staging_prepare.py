from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "staging_prepare.py"
spec = importlib.util.spec_from_file_location("staging_prepare", MODULE_PATH)
assert spec and spec.loader
staging_prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(staging_prepare)


class StagingPrepareTest(unittest.TestCase):
    def test_generated_token_is_safe_for_sql_uri_and_acl_interpolation(self) -> None:
        value = staging_prepare.token()
        self.assertIsNotNone(staging_prepare.TOKEN_RE.fullmatch(value))
        self.assertNotIn("'", value)
        self.assertNotIn(":", value)
        self.assertNotIn("@", value)

    def test_metadata_rejects_unsafe_or_unexpected_state(self) -> None:
        names = ["postgres_admin"]
        valid = {"postgres_admin": "A" * 43}
        self.assertEqual(staging_prepare.validate_metadata(valid, names), valid)
        for invalid in (
            {"postgres_admin": "unsafe'quote"},
            {"postgres_admin": "A" * 43, "unexpected": "B" * 43},
            ["not", "an", "object"],
        ):
            with self.assertRaises(SystemExit):
                staging_prepare.validate_metadata(invalid, names)


if __name__ == "__main__":
    unittest.main()
