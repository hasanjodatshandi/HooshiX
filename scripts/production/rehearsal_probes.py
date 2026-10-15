"""Two independent disposable probes at most; callers own stable policy and cleanup."""
from concurrent.futures import ThreadPoolExecutor


def independent_probes(cases, probe):
    with ThreadPoolExecutor(max_workers=2) as pool:
        return list(pool.map(probe, cases))
