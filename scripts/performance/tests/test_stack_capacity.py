from __future__ import annotations

import copy
import datetime as dt
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stack_capacity


class StackCapacityEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        now = dt.datetime(2026, 8, 31, tzinfo=dt.timezone.utc)
        self.evidence = {
            "schema": stack_capacity.SCHEMA,
            "profile": "staging-single-server",
            "git_revision": "a" * 40,
            "started_at": now.isoformat(),
            "completed_at": (now + dt.timedelta(minutes=1)).isoformat(),
            "mode": "load",
            "scenario": "invalid-login",
            "configuration": {
                "duration_seconds": 60,
                "concurrency": 16,
                "p99_limit_ms": 500,
                "min_success_percent": 99,
                "min_cpu_headroom_percent": 30,
                "min_memory_headroom_percent": 30,
            },
            "results": {
                "operations": 1000,
                "successes": 1000,
                "unexpected_failures": 0,
                "success_percent": 100.0,
                "errors_by_code": {},
                "latency_ms": {"p50": 30, "p95": 80, "p99": 120, "max": 200},
                "system": {
                    "sample_count": 60,
                    "max_cpu_used_percent": 62,
                    "min_cpu_headroom_percent": 38,
                    "max_memory_used_percent": 57,
                    "min_memory_headroom_percent": 43,
                    "max_swap_used_bytes": 0,
                    "min_root_disk_free_bytes": 10_000_000_000,
                },
            },
            "passed": True,
            "failure_reasons": [],
        }

    def test_accepts_complete_passing_evidence(self) -> None:
        self.assertEqual([], stack_capacity.validate_evidence(self.evidence))

    def test_rejects_short_soak_and_inconsistent_counters(self) -> None:
        data = copy.deepcopy(self.evidence)
        data["mode"] = "soak"
        data["results"]["successes"] = 999
        errors = stack_capacity.validate_evidence(data)
        self.assertTrue(any("duration_seconds" in error for error in errors))
        self.assertTrue(any("operation counters" in error for error in errors))

    def test_failure_reasons_and_passed_must_match_measurements(self) -> None:
        data = copy.deepcopy(self.evidence)
        data["results"]["system"]["min_memory_headroom_percent"] = 29
        data["results"]["system"]["max_memory_used_percent"] = 71
        self.assertTrue(any("failure_reasons" in error for error in stack_capacity.validate_evidence(data)))
        data["failure_reasons"] = ["MEMORY_HEADROOM_BELOW_LIMIT"]
        data["passed"] = False
        self.assertEqual([], stack_capacity.validate_evidence(data))

    def test_rejects_placeholder_revision_unknown_keys_and_error_cardinality(self) -> None:
        data = copy.deepcopy(self.evidence)
        data["git_revision"] = "main"
        data["unexpected"] = True
        data["results"]["errors_by_code"] = {f"ERROR_{index}": 1 for index in range(33)}
        errors = stack_capacity.validate_evidence(data)
        self.assertTrue(any("top-level" in error for error in errors))
        self.assertTrue(any("git_revision" in error for error in errors))
        self.assertTrue(any("errors_by_code" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
