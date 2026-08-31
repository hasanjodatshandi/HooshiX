#!/usr/bin/env python3
"""Run and verify bounded HooshiX staging load/soak capacity evidence."""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import datetime as dt
import http.cookiejar
import json
import math
import os
import re
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

SCHEMA = "hooshix-stack-capacity-v1"
REVISION = re.compile(r"^[0-9a-f]{40}$")
MIN_DURATION = {"load": 60, "soak": 1800}
MAX_DURATION = 86_400
MAX_CONCURRENCY = 256
MAX_BODY = 16_384


def _number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def validate_evidence(data: object) -> list[str]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["evidence must be an object"]
    expected = {
        "schema",
        "profile",
        "git_revision",
        "started_at",
        "completed_at",
        "mode",
        "scenario",
        "configuration",
        "results",
        "passed",
        "failure_reasons",
    }
    if set(data) != expected:
        errors.append("top-level evidence keys are invalid")
    if data.get("schema") != SCHEMA:
        errors.append("schema is invalid")
    if data.get("profile") not in {"staging-single-server", "production-single-server"}:
        errors.append("profile is invalid")
    if not isinstance(data.get("git_revision"), str) or not REVISION.fullmatch(data["git_revision"]):
        errors.append("git_revision must be a full lowercase SHA")
    for field in ("started_at", "completed_at"):
        try:
            parsed = dt.datetime.fromisoformat(str(data.get(field)).replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                raise ValueError
        except ValueError:
            errors.append(f"{field} must be an offset timestamp")
    mode = data.get("mode")
    if mode not in MIN_DURATION:
        errors.append("mode is invalid")
    if data.get("scenario") not in {"session-bootstrap", "invalid-login"}:
        errors.append("scenario is invalid")

    config = data.get("configuration")
    config_keys = {
        "duration_seconds",
        "concurrency",
        "p99_limit_ms",
        "min_success_percent",
        "min_cpu_headroom_percent",
        "min_memory_headroom_percent",
    }
    if not isinstance(config, dict) or set(config) != config_keys:
        errors.append("configuration is invalid")
        config = {}
    duration = config.get("duration_seconds")
    if not isinstance(duration, int) or isinstance(duration, bool) or not (
        mode in MIN_DURATION and MIN_DURATION[mode] <= duration <= MAX_DURATION
    ):
        errors.append("duration_seconds is outside the admissible mode bound")
    concurrency = config.get("concurrency")
    if not isinstance(concurrency, int) or isinstance(concurrency, bool) or not (
        1 <= concurrency <= MAX_CONCURRENCY
    ):
        errors.append("concurrency is outside the admissible bound")
    for field, minimum, maximum in (
        ("p99_limit_ms", 1, 60_000),
        ("min_success_percent", 90, 100),
        ("min_cpu_headroom_percent", 30, 100),
        ("min_memory_headroom_percent", 30, 100),
    ):
        value = config.get(field)
        if not _number(value) or not minimum <= value <= maximum:
            errors.append(f"{field} is outside the admissible bound")

    results = data.get("results")
    result_keys = {
        "operations",
        "successes",
        "unexpected_failures",
        "success_percent",
        "errors_by_code",
        "latency_ms",
        "system",
    }
    if not isinstance(results, dict) or set(results) != result_keys:
        errors.append("results are invalid")
        results = {}
    operations = results.get("operations")
    successes = results.get("successes")
    failures = results.get("unexpected_failures")
    if not all(isinstance(v, int) and not isinstance(v, bool) and v >= 0 for v in (operations, successes, failures)):
        errors.append("operation counters are invalid")
    elif operations <= 0 or successes + failures != operations:
        errors.append("operation counters are inconsistent")
    success_percent = results.get("success_percent")
    if not _number(success_percent) or not 0 <= success_percent <= 100:
        errors.append("success_percent is invalid")
    elif isinstance(operations, int) and operations > 0 and isinstance(successes, int):
        expected_percent = round(successes * 100 / operations, 3)
        if abs(success_percent - expected_percent) > 0.001:
            errors.append("success_percent does not match operation counters")
    error_codes = results.get("errors_by_code")
    if not isinstance(error_codes, dict) or len(error_codes) > 32 or any(
        not isinstance(key, str)
        or not re.fullmatch(r"[A-Z0-9_]{1,64}", key)
        or not isinstance(value, int)
        or isinstance(value, bool)
        or value <= 0
        for key, value in (error_codes.items() if isinstance(error_codes, dict) else [])
    ):
        errors.append("errors_by_code is invalid")
    latency = results.get("latency_ms")
    if not isinstance(latency, dict) or set(latency) != {"p50", "p95", "p99", "max"}:
        errors.append("latency_ms is invalid")
        latency = {}
    latency_values = [latency.get(key) for key in ("p50", "p95", "p99", "max")]
    if not all(_number(value) and value >= 0 for value in latency_values):
        errors.append("latency values are invalid")
    elif latency_values != sorted(latency_values):
        errors.append("latency percentiles are not monotonic")

    system = results.get("system")
    system_keys = {
        "sample_count",
        "max_cpu_used_percent",
        "min_cpu_headroom_percent",
        "max_memory_used_percent",
        "min_memory_headroom_percent",
        "max_swap_used_bytes",
        "min_root_disk_free_bytes",
    }
    if not isinstance(system, dict) or set(system) != system_keys:
        errors.append("system evidence is invalid")
        system = {}
    if not isinstance(system.get("sample_count"), int) or system.get("sample_count", 0) < 2:
        errors.append("system sample_count must be at least two")
    for field in (
        "max_cpu_used_percent",
        "min_cpu_headroom_percent",
        "max_memory_used_percent",
        "min_memory_headroom_percent",
    ):
        if not _number(system.get(field)) or not 0 <= system[field] <= 100:
            errors.append(f"system.{field} is invalid")
    for field in ("max_swap_used_bytes", "min_root_disk_free_bytes"):
        if not isinstance(system.get(field), int) or isinstance(system.get(field), bool) or system[field] < 0:
            errors.append(f"system.{field} is invalid")

    reasons = data.get("failure_reasons")
    if not isinstance(reasons, list) or len(reasons) > 16 or any(
        not isinstance(reason, str) or not re.fullmatch(r"[A-Z0-9_]{1,64}", reason)
        for reason in (reasons if isinstance(reasons, list) else [])
    ):
        errors.append("failure_reasons is invalid")
        reasons = []
    calculated: list[str] = []
    if _number(success_percent) and _number(config.get("min_success_percent")) and success_percent < config["min_success_percent"]:
        calculated.append("SUCCESS_RATE_BELOW_LIMIT")
    if _number(latency.get("p99")) and _number(config.get("p99_limit_ms")) and latency["p99"] > config["p99_limit_ms"]:
        calculated.append("P99_ABOVE_LIMIT")
    if _number(system.get("min_cpu_headroom_percent")) and _number(config.get("min_cpu_headroom_percent")) and system["min_cpu_headroom_percent"] < config["min_cpu_headroom_percent"]:
        calculated.append("CPU_HEADROOM_BELOW_LIMIT")
    if _number(system.get("min_memory_headroom_percent")) and _number(config.get("min_memory_headroom_percent")) and system["min_memory_headroom_percent"] < config["min_memory_headroom_percent"]:
        calculated.append("MEMORY_HEADROOM_BELOW_LIMIT")
    if isinstance(system.get("max_swap_used_bytes"), int) and system["max_swap_used_bytes"] > 0:
        calculated.append("SWAP_USED")
    if sorted(reasons) != sorted(calculated):
        errors.append("failure_reasons do not match measured thresholds")
    if data.get("passed") is not (not calculated):
        errors.append("passed does not match measured thresholds")
    return errors


def _cpu() -> tuple[int, int]:
    fields = [int(value) for value in Path("/proc/stat").read_text(encoding="ascii").splitlines()[0].split()[1:]]
    idle = fields[3] + fields[4]
    return sum(fields), idle


def _memory() -> tuple[int, int, int]:
    values: dict[str, int] = {}
    for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
        key, raw = line.split(":", 1)
        values[key] = int(raw.strip().split()[0]) * 1024
    total = values["MemTotal"]
    available = values["MemAvailable"]
    swap_used = values.get("SwapTotal", 0) - values.get("SwapFree", 0)
    return total, available, swap_used


class SystemSampler:
    def __init__(self) -> None:
        self.stop = threading.Event()
        self.samples: list[tuple[float, float, int, int]] = []
        self.thread = threading.Thread(target=self._run, name="capacity-system-sampler", daemon=True)

    def __enter__(self) -> "SystemSampler":
        self.thread.start()
        return self

    def __exit__(self, *_: object) -> None:
        self.stop.set()
        self.thread.join(timeout=3)

    def _run(self) -> None:
        previous_total, previous_idle = _cpu()
        while not self.stop.wait(1):
            total, idle = _cpu()
            delta = max(1, total - previous_total)
            used = max(0.0, min(100.0, 100.0 * (delta - (idle - previous_idle)) / delta))
            memory_total, memory_available, swap_used = _memory()
            memory_used = 100.0 * (memory_total - memory_available) / memory_total
            disk_free = shutil.disk_usage("/").free
            self.samples.append((used, memory_used, swap_used, disk_free))
            previous_total, previous_idle = total, idle

    def result(self) -> dict[str, int | float]:
        if not self.samples:
            memory_total, memory_available, swap_used = _memory()
            memory_used = 100.0 * (memory_total - memory_available) / memory_total
            disk_free = shutil.disk_usage("/").free
            self.samples.append((0.0, memory_used, swap_used, disk_free))
        cpu = [sample[0] for sample in self.samples]
        memory = [sample[1] for sample in self.samples]
        return {
            "sample_count": len(self.samples),
            "max_cpu_used_percent": round(max(cpu), 3),
            "min_cpu_headroom_percent": round(100 - max(cpu), 3),
            "max_memory_used_percent": round(max(memory), 3),
            "min_memory_headroom_percent": round(100 - max(memory), 3),
            "max_swap_used_bytes": max(sample[2] for sample in self.samples),
            "min_root_disk_free_bytes": min(sample[3] for sample in self.samples),
        }


def _opener(ca_file: str | None, insecure_local: bool, host: str) -> urllib.request.OpenerDirector:
    if insecure_local:
        if host not in {"hooshix.local", "localhost", "127.0.0.1"}:
            raise ValueError("insecure TLS is restricted to the local staging host")
        context = ssl._create_unverified_context()
    else:
        context = ssl.create_default_context(cafile=ca_file)
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.HTTPSHandler(context=context),
    )


def _read_json(response: Any) -> dict[str, Any]:
    body = response.read(MAX_BODY + 1)
    if len(body) > MAX_BODY:
        raise ValueError("RESPONSE_TOO_LARGE")
    value = json.loads(body)
    if not isinstance(value, dict):
        raise ValueError("INVALID_JSON_SHAPE")
    return value


def _request(base_url: str, scenario: str, ca_file: str | None, insecure_local: bool) -> str | None:
    parsed = urllib.parse.urlparse(base_url)
    opener = _opener(ca_file, insecure_local, parsed.hostname or "")
    common = {
        "Origin": base_url,
        "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
    }
    bootstrap = urllib.request.Request(
        base_url + "/api/v1/auth/session/bootstrap", data=b"", headers=common, method="POST"
    )
    with opener.open(bootstrap, timeout=10) as response:
        if response.status != 201:
            return f"HTTP_{response.status}"
        document = _read_json(response)
    if document.get("mode") != "PREAUTH" or not isinstance(document.get("csrfToken"), str):
        return "INVALID_BOOTSTRAP"
    if scenario == "session-bootstrap":
        return None
    payload = json.dumps(
        {
            "channel": "EMAIL",
            "contact": "capacity-unknown@example.invalid",
            "password": "CapacityProbe-NotARealCredential-123!",
        },
        separators=(",", ":"),
    ).encode("utf-8")
    headers = dict(common)
    headers.update(
        {
            "X-CSRF-Token": document["csrfToken"],
            "X-Request-Id": __import__("uuid").uuid4().urn.removeprefix("urn:uuid:"),
            "Content-Type": "application/json",
        }
    )
    login = urllib.request.Request(base_url + "/api/v1/auth/local", data=payload, headers=headers, method="POST")
    try:
        opener.open(login, timeout=10)
        return "UNEXPECTED_LOGIN_SUCCESS"
    except urllib.error.HTTPError as error:
        document = _read_json(error)
        return None if error.code == 401 and document.get("code") == "AUTHENTICATION_FAILED" else f"HTTP_{error.code}"


def _percentile(values: list[float], percent: float) -> float:
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * percent) - 1)], 3)


def run(args: argparse.Namespace) -> dict[str, Any]:
    base_url = args.base_url.rstrip("/")
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("base URL must be credential-free HTTPS")
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"], check=True, text=True, stdout=subprocess.PIPE
    ).stdout.strip()
    started = dt.datetime.now(dt.timezone.utc)
    deadline = time.monotonic() + args.duration_seconds
    latencies: list[float] = []
    errors: Counter[str] = Counter()
    lock = threading.Lock()

    def worker() -> None:
        while time.monotonic() < deadline:
            before = time.monotonic()
            try:
                error = _request(base_url, args.scenario, args.ca_file, args.insecure_local_staging)
            except (OSError, ValueError, json.JSONDecodeError) as exception:
                error = type(exception).__name__.upper()
            elapsed = (time.monotonic() - before) * 1000
            with lock:
                latencies.append(elapsed)
                if error:
                    errors[re.sub(r"[^A-Z0-9_]", "_", error.upper())[:64]] += 1

    with SystemSampler() as sampler, concurrent.futures.ThreadPoolExecutor(
        max_workers=args.concurrency, thread_name_prefix="capacity-load"
    ) as executor:
        futures = [executor.submit(worker) for _ in range(args.concurrency)]
        for future in futures:
            future.result()
    completed = dt.datetime.now(dt.timezone.utc)
    operations = len(latencies)
    failures = sum(errors.values())
    successes = operations - failures
    latency = latencies or [0.0]
    system = sampler.result()
    success_percent = round(successes * 100 / operations, 3) if operations else 0.0
    reasons: list[str] = []
    if success_percent < args.min_success_percent:
        reasons.append("SUCCESS_RATE_BELOW_LIMIT")
    if _percentile(latency, 0.99) > args.p99_limit_ms:
        reasons.append("P99_ABOVE_LIMIT")
    if system["min_cpu_headroom_percent"] < args.min_cpu_headroom_percent:
        reasons.append("CPU_HEADROOM_BELOW_LIMIT")
    if system["min_memory_headroom_percent"] < args.min_memory_headroom_percent:
        reasons.append("MEMORY_HEADROOM_BELOW_LIMIT")
    if system["max_swap_used_bytes"] > 0:
        reasons.append("SWAP_USED")
    evidence = {
        "schema": SCHEMA,
        "profile": args.profile,
        "git_revision": revision,
        "started_at": started.isoformat().replace("+00:00", "Z"),
        "completed_at": completed.isoformat().replace("+00:00", "Z"),
        "mode": args.mode,
        "scenario": args.scenario,
        "configuration": {
            "duration_seconds": args.duration_seconds,
            "concurrency": args.concurrency,
            "p99_limit_ms": args.p99_limit_ms,
            "min_success_percent": args.min_success_percent,
            "min_cpu_headroom_percent": args.min_cpu_headroom_percent,
            "min_memory_headroom_percent": args.min_memory_headroom_percent,
        },
        "results": {
            "operations": operations,
            "successes": successes,
            "unexpected_failures": failures,
            "success_percent": success_percent,
            "errors_by_code": dict(sorted(errors.items())),
            "latency_ms": {
                "p50": _percentile(latency, 0.50),
                "p95": _percentile(latency, 0.95),
                "p99": _percentile(latency, 0.99),
                "max": round(max(latency), 3),
            },
            "system": system,
        },
        "passed": not reasons,
        "failure_reasons": reasons,
    }
    validation = validate_evidence(evidence)
    if validation:
        raise ValueError("generated evidence is invalid: " + "; ".join(validation))
    return evidence


def _write_atomic(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(data, handle, indent=2, sort_keys=True)
            handle.write("\n")
        os.replace(temporary, path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("evidence", type=Path)
    execute = commands.add_parser("run")
    execute.add_argument("--base-url", required=True)
    execute.add_argument("--profile", choices=("staging-single-server", "production-single-server"), default="staging-single-server")
    execute.add_argument("--mode", choices=tuple(MIN_DURATION), required=True)
    execute.add_argument("--scenario", choices=("session-bootstrap", "invalid-login"), required=True)
    execute.add_argument("--duration-seconds", type=int, required=True)
    execute.add_argument("--concurrency", type=int, required=True)
    execute.add_argument("--p99-limit-ms", type=float, required=True)
    execute.add_argument("--min-success-percent", type=float, default=99.0)
    execute.add_argument("--min-cpu-headroom-percent", type=float, default=30.0)
    execute.add_argument("--min-memory-headroom-percent", type=float, default=30.0)
    execute.add_argument("--ca-file")
    execute.add_argument("--insecure-local-staging", action="store_true")
    execute.add_argument("--output", type=Path, required=True)
    return root


def main() -> int:
    args = parser().parse_args()
    if args.command == "verify":
        try:
            data = json.loads(args.evidence.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            print(f"capacity evidence cannot be loaded: {exception}", file=__import__("sys").stderr)
            return 2
        errors = validate_evidence(data)
        if errors:
            for error in errors:
                print(error, file=__import__("sys").stderr)
            return 1
        print("Capacity evidence PASSED")
        return 0
    evidence = run(args)
    _write_atomic(args.output, evidence)
    print(f"Capacity evidence {'PASSED' if evidence['passed'] else 'FAILED'}: {args.output}")
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
