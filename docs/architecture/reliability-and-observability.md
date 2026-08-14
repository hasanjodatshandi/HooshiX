# Reliability and Observability Architecture — Current State

## 1. Reliability model

Reliability is built from bounded work, finite deadlines, durable local state, idempotency, explicit dependency semantics, and failure isolation. Blind retry or timeout inflation is not a reliability strategy.

ADR-0042 selects `production-single-server` as the initial production topology. This profile deliberately accepts infrastructure availability loss. It does **not** weaken correctness, security, durability evidence, fail-closed behavior, or recovery requirements. ADR-0043 defines the public client-address trust chain and single-server management-network boundary.

## 2. Dependency failure semantics

`dependency-criticality.yaml` is the machine-readable authority for operation-level synchronous dependency class, failure action, retry owner, fallback, owner, and current policy references. Its rendered matrix is generated evidence, not a second decision source.

Per ADR-0033, exact caller/child deadlines, cancellation, attempt limits, breaker behavior, idempotency conditions, concurrency/bulkhead/queue bounds, and implementation-specific retry timing remain in the owning service/operation contract and applicable current policy. They are not duplicated into the dependency registry merely for completeness.

For every synchronous edge:

- caller deadline is finite;
- child deadline fits the remaining parent budget;
- cancellation propagates where the technology supports it;
- retry is finite and owned by one layer only;
- layered mesh + client/application retry for the same failure is prohibited;
- bulkheads/queues/concurrency are bounded;
- fallback exists only when the operation-level registry explicitly permits it;
- security/authorization dependencies never fabricate success on error.

Authorization remains one authoritative online `CheckPermission`, one attempt, no stale permission cache/fallback/retry and fail closed.

## 3. Virtual Threads and bounded resources

Java Virtual Threads do not create downstream capacity. Hikari connections, Redis operations, Kafka IO, provider quotas, CPU-heavy work, cryptographic work and storage IOPS remain finite shared resources.

Adapters use bounded concurrency/queues where required. CPU-heavy work uses bounded workers. Queue growth and pool wait are observable. `Thread.sleep` is not a coordination strategy.

In `production-single-server`, all platform and application components compete for one host. Complete-stack CPU/RAM/storage/network pressure is therefore a first-class reliability signal, not a local component detail.

## 4. Production availability profiles

### 4.1 `production-single-server`

The selected initial profile has no infrastructure HA claim for:

- Kubernetes control plane/node;
- workload rescheduling to another node;
- PostgreSQL primary;
- Redis;
- Kafka broker/controller;
- Kyverno admission plane;
- host maintenance.

A host/node/kernel/storage event may stop the complete platform. This is formally accepted only after operator sign-off and recovery evidence.

Security/correctness behavior remains fail closed. Examples:

- Authorization failure never becomes ALLOW;
- Redis security decision failure never becomes local quota/session bypass;
- missing trusted public client-network identity never becomes caller-header or shared-proxy quota identity;
- Kafka outage does not change Outbox/Inbox/idempotency or turn Kafka into business truth;
- PostgreSQL outage does not authorize unsafe state reconstruction;
- Kyverno outage does not create an admission bypass;
- Ambient resource pressure does not authorize disabling workload identity/mTLS;
- OpenBao outage follows existing mounted/local-key/recovery contracts and does not authorize plaintext/Git fallback;
- management-overlay failure does not authorize public SSH.

### 4.2 `production-ha`

The expansion profile retains current redundant Kubernetes, dedicated PostgreSQL, Kafka, Redis, Kyverno and workload replica topology. Its failure tests prove one-component/node loss according to the applicable SLO and ADR.

## 5. SLO and error-budget interpretation

ADR-0005 and service-specific ADRs remain the SLO authority.

Application SLIs continue to be measured in both profiles. The single-server profile MUST NOT claim that infrastructure redundancy-dependent availability is proven merely because an application-level percentage target is written in an ADR/service document.

For `production-single-server`:

- record actual user-visible availability, latency, correctness and dependency error rates;
- retain burn/error-budget observability where a service SLO is defined;
- mark node-failover/replica-failover objectives `Not applicable to production-single-server` when physical redundancy is required to prove them;
- production approval requires explicit acceptance of whole-platform node-failure/maintenance downtime;
- repeated or unacceptable downtime is a trigger to move to `production-ha`.

Security/correctness gates do not become N/A because the profile is non-HA.

## 6. PostgreSQL reliability and recovery

Both profiles retain continuous WAL archive, encrypted off-site physical backup, daily base backup, 35-day PITR, monthly retained recovery artifacts, monthly restore evidence and quarterly full cold-DR.

`pg_dump + cron` is not the production recovery strategy.

`production-single-server`:

- one physical PostgreSQL process/host/storage failure domain for mutable service databases;
- distinct DB/runtime role/migration role/Flyway/RLS boundaries remain;
- no automatic primary failover claim;
- physical PITR restores the whole shared cluster into an isolated recovery environment;
- service-specific recovery then transfers only the required database through the approved controlled procedure;
- a failed shared-cluster restore blocks ordinary promotion for affected PostgreSQL-backed services as defined by ADR-0037.

`production-ha` retains per-service dedicated physical clusters, failover and independent physical backup identities.

Restored data does not receive traffic until integrity, schema, RLS and applicable erasure/legal-hold reconciliation pass.

`../runbooks/production-cold-dr.md` is the implementation-facing full-platform recovery procedure. ADR-0004 remains the RPO/RTO authority. The quarterly exercise measures the actual platform RTO and does not infer it from backup success.

## 7. Kafka reliability and replay

Kafka is rebuildable async transport, not business authority.

Single-server topology is one combined KRaft broker/controller, RF=1/minISR=1, `acks=all`, idempotence and unclean leader election disabled. Broker/node/disk outage may stop async transport and may lose broker-local data. That exposure is accepted only because critical event flows retain service-owned publication/reconstruction and Inbox/dedup evidence for the required recovery horizon.

HA topology retains RF=3/minISR=2 with the current broker/controller failure tests.

Both profiles require:

- Transactional Outbox for atomic state+publication business effects;
- at-least-once consumer assumption;
- idempotent/Inbox semantics where required;
- finite retry/DLQ behavior;
- stable event identities;
- critical publication/dedup evidence for at least 35 days;
- clean-cluster rebuild/replay/reconstruction tests.

## 8. Redis reliability

Security Redis is ephemeral security state, not business source of truth.

Single-server uses one TLS/ACL/`noeviction` Redis with AOF `appendfsync everysec`. AOF assists restart recovery but is not HA. If session state is lost, users reauthenticate. Covered semantic-quota/session decisions remain fail closed while Redis cannot produce a valid decision.

Network quota dimensions use only the ADR-0043 trusted edge/BFF client-network context. Missing/malformed/untrusted context fails closed for operations that require the network dimension; public forwarding headers never become a fallback identity.

HA uses the current primary/replica/Sentinel topology and failover tests.

Memory headroom, eviction count, AOF/rewrite latency, time-source health and operation latency are observable without raw/high-cardinality subject or client-address labels.

## 9. Istio/Kyverno reliability in the single-server profile

Istio Ambient is retained and must fit the complete-stack capacity envelope. Track `istiod`, CNI and `ztunnel` CPU/RAM, request p95/p99 impact, connection pressure, restarts/OOM and Calico interaction. A failed benchmark blocks production approval.

Kyverno may use one replica in the single-server profile. Admission availability is lower, but fail-closed enforcement remains. Track admission latency/errors/unavailability and policy-engine resource pressure. Existing workloads are not killed merely because admission later becomes unavailable.

The HA profile retains redundant Kyverno and node/failure-domain behavior.

## 10. Human-access and audit reliability

Required privileged-access audit is authoritative security evidence, not best-effort telemetry.

Single-server normal management reachability uses the ADR-0043 WireGuard overlay. Network admission is separate from hardened OpenSSH/FIDO2 authentication and separate from time-bounded JIT privilege. Public TCP/22 remains denied. A WireGuard outage never authorizes public SSH, password SSH, or shared access.

Required OS/`sudo`/Kubernetes/database records are exported off-host to append-only/tamper-resistant storage so loss or compromise of the single host does not erase the only audit copy. Shell history/`.bashrc` is not a recovery or audit substitute.

HA uses the current Teleport session/audit model.

Audit pipeline failure is an incident condition with explicit operational handling; it MUST NOT silently downgrade privileged-session evidence requirements.

## 11. OpenBao reliability — unchanged

ADR-0042 does not change OpenBao.

OpenBao remains the current secret authority with the existing Raft/PVC, Shamir, encrypted snapshot, restore/unseal and External Secrets workflows. Normal hot paths use validated mounted/local material so OpenBao is not a per-request availability dependency.

Secret-source recovery evidence remains mandatory. A profile capacity problem MUST NOT be solved by deleting/replacing OpenBao without a separate current security decision.

## 12. Observability model

Use OpenTelemetry/Micrometer with structured, bounded telemetry.

Required signals include, where applicable:

- request rate, latency, errors and saturation by low-cardinality route/operation;
- parent/child deadline exhaustion and cancellation;
- bulkhead/queue/pool wait and rejection;
- PostgreSQL pool/query/transaction/WAL/archive/backup/restore/storage pressure;
- Redis latency/memory/eviction/AOF/time-source state;
- Kafka produce/fetch/consumer lag/disk/rebuild/replay state;
- Istio/Kyverno/edge control-plane health;
- node CPU/memory/pressure/storage/free-space in single-server;
- effective MTU/PMTU failures where observable;
- conntrack usage/drops, file-descriptor/listen-queue pressure and ephemeral-port/TIME_WAIT pressure;
- public/management interface packet/error/drop pressure;
- client-address trust-chain health without emitting the raw client IP as a normal metric/log/trace dimension;
- security/authentication/authorization/abuse signals without raw subject identifiers;
- last successful backup/restore/DR evidence and overdue status;
- audit export health.

Trace/log/metric dimensions never use unbounded tenant/user/session/request/resource identifiers or raw client IPs.

## 13. Logging and PII

Logging is allow-list based, structured and injection-safe.

Do not log raw passwords, OTP/recovery codes, tokens, cookies, private keys/secrets/provider credentials, full request/response bodies, unreviewed SQL binds, complete gRPC metadata or unreviewed exception/provider payloads.

PII appears only for approved purpose with masking/tokenization or managed-key HMAC pseudonymization where correlation is required. Raw client IP used transiently for network security context is not an ordinary telemetry field. Metric labels remain low-cardinality.

Ordinary telemetry may use bounded buffering/sampling/drop according to its contract. Required audit/security evidence MUST NOT be silently dropped or reclassified as ordinary telemetry.

## 14. Single-server recovery order

A full-host recovery/reboot uses the tested procedure in `../runbooks/production-cold-dr.md` for disaster recovery and the applicable component runbooks for narrower events.

At minimum, evidence proves:

1. host/storage/network/time baseline is healthy;
2. management-only access and audit are available without enabling public SSH;
3. K3s control plane and Calico are healthy;
4. OpenBao/secret delivery and required local mounted material can recover under their existing contract;
5. PostgreSQL and required physical recovery state are healthy;
6. Redis/Kafka recover with their profile-specific semantics;
7. Istio/Kyverno/edge security controls and client-address trust are healthy before unsafe traffic/deployment paths open;
8. applications become ready only after local/security dependencies permit safe service;
9. erasure/legal-hold reconciliation and audit/telemetry confirm recovery and no fail-open bypass occurred.

The sequence is validated by reboot/recovery tests rather than assumed from startup ordering.

## 15. Capacity/recovery triggers for `production-ha`

Move from single-server toward HA when any of these becomes material:

- business requirement for maintenance without full-platform outage;
- repeated node/host incidents or unacceptable user downtime;
- inability to keep >=30% validated CPU/memory headroom;
- persistent PostgreSQL/Redis/Kafka/telemetry storage contention;
- security/control-plane components cannot meet safe latency/capacity on one host;
- shared PostgreSQL blast radius or upgrade window becomes unacceptable;
- recovery tests show single-server RTO is no longer acceptable;
- broker/session/security dependency availability needs exceed the non-HA profile.

Do not mask these triggers by weakening correctness or security.

## 16. Verification

Required evidence includes the applicable dependency registry/schema/render checks, owning-contract deadline/cancellation/bulkhead tests, restore/PITR/DR evidence, Kafka replay/idempotency tests, Redis failure/time/network-identity tests, logging/PII controls, security negative tests and smoke/critical-journey evidence.

Single-server additionally requires:

- whole-host reboot/loss/rebuild exercise under the production cold-DR procedure;
- measured ADR-0004 RPO/RTO evidence;
- complete-stack >=2x projected critical-path/security-dependency load evidence where required;
- >=30% validated CPU+memory headroom;
- no OOM/sustained swap/node memory-pressure eviction;
- storage/IO contention evidence including WAL/AOF/Kafka/telemetry;
- MTU/PMTU, conntrack, file-descriptor/listen-queue and ephemeral-port safe-headroom evidence;
- isolated shared-PostgreSQL PITR and service-specific recovery evidence;
- Redis AOF/restart/re-authentication behavior;
- Kafka clean rebuild/replay under RF=1;
- Ambient/Kyverno benchmark/fail-closed evidence;
- trusted client-address anti-spoofing/fail-closed evidence;
- WireGuard management/public-SSH-denial plus off-host privileged-audit integrity;
- explicit operator sign-off on whole-platform downtime and host/root blast-radius risk.

Production readiness remains blocked until required runtime evidence exists.
