# Performance and Bottleneck Register

This document identifies the current architecture's likely runtime, availability, operational, and developer-delivery bottlenecks. It does not authorize bypassing security/correctness rules.

Optimize from measured evidence in this order:

```text
algorithm/query/model
-> bounded dependency/concurrency tuning
-> capacity/replica scaling
-> physical isolation/split
-> new architecture mechanism only when evidence earns it
```

## Priority register

| Priority | Bottleneck / risk | Why it can bottleneck | Current mitigation | Scale/split trigger |
| --- | --- | --- | --- | --- |
| P0 | Online Authorization `CheckPermission` | Paid on every protected resource operation; fail-closed dependency | one final check only; safe local invalid-token/claim prechecks; 300ms ceiling; p95<=100ms/p99<=200ms SLO (75/150 engineering target); >=3 replicas; per-caller/global bulkheads; fail-closed breaker; ADR-0062/ADR-0066 paired-window burn alerts + de-correlated serialized real half-open probes; dedicated DB cluster | SLO misses after query/index/pool/replica tuning at >=2x peak; sustained DB CPU/IO pressure; only then consider read-model/cache via new ADR |
| P0 | Per-service PostgreSQL HA cost | ADR-0057 removes cross-service physical/backup blast radius but multiplies PostgreSQL pods/storage/backup streams | ADR-0064/ADR-0067 reusable GitOps fleet baseline; 3-instance HA for critical services; per-service pool <=70% `max_connections`; independent backup/restore identity; forced tenant RLS; common dashboards and one-cluster-at-a-time upgrades | operations/upgrade/storage overhead becomes material; automate fleet operations rather than collapsing service databases back together |
| P1 | Kubernetes worker/control-plane capacity | HA services still fail together if replicas co-locate or the active cluster lacks quorum/headroom | 3 stacked control planes + >=3 workers, topology spread, N+1 critical capacity, one-node-loss tests | node drains/failures cause SLO burn or repeated pending pods; add worker capacity before increasing service replicas blindly |
| P1 | Security Redis | BFF sessions + semantic quotas may share physical Redis; quota dependency is fail-closed | primary+2 replicas+3 Sentinels; TLS/ACL namespaces; noeviction; 75ms limiter ceiling; >=30% headroom | sustained session/quota interference, failover latency, or memory pressure; split session Redis and quota Redis before Redis Cluster |
| P1 | Edge WAF hop | Every public request adds Caddy/Coraza inspection and body-processing cost | stateless replicas, CRS PL1, narrow tuning, bounded inspection, no full-body logs | measured WAF-added latency/CPU causes Class-A burn; scale WAF first, then endpoint-specific inspection policy; direct bypass remains prohibited |
| P1 | Kafka broker/disk/partition capacity | RF3 + acks=all + retries/DLQ + replayable outbox retention costs disk/network | async off request path; 3 brokers + 3 controllers; minISR2; quotas; bounded partition design | ISR instability, disk/IO saturation, produce p99/consumer lag deadline breach; add broker capacity/partitions based on measured hot topics |
| P1 | Virtual Threads vs scarce dependencies | Cheap threads can overdrive DB/Redis/providers even when JVM looks healthy | Hikari budgets, adapter bulkheads, bounded queues, child deadlines | pool pending/provider throttling/DB saturation while CPU/thread creation looks healthy; tighten bulkheads rather than add threads |
| P1 | Password hashing CPU/memory | Argon2id deliberately consumes memory/CPU and can become a DoS target | OWASP-minimum memory-hard profile, network quota, failed-attempt budget, bounded hash bulkhead, no unbounded queue | hash queue/saturation raises Class-A latency; add CPU/replicas or tune bulkhead first; never silently weaken Argon2 parameters |
| P1 | IPPanel receipt polling | SMS delivery-evidence polling can amplify provider QPS at volume | bounded 15s->15m schedule, provider concurrency/QPS budget, backpressure, 12h observation, no blind submission retry | provider throttling, persistent poll backlog, receipt-lag SLI breach; tune concurrency/batching first, then consider provider webhook/secondary provider only by ADR |
| P2 | Liara/IPPanel single-provider choices | External outage affects message delivery | durable acceptance separated from delivery; exact ambiguity handling; bounded retry/observation; no hidden fallback | provider SLI/business impact justifies secondary provider; introduce through ADR with deterministic routing/idempotency |
| P2 | OpenBao single-node control plane | Outage blocks new secret materialization/rotation and eventual key refresh, but not normal hot path while validated local key snapshot remains valid | mounted local keys, one-hour Notification key-staleness bound, hourly encrypted snapshots, Shamir recovery exercise | refresh/recovery repeatedly threatens RTO/SLO or compliance requires HA; then adopt 3-node Raft/auto-unseal via ADR |
| P2 | Kyverno admission | verifier/registry dependency can block deployment admission | >=3 admission replicas before fail-close, audit rollout, CI preflight; not request path | admission latency/availability blocks releases; scale/control dependencies, never disable signature enforcement as the first fix |
| P2 | Telemetry cardinality | raw IDs/URLs/PII can overload logs/metrics/traces and create privacy risk | allow-list logs, bounded metric labels/baggage, trace sampling | series/log volume growth exceeds budget; reduce labels/sampling, not security fields/redaction |
| P2 | CI/platform validation | full mesh/WAF/HA/DR/provider tests can slow feedback if all run on every edit | fast local loop + parallel PR gates + heavy staging/release/scheduled exercises | median PR feedback becomes a delivery bottleneck; profile/shard/cache and move only non-PR-critical heavy tests while preserving release gates |
| P2 | Premature microservice proliferation | each service adds contracts, DB, deployment, observability, auth, on-call surface | service only for real bounded capability | candidate `workflow-service`/`reference-data-service` lacks independent ownership/change/scale need; keep as module/data until evidence |

## 1. Authorization is the main request-path platform bottleneck

ADR-0039 deliberately chooses fresh online authorization. Keep it because deny/freshness semantics are security-critical, but pay for it only once at the resource owner.

Before proposing a cache, investigate in order:

1. SQL plan/index/cardinality;
2. Hikari acquisition and DB connection budget;
3. permission projection/data model fan-out;
4. bounded concurrency and replica CPU;
5. dedicated-Authorization PostgreSQL storage/IO and synchronous-replication pressure.

A local permission-result cache/read model that changes freshness requires a new ADR.

## 2. PostgreSQL is isolated per service in production

ADR-0057 deliberately trades more database fleet operations for smaller security,
backup, noisy-neighbor, and superuser blast radius. Each persistent production
microservice has its own CloudNativePG cluster. The resulting bottleneck is no
longer shared service workload; it is fleet cost: pods, storage, WAL streams,
upgrades, backup verification, and monitoring.

ADR-0064/ADR-0067 standardize one reusable GitOps/operator baseline, common bounded
monitoring/alert rules, service-specific backup credentials/artifact namespaces,
per-service capacity budgets, one-cluster-at-a-time upgrade waves, and automated
restore evidence. Do not collapse databases back together merely to save
operations. PgBouncer remains evidence-driven per service cluster.

## 3. Synchronous PostgreSQL durability costs latency intentionally

ADR-0048 pays a same-site synchronous acknowledgement before required durable writes are acknowledged. This is intentional for state whose loss would violate correctness, especially Authorization/Identity/Notification dispatch state.

Measure commit p95/p99 on real storage/network topology. Do not disable required synchronous durability to win latency; fix placement/storage/network first. Weaker durability requires explicit data-loss semantics in a new ADR.

## 4. Two major Notification bottlenecks were deliberately removed

The production review removes the largest bespoke/hot-path complexity:

1. **OpenBao Transit per-message RPC** -> ADR-0043 local mounted AES-256-GCM key ring.
2. **clock-health-agent + Chrony root/hostPath sidecar + 2-second gRPC loop + dispatch fence/coordinator** -> ADR-0047 PostgreSQL-authoritative time + short durable `DISPATCHING` transaction + ADR-0048 synchronous HA.

This removes request-path network hops, a privileged sidecar, continuous control traffic, custom coordinator state, failover epoch/re-arm machinery, and a large testing/on-call surface while preserving exact-content encryption, immutable deadlines, credential-expiry authority, and no-blind-redispatch safety.

## 5. Redis should split before it clusters

The chosen Sentinel topology is simple and supports the atomic multi-dimension limiter. If BFF sessions and quotas interfere, use separate Sentinel deployments first. Redis Cluster would complicate multi-key atomic quota semantics and should not be the first scaling step.

## 6. Kafka stays off synchronous request paths

Use Kafka for durable asynchronous work, not to turn simple reads or every cross-service operation into events. RF3/acks=all costs disk/network, but the cost is paid at durable async boundaries.

Avoid cardinality-driven partition explosion such as partition-per-tenant. Partition count/key changes require load and ordering evidence.

## 7. WAF latency is measured, never bypassed

Coraza/CRS sits on every public application request. Measure incremental latency, CPU, body-inspection cost, and false-positive rate in DetectionOnly/staging, then blocking. Scale replicas and tune narrow endpoint rules before considering architectural change. Direct Traefik->BFF remains prohibited.

## 8. Developer velocity remains intentionally lighter than production

Normal Domain/Application work does not require a full Kubernetes/Istio/OpenBao/WAF/Kafka-HA environment.

```text
local:        unit + focused architecture/application tests
adapter:      focused Testcontainers/contract tests
PR fast lane: compile/unit/ArchUnit/contracts/static/security scans in parallel
staging:      real mesh/WAF/HA/integration/smoke/critical Playwright
scheduled:    heavy load/chaos/PITR/DR/certificate/provider exercises
```

A heavy test may move off every PR only if a faster deterministic gate still protects the same regression class and the heavy test remains mandatory at release/scheduled cadence.

## 9. Scale decisions require evidence

Before adding a cache, new broker, DB proxy, new microservice, second provider, extra control plane, or physical database split, record:

- measured bottleneck and affected SLI/SLO;
- current load shape/cardinality;
- query/config/capacity fixes already attempted;
- security/consistency impact;
- operational/on-call cost;
- migration and rollback plan.

Keep architecture simple until evidence earns complexity.
