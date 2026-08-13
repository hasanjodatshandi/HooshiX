# Production Architecture Review — Current State

- **Reviewed:** 2026-08-13
- **Status:** architecture target accepted; implementation evidence is not implied
- **Documentation mode:** current-only

## Outcome

The current v1 architecture is coherent as the repository production target, subject to the executable implementation/evidence gates in `PRODUCTION-READINESS-CHECKLIST.md`.

The design favors strong correctness/security with bounded operational complexity: add redundancy where loss would violate user/security/data guarantees, and avoid extra synchronous services, request-path secret-manager RPCs, duplicate retry layers, or bespoke distributed coordination without measured need.

## Current architecture conclusions

- Identity owns registration, credential/MFA/session/token-signing concerns; external identities bind by issuer+subject and browser credentials remain BFF-managed.
- Authorization remains one online authoritative fail-closed dependency with no permission cache/Kafka invalidation/stale fallback/retry. It has explicit SLOs, fair overload isolation, HA/capacity gates, burn alerts, and de-correlated real-contract breaker recovery.
- Semantic security quotas remain service-owned and atomically enforced in isolated Redis; no quota microservice is introduced.
- Notification owns durable human-channel delivery. Sensitive retry state uses bounded local AES-GCM key rings rather than request-path OpenBao Transit. PostgreSQL-authoritative time and a durable `DISPATCHING` commit replace bespoke clock/fence coordination.
- Production Notification providers are Liara Transactional Email and IPPanel Webservice-mode Iran SMS. Provider ambiguity is explicit and never converted to fabricated success or blind resend.
- Every persistent production microservice owns a distinct PostgreSQL database/credentials/Flyway history and dedicated CloudNativePG cluster; tenant-owned tables use forced RLS.
- Kafka is replicated rebuildable transport, with transactional outbox/idempotent consumer semantics and 35-day critical recovery evidence.
- Browser traffic follows upstream L3/L4 volumetric mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> Caddy/Coraza WAF -> Web BFF; internal traffic uses Istio Ambient strict mTLS + workload identity + least-privilege authorization.
- GitOps, signed/provenanced immutable artifacts, admission verification with least-privilege policy authoring and bounded policy-engine egress/SSRF controls, continuous SBOM/advisory correlation, PII-safe telemetry, and JIT privileged access form the production security/operations baseline.
- Java/source quality uses the canonical coding standard plus executable Spotless, SpotBugs, ArchUnit, Semgrep, dependency verification, contract, test, and GitHub Actions gates where implementation exists.

## Main bottlenecks and failure domains

Highest-risk current capacity/availability boundaries are:

1. Authorization service + PostgreSQL query/pool path;
2. per-service PostgreSQL HA fleet capacity, synchronous-write latency, backup/restore load, and upgrade/operations overhead;
3. security Redis latency/failover for semantic quotas and BFF session state;
4. password-hash CPU/memory under login/credential attack load;
5. WAF inspection cost on every public request;
6. Kafka disk, broker, partition, consumer-lag, and replay capacity;
7. Liara/IPPanel latency/throttling and IPPanel report polling/reconciliation;
8. Kubernetes worker capacity/replica placement during node loss;
9. external upstream DDoS/provider/identity dependencies outside the cluster.

The metric, mitigation, and scale/split triggers are maintained in `performance-and-bottlenecks.md`.

## Deliberately absent complexity

The current production target intentionally does **not** add:

- a duplicate routine Authorization check in the BFF;
- a permission-result cache or Kafka invalidation path;
- a quota microservice;
- a runtime Schema Registry in v1;
- Notification per-message OpenBao RPCs;
- a bespoke Notification clock-health agent, Chrony `hostPath` sidecar, or dispatch-fence coordinator;
- per-request remote JWKS lookup for normal internal token verification;
- PgBouncer/Redis Cluster/external etcd without measured need;
- an Istio waypoint for every service/namespace;
- retries in both the application and mesh/client layers for one failure;
- Argo CD/OpenBao request-path HA merely for symmetry when hot paths do not depend on them.

These omissions are intentional current decisions, not unresolved historical alternatives.

## Security review

Current security boundaries are coherent only when enforced together:

- trusted tenant/user/workload identity is derived/validated at the correct boundary;
- internal services are not directly Internet-exposed;
- upstream volumetric protection and redundant external load balancing precede Traefik/WAF; no public route bypasses the WAF;
- WAF does not replace authentication/authorization/validation/semantic quotas;
- Istio identity does not replace NetworkPolicy or native datastore authentication;
- local reject-only Authorization prechecks cannot grant permission;
- sensitive material never enters Kafka/logs/traces/metrics/raw provider telemetry;
- production secrets are not committed to Git/Helm values/images;
- production workloads use hardened security contexts and independent ServiceAccounts;
- admission-policy authoring is least privilege and policy-engine external context cannot become unrestricted SSRF-capable egress;
- signed artifact admission does not replace continuous vulnerability response;
- no vulnerability feed/scanner is considered proof that unknown vulnerabilities do not exist.

## Reliability review

- All synchronous dependencies have finite deadlines and bounded concurrency.
- Retry is safe/idempotent and single-owner only.
- Remote I/O is not performed inside database transactions.
- Kafka publication uses transactional outbox and at-least-once consumers are idempotent.
- CloudNativePG failover must preserve required durable commits or refuse unsafe failover.
- Restore evidence, not backup existence, proves recovery capability.
- Release rollback is allowed only when schema/data/runtime state is backward compatible; unsafe database downgrade is not used to satisfy an arbitrary rollback timer.
- Error-budget/burn policy, chaos tests, and failover/load evidence remain production gates.

## Delivery-speed guardrail

Local development remains smaller than production. Pure Domain/Application work should run without Kubernetes/Istio/WAF/Kafka HA. Integration work uses the pinned local kind/Ambient/Traefik/WAF foundation only when the integration behavior is actually under test. Heavy load, failover, backup/PITR, DR, provider, certificate, and production-policy evidence belongs to staging/release/scheduled pipelines.

## Coding-quality review

The coding baseline incorporates feature-first/nature-separated packages, strict package naming, Domain/persistence separation, constructor injection, bounded files/responsibilities, no dumping-ground packages, explicit transaction/deadline/retry/idempotency rules, PII-safe telemetry, hardened container/Kubernetes settings, Helm migration discipline, and immutable same-digest staging-to-production promotion.

Machine-checkable rules should be executable. Documentation-only presence is not source compliance.

## Evidence gap

The architecture review does **not** claim that the implementation already satisfies these rules. Until service source/builds, Gradle wrappers/locks, workflows, manifests, policy tests, scans, load/failover/restore exercises, and deployment evidence exist and pass, those implementation dimensions remain `NOT VERIFIED`.
