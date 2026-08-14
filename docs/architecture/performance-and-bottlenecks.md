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

## Priority register

| Priority | Bottleneck / risk | Current mitigation | Scale/split trigger |
| --- | --- | --- | --- |
| P0 | Online Authorization `CheckPermission` on every protected resource operation | exactly one final check; safe reject-only local prechecks; 300ms ceiling; p95<=100ms/p99<=200ms; >=3 replicas; bounded per-caller/global concurrency; ADR-0032 paired-window burn + breaker opening; ADR-0036 de-correlated serialized real HALF_OPEN recovery; dedicated DB cluster | SLO miss after SQL/index/pool/replica tuning at >=2x peak, sustained DB CPU/IO or queue pressure; only then evaluate read model/cache through a revised current decision |
| P0 | Per-service PostgreSQL HA fleet cost | dedicated service clusters/backup identities/RLS; reusable GitOps fleet baseline; pool <=70% `max_connections`; common bounded observability; one-cluster upgrade waves | operational/storage/upgrade overhead becomes material; automate fleet operations before reducing isolation |
| P1 | Web BFF fan-out/session/credential path | 2600ms outer request budget; registered bounded gRPC/provider/Redis edges; server-owned audience mapping; no automatic authoritative-security retries; HMAC-located Redis state; five-minute `last_seen` write coalescing; <=256KiB/64KiB body and <=16KiB headers; bounded AES-GCM work; >=3 replicas/PDB2 | BFF p95/p99, Redis ops/sec, CPU/crypto, connection pools or downstream saturation burn SLO under >=2x peak; tune route budgets/pools/bulkheads and scale replicas, split BFF session/quota Redis before adding new brokers/caches |
| P1 | Kubernetes control-plane/worker capacity | 3 stacked control planes + >=3 workers, topology spread, N+1 critical capacity, one-node-loss tests | drains/failures cause SLO burn/pending pods; add worker capacity before blindly adding replicas |
| P1 | Security Redis | primary+2 replicas+3 Sentinels, TLS/ACL/noeviction, bounded quota/session contracts; BFF session last-seen writes coalesced to <=1/5m/activity window; raw IDs excluded from keys | sustained session/quota interference, failover latency, memory/ops pressure; split Sentinel deployments before Redis Cluster |
| P1 | Edge WAF hop | replicated Caddy/Coraza, PL1, narrow tuning, bounded inspection; BFF rejects oversized input early | WAF-added latency/CPU causes SLO burn; scale/tune endpoint policy, never direct-bypass BFF |
| P1 | Kafka disk/broker/partition capacity | RF3/minISR2/acks=all/idempotence, quotas, bounded partitioning, async-only use | ISR instability, IO saturation, produce p99/consumer lag breach; scale based on measured hot topics/order requirements |
| P1 | Virtual Threads vs scarce downstreams | Hikari budgets, adapter bulkheads, bounded queues/deadlines | pool/provider/Redis saturation while JVM thread creation looks healthy; tighten bulkheads/capacity rather than add threads |
| P1 | Password hashing CPU/memory | Argon2id approved profile, semantic quotas, bounded hash bulkhead | hash queue/saturation affects Class-A SLO; add CPU/replicas/tune bulkhead, never silently weaken password hash |
| P1 | IPPanel delivery-evidence polling | bounded polling/backpressure/QPS, 12h observation, no blind resend | provider throttling, poll backlog, receipt-lag breach; tune batching/concurrency before new webhook/provider design |
| P2 | Liara/IPPanel single-provider availability | durable acceptance separated from delivery; explicit ambiguity/reconciliation | sustained provider SLI/business impact justifies a secondary provider and deterministic routing/idempotency decision |
| P2 | OpenBao single-node control plane | request hot paths use validated local material; hourly encrypted snapshots; tested Shamir restore | refresh/recovery threatens RTO/SLO or compliance; evaluate 3-node Raft/auto-unseal through current architecture review |
| P2 | Kyverno/registry admission path | >=3 admission replicas before fail-close, audit rollout, CI preflight | admission availability/latency blocks releases; scale dependencies, never disable signing as first fix |
| P2 | Telemetry cardinality/privacy | allow-list logs, bounded labels/baggage, sampling/redaction | series/log cost exceeds budget; reduce dimensions/sampling without removing required security evidence |
| P2 | CI/platform validation time | fast inner loop, parallel PR checks, heavy staging/release/scheduled tests | median feedback becomes bottleneck; profile/shard/cache and move only non-PR-critical heavy tests while retaining release authority |
| P2 | Premature microservice proliferation | service only for independent bounded capability | proposed service lacks independent ownership/change/scale/security need; keep as module/capability until evidence |

## 1. Authorization remains the primary synchronous platform bottleneck

Before proposing a permission cache/read model, investigate in order:

1. SQL plan/index/cardinality;
2. Hikari acquisition/connection budget;
3. permission projection/data-model fan-out;
4. bounded concurrency and replica CPU;
5. dedicated Authorization PostgreSQL storage/IO/synchronous replication.

Current authorization freshness semantics prohibit permission-result cache/stale allow. Any mechanism that changes this security property requires a revised current decision and load/security evidence.

## 2. Web BFF performance is bounded before it is scaled

Web BFF sits on every browser application path and can amplify load into Identity, Authorization, resource services, Redis and Google if route fan-out is not bounded.

Current controls deliberately reduce amplification:

- request bodies/headers are rejected at fixed limits before expensive parsing/downstream work;
- one inbound request has the existing 2600ms total budget and child calls use stricter registered deadlines;
- authoritative-security calls such as Identity evidence/token brokerage and Authorization management use one attempt/no automatic retry/no fallback;
- server-owned route->audience mapping prevents arbitrary downstream/token fan-out;
- completed session `last_seen` persistence is coalesced to at most once per five-minute activity window rather than one Redis write per HTTP request;
- raw session/pre-auth identifiers are HMAC-located, so privacy-safe keying does not require secondary lookup scans;
- User->sessions index permits bounded revocation/erasure rather than full Redis key scans;
- AES-256-GCM refresh encryption happens only when credential persistence/rotation requires it, not as gratuitous per-resource payload encryption;
- access JWT retention is bounded to token expiry/session state and is transport reuse only; it never avoids resource-owner online Authorization.

Measure BFF separately by route class: auth/OIDC, session/bootstrap, Identity onboarding, Authorization management, and ordinary resource dispatch. Track HTTP latency, active/in-flight requests, cancellation, downstream pool saturation, Redis ops/latency, OIDC provider latency, token-broker latency, AES-GCM CPU, rejected body/header counts, and session write-coalescing effectiveness with low-cardinality labels.

HPA 3..12 remains disabled until load evidence shows safe scaling signals and downstream capacity. Replica scaling that simply multiplies Redis/downstream pressure is not a performance fix.

## 3. Production PostgreSQL trades fleet cost for isolation

Every persistent production microservice owns its own CloudNativePG cluster. This deliberately reduces application-credential, superuser, backup, noisy-neighbor, and recovery blast radius while increasing pod/storage/WAL/backup/upgrade operational cost.

Fleet automation, common policy/observability, independent backup trust, per-service capacity budgets, restore evidence, and one-cluster upgrade waves are intended mitigation. Physical consolidation is not a default performance optimization.

Required synchronous durability adds commit latency intentionally. Measure commit p95/p99 on real storage/network topology; do not disable required durability merely to improve latency.

## 4. Security Redis splits before it clusters

Sentinel topology preserves simple atomic quota/session semantics. If BFF sessions and security quotas materially interfere, split them into independent Sentinel deployments before adding Redis Cluster complexity that may complicate multi-key atomic policy.

For Web BFF specifically, measure session cardinality, User->sessions index cardinality, five-minute last-seen coalescing hit rate, pre-auth churn, OIDC quota operations, memory headroom, failover latency and eviction count. `noeviction` and security fail-closed behavior are not relaxed to recover throughput.

## 5. Kafka stays off synchronous request paths

Kafka is durable async transport. RF3/acks=all costs disk/network but remains outside ordinary synchronous request/reply. Avoid partition-per-tenant/cardinality explosion; partition/key changes require measured throughput/order evidence.

Critical replayable publication + consumer dedup evidence covers the 35-day recovery horizon.

## 6. WAF latency is measured, never bypassed

Measure Coraza/CRS incremental latency, CPU, body inspection, and false-positive rate in DetectionOnly/staging before blocking. Scale replicas and apply narrow route-specific body/rule policy before architecture changes. Direct Traefik -> BFF application routing remains prohibited.

BFF body/header limits remain independent defense in depth; increasing WAF body limits never silently increases application limits.

## 7. Notification current design removed bespoke hot-path complexity

Current Notification avoids two prior classes of overhead:

- request-path OpenBao Transit calls: replaced by mounted purpose-specific local AES-256-GCM key rings;
- bespoke application clock/fence control plane: replaced by PostgreSQL-authoritative immutable deadlines, durable `DISPATCHING` commit, synchronous DB durability, and reconciliation.

Do not reintroduce these mechanisms without measured evidence and a current architecture decision.

## 8. Developer velocity remains lighter than production verification

```text
local:        unit + focused architecture/application tests
adapter:      focused Testcontainers/contract tests
PR:           compile/unit/ArchUnit/contracts/static/security checks in parallel
staging:      real mesh/WAF/HA/integration/smoke/critical Playwright
scheduled:    heavy load/chaos/PITR/DR/certificate/provider exercises
```

A heavy test may leave every-PR cadence only if a faster deterministic gate protects the regression class and the heavy test remains mandatory at appropriate release/scheduled cadence.

## 9. Evidence required before adding complexity

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
