# Production Decision Summary — Current State

- **Reviewed:** 2026-08-13
- **Mode:** current-only
- **Status:** architecture target; implementation evidence remains subject to `PRODUCTION-READINESS-CHECKLIST.md`

This document summarizes the effective production architecture only. The current Decision Register and current-state architecture documents are authoritative for detailed scope.

## Application and service model

- Backend services use DDD + Hexagonal Architecture with inward dependencies.
- Java services use Java 25, Spring MVC + Virtual Threads, constructor injection, independent Gradle builds, and the executable quality gates in `../engineering/coding-standards.md` and `../engineering/build-and-ci-quality-enforcement.md`.
- Public/browser traffic terminates through Web BFF; ordinary internal synchronous communication is gRPC; asynchronous integration uses Kafka where a durable event boundary is appropriate.
- Every service owns its contracts, data, deployment, and release lifecycle. Cross-service database access and shared business/persistence models are prohibited.

## Identity and browser security

- Identity owns registration, password credentials, external identity linking, MFA, token signing, and identity/session semantics.
- Password hashing uses the Technology Baseline Argon2id parameters with bounded hashing concurrency and compromised-password screening.
- External identities are bound by stable issuer + subject, never auto-linked only by email.
- Browser login uses OIDC Authorization Code + PKCE S256 through the BFF with server-side state/nonce/verifier handling.
- The browser receives only the secure BFF session cookie, not provider/internal access tokens.
- BFF session security includes fixation/rotation defense, strict CORS, Origin + synchronizer-token CSRF, and reviewed security headers.
- Identity JWT signing uses local RSA-3072/RS256 key material with stable `kid`, planned rotation, and local GitOps verifier bundles.

## Authorization

- Protected resource services make one authoritative online `CheckPermission` call after safe local reject-only token/context/syntax checks.
- There is no permission-result cache, Bloom-filter authorization, Kafka invalidation, retry, stale allow fallback, or BFF duplicate routine check.
- Production Authorization target: >=3 replicas, PDB/topology spread, >=99.95% rolling-30d availability, p95<=100ms, p99<=200ms, 300ms caller deadline, one attempt.
- Authorization uses bounded global/per-caller concurrency, <=25ms server queue wait, bounded PostgreSQL pool/queries, explicit overload shedding, and fail-closed caller breakers.
- Breaker recovery is de-correlated per caller instance and uses serialized real `CheckPermission` probes; a health endpoint cannot authorize breaker closure.
- Dependency criticality/fallback semantics are registered per operation/dependency edge in `dependency-criticality.yaml`.

## Semantic security quotas

- There is no quota microservice. The owning security service enforces its semantic quota atomically in service-owned ACL-isolated Redis.
- Quotas are fail-safe, use pseudonymous keys, explicit anti-lockout sequencing, and the current trusted-application-time + Redis-time skew/TTL rules.

## Notification

- Identity durably hands off human-channel delivery to Notification through idempotent internal gRPC.
- Notification owns template versioning/rendering, provider adapters, retry/reconciliation, delivery evidence, and terminal callback results.
- PostgreSQL is authoritative for Notification templates and lifecycle time.
- Sensitive caller/Notification escrow uses independent local AES-256-GCM key rings sourced through OpenBao/External Secrets. Routine Notification acceptance/dispatch/retry does not call OpenBao Transit.
- Exact accepted recipient/template/content/deadline identity remains stable across retries/reconciliation; sensitive ciphertext has a <=24h hard maximum and is erased earlier when possible.
- Provider I/O occurs only after a durable local `DISPATCHING` commit. Unknown outcomes are reconciled and never authorize blind redispatch.
- Production Email uses Liara Transactional Email over authenticated SMTP + STARTTLS.
- Production Iran SMS uses IPPanel Edge Webservice mode with Notification-rendered exact text and bounded authenticated status polling. Provider Pattern rendering is not the semantic authority.
- Local logging SMS is local-only and never a staging/production fallback.

## PostgreSQL and data isolation

- Every persistent production microservice owns a distinct PostgreSQL database, runtime/migration credentials, Flyway history, and dedicated CloudNativePG cluster.
- Critical clusters use the current three-instance synchronous durability/failover baseline, independent backup credentials/encryption context, continuous WAL archive, daily base backup, and tested PITR/restore.
- Tenant-owned production tables use forced RLS. Runtime roles are `NOSUPERUSER NOBYPASSRLS`, are not table owners, and cannot cross service/database boundaries.
- Flyway is the only schema-change mechanism. Executed migrations are immutable; evolution uses expand -> migrate -> contract. Application rollback must remain compatible with the expanded schema.
- Fleet operations use one reviewed GitOps baseline, one-cluster-at-a-time upgrade waves, monthly isolated restore evidence, and quarterly DR exercises.

## Kafka and contracts

- Kafka uses KRaft with three brokers + three dedicated controllers for the approved production baseline.
- Critical topics use RF=3, minISR=2, `acks=all`, idempotent producers, and no unclean leader election.
- State change + integration-event publication uses Transactional Outbox; consumers assume at-least-once delivery and are idempotent with durable Inbox/dedup semantics where required.
- Critical publication/dedup evidence is retained for the current 35-day recovery horizon.
- Kafka is rebuildable transport, not business source of truth. Cold DR recreates infrastructure/configuration and replays retained publication/state evidence.
- Protobuf is governed in Git with Buf compatibility; no runtime Schema Registry is deployed in v1.

## Kubernetes, edge, mesh, and secrets

- Production Kubernetes uses three dedicated stacked control-plane/etcd nodes and >=3 schedulable workers with a redundant stable API endpoint and N+1 capacity for critical paths.
- Application workloads use immutable digests, non-root execution, `allowPrivilegeEscalation=false`, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem where compatible, resources, separate startup/readiness/liveness probes, dedicated ServiceAccounts, and deny-by-default network policy.
- Public path: upstream volumetric-DDoS mitigation -> Traefik -> dedicated Caddy/Coraza WAF -> Web BFF.
- Direct public/Traefik application routing to BFF that bypasses the WAF is prohibited.
- Internal application workloads use Istio Ambient strict mTLS, ServiceAccount-derived identity, and least-privilege authorization. Waypoints exist only for an explicit L7 need.
- OpenBao 2.6.1 is the secret authority; External Secrets is the normal Kubernetes materialization boundary. Routine application request paths use local validated material rather than OpenBao RPCs.
- GitOps desired state lives in this repository under reviewed environment roots and is reconciled by Argo CD.

## Supply chain, CI/CD, and production access

- Third-party CI actions/tools/artifacts are pinned/verified according to repository policy; workflow permissions are least privilege and privileged secrets are not exposed to untrusted PR execution.
- Release images are immutable, SBOM-indexed, signed with Cosign, carry provenance/source identity, and are verified by admission policy.
- The exact signed image digest validated in staging is promoted to production; production rebuild is prohibited.
- Vulnerability response continuously correlates deployed digests/SBOMs with approved advisory/threat-intelligence inputs, enforces expiring exceptions, and applies production remediation/escalation policy. No feed/scanner is treated as proof of zero unknown vulnerabilities.
- Human privileged production access uses Teleport JIT SSO/WebAuthn, short-lived elevation, approvals, least privilege, and audited/recorded sessions.

## Logging and observability

- Services emit structured JSON stdout and OpenTelemetry/Micrometer metrics/traces with bounded low-cardinality attributes.
- Logging is allow-list based. Secrets, credentials, tokens/cookies, OTPs, sensitive payloads, SQL binds, complete metadata/headers, and unreviewed PII are prohibited from raw telemetry.
- Source rules, pipeline redaction, synthetic canary tests, and runtime detection provide defense in depth.
- SLOs/error budgets use burn-rate policy rather than isolated percentile paging.

## Current primary capacity boundaries

1. Authorization + PostgreSQL request path;
2. per-service PostgreSQL HA fleet capacity/restore/upgrade overhead;
3. security Redis latency/failover;
4. password-hashing CPU/memory under attack/load;
5. WAF inspection on every public request;
6. Kafka disk/partition/consumer capacity when async flows grow;
7. Liara/IPPanel availability and IPPanel polling limits;
8. worker-node capacity and replica placement during node loss.

Detailed metrics/scale triggers live in `performance-and-bottlenecks.md`.

## Evidence status

Architecture and documentation are decisions, not executable proof. Production readiness requires the actual source/build/workflows/manifests plus the tests, scans, load/failover/restore/security evidence defined in `PRODUCTION-READINESS-CHECKLIST.md`. Where implementation artifacts do not yet exist, evidence remains **NOT VERIFIED**.
