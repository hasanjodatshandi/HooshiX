# Performance and Bottleneck Register — Current State

This document identifies current runtime, availability, operational, and delivery bottlenecks. It never authorizes bypassing security/correctness rules.

Optimize from evidence in this order:

```text
algorithm/query/model
-> bounded dependency/concurrency/cardinality tuning
-> capacity/replica scaling
-> physical isolation/externalization
-> new architecture mechanism only when evidence earns it
```

`production-single-server` deliberately puts application, data, security, edge, and ordinary observability on one host failure/resource domain. A `2 vCPU / 3-4 GiB RAM` host is not an approved production claim.

## Priority register

| Priority | Bottleneck/risk | Current mitigation | Trigger/action |
| --- | --- | --- | --- |
| P0 | Single-host CPU/RAM/IO/network/failure domain | simultaneous complete-stack benchmark; >=30% CPU/RAM reserve; critical/security load evidence; network/kernel limits measured | any OOM/swap/MemoryPressure/<30% reserve/repeated outage/unsafe IO -> add capacity or move HA |
| P0 | Online Authorization on protected request path | one final check, 300ms max, bounded concurrency, no cache/retry/stale allow | tune SQL/index/pool/process first; architecture change only after measured SLO miss |
| P0 | Shared physical PostgreSQL in single-server | distinct DB/roles/Flyway/RLS, global pool <=70%, WAL/PITR, noisy-neighbor tests | sustained IO/connection/recovery blast radius -> HA dedicated clusters |
| P0 | Semantic quota security under clock/cardinality attack | exact-IP hard identity; aggregate pressure; Clock Safety Guard; low-cardinality allocation guard; noeviction; >=30% Redis memory reserve | time/capacity guard trips, memory reserve/cleanup unsafe, attack envelope fails -> upstream controls/capacity/split Redis/HA; never fail open |
| P1 | Web BFF fan-out/session/OIDC/quota | bounded outer/child deadlines, exact audience map, Redis/session limits, no authoritative retry | route/dependency/crypto/Redis saturation -> tune bounded work then add capacity/HA |
| P1 | Public edge/client-address chain | trusted L4 PROXY v2, WAF-only BFF, exact client context, bounded request size | handshake/CPU/conntrack/FD/port pressure -> capacity/tuning; never insecure trust/bypass |
| P1 | Observability on same host | bounded labels/sampling/retention; Collector memory/finite queues; Prometheus/Loki/Tempo explicit storage limits; external host monitor | telemetry pressure threatens headroom/IO -> reduce safe telemetry volume or externalize/add capacity; required audit cannot be dropped |
| P1 | Istio Ambient on one host | no waypoint by default; measure CNI/ztunnel/istiod latency/resources | cannot fit reserve/SLO -> add capacity or reviewed replacement; never silently disable identity/mTLS |
| P1 | Security Redis single instance | TLS/ACL/noeviction/AOF, fail closed, allocation/cardinality monitoring | AOF/memory/restart/security availability unacceptable -> split/add capacity/HA Sentinel |
| P1 | Kafka single broker/controller | RF1/minISR1/acks-all/idempotence, Outbox/Inbox/replay | disk/async outage exposure unacceptable -> HA RF3/minISR2 |
| P1 | Kyverno one replica | CEL policy set, fail-closed admission, reduced evidence-backed policy inventory | admission latency/capacity blocks release -> capacity/HA; never disable enforcement |
| P1 | WAF hop | bounded inspection/tuning/body limits | latency/CPU burn -> narrow tune/capacity; never direct BFF bypass |
| P1 | Virtual Threads vs scarce downstreams | bounded pools/bulkheads/queues | downstream saturation while threads appear healthy -> tighten limits/capacity |
| P1 | Argon2id CPU/memory | semantic quotas + bounded hash bulkhead | queue/SLO burn -> add CPU/capacity; never weaken hash silently |
| P2 | HIBP-derived Compromised Password SQLite lookup | immutable indexed complete corpus, disk-backed fixed query, bounded concurrency | full-corpus latency/IO/cardinality compatibility fails -> tune schema/storage/capacity; no false-clean or runtime provider fallback |
| P2 | Reference Data | local immutable bundle until independent-service trigger | only create service for independent consumers/lifecycle/security/scale/ownership evidence |
| P2 | External providers | durable handoff/ambiguity/reconciliation | sustained provider impact -> reviewed second-provider decision |
| P1 | Conversation ModelRun queue/provider/cost path | bounded DB claims; no locks across provider I/O; global/per-tenant bulkheads; 60s one-attempt ceiling; worst-case cost reservation | queue age/provider saturation/cost ambiguity threatens SLO or budget -> shed safely, reduce configured bounds, add measured capacity, or review provider/worker boundary; no blind retry/free ambiguous run |
| P2 | OpenBao control plane | unchanged; request hot paths use local validated material; recovery evidence | refresh/recovery/RTO risk -> review OpenBao HA, not removal |
| P2 | CI/platform validation time | fast inner loop + parallel checks + heavy staged/scheduled gates | feedback bottleneck -> profile/shard/cache without removing authority |
| P2 | Premature microservice proliferation | independent deployable requires real boundary/trigger | no ownership/lifecycle/security/scale evidence -> keep module/capability local/gated |

## 1. Complete-stack single-server capacity

Measure all intended components **together**:

- K3s/system overhead;
- all application JVM CPU/RSS;
- PostgreSQL connections/process memory/query/WAL/checkpoint/backup IO;
- Redis memory/AOF/rewrite, active bucket cardinality, new allocation/cleanup rate;
- Kafka broker/controller memory/log IO/produce/fetch/lag;
- Istio CNI/ztunnel/istiod;
- Kyverno admission;
- Traefik/Caddy/Coraza;
- OpenBao/secret delivery support components;
- OpenTelemetry Collector CPU/RSS/receive/export/queue/drop;
- Prometheus series/scrape/TSDB memory/disk/IO;
- Loki ingest/query/storage;
- Tempo ingest/query/storage;
- Grafana/Alertmanager;
- filesystem/free space/latency/IOPS/queue depth;
- MTU/PMTU, conntrack, file descriptors/listen queues, ephemeral ports/TIME_WAIT;
- public/management interface packet/error/drop;
- reboot/startup/recovery and external host-monitor behavior.

Pass criteria:

```text
no OOM kill
no sustained swap
no MemoryPressure eviction
>=30% validated CPU reserve
>=30% validated memory reserve
applicable >=2x projected critical/security peak
safe WAL+AOF+Kafka+telemetry concurrent IO
safe kernel/network table reserve
no security/admission/backup/audit/network-trust/observability bypass
```

If it fails, tune safe cardinality/retention/concurrency, add CPU/RAM/SSD/network, externalize ordinary observability, or move HA. Do not remove OpenBao/Kyverno/Ambient/PITR/MFA/WAF/fail-closed controls or required audit.

### Executable staging evidence

Run the repository-owned bounded suite only after the production-fidelity lane is
healthy:

```bash
make production-fidelity-verify
scripts/performance/staging_capacity_suite.sh
```

It records a 60-second invalid-login load and a 30-minute session-bootstrap soak,
with >=30% CPU and memory headroom, no swap, finite latency limits, and exact Git
revision. Evidence is written below `.platform-runtime/stage7/capacity/` and is
validated again after execution. The loopback connection override preserves the
`hooshix.local` TLS/Host identity and refuses non-loopback destinations.

This suite is a safe, repeatable staging lower bound. It does not measure the
Production K3s host, Production HIBP corpus, real providers, backups, external host
monitor, or the full WAL/AOF/Kafka/telemetry concurrent-IO envelope. Those claims
remain `NOT VERIFIED` until their owning environment executes the complete register.

## 2. Semantic quota attack capacity

Normal user load is insufficient capacity evidence because attacker-selected unique inputs can grow Redis security state.

Measure:

- active security bucket count and bytes/bucket distribution;
- new allocation rate by bounded operation/dimension;
- cleanup rate/backlog;
- Redis used/max memory and reserve;
- AOF growth/rewrite/fsync under allocation flood;
- exact-IP vs aggregate-prefix pressure under NAT/campus/VPN/IPv6;
- common-mode clock guard behavior while Redis is under pressure;
- upstream coarse rate-limit effect before new Redis state.

A valid attack test reaches the reviewed safety threshold and observes `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM. Capacity guard state must remain low-cardinality.

Aggregate `/24`/`/64` pressure helps detect source concentration but is not the sole v1 hard user-deny bucket. Exact `/32`/`/128` hard gate trades some evasion resistance for lower collateral lockout; contact/subject/browser dimensions and upstream controls provide defense in depth.

## 3. Authorization bottleneck

Investigate SQL plan/index/cardinality, Hikari acquisition/global connection budget, projection fan-out, bounded concurrency/process CPU, and shared-host storage pressure before proposing cache/read-model changes. Current freshness semantics prohibit stale permission authority.

## 4. Web BFF

Measure by bounded route class: HTTP p95/p99, in-flight, cancellation, downstream deadlines/pools, Redis, OIDC/provider/token brokerage, crypto, trusted client-address parsing/failure, quota time/capacity state, and telemetry overhead.

In single-server, one BFF replica/HPA off remains. Saturation after code/bulkhead/pool tuning means more host capacity or HA, not fake same-host HA.

## 5. Compromised Password

Measure actual approved HIBP SHA-1 corpus:

- total rows/dataset bytes;
- observed per-prefix distribution/max;
- serialized response size distribution;
- warm/cold fixed lookup p95/p99;
- storage IOPS/latency;
- Xerial native startup/extraction;
- bounded concurrency/queue;
- dataset build time and <=30-day refresh cadence;
- >=2x projected credential-write peak.

A static historical cardinality assumption is not evidence because HIBP corpus grows. Release selects compatibility bounds from measured complete corpus + safety margin and fails build rather than truncate results.

## 6. Reference Data

Before independent-service trigger, measure bundle size/startup heap/serialization/cache effectiveness inside owning deployable. A local immutable bundle is cheaper than a speculative gRPC hop.

Create independent service only after ADR-0041 evidence trigger. Then measure its own CPU/RAM/latency/allocation/queue and verify the boundary creates real value.

## 7. PostgreSQL/Redis/Kafka

PostgreSQL: total/per-service connections, query/transaction latency, WAL/checkpoint/backup IO, storage queue depth/free space, noisy-neighbor impact.

Redis: session cardinality, quota exact/aggregate keys, allocation/cleanup, AOF/rewrite, restart recovery, common-clock guard, memory reserve.

Kafka: disk/log IO, produce/fetch p99, lag, restart/rebuild/replay. Broker is not business authority.

## 8. Istio/Kyverno/edge

Ambient: measure idle/peak resources, latency/throughput/connections, OOM/restart, Calico interaction.

Kyverno: measure CEL policy admission latency/resource pressure; legacy policy migration is not part of greenfield runtime.

Edge: measure L4/Traefik/WAF incremental latency, connection/handshake cost, body inspection, client-address parsing, false positives, and kernel pressure. Performance never permits direct BFF route or insecure proxy trust.

## 9. Day-One observability performance

ADR-0044 ordinary telemetry has explicit budgets.

Measure:

- per-service observation/span/log volume;
- sampling effectiveness;
- metric series cardinality by service/metric family;
- Collector receive/export throughput, memory, queue and drop;
- Prometheus scrape/ingest/query/TSDB;
- Loki ingest/query/compaction/storage growth;
- Tempo ingest/query/storage growth;
- Grafana/Alertmanager overhead;
- telemetry IO contention with WAL/AOF/Kafka/backups.

Retention/sampling/cardinality are evidence-driven deployment values. Reduce only ordinary telemetry dimensions/volume; required security audit cannot be sampled/dropped into non-authority.

External black-box monitoring must survive total local host loss and should not create high-frequency/public-load amplification.

## 10. Developer velocity

```text
local: unit + focused architecture/application
adapter: Testcontainers/contract/dataset fixtures
PR: compile/unit/ArchUnit/contracts/static/security/quota/observability config
staging: mesh/WAF/integration/critical journey/telemetry correlation
scheduled/release: heavy load/chaos/DR/provider/full-stack
```

Do not require full local cluster for pure Domain/Application work. Heavy tests may leave every-PR cadence only when a faster deterministic gate protects the regression class and heavy evidence remains mandatory later.

## 11. Evidence before adding complexity

Before adding cache/broker/proxy/service/provider/control-plane/pool/retry layer, record measured bottleneck, affected SLI/SLO, current tuning attempted, expected measurable gain, security/correctness impact, rollback, and new operational cost.

Architecture complexity must be earned by evidence.
