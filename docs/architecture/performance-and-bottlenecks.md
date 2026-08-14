# Performance and Bottleneck Register — Current State

This document identifies current runtime, availability, operational, and delivery bottlenecks. It never authorizes bypassing security/correctness rules.

Optimize from measured evidence in this order:

```text
algorithm/query/model
-> bounded dependency/concurrency tuning
-> capacity/replica scaling
-> physical isolation/split
-> new architecture mechanism only when evidence earns it
```

The selected initial production profile is `production-single-server` under ADR-0042. It intentionally reduces infrastructure redundancy. A `2 vCPU / 3-4 GiB RAM` full-stack host is **not** an approved capacity statement.

## Priority register

| Priority | Bottleneck / risk | Current mitigation | Scale/split trigger |
| --- | --- | --- | --- |
| P0 | Single-server shared CPU/RAM/IO/failure domain | complete-stack benchmark; finite requests/limits; >=30% validated CPU+memory headroom; >=2x projected peak evidence on current critical paths/security dependencies; SSD/IO/WAL/AOF/Kafka/telemetry contention measured together; explicit non-HA acceptance | any OOM/memory-pressure eviction, sustained swap, <=30% headroom, repeated host outage, unacceptable maintenance downtime, or unsafe storage contention -> increase host capacity or move to `production-ha` |
| P0 | Online Authorization `CheckPermission` on every protected resource operation | exactly one final check; safe reject-only local prechecks; 300ms ceiling; bounded per-caller/global concurrency; no stale allow/cache/retry; current burn/breaker rules | SLO miss after SQL/index/pool/process-capacity tuning at >=2x peak; only then evaluate read-model/cache through revised security decision |
| P0 | Shared PostgreSQL physical blast radius in single-server profile | distinct service DB/runtime/migration roles/Flyway/RLS; global pool budget <=70% max connections; >=30% operational headroom; WAL/PITR/off-site backup; noisy-neighbor telemetry; isolated whole-cluster restore | sustained IO/checkpoint/WAL/connection contention, unacceptable platform-wide DB maintenance/recovery, or service isolation need -> move persistent services to dedicated `production-ha` clusters |
| P1 | Web BFF fan-out/session/credential/reference path | 2600ms outer budget; registered bounded downstreams; no automatic authoritative-security retries; HMAC-located Redis state; five-minute `last_seen` write coalescing; bounded AES-GCM work | p95/p99, Redis ops, CPU/crypto, connection pools or downstream saturation burn SLO; tune route budgets/pools/bulkheads, then add host capacity or move to HA before multiplying saturated downstream load |
| P1 | Istio Ambient on one host | no waypoints by default; measure `istiod`/CNI/`ztunnel` idle+peak CPU/RAM, p95/p99 latency, throughput, OOM/restarts and Calico interaction as one full-stack test | benchmark cannot preserve >=30% validated headroom or critical-path budgets -> increase host resources or approve a different reviewed security architecture; do not silently disable workload identity/mTLS |
| P1 | Security Redis single instance | TLS/ACL/noeviction, AOF everysec, fail-closed quota/session behavior, >=30% memory headroom | sustained AOF/rewrite latency, memory pressure, unacceptable restart/session-loss impact, or security availability need -> split workloads/add capacity or move to HA Sentinel topology |
| P1 | Kafka single broker/controller | RF1/minISR1/acks=all/idempotence, ACL/TLS/quotas, Outbox/Inbox/replay evidence, broker is not business truth | disk/IO saturation, unacceptable async outage/data-loss exposure, or business need for broker failure tolerance -> move to HA RF3/minISR2 topology |
| P1 | Kyverno admission on one host | one replica permitted only in non-HA profile; reduced high-value policy set; fail-closed digest/signature/provenance/SBOM/security-context admission | admission latency/resource pressure blocks release or policy engine cannot fit safely -> add host capacity or move to HA; do not disable enforcement |
| P1 | Edge WAF hop | Caddy/Coraza PL1, narrow tuning, bounded inspection; BFF rejects oversized input early | WAF-added latency/CPU causes SLO burn -> tune/scale capacity; never direct-bypass BFF |
| P1 | Virtual Threads vs scarce downstreams | global/shared PostgreSQL pool budgets, adapter bulkheads, bounded queues/deadlines | DB/provider/Redis saturation while JVM threads look healthy -> tighten bulkheads/capacity rather than add threads |
| P1 | Password hashing CPU/memory | Argon2id approved profile, semantic quotas, bounded hash bulkhead | hash queue/saturation affects Class-A SLO -> add CPU/host capacity or move to HA; never silently weaken password hash |
| P1 | Observability on the same server | low-cardinality metrics, bounded logs/traces/sampling, retention and disk limits, required security audit exported off-host | telemetry causes memory/disk/IO pressure -> reduce safe dimensions/sampling/retention or move observability externally; required security evidence cannot be dropped |
| P2 | Compromised Password SQLite disk-backed lookup | immutable indexed dataset, bounded prefix/response, no full-dataset JVM cache, one attempt/no fallback, representative warm+cold storage tests | latency/IO saturation after schema/index/storage/concurrency tuning -> add capacity/move to HA replicas; do not add Redis/PostgreSQL/external provider without measured evidence + revised decision |
| P2 | Reference Data future read path | implementation deferred until explicit trigger; four small immutable families; bounded in-process index/pagination/response | only after implementation trigger and measured pressure; optimize bundle/index/serialization first |
| P2 | Liara/IPPanel single-provider availability | durable acceptance separated from delivery; explicit ambiguity/reconciliation | sustained provider SLI/business impact justifies secondary provider decision |
| P2 | OpenBao single-node control plane | unchanged by ADR-0042; request hot paths use validated local material; hourly encrypted snapshots; tested restore | refresh/recovery threatens RTO/SLO/compliance -> evaluate OpenBao HA separately; do not remove secret authority for RAM savings |
| P2 | Telemetry cardinality/privacy | allow-list logs, bounded labels/baggage, sampling/redaction | series/log cost exceeds budget -> reduce safe dimensions without removing required audit/security evidence |
| P2 | CI/platform validation time | fast inner loop, parallel PR checks, heavy staging/release/scheduled tests | feedback becomes bottleneck -> profile/shard/cache while retaining release authority |
| P2 | Premature microservice proliferation | service only for independent bounded capability; planned services remain implementation-gated | proposed service lacks independent ownership/change/scale/security need -> keep capability planned/module-level until evidence |

## 1. Single-server production capacity is an evidence gate

The full stack must be measured **together**. Isolated component sizing is not enough because PostgreSQL WAL/checkpoints/backups, Redis AOF/rewrite, Kafka log IO, Istio, Kyverno, WAF, JVMs and observability compete for the same CPU, memory and storage.

Before production approval, measure at least:

- Kubernetes/K3s system overhead and node allocatable resources;
- all application JVM idle and representative peak RSS/CPU;
- PostgreSQL connections, shared buffers/process memory, WAL/checkpoint/backup IO, query latency and storage free space;
- Redis memory, AOF fsync/rewrite latency and restart recovery;
- Kafka broker/controller memory, log IO, produce/fetch p95/p99 and restart/rebuild behavior;
- Istio `istiod`, CNI and `ztunnel` CPU/RAM/latency/throughput;
- Kyverno admission latency/memory and fail-closed behavior;
- edge/WAF and telemetry resource use;
- complete-stack reboot/startup order and dependency fail-closed behavior.

Pass criteria include:

```text
no OOM kill
no sustained swap pressure
no node MemoryPressure eviction
>=30% validated CPU headroom at approved peak
>=30% validated memory headroom at approved peak
critical/security dependency load evidence at >=2x projected peak where current readiness policy requires it
storage latency/free-space inside tested thresholds
no security/admission/backup bypass required to fit the host
```

If the profile does not pass, increase CPU/RAM/SSD or move to `production-ha`. Do not remove OpenBao, disable Kyverno, weaken Ambient security, reduce PITR, weaken MFA, or convert fail-closed dependencies to fail-open merely to fit a smaller server.

## 2. Authorization remains the primary synchronous platform bottleneck

Before proposing a permission cache/read model, investigate in order:

1. SQL plan/index/cardinality;
2. Hikari acquisition/connection budget;
3. permission projection/data-model fan-out;
4. bounded concurrency and process CPU;
5. PostgreSQL storage/IO and, in single-server, cross-service noisy-neighbor pressure.

Current authorization freshness semantics prohibit permission-result cache/stale allow. Any mechanism that changes this security property requires a revised current decision and load/security evidence.

## 3. Web BFF performance is bounded before it is scaled

Web BFF sits on every browser application path and can amplify load into Identity, Authorization, Reference Data, resource services, Redis and Google if route fan-out is not bounded.

Current controls deliberately reduce amplification:

- request bodies/headers are rejected at fixed limits before expensive parsing/downstream work;
- one inbound request has the existing 2600ms total budget and child calls use stricter registered deadlines;
- authoritative-security calls use one attempt/no automatic retry/no fallback;
- Reference Data read, when active, uses <=1000ms, one attempt, no automatic retry and no server-side stale/fabricated fallback;
- server-owned route->audience mapping prevents arbitrary downstream/token fan-out;
- completed session `last_seen` persistence is coalesced to at most once per five-minute activity window;
- session/pre-auth identifiers are HMAC-located;
- User->sessions index permits bounded revocation/erasure;
- AES-256-GCM refresh encryption is used only when credential persistence/rotation requires it;
- access JWT retention never avoids final resource-owner online Authorization;
- valid public Reference Data uses HTTP validators/cache headers without creating stale BFF authority.

Measure BFF by route class and track HTTP latency, in-flight requests, cancellation, downstream pool saturation, Redis ops/latency, provider/token-broker latency, AES-GCM CPU, rejected input counts and write-coalescing effectiveness with low-cardinality labels.

In `production-single-server`, replica count remains one and HPA is disabled. If the BFF saturates after code/bulkhead/pool tuning, first add host capacity or move to `production-ha`; do not create same-host replicas and call them HA.

## 4. Compromised Password stays disk-backed and bounded

Compromised Password is called only for password create/change/reset screening. Identity computes the full SHA-256 locally and sends only the five-hex/20-bit prefix. The service performs one fixed indexed SQLite read from its immutable artifact and returns the bounded suffix/count range; Identity owns exact comparison.

The design does not load the corpus into JVM heap, maintain an application hash/Bloom authority, use Redis/PostgreSQL as a second copy, or call an external provider at runtime.

Measure dataset size/cardinality, prefix distribution, warm/cold lookup latency, storage IOPS/latency, native extraction startup, bounded concurrency/queue, serialization size, and >=2x projected credential-write peak. The hard `<=2048` rows/prefix and `<=128 KiB` response bounds prevent unbounded work. Runtime never truncates because missing a suffix could produce a false-clean result.

Service-doc replicated targets apply to `production-ha`; single-server uses one replica and accepts host outage.

## 5. Reference Data remains small, immutable, and implementation-gated

ADR-0041 separates architecture decision from deployment need. Implementation starts only when at least two independent consumers or one specific production journey proves the central boundary is needed.

When implemented, measure record/serialized size, startup validation/heap, list/detail latency/allocation, pagination/default100/max200, <=128 KiB response, BFF gRPC latency/cancellation, ETag/304 cache effectiveness and >=2x projected reference-route peak. Its service-doc replicated target is HA-only; single-server uses the profile overlay.

A database, Redis cache, runtime standards-source sync, fuzzy-search engine or broader split requires measured evidence and revised architecture where semantics change.

## 6. PostgreSQL profile trade-off

`production-single-server` trades physical isolation/HA for lower infrastructure cost. All mutable services share one PostgreSQL process/host/storage failure domain, while database/role/Flyway/RLS ownership stays separate.

Measure:

- total and per-service connection usage against the global <=70% application budget;
- per-service query/transaction p95/p99;
- CPU/IO/checkpoint/WAL pressure;
- backup/archive latency during load;
- storage queue depth/free space;
- noisy-neighbor impact when one service reaches its approved peak.

If contention or recovery blast radius becomes unacceptable, move to dedicated `production-ha` clusters. Do not weaken required durability or RLS merely to improve latency.

## 7. Security Redis fails closed before it scales

Single-server uses one Redis instance with AOF `appendfsync everysec`; HA uses Sentinel. `noeviction`, TLS/ACL, pseudonymous keys, atomic quota semantics, dual-clock safety and fail-closed behavior are invariant.

Measure session cardinality, User->sessions index size, write coalescing, quota operations, AOF/rewrite latency, memory headroom, restart recovery and session re-authentication behavior. AOF is restart durability assistance, not HA.

If single-instance availability or interference becomes unacceptable, split workload/add capacity or move to HA Sentinel topology before Redis Cluster complexity.

## 8. Kafka stays off synchronous request paths

Single-server Kafka RF1/combined KRaft is a formal non-HA cost trade-off. It keeps `acks=all`, idempotent producers, TLS/ACL/quotas, Outbox/Inbox and 35-day critical replay/dedup evidence.

Measure disk/log IO, produce/fetch p99, consumer lag, restart/rebuild and replay time. Broker outage/data loss cannot become business-state loss because Kafka is not authority. Move to HA RF3/minISR2 when async availability/data-loss exposure is unacceptable.

## 9. Istio Ambient is benchmark-gated, not assumption-gated

Single-server production must measure Ambient as part of the whole stack. At minimum record `istiod`, Istio CNI and `ztunnel` idle/peak RSS+CPU, p95/p99 request impact, throughput, connection counts, OOM/restart behavior and Calico NetworkPolicy interaction.

Waypoints remain absent by default. Add them only for an explicit L7 policy/routing/telemetry need with separate resource/security evidence.

If Ambient does not fit the validated capacity envelope, the production gate fails. Increase host capacity or approve a reviewed replacement security architecture. Do not silently disable strict mTLS/workload identity.

## 10. WAF latency is measured, never bypassed

Measure Coraza/CRS incremental latency, CPU, body inspection, and false-positive rate in DetectionOnly/staging before blocking. Scale host capacity or apply narrow route-specific rule/body policy before architecture changes. Direct Traefik -> BFF application routing remains prohibited.

BFF body/header limits remain independent defense in depth.

## 11. Notification avoids bespoke hot-path complexity

Notification uses mounted purpose-specific local AES-256-GCM key rings instead of request-path OpenBao RPCs, and PostgreSQL-authoritative deadlines/durable `DISPATCHING` state instead of a bespoke clock/fence control plane.

ADR-0042 does not change OpenBao or these Notification correctness semantics.

## 12. Developer velocity remains lighter than production verification

```text
local:        unit + focused architecture/application tests
adapter:      focused Testcontainers/contract tests
PR:           compile/unit/ArchUnit/contracts/static/security checks in parallel
staging:      mesh/WAF/integration/smoke/critical Playwright + profile render
scheduled:    heavy load/recovery/DR/certificate/provider exercises
```

A heavy test may leave every-PR cadence only if a faster deterministic gate protects the regression class and the heavy test remains mandatory at appropriate release/scheduled cadence.

## 13. Evidence required before adding complexity

Before adding cache, broker/proxy, service, second provider, extra control plane, pool/concurrency increase, physical data split/merge, or retry layer, record:

- measured bottleneck and affected SLI/SLO;
- load/cardinality/traffic shape;
- query/config/capacity fixes already attempted;
- security/consistency/tenant impact;
- dependency/failure-mode impact;
- operational/on-call cost;
- migration and rollback/fail-forward plan;
- measurable success/abort criteria.

Keep architecture simple until evidence earns complexity.
