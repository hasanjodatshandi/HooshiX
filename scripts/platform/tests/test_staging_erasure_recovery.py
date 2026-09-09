import importlib.util
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "staging_erasure_recovery", ROOT / "scripts/platform/staging_erasure_recovery.py"
)
rehearsal = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(rehearsal)


class StagingErasureRecoveryTest(unittest.TestCase):
    def test_image_state_requires_exact_five_service_repositories_and_digests(self):
        lines = [
            "BUILD_GIT_REVISION=" + "a" * 40,
            "BUILD_SOURCE_STATE=clean",
            "BUILD_WORKTREE_SHA256=" + "b" * 64,
        ]
        for application, repository in rehearsal.IMAGE_REPOSITORIES.items():
            prefix = application.upper().replace("-", "_")
            lines.extend(
                [
                    prefix + "_REPOSITORY=" + repository,
                    prefix + "_DIGEST=sha256:" + "c" * 64,
                ]
            )
        valid = "\n".join(lines) + "\n"
        self.assertEqual(13, len(rehearsal.parse_image_state(valid)))

        with self.assertRaisesRegex(rehearsal.RehearsalError, "invalid"):
            rehearsal.parse_image_state(valid + "UNEXPECTED=value\n")
        with self.assertRaisesRegex(rehearsal.RehearsalError, "invalid"):
            rehearsal.parse_image_state(valid.replace("sha256:" + "c" * 64, "latest", 1))

    def test_seed_is_non_pii_and_has_all_participants(self):
        sql = rehearsal.erasure_seed_sql(
            uuid.UUID("10000000-0000-4000-8000-000000000001"),
            uuid.UUID("20000000-0000-4000-8000-000000000002"),
            uuid.UUID("30000000-0000-4000-8000-000000000003"),
        )
        for participant in (
            "IDENTITY_SERVICE",
            "AUTHORIZATION_SERVICE",
            "NOTIFICATION_SERVICE",
            "WEB_BFF",
        ):
            self.assertIn(participant, sql)
        for prohibited in ("email", "phone", "first_name", "last_name", "contact"):
            self.assertNotIn(prohibited, sql.lower())

    def test_snapshot_and_restore_are_restricted_to_participant_databases(self):
        self.assertIn("--format=custom", rehearsal.snapshot_command("identity"))
        restore = rehearsal.restore_command("notification")
        self.assertIn("--clean", restore)
        self.assertIn("--single-transaction", restore)
        with self.assertRaisesRegex(ValueError, "outside"):
            rehearsal.snapshot_command("postgres")
        with self.assertRaisesRegex(ValueError, "outside"):
            rehearsal.restore_command("postgres")

    def test_evidence_is_identifier_free_commit_bound_staging_aggregate(self):
        evidence = rehearsal.staging_evidence("a" * 40)
        self.assertEqual("staging", evidence["environment"])
        self.assertEqual(4, evidence["participant_count"])
        self.assertTrue(evidence["restore_completed"])
        self.assertTrue(evidence["no_reappearance"])
        self.assertNotIn("user_id", evidence)
        self.assertNotIn("erasure_request_id", evidence)
        with self.assertRaisesRegex(ValueError, "full Git revision"):
            rehearsal.staging_evidence("main")


if __name__ == "__main__":
    unittest.main()
