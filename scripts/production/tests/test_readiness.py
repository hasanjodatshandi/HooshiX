from __future__ import annotations

import copy
import datetime as dt
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import readiness


class ProductionReadinessEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.revision = "a" * 40
        self.now = dt.datetime(2026, 8, 22, tzinfo=dt.timezone.utc)
        profile = __import__("json").loads(readiness.PROFILE.read_text(encoding="utf-8"))
        self.data = {
            "schema_version": 1,
            "profile": "production-single-server",
            "git_revision": self.revision,
            "external_inputs": {key: f"evidence/{key}/2026-08-22" for key in profile["required_external_inputs"]},
            "gates": {
                gate: {
                    "status": "PASS",
                    "evidence_id": f"evidence/{gate}/2026-08-22",
                    "observed_at": "2026-08-22T00:00:00Z",
                }
                for gate in readiness.GATES
            },
            "go_live_approved": True,
        }

    def test_complete_evidence_passes(self) -> None:
        self.assertEqual([], readiness.validate(self.data, self.revision, now=self.now))

    def test_missing_gate_fails_closed(self) -> None:
        data = copy.deepcopy(self.data)
        del data["gates"]["cold_dr"]
        self.assertTrue(any("exactly the mandatory" in e for e in readiness.validate(data, self.revision, now=self.now)))

    def test_non_pass_gate_fails_closed(self) -> None:
        data = copy.deepcopy(self.data)
        data["gates"]["cold_dr"]["status"] = "NOT_VERIFIED"
        self.assertTrue(any("cold_dr is not PASS" in e for e in readiness.validate(data, self.revision, now=self.now)))

    def test_go_live_requires_explicit_approval(self) -> None:
        data = copy.deepcopy(self.data)
        data["go_live_approved"] = False
        self.assertTrue(any("go_live_approved" in e for e in readiness.validate(data, self.revision, now=self.now)))

    def test_external_input_placeholder_or_invalid_reference_fails(self) -> None:
        data = copy.deepcopy(self.data)
        data["external_inputs"]["production_registry"] = "TBD"
        # Current evidence ID grammar allows letters, but the readiness verifier must reject placeholder semantics.
        self.assertTrue(any("production_registry" in e for e in readiness.validate(data, self.revision, now=self.now)))


if __name__ == "__main__":
    unittest.main()
