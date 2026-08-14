# Production Decision Summary — Current State

- **Reviewed:** 2026-08-14
- **Mode:** current-only
- **Status:** architecture target; implementation evidence remains subject to `PRODUCTION-READINESS-CHECKLIST.md`

This document summarizes effective production architecture only. Current Decision Register and current-state architecture documents are authoritative for detailed scope.

## Application and service model

- Backend services use DDD + Hexagonal Architecture with inward dependencies.
- Java services use Java 25, Spring MVC + Virtual Threads, constructor injection, independent Gradle builds, and executable quality gates in `../engineering/coding-standards.md` and `../engineering/build-and-ci-quality-enforcement.md`.
- Public/browser traffic terminates through Web BFF; ordinary internal synchronous communication is gRPC; asynchronous integration uses Kafka where a durable event boundary is appropriate.
- Every service owns its contracts, data, deployment, and release lifecycle. Cross-service database access and shared business/persistence models are prohibited.

## Identity and Web BFF/browser security

- Identity owns registration, password credentials, external identity linking, MFA, token signing, RefreshFamily/session semantics, and the internal BFF exact-audience token-broker operation.
- Password hashing uses Technology Baseline Argon2id parameters with bounded hashing concurrency and compromised-password screening.
- External identities bind by stable issuer + subject, never auto-linked only by email.
- Web BFF is the only browser-facing application API boundary. v1 public REST is under `/api/v1` with reviewed `/auth`, `/identity`, and `/authorization` subspaces; internal gRPC names are not mechanically public API.
- Public JSON is <=256KiB, auth/OIDC/session body <=64KiB, headers/metadata <=16KiB, multipart/file upload is absent in v1, and public RFC-9457 errors expose only stable redacted fields.
- Browser login uses OIDC Authorization Code + PKCE S256. `state` and `nonce` are exactly 256 CSPRNG bits; verifier is exactly 32 random bytes Base64URL-no-padding. Server-side pre-auth uses purpose-HMAC locator, <=10m TTL, single-use, max five live/browser, and `__Host-sajtech-preauth` Secure/HttpOnly/SameSite=Lax/Path=/ cookie.
- Redirect URI is exact; post-login target is a canonical same-origin relative path <=1024 and rejects absolute, `//`, backslash, control and encoded bypass forms.
- BFF validates provider protocol before Identity and forwards only bounded trusted evidence; provider authorization/access/refresh/ID tokens never enter Identity or browser storage.
- BFF->Identity OIDC evidence is exactly 256-bit CSPRNG, two-minute lifetime, >=10-minute spent/replay retention, workload/request/issuer/subject/time/metadata bound, and email equality never auto-links.
- Browser receives only secure BFF cookies/CSRF bootstrap state, not provider/Identity/downstream access or refresh tokens.
- BFF uses a server-owned route->downstream/audience mapping and Identity internal `IssueAudienceAccessToken` to obtain five-minute exact-audience JWTs only for authorized BFF workload + active Session/RefreshFamily + allow-listed current session/tenant mode. Browser cannot select arbitrary audience; `authenticated_onboarding` cannot obtain ordinary resource or `authorization-service` audiences. This is not a public generic OAuth token exchange.
- BFF session IDs have >=256-bit entropy and use purpose-HMAC Redis locators. Session idle <=7d, absolute <=30d and immutable; `last_seen` persistence is coalesced to at most once per five-minute activity window.
- Every completed BFF session maps to one Identity RefreshFamily and a purpose-HMAC/pseudonymous User->sessions index supports logout-all/suspension/DELETING/erasure/family revocation without unbounded scans.
- Session rotation is atomic with predecessor immediately invalid and no dual-valid grace. Any retained refresh credential is AES-256-GCM encrypted with random 96-bit nonce/128-bit tag/session-purpose-key AAD; key rotation 90d, previous decrypt keys through dependent-session lifetime+7d, atomic reload, <=1h last-valid snapshot then fail closed.
- Active TOTP after password or Google primary proof remains pre-auth only until MFA succeeds. `authenticated_onboarding` allows only reviewed Identity onboarding/profile/tenant/invitation-selection routes and has no ordinary resource JWT.
- CSRF is exactly 256 CSPRNG bits/session-bound; only purpose-HMAC is stored and compare is constant-time. Unsafe production browser requests require exact Origin + CSRF + `Sec-Fetch-Site:same-origin`; missing Fetch Metadata fails closed. v1 is same-origin only with no cross-origin credentialed CORS.
- Exact CSP prohibits `unsafe-inline`/`unsafe-eval`; auth/OIDC/session/Authorization-admin responses are `Cache-Control: no-store`. HSTS/nosniff/referrer/Permissions-Policy/frame controls are tested.
- BFF owns OIDC protocol quotas: start network 60/refill1 per5s/1h and callback network 120/refill2 per1s/30m, separate from Identity Google-login pressure.
- BFF erasure removes sessions, pre-auth/OIDC state, encrypted refresh, User->sessions index and other user-linked continuation; receipt is non-PII.
- BFF runtime is `platform-apps/web-bff`, HTTP8080, separate management port, >=3 replicas/PDB2, HPA 3..12 only after load evidence, hardened pod security, and deny-by-default egress only to Identity, Authorization management, registered resource services, BFF/security Redis, configured Google OIDC and approved telemetry.
- Identity JWT signing uses local RSA-3072/RS256 key material with stable `kid`, planned rotation, exact audience, five-minute access lifetime and local GitOps verifier bundles.
- Platform-admin Identity operations do not trust platform-role claim from browser/JWT; Identity uses Authorization's separate fail-closed `CheckPlatformPermission` for exact platform tenant/legal-hold permission.

## Authorization

- Permission definitions are exact Git-owned contracts with TENANT/PLATFORM scope and `ACTIVE -> DEPRECATED -> RETIRED` lifecycle; unknown/retired keys fail closed, deprecated keys cannot receive new grants, and permission identifiers are never reused for new meaning.
- Tenant authorization precedence is `Direct Deny > Direct Grant > Role Grant > Default Deny`. SYSTEM Roles are server-owned immutable; custom Roles are bounded/versioned `ACTIVE -> ARCHIVED`; v1 has no role inheritance, wildcard assignment, resource-condition policy, or TTL-based override.
- Protected resource services make one authoritative online `CheckPermission(tenant_id,membership_id,permission_key)` call after safe local reject-only token/context/syntax checks. Successful RPC completion means ALLOW; authoritative denial is denial status, not `allowed=false`.
- There is no permission-result cache, Bloom-filter authorization, Kafka invalidation, retry, stale allow fallback, or BFF duplicate routine resource check.
- Browser tenant administration is BFF facade. Authorization locally verifies Identity JWT with exact audience `authorization-service`, trusts no role/permission snapshot, evaluates management permission in-process, and prevents actors from introducing authority they do not possess.
- Administration is bounded: 100 custom Roles/tenant, 200 permissions/custom Role, 20 Roles/Membership, 100 direct overrides/Membership, max 100 semantic mutations/bulk, pagination 50 default/200 maximum. `AUTH_ADMIN_WRITE` is charged by actual semantic mutation delta before DB transaction and is not refunded after later DB failure; local mutation is all-or-none.
- Every management/lifecycle/platform write has UUIDv4 idempotency + purpose/version HMAC intent fingerprinting; equal replay returns original result, conflict is stable, and security idempotency evidence remains >=35d. Required management/platform audit is bounded/PII-safe and retained >=365d.
- Identity owner-safe Membership removal and local `tenant_owner` changes share one tenant-scoped atomic owner-safety serialization domain; no read-only count race, force-last-owner flag, or reservation auto-expiry can remove final effective owner.
- `platform_admin` is a global explicit SYSTEM capability profile, not tenant Role/wildcard. `CheckPlatformPermission` is Identity-only, 300ms/one attempt/no-cache/no-retry/no-fallback/fail-closed, and never bypasses tenant/resource/domain invariants. Platform-profile assignment/revocation is JIT-controlled/audited and absent from ordinary tenant APIs.
- Authorization uses jOOQ/JDBC without JPA, forced tenant RLS, transaction-local tenant context, bounded SQL/query-plan evidence, and no remote I/O inside DB transactions. Erasure removes subject-linked tenant/platform authority while preserving tenant-owned Role definitions.
- Production Authorization target: >=3 replicas, PDB/topology spread, >=99.95% rolling-30d availability, p95<=100ms, p99<=200ms, 300ms caller deadline, one attempt.
- Authorization uses bounded global/per-caller concurrency, <=25ms server queue wait, bounded PostgreSQL pool/queries, explicit overload shedding, and fail-closed caller breakers. Breaker recovery is de-correlated and uses serialized real `CheckPermission` probes; health endpoint cannot authorize breaker closure.
- Dependency criticality/fallback semantics, including Identity platform-permission and Web-BFF Authorization-management edges, are registered per operation/dependency edge in `dependency-criticality.yaml`.

## Semantic security quotas

- There is no quota microservice. Owning security service enforces its semantic quota atomically in service-owned ACL-isolated Redis.
- Quotas are fail-safe, use pseudonymous keys, explicit anti-lockout sequencing, and current trusted-application-time + Redis-time skew/TTL rules.
- Identity registration, Web BFF OIDC start/callback and Authorization administration have exact current numeric contracts in ADR-0024.
- Authorization administration quota cost is proportional to actual bounded semantic mutation count rather than one unit for arbitrarily large mutation request.

## Notification

- Identity durably hands off human-channel delivery to Notification through idempotent internal gRPC.
- Notification owns template versioning/rendering, provider adapters, retry/reconciliation, delivery evidence, and terminal callback results.
- PostgreSQL is authoritative for Notification templates and lifecycle time.
- Sensitive caller/Notification escrow uses independent local AES-256-GCM key rings sourced through OpenBao/External Secrets. Routine Notification acceptance/dispatch/retry does not call OpenBao Transit.
- Exact accepted recipient/template/content/deadline identity remains stable across retries/reconciliation; sensitive ciphertext has <=24h hard maximum and is erased earlier when possible.
- Provider I/O occurs only after durable local `DISPATCHING` commit. Unknown outcomes are reconciled and never authorize blind redispatch.
- Production Email uses Liara Transactional Email over authenticated SMTP + STARTTLS.
- Production Iran SMS uses IPPanel Edge Webservice mode with Notification-rendered exact text and bounded authenticated status polling. Provider Pattern rendering is not semantic authority.
- Local logging SMS is local-only and never staging/production fallback.

## PostgreSQL and data isolation

- Every persistent production microservice owns distinct PostgreSQL database, runtime/migration credentials, Flyway history, and dedicated CloudNativePG cluster.
- Critical clusters use current three-instance synchronous durability/failover baseline, independent backup credentials/encryption context, continuous WAL archive, daily base backup, and tested PITR/restore.
- Tenant-owned production tables use forced RLS. Runtime roles are `NOSUPERUSER NOBYPASSRLS`, are not table owners, and cannot cross service/database boundaries.
- Tenant database context comes only from validated authenticated context and is parameterized/transaction-local; session-scoped tenant state on pooled connections is prohibited, missing/malformed context fails closed, and cross-tenant pooled-connection reuse after commit/rollback is mandatory negative test.
- Flyway is only schema-change mechanism. Executed migrations are immutable; evolution uses expand -> migrate -> contract. Application rollback must remain compatible with expanded schema.
- Fleet operations use one reviewed GitOps baseline, one-cluster-at-a-time upgrade waves, monthly isolated restore evidence, and quarterly DR exercises.

## Kafka and contracts

- Kafka uses KRaft with three brokers + three dedicated controllers for approved production baseline.
- Critical topics use RF=3, minISR=2, `acks=all`, idempotent producers, and no unclean leader election.
- State change + integration-event publication uses Transactional Outbox; consumers assume at-least-once delivery and are idempotent with durable Inbox/dedup semantics where required.
- Critical publication/dedup evidence is retained for current 35-day recovery horizon.
- Kafka is rebuildable transport, not business source of truth. Cold DR recreates infrastructure/configuration and replays retained publication/state evidence.
- Protobuf is governed in Git with Buf compatibility; no runtime Schema Registry is deployed in v1.

## Kubernetes, edge, mesh, and secrets

- Production Kubernetes uses three dedicated stacked control-plane/etcd nodes and >=3 schedulable workers with redundant stable API endpoint and N+1 capacity for critical paths.
- Application workloads use immutable digests, non-root execution, `allowPrivilegeEscalation=false`, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem where compatible, resources, separate startup/readiness/liveness probes, dedicated ServiceAccounts, and deny-by-default network policy.
- Public path: upstream L3/L4 volumetric mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> dedicated Caddy/Coraza WAF -> Web BFF.
- Direct public/Traefik application routing to BFF that bypasses WAF is prohibited.
- Internal application workloads use Istio Ambient strict mTLS, ServiceAccount-derived identity, and least-privilege authorization. Waypoints exist only for explicit L7 need.
- OpenBao 2.6.1 is secret authority; External Secrets is normal Kubernetes materialization boundary. Routine application request paths use local validated material rather than OpenBao RPCs.
- GitOps desired state lives in this repository under reviewed environment roots and is reconciled by Argo CD.

## Supply chain, CI/CD, and production access

- Third-party CI actions/tools/artifacts are pinned/verified according to repository policy; workflow permissions are least privilege and privileged secrets are not exposed to untrusted PR execution.
- Privileged GitHub Actions event contexts such as `pull_request_target`/`workflow_run` do not execute unreviewed PR-controlled code/config with privileged credentials; any trusted follow-up promotion verifies repository/event/source SHA/artifact integrity/producer workflow before granting privilege.
- Release images are immutable, SBOM-indexed, signed with Cosign, carry provenance/source identity, and are verified by admission policy.
- Admission-policy authoring is restricted to controlled GitOps/CI identities; policy-engine external context/egress is bounded and SSRF-tested according to ADR-0017.
- Exact signed image digest validated in staging is promoted to production; production rebuild is prohibited.
- Vulnerability response continuously correlates deployed digests/SBOMs with approved advisory/threat-intelligence inputs, enforces expiring exceptions, and applies production remediation/escalation policy. No feed/scanner is treated as proof of zero unknown vulnerabilities.
- Human privileged production access uses Teleport JIT SSO/WebAuthn, short-lived elevation, approvals, least privilege, and audited/recorded sessions.

## Logging and observability

- Services emit structured JSON stdout and OpenTelemetry/Micrometer metrics/traces with bounded low-cardinality attributes.
- Logging is allow-list based. Secrets, credentials, tokens/cookies, session/pre-auth IDs, OTPs, sensitive payloads, SQL binds, complete metadata/headers, and unreviewed PII are prohibited from raw telemetry.
- Ordinary non-audit telemetry may use bounded buffering/drop; required security/audit evidence classified as authoritative state must durably persist/outbox and cannot silently disappear with exporter/backend failure.
- Source rules, pipeline redaction, synthetic canary tests, and runtime detection provide defense in depth.
- SLOs/error budgets use burn-rate policy rather than isolated percentile paging.

## Current primary capacity boundaries

1. Authorization + PostgreSQL request path;
2. Web BFF fan-out/session Redis/token-broker/crypto/downstream pool path;
3. per-service PostgreSQL HA fleet capacity/restore/upgrade overhead;
4. security Redis latency/failover;
5. password-hashing CPU/memory under attack/load;
6. WAF inspection on every public request;
7. Kafka disk/partition/consumer capacity when async flows grow;
8. Liara/IPPanel availability and IPPanel polling limits;
9. worker-node capacity and replica placement during node loss.

Detailed metrics/scale triggers live in `performance-and-bottlenecks.md`.

## Evidence status

Architecture and documentation are decisions, not executable proof. Production readiness requires actual source/build/workflows/manifests plus tests, scans, load/failover/restore/security evidence defined in `PRODUCTION-READINESS-CHECKLIST.md`. Where implementation artifacts do not yet exist, evidence remains **NOT VERIFIED**.
