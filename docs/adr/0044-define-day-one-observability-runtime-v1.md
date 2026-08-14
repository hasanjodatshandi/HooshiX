# ADR-0044: Day-One Observability Runtime v1

## Status

Accepted — current effective decision

## Date

2026-08-15

## Decision

Observability is part of the first executable service implementation. Logging, metrics, and tracing MUST NOT be deferred until after feature development.

This ADR defines the initial runtime path for ordinary application/platform telemetry. It does not replace authoritative security/audit evidence, and telemetry availability is never authentication, authorization, quota, or business authority.

## 1. Application instrumentation

Java services use Spring Boot 4.1 observability through Micrometer Observation/Tracing with OpenTelemetry-compatible tracing.

Mandatory from the first service commit:

- structured JSON stdout logs with the allow-list/PII controls from ADR-0031;
- Micrometer metrics and observations for inbound requests, owned outbound dependencies, bounded queues/pools/bulkheads, and service-specific critical operations;
- OpenTelemetry trace export through OTLP to the approved Collector endpoint;
- W3C trace-context propagation on approved HTTP/gRPC boundaries where supported;
- bounded Kafka trace correlation only as telemetry metadata and never as event identity, idempotency, ordering, or authorization authority;
- service/resource identity attributes are server-owned and bounded;
- trace context and baggage are correlation data only and are never trusted as authentication, tenant, authorization, quota, or business context.

Application code uses the Micrometer Observation abstraction for custom observations unless a reviewed library boundary requires direct OpenTelemetry APIs. Do not create duplicate manual spans/metrics around framework instrumentation without a distinct semantic need.

## 2. Metrics path

Prometheus remains the production metrics backend.

Java services expose the reviewed management-only Prometheus scrape endpoint. The endpoint is not public application ingress and is reachable only from the approved monitoring identity/network path.

Prometheus scrapes application and platform metrics. Alertmanager remains the alert-routing component. Grafana remains the visualization/query UI.

Metric labels MUST be low-cardinality and MUST NOT contain raw or pseudonymous User/Tenant/Membership/Contact/session/request/resource identifiers, trace IDs, raw URLs, raw client IPs, free-form exceptions, secrets, credentials, or arbitrary caller input.

Where exemplars are used, only sampled trace correlation is permitted. Exemplar support does not make trace IDs normal metric labels.

## 3. Trace path

The approved trace path is:

```text
Java service
-> OTLP
-> OpenTelemetry Collector
-> Grafana Tempo
-> Grafana
```

Initial production versions are pinned in Technology Baseline.

For `production-single-server`, Tempo runs in its supported monolithic/single-process deployment mode. The profile does not claim trace-backend HA. Tempo MUST NOT introduce another Kafka requirement merely for trace storage in this profile.

Trace sampling is versioned configuration. The initial implementation MUST support deterministic server-owned sampling configuration and retain critical error/security-path visibility without using caller-controlled sampling headers as authority. Exact production rates are evidence-driven deployment configuration, not an invented architecture constant.

Traces MUST NOT contain secrets, credentials, raw Contact data, full request/response bodies, SQL bind values, complete gRPC metadata, raw client IPs, compromised-password hash material, or other prohibited PII. Sensitive attributes are dropped/redacted before export.

## 4. Log path

Applications write structured JSON to stdout. Request threads do not synchronously ship logs to a remote backend.

The approved ordinary application log path is:

```text
container stdout/stderr
-> node-local OpenTelemetry Collector
-> Grafana Loki
-> Grafana
```

ADR-0031 remains authoritative for source allow-list logging, static checks, pipeline redaction, canary testing, and runtime leak detection.

For `production-single-server`, Loki uses its supported single-binary/single-process deployment mode and is explicitly non-HA. Log retention/storage quotas are bounded deployment configuration and are included in the complete-stack capacity gate.

Authoritative security/privileged audit records remain on their separately required durable/off-host path. A Loki/Collector outage MUST NOT silently convert required audit evidence into ordinary best-effort logs.

## 5. OpenTelemetry Collector topology

`production-single-server` uses one `otelcol-contrib` node-local Collector deployment for application OTLP receive and container-log collection.

Required controls:

- exact Collector image digest/version is pinned in GitOps;
- dedicated ServiceAccount and least-privilege RBAC;
- OTLP receiver is internal only and reachable only from approved workloads;
- no public OTLP receiver or unauthenticated Internet ingress;
- Collector egress is restricted to approved telemetry backends;
- memory limiter, batching, finite exporter queues, and explicit backpressure/drop behavior are configured;
- ordinary telemetry exporter failure does not fail a business request;
- sustained exporter loss, queue saturation, or dropped telemetry is itself observable and alerted;
- redaction/filtering occurs before data leaves the trusted cluster path;
- no unbounded persistent retry spool is introduced.

Container-log collection requires a narrow security exception to read the node's Kubernetes pod/container log paths. The mount is read-only and limited to the exact required log paths. The Collector is non-root where the selected image/runtime permits, has no privilege escalation, drops capabilities by default, uses `RuntimeDefault` seccomp, has no host network, and receives no general host-filesystem mount. A broader `hostPath` or host privilege requires a separate current security decision.

The HA expansion profile may use agent-to-gateway Collector topology when multiple nodes/failure domains justify it. That expansion does not change telemetry data-classification rules.

## 6. Correlation contract

Every service emits a stable server-owned `service.name` and environment/resource identity from deployment configuration.

Logs may include bounded trace/span correlation fields when a current span exists. They MUST NOT include trace/span IDs as metric labels.

Caller-supplied correlation headers are untrusted input. A public correlation value may be accepted only after format/size validation and MUST NOT become authorization, tenancy, idempotency, replay, audit identity, or security-decision authority.

Trace baggage is allow-list only. Baggage carrying User/Tenant/session/contact/raw-IP/credential/secret values is prohibited.

## 7. External host-down detection

The selected single-server profile has a common failure domain: Prometheus, Alertmanager, Grafana, Loki, Tempo, Collector, and workloads may all disappear with the host.

Therefore production approval requires at least one independent external black-box availability signal outside the production host failure domain. It verifies the approved public endpoint/health journey from outside and alerts through a path that does not depend on the failed host.

The exact external monitoring provider is environment configuration and MUST be selected, authenticated, tested, and recorded before production. Documentation does not invent a provider.

The external check MUST NOT bypass the public edge/WAF path or require production secrets that create a new high-risk external dependency.

## 8. Capacity and failure behavior

Observability runs inside the `production-single-server` resource envelope and is included in the simultaneous complete-stack benchmark.

Measure at least:

- Collector CPU/RSS, receive/export rates, queue usage, dropped telemetry, and exporter latency/errors;
- Prometheus series/cardinality, scrape duration/errors, TSDB memory/disk/IO;
- Loki ingest/query memory/disk/IO and rejected/rate-limited data;
- Tempo ingest/query memory/disk/IO and sampling volume;
- Grafana and Alertmanager resource use;
- telemetry disk growth, free-space reserve, and contention with PostgreSQL WAL/backup, Redis AOF, Kafka, WAF, and applications.

Ordinary telemetry may be sampled/dropped under its registered `OBSERVABILITY` semantics. Security/correctness controls, required audit, and business durability MUST NOT be weakened to save telemetry capacity.

A telemetry component outage is degraded observability, not permission to fail open. A telemetry resource-pressure problem is solved by safe retention/sampling/cardinality tuning, externalization, or host/profile capacity increase.

## 9. Day-one service Definition of Done

An executable service is not repository-complete until it has, where applicable:

- structured allow-listed JSON logs;
- Micrometer request/operation/dependency metrics;
- trace creation/propagation/export through the approved OTLP path;
- bounded service-specific saturation/dependency metrics;
- health/readiness signals with correct dependency semantics;
- PII/secret/cardinality tests for logs/metrics/traces;
- telemetry-backend outage test proving ordinary telemetry loss does not fail business processing;
- dashboard/alert ownership for its defined SLO/security/reliability signals;
- deployment policy allowing only approved Collector/Prometheus paths.

A service feature PR MUST NOT postpone these items to an unspecified later observability phase when they apply to the new path.

## 10. Verification requirements

Before production, prove at least:

- one synthetic request produces expected safe logs, metrics, and an end-to-end trace across BFF/internal gRPC boundaries where implemented;
- trace propagation/cancellation does not alter authentication/Authorization or request identity;
- baggage/attribute allow-list rejects prohibited identifiers and secrets;
- Prometheus scrape endpoints are not publicly reachable;
- OTLP receiver is internal-only and wrong workloads/public sources are denied;
- Collector log mounts are exact/read-only and no broad host filesystem is exposed;
- Collector/backend outage produces bounded queue/drop behavior and alerts without failing ordinary business requests;
- ADR-0031 canary values are absent from Loki/Tempo/Prometheus/Grafana-visible telemetry;
- Loki/Tempo/Prometheus/Collector cardinality/storage/resource pressure remains inside the complete-stack capacity envelope;
- the independent external black-box probe still detects total host loss when local monitoring is unavailable;
- required security/audit evidence remains durable/off-host and is not silently routed only through the best-effort telemetry path.

## Rollback considerations

Rollback MUST preserve structured PII-safe logging, service metrics, trace-context safety, internal-only telemetry ingress, bounded queues/cardinality, and external host-down detection. It MUST NOT make telemetry headers authority, expose management/OTLP endpoints publicly, add synchronous remote logging to request paths, weaken required audit durability, or disable security controls to recover observability capacity.