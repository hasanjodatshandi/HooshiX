#!/usr/bin/env python3
"""Run and verify bounded HooshiX staging load/soak capacity evidence."""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import datetime as dt
import http.client
import http.cookiejar
import ipaddress
import json
import math
import os
import re
import shutil
import socket
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

SCHEMA = "hooshix-stack-capacity-v2"
REVISION = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MIN_DURATION = {"load": 60, "soak": 1800}
MAX_DURATION = 86_400
MAX_CONCURRENCY = 256
MAX_BODY = 16_384
EXPECTED_LOGIN_DENIALS = {
    (401, "AUTHENTICATION_FAILED"),
    (429, "RATE_LIMITED"),
}


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
        "max_consecutive_swap_active_samples",
        "kubernetes_namespace",
        "kubernetes_deployments",
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
    swap_samples = config.get("max_consecutive_swap_active_samples")
    if not isinstance(swap_samples, int) or isinstance(swap_samples, bool) or not (
        2 <= swap_samples <= 60
    ):
        errors.append("max_consecutive_swap_active_samples is outside the admissible bound")
    namespace = config.get("kubernetes_namespace")
    if not isinstance(namespace, str) or not re.fullmatch(r"[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?", namespace):
        errors.append("kubernetes_namespace is invalid")
    deployments = config.get("kubernetes_deployments")
    deployment_names = deployments if isinstance(deployments, list) else []
    if (
        not isinstance(deployments, list)
        or not 1 <= len(deployments) <= 32
        or any(
            not isinstance(deployment, str)
            or not re.fullmatch(r"[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?", deployment)
            for deployment in deployment_names
        )
        or deployments != sorted(deployment_names)
        or len(set(deployment_names)) != len(deployment_names)
    ):
        errors.append("kubernetes_deployments is invalid")
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
        "expected_outcomes_by_code",
        "errors_by_code",
        "latency_ms",
        "system",
        "workloads",
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
    outcome_codes = results.get("expected_outcomes_by_code")
    for field, counter, expected_total in (
        ("expected_outcomes_by_code", outcome_codes, successes),
        ("errors_by_code", error_codes, failures),
    ):
        if (
            not isinstance(counter, dict)
            or len(counter) > 32
            or any(
                not isinstance(key, str)
                or not re.fullmatch(r"[A-Z0-9_]{1,64}", key)
                or not isinstance(value, int)
                or isinstance(value, bool)
                or value <= 0
                for key, value in (counter.items() if isinstance(counter, dict) else [])
            )
            or (isinstance(expected_total, int) and sum(counter.values()) != expected_total)
        ):
            errors.append(f"{field} is invalid")
    expected_scenario_outcomes = {
        "session-bootstrap": {"SESSION_BOOTSTRAP_CREATED"},
        "invalid-login": {"AUTHENTICATION_FAILED", "RATE_LIMITED"},
    }
    scenario_outcomes = expected_scenario_outcomes.get(data.get("scenario"))
    if (
        isinstance(outcome_codes, dict)
        and scenario_outcomes is not None
        and set(outcome_codes) != scenario_outcomes
    ):
        errors.append("expected_outcomes_by_code does not prove the selected scenario")
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
        "swap_in_pages",
        "swap_out_pages",
        "swap_active_sample_count",
        "max_consecutive_swap_active_samples",
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
    for field in (
        "max_swap_used_bytes",
        "swap_in_pages",
        "swap_out_pages",
        "swap_active_sample_count",
        "max_consecutive_swap_active_samples",
        "min_root_disk_free_bytes",
    ):
        if not isinstance(system.get(field), int) or isinstance(system.get(field), bool) or system[field] < 0:
            errors.append(f"system.{field} is invalid")
    sample_count = system.get("sample_count")
    active_samples = system.get("swap_active_sample_count")
    consecutive_samples = system.get("max_consecutive_swap_active_samples")
    if (
        isinstance(sample_count, int)
        and isinstance(active_samples, int)
        and isinstance(consecutive_samples, int)
        and not (0 <= consecutive_samples <= active_samples <= sample_count)
    ):
        errors.append("system swap sample counters are inconsistent")

    workloads = results.get("workloads")
    workload_keys = {
        "pod_count_start",
        "pod_count_end",
        "restart_count_start",
        "restart_count_end",
        "restart_count_increase",
        "oom_killed_count_start",
        "oom_killed_count_end",
        "oom_killed_count_increase",
        "pod_uid_change_count",
    }
    if not isinstance(workloads, dict) or set(workloads) != workload_keys:
        errors.append("workload evidence is invalid")
        workloads = {}
    for field in workload_keys:
        if (
            not isinstance(workloads.get(field), int)
            or isinstance(workloads.get(field), bool)
            or workloads[field] < 0
        ):
            errors.append(f"workloads.{field} is invalid")
    if workloads.get("pod_count_start", 0) < len(deployment_names):
        errors.append("workload start snapshot is incomplete")
    if workloads.get("pod_count_end", 0) < len(deployment_names):
        errors.append("workload end snapshot is incomplete")

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
    if (
        isinstance(system.get("max_consecutive_swap_active_samples"), int)
        and isinstance(config.get("max_consecutive_swap_active_samples"), int)
        and system["max_consecutive_swap_active_samples"]
        >= config["max_consecutive_swap_active_samples"]
    ):
        calculated.append("SUSTAINED_SWAP_ACTIVITY")
    if isinstance(workloads.get("restart_count_increase"), int) and workloads["restart_count_increase"] > 0:
        calculated.append("WORKLOAD_RESTART_DETECTED")
    if isinstance(workloads.get("oom_killed_count_increase"), int) and workloads["oom_killed_count_increase"] > 0:
        calculated.append("WORKLOAD_OOM_DETECTED")
    if isinstance(workloads.get("pod_uid_change_count"), int) and workloads["pod_uid_change_count"] > 0:
        calculated.append("WORKLOAD_POD_SET_CHANGED")
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


def _swap_io() -> tuple[int, int]:
    values: dict[str, int] = {}
    for line in Path("/proc/vmstat").read_text(encoding="ascii").splitlines():
        key, raw = line.split()
        if key in {"pswpin", "pswpout"}:
            values[key] = int(raw)
    return values.get("pswpin", 0), values.get("pswpout", 0)


def _clean_git_revision() -> str:
    revision = subprocess.run(
        ["git", "-C", str(REPOSITORY_ROOT), "rev-parse", "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout.strip()
    status = subprocess.run(
        [
            "git",
            "-C",
            str(REPOSITORY_ROOT),
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
        ],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout
    if status:
        raise ValueError("capacity evidence requires a clean Git worktree")
    if not REVISION.fullmatch(revision):
        raise ValueError("capacity evidence requires a full lowercase Git revision")
    return revision


class SystemSampler:
    def __init__(self) -> None:
        self.stop = threading.Event()
        self.samples: list[tuple[float, float, int, int, int, int]] = []
        self.swap_in_start, self.swap_out_start = _swap_io()
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
            swap_in, swap_out = _swap_io()
            self.samples.append((used, memory_used, swap_used, disk_free, swap_in, swap_out))
            previous_total, previous_idle = total, idle

    def result(self) -> dict[str, int | float]:
        if not self.samples:
            memory_total, memory_available, swap_used = _memory()
            memory_used = 100.0 * (memory_total - memory_available) / memory_total
            disk_free = shutil.disk_usage("/").free
            swap_in, swap_out = _swap_io()
            self.samples.append((0.0, memory_used, swap_used, disk_free, swap_in, swap_out))
        cpu = [sample[0] for sample in self.samples]
        memory = [sample[1] for sample in self.samples]
        final_swap_in, final_swap_out = _swap_io()
        final_sample = self.samples[-1]
        self.samples[-1] = (*final_sample[:4], final_swap_in, final_swap_out)
        previous_in, previous_out = self.swap_in_start, self.swap_out_start
        active_samples = 0
        consecutive_samples = 0
        max_consecutive_samples = 0
        for sample in self.samples:
            active = sample[4] > previous_in or sample[5] > previous_out
            active_samples += int(active)
            consecutive_samples = consecutive_samples + 1 if active else 0
            max_consecutive_samples = max(max_consecutive_samples, consecutive_samples)
            previous_in, previous_out = sample[4], sample[5]
        return {
            "sample_count": len(self.samples),
            "max_cpu_used_percent": round(max(cpu), 3),
            "min_cpu_headroom_percent": round(100 - max(cpu), 3),
            "max_memory_used_percent": round(max(memory), 3),
            "min_memory_headroom_percent": round(100 - max(memory), 3),
            "max_swap_used_bytes": max(sample[2] for sample in self.samples),
            "swap_in_pages": max(0, final_swap_in - self.swap_in_start),
            "swap_out_pages": max(0, final_swap_out - self.swap_out_start),
            "swap_active_sample_count": active_samples,
            "max_consecutive_swap_active_samples": max_consecutive_samples,
            "min_root_disk_free_bytes": min(sample[3] for sample in self.samples),
        }


def _kubernetes_snapshot(
    namespace: str, deployments: list[str]
) -> dict[str, dict[str, tuple[int, int]]]:
    completed = subprocess.run(
        ["kubectl", "get", "pods", "-n", namespace, "-o", "json"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    document = json.loads(completed.stdout)
    items = document.get("items")
    if not isinstance(items, list):
        raise ValueError("Kubernetes pod response is invalid")
    snapshot: dict[str, dict[str, tuple[int, int]]] = {
        deployment: {} for deployment in deployments
    }
    for pod in items:
        if not isinstance(pod, dict):
            continue
        metadata = pod.get("metadata")
        if not isinstance(metadata, dict):
            continue
        labels = metadata.get("labels")
        uid = metadata.get("uid")
        if not isinstance(labels, dict) or not isinstance(uid, str):
            continue
        deployment = labels.get("app.kubernetes.io/name")
        if deployment not in snapshot:
            continue
        statuses = pod.get("status", {}).get("containerStatuses", [])
        if not isinstance(statuses, list):
            statuses = []
        restarts = 0
        oom_killed = 0
        for status in statuses:
            if not isinstance(status, dict):
                continue
            restart_count = status.get("restartCount", 0)
            if isinstance(restart_count, int) and not isinstance(restart_count, bool):
                restarts += max(0, restart_count)
            for state_key in ("state", "lastState"):
                state = status.get(state_key, {})
                terminated = state.get("terminated", {}) if isinstance(state, dict) else {}
                if isinstance(terminated, dict) and terminated.get("reason") == "OOMKilled":
                    oom_killed += 1
        snapshot[deployment][uid] = (restarts, oom_killed)
    missing = [deployment for deployment, pods in snapshot.items() if not pods]
    if missing:
        raise ValueError("Kubernetes workloads are missing: " + ", ".join(missing))
    return snapshot


def _workload_evidence(
    start: dict[str, dict[str, tuple[int, int]]],
    end: dict[str, dict[str, tuple[int, int]]],
) -> dict[str, int]:
    start_pods = {uid: counts for pods in start.values() for uid, counts in pods.items()}
    end_pods = {uid: counts for pods in end.values() for uid, counts in pods.items()}
    common_uids = start_pods.keys() & end_pods.keys()
    new_uids = end_pods.keys() - start_pods.keys()
    restart_increase = sum(
        max(0, end_pods[uid][0] - start_pods[uid][0]) for uid in common_uids
    ) + sum(end_pods[uid][0] for uid in new_uids)
    oom_increase = sum(
        max(
            0,
            end_pods[uid][1] - start_pods[uid][1],
            int(
                end_pods[uid][1] > 0
                and end_pods[uid][0] > start_pods[uid][0]
            ),
        )
        for uid in common_uids
    ) + sum(end_pods[uid][1] for uid in new_uids)
    return {
        "pod_count_start": len(start_pods),
        "pod_count_end": len(end_pods),
        "restart_count_start": sum(counts[0] for counts in start_pods.values()),
        "restart_count_end": sum(counts[0] for counts in end_pods.values()),
        "restart_count_increase": restart_increase,
        "oom_killed_count_start": sum(counts[1] for counts in start_pods.values()),
        "oom_killed_count_end": sum(counts[1] for counts in end_pods.values()),
        "oom_killed_count_increase": oom_increase,
        "pod_uid_change_count": len(start_pods.keys() ^ end_pods.keys()),
    }


class _LoopbackHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, *, connect_host: str, **kwargs: Any) -> None:
        super().__init__(host, **kwargs)
        self.connect_host = connect_host

    def connect(self) -> None:
        raw = socket.create_connection((self.connect_host, self.port), self.timeout, self.source_address)
        if self._tunnel_host:
            self.sock = raw
            self._tunnel()
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)


class _LoopbackHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, context: ssl.SSLContext, connect_host: str) -> None:
        super().__init__(context=context)
        self.connect_host = connect_host

    def https_open(self, request: urllib.request.Request) -> Any:
        return self.do_open(
            lambda host, **kwargs: _LoopbackHTTPSConnection(
                host, connect_host=self.connect_host, **kwargs
            ),
            request,
            context=self._context,
        )


def _opener(
    ca_file: str | None, insecure_local: bool, host: str, connect_host: str | None
) -> urllib.request.OpenerDirector:
    if insecure_local:
        if host not in {"hooshix.local", "localhost", "127.0.0.1"}:
            raise ValueError("insecure TLS is restricted to the local staging host")
        context = ssl._create_unverified_context()
    else:
        context = ssl.create_default_context(cafile=ca_file)
    handler: urllib.request.BaseHandler
    if connect_host:
        if not ipaddress.ip_address(connect_host).is_loopback:
            raise ValueError("connect host override must be a loopback IP address")
        handler = _LoopbackHTTPSHandler(context, connect_host)
    else:
        handler = urllib.request.HTTPSHandler(context=context)
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        handler,
    )


def _read_json(response: Any) -> dict[str, Any]:
    body = response.read(MAX_BODY + 1)
    if len(body) > MAX_BODY:
        raise ValueError("RESPONSE_TOO_LARGE")
    value = json.loads(body)
    if not isinstance(value, dict):
        raise ValueError("INVALID_JSON_SHAPE")
    return value


def _problem_code(response: Any) -> str | None:
    try:
        code = _read_json(response).get("code")
    except (ValueError, json.JSONDecodeError):
        return None
    return code if isinstance(code, str) and re.fullmatch(r"[A-Z0-9_]{1,48}", code) else None


def _http_failure(prefix: str, status: int, problem_code: str | None) -> str:
    suffix = problem_code if problem_code is not None else "INVALID_PROBLEM"
    return f"{prefix}_HTTP_{status}_{suffix}"


def _request(
    base_url: str,
    scenario: str,
    ca_file: str | None,
    insecure_local: bool,
    connect_host: str | None,
) -> tuple[str | None, str | None]:
    parsed = urllib.parse.urlparse(base_url)
    opener = _opener(ca_file, insecure_local, parsed.hostname or "", connect_host)
    common = {
        "Origin": base_url,
        "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
    }
    bootstrap = urllib.request.Request(
        base_url + "/api/v1/auth/session/bootstrap", data=b"", headers=common, method="POST"
    )
    try:
        with opener.open(bootstrap, timeout=10) as response:
            if response.status != 201:
                return None, f"BOOTSTRAP_HTTP_{response.status}"
            document = _read_json(response)
    except urllib.error.HTTPError as error:
        return None, _http_failure("BOOTSTRAP", error.code, _problem_code(error))
    if document.get("mode") != "PREAUTH" or not isinstance(document.get("csrfToken"), str):
        return None, "INVALID_BOOTSTRAP"
    if scenario == "session-bootstrap":
        return "SESSION_BOOTSTRAP_CREATED", None
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
            "Idempotency-Key": __import__("uuid").uuid4().urn.removeprefix("urn:uuid:"),
            "Content-Type": "application/json",
        }
    )
    login = urllib.request.Request(base_url + "/api/v1/auth/local", data=payload, headers=headers, method="POST")
    try:
        with opener.open(login, timeout=10):
            pass
        return None, "UNEXPECTED_LOGIN_SUCCESS"
    except urllib.error.HTTPError as error:
        problem_code = _problem_code(error)
        denial = (error.code, problem_code)
        if denial in EXPECTED_LOGIN_DENIALS:
            return str(problem_code), None
        return None, _http_failure("LOGIN", error.code, problem_code)


def _percentile(values: list[float], percent: float) -> float:
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * percent) - 1)], 3)


def run(args: argparse.Namespace) -> dict[str, Any]:
    base_url = args.base_url.rstrip("/")
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("base URL must be credential-free HTTPS")
    revision = _clean_git_revision()
    deployments = sorted(set(args.kubernetes_deployment))
    workload_start = _kubernetes_snapshot(args.kubernetes_namespace, deployments)
    started = dt.datetime.now(dt.timezone.utc)
    deadline = time.monotonic() + args.duration_seconds
    latencies: list[float] = []
    errors: Counter[str] = Counter()
    expected_outcomes: Counter[str] = Counter()
    lock = threading.Lock()

    def worker() -> None:
        while time.monotonic() < deadline:
            before = time.monotonic()
            try:
                outcome, error = _request(
                    base_url,
                    args.scenario,
                    args.ca_file,
                    args.insecure_local_staging,
                    args.connect_host,
                )
            except (OSError, ValueError, json.JSONDecodeError) as exception:
                outcome = None
                error = type(exception).__name__.upper()
            elapsed = (time.monotonic() - before) * 1000
            with lock:
                latencies.append(elapsed)
                if outcome:
                    expected_outcomes[outcome] += 1
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
    workload_end = _kubernetes_snapshot(args.kubernetes_namespace, deployments)
    workloads = _workload_evidence(workload_start, workload_end)
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
    if (
        system["max_consecutive_swap_active_samples"]
        >= args.max_consecutive_swap_active_samples
    ):
        reasons.append("SUSTAINED_SWAP_ACTIVITY")
    if workloads["restart_count_increase"] > 0:
        reasons.append("WORKLOAD_RESTART_DETECTED")
    if workloads["oom_killed_count_increase"] > 0:
        reasons.append("WORKLOAD_OOM_DETECTED")
    if workloads["pod_uid_change_count"] > 0:
        reasons.append("WORKLOAD_POD_SET_CHANGED")
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
            "max_consecutive_swap_active_samples": args.max_consecutive_swap_active_samples,
            "kubernetes_namespace": args.kubernetes_namespace,
            "kubernetes_deployments": deployments,
        },
        "results": {
            "operations": operations,
            "successes": successes,
            "unexpected_failures": failures,
            "success_percent": success_percent,
            "expected_outcomes_by_code": dict(sorted(expected_outcomes.items())),
            "errors_by_code": dict(sorted(errors.items())),
            "latency_ms": {
                "p50": _percentile(latency, 0.50),
                "p95": _percentile(latency, 0.95),
                "p99": _percentile(latency, 0.99),
                "max": round(max(latency), 3),
            },
            "system": system,
            "workloads": workloads,
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
    execute.add_argument("--max-consecutive-swap-active-samples", type=int, default=5)
    execute.add_argument("--kubernetes-namespace", required=True)
    execute.add_argument("--kubernetes-deployment", action="append", required=True)
    execute.add_argument("--ca-file")
    execute.add_argument("--connect-host")
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
    try:
        evidence = run(args)
    except (OSError, subprocess.CalledProcessError, ValueError) as exception:
        print(f"capacity run failed: {exception}", file=__import__("sys").stderr)
        return 2
    _write_atomic(args.output, evidence)
    print(f"Capacity evidence {'PASSED' if evidence['passed'] else 'FAILED'}: {args.output}")
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
