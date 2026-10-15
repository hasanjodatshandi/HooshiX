"""Independent probes overlap, stay bounded and drain before caller cleanup."""
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rehearsal_probes import independent_probes


class IndependentProbesTest(unittest.TestCase):
    def test_two_workers_overlap_without_reordering_or_omitting_cases(self):
        barrier = threading.Barrier(2)
        lock = threading.Lock()
        active = peak = 0
        seen = []

        def probe(case):
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(peak, active)
                seen.append(case)
            barrier.wait(timeout=5)
            with lock: active -= 1
            return case * 2

        self.assertEqual(list(range(0, 12, 2)), independent_probes(range(6), probe))
        self.assertEqual(list(range(6)), sorted(seen))
        self.assertEqual(2, peak)
        self.assertEqual(0, active)

    def test_error_is_not_success_and_workers_drain_before_cleanup(self):
        started = threading.Event()
        finished = threading.Event()

        def probe(case):
            if case == 0:
                if not started.wait(5): raise AssertionError('second worker did not start')
                raise ValueError('synthetic probe failed')
            started.set()
            finished.set()

        with self.assertRaisesRegex(ValueError, 'synthetic probe failed'):
            independent_probes([0, 1], probe)
        self.assertTrue(finished.is_set())
        self.assertEqual([], independent_probes([], probe))


if __name__ == '__main__': unittest.main()
