from __future__ import annotations

import copy
import datetime as dt
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hibp_dataset_staging


class HibpDatasetStagingTest(unittest.TestCase):
    NOW = dt.datetime(2026, 9, 10, 9, 0, tzinfo=dt.timezone.utc)
    REVISION = "a" * 40

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.sqlite = self.root / "corpus.sqlite"
        self.sqlite.write_bytes(b"immutable-sqlite-fixture")
        self.manifest = self.root / "manifest.json"
        self.data = {
            "manifest_version": 2,
            "format_version": 1,
            "sqlite_schema_version": 1,
            "source_kind": hibp_dataset_staging.SOURCE_KIND,
            "hash_mode": "SHA1",
            "retrieval_started_at_utc": "2026-09-09T05:00:00Z",
            "retrieval_completed_at_utc": "2026-09-09T06:00:00Z",
            "source_artifact_sha256": "b" * 64,
            "acquisition_tool": {
                "name": "PwnedPasswordsDownloader",
                "version": "0.5.2608.1930",
                "sha256": "c" * 64,
            },
            "builder_git_revision": self.REVISION,
            "source_line_count": 3,
            "record_count": 2,
            "duplicate_line_count": 1,
            "max_prefix_cardinality": 2509,
            "max_serialized_response_bytes": 102932,
            "prefix_cardinality_bound": hibp_dataset_staging.PREFIX_BOUND,
            "serialized_response_bytes_bound": hibp_dataset_staging.RESPONSE_BOUND,
            "content_sha256": "d" * 64,
            "sqlite_artifact_sha256": hashlib.sha256(self.sqlite.read_bytes()).hexdigest(),
        }
        self._write_manifest(self.data)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_manifest(self, data: dict) -> None:
        self.manifest.write_text(json.dumps(data), encoding="utf-8")

    def test_validates_release_and_stages_private_exact_values(self) -> None:
        data, digest = hibp_dataset_staging.validate_release(
            self.sqlite, self.manifest, self.REVISION, self.NOW
        )
        private = self.root / "private"
        private.mkdir(mode=0o700)
        overlay = private / "hibp.yaml.next"
        state = self.root / "dataset.env.next"

        hibp_dataset_staging.stage_private_values(data, digest, overlay, state)

        self.assertEqual(0o600, overlay.stat().st_mode & 0o777)
        self.assertEqual(0o600, state.stat().st_mode & 0o777)
        self.assertIn("existingClaim: compromised-password-hibp-dataset", overlay.read_text())
        self.assertIn("maxPrefixCardinality: 4096", overlay.read_text())
        self.assertIn("maxSerializedResponseBytes: 131072", overlay.read_text())
        self.assertEqual(f"COMPROMISED_PASSWORD_MANIFEST_SHA256={digest}\n", state.read_text())

    def test_rejects_symlink_and_artifact_digest_mismatch(self) -> None:
        symlink = self.root / "linked.sqlite"
        symlink.symlink_to(self.sqlite)
        with self.assertRaisesRegex(ValueError, "non-symlink"):
            hibp_dataset_staging.validate_release(
                symlink, self.manifest, self.REVISION, self.NOW
            )
        self.sqlite.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            hibp_dataset_staging.validate_release(
                self.sqlite, self.manifest, self.REVISION, self.NOW
            )

    def test_rejects_stale_wrong_source_revision_and_unreviewed_envelope(self) -> None:
        mutations = (
            ("retrieval_completed_at_utc", "2026-07-01T00:00:00Z", "readiness window"),
            ("source_kind", "GENERATED_TEST_FIXTURE", "source/hash identity"),
            ("builder_git_revision", "e" * 40, "current HEAD"),
            ("prefix_cardinality_bound", 8192, "reviewed compatibility envelope"),
        )
        for field, value, message in mutations:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.data)
                changed[field] = value
                if field == "retrieval_completed_at_utc":
                    changed["retrieval_started_at_utc"] = "2026-06-30T23:00:00Z"
                self._write_manifest(changed)
                with self.assertRaisesRegex(ValueError, message):
                    hibp_dataset_staging.validate_release(
                        self.sqlite, self.manifest, self.REVISION, self.NOW
                    )

    def test_rejects_insecure_private_directory(self) -> None:
        data, digest = hibp_dataset_staging.validate_release(
            self.sqlite, self.manifest, self.REVISION, self.NOW
        )
        private = self.root / "private"
        private.mkdir(mode=0o755)
        os.chmod(private, 0o755)
        with self.assertRaisesRegex(ValueError, "mode 0700"):
            hibp_dataset_staging.stage_private_values(
                data, digest, private / "hibp.yaml", self.root / "dataset.env"
            )


if __name__ == "__main__":
    unittest.main()
