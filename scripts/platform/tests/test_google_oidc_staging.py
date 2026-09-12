from __future__ import annotations

import json
import stat
import tempfile
import unittest
from pathlib import Path

from scripts.platform import google_oidc_staging


class GoogleOidcStagingTest(unittest.TestCase):
    def test_derives_private_secret_and_values_without_secret_in_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "client.json"
            client_id = "123-example.apps.googleusercontent.com"
            client_secret = "client-secret-value"
            source.write_text(
                json.dumps({"web": {"client_id": client_id, "client_secret": client_secret}}),
                encoding="utf-8",
            )
            secret = root / "secret"
            values = root / "values.yaml"

            google_oidc_staging.derive(source, secret, values)

            self.assertEqual(client_secret, secret.read_text(encoding="utf-8"))
            parsed = json.loads(values.read_text(encoding="utf-8"))
            self.assertEqual({"googleOidc": {"enabled": True, "clientId": client_id}}, parsed)
            self.assertNotIn(client_secret, values.read_text(encoding="utf-8"))
            self.assertEqual(0o600, stat.S_IMODE(secret.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(values.stat().st_mode))

    def test_rejects_invalid_or_escaped_inputs_before_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as other:
            root = Path(directory)
            source = root / "client.json"
            secret = root / "secret"
            values = root / "values.yaml"
            cases = (
                {},
                {"web": {"client_id": "invalid", "client_secret": "secret"}},
                {
                    "web": {
                        "client_id": "123-example.apps.googleusercontent.com",
                        "client_secret": "unsafe secret",
                    }
                },
            )
            for document in cases:
                with self.subTest(document=document):
                    source.write_text(json.dumps(document), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        google_oidc_staging.derive(source, secret, values)
                    self.assertFalse(secret.exists())
                    self.assertFalse(values.exists())

            source.write_text(
                json.dumps(
                    {
                        "web": {
                            "client_id": "123-example.apps.googleusercontent.com",
                            "client_secret": "secret",
                        }
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                google_oidc_staging.derive(source, Path(other) / "secret", values)

    def test_rejects_symlink_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            actual = root / "actual.json"
            actual.write_text("{}", encoding="utf-8")
            source = root / "client.json"
            source.symlink_to(actual.name)
            with self.assertRaises(ValueError):
                google_oidc_staging.derive(source, root / "secret", root / "values.yaml")


if __name__ == "__main__":
    unittest.main()
