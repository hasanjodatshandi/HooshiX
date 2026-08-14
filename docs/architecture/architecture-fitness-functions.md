# Architecture Fitness Functions

This catalog defines architecture properties that should be continuously verified. A row is not proof until its executable evidence exists and passes.

| ID | Property | Expected evidence | Frequency | Failure behavior |
| --- | --- | --- | --- | --- |
| AFF-001 | Domain/Application dependency purity | ArchUnit forbidden-import/dependency tests | PR | block |
| AFF-002 | Service/database ownership isolation | config/source policy checks + negative DB privilege tests; profile-aware physical placement | PR/release | block |
| AFF-003 | Package architecture and no cycles | ArchUnit package/dependency-cycle rules | PR | block |
| AFF-004 | No cross-service implementation/model sharing | dependency/ArchUnit checks | PR | block |
| AFF-005 | API/event compatibility | OpenAPI/Buf/schema compatibility checks | PR | block |
| AFF-006 | Transactional outbox/idempotent consumer semantics | integration duplicate/restart/outbox tests | PR/release | block |
| AFF-007 | Query boundedness and critical query plans | static policy + integration/plan evidence | PR | block |
| AFF-008 | Migration/RLS safety including pool-safe transaction-local tenant context | Flyway validation + forced-RLS/role negatives + cross-tenant pooled-connection reuse after commit/rollback + rolling compatibility | PR/release | block |
| AFF-009 | Authorization current model | permission-catalog lifecycle/non-reuse + exact `CheckPermission`/`CheckPlatformPermission` fail-closed contracts + tenant-management privilege-escalation/owner-safety/no-cache/no-retry tests | PR/release | block |
| AFF-010 | Workload identity and east-west authorization | Istio/ServiceAccount positive + negative policy tests | release | block |
| AFF-011 | Logging/PII safety | Semgrep + canary/redaction sink tests | PR/release/continuous | block/page per policy |
| AFF-012 | Supply-chain/admission integrity | dependency verification, SBOM, CVE correlation, Cosign/provenance/admission, admission-policy RBAC, policy-engine egress/SSRF negatives | PR/release/continuous | block/escalate |
| AFF-013 | Container/workload hardening | rendered manifest policy tests | PR/release | block |
| AFF-014 | SLO/capacity critical paths | load tests + SLI/burn/saturation evidence; selected-profile availability interpretation | release/continuous | release policy |
| AFF-015 | Backup/PITR/restore | queryable profile-aware restore evidence; single-server isolated shared-cluster PITR + service-specific transfer | monthly/quarterly | production promotion freeze per policy |
| AFF-016 | Kafka durability/rebuildability | selected-profile broker/topic policy + replay/rebuild exercise | release/scheduled | block/escalate |
| AFF-017 | Dependency-criticality registry | schema, duplicate/orphan, policy-ref, coverage and Markdown-render checks including active service/dependency edges | PR | block |
| AFF-018 | Frontend type/module boundaries | TypeScript/ESLint/import-boundary checks | PR | block |
| AFF-019 | Web BFF browser/session/token/reference isolation | `/api/v1` OpenAPI/error/request-bound tests + exact OIDC/pre-auth entropy/replay/redirect tests + server-owned audience brokerage/arbitrary-audience rejection + HMAC session/pre-auth locators + atomic rotation + refresh AES-GCM/key-staleness + CSRF/Origin/Fetch-Metadata/same-origin-CORS/CSP/cache + Reference Data safe-read tests | PR/release | block |
| AFF-020 | Accessibility/RTL/browser critical journeys | accessibility + keyboard + RTL/LTR + Playwright evidence | PR/release | block when affected |
| AFF-021 | Documentation current-only integrity | link/dead-ADR/authority/index/version/profile-consistency checks | PR | block |
| AFF-022 | PR-first repository workflow | branch protection/required checks + PR review evidence | PR | block |
| AFF-023 | Web BFF exact network/runtime boundary | rendered ServiceAccount/security-context + selected-profile replicas/HPA/PDB + public-edge ingress + deny-by-default egress allow-list + wrong-workload/arbitrary-Internet negatives | PR/release | block |
| AFF-024 | Web BFF revocation/erasure continuity | one-session-to-RefreshFamily binding, pseudonymous User->sessions index, logout-all/suspension/deleting/family-reuse/erasure cleanup and non-PII receipt tests | PR/release | block |
| AFF-025 | Semantic-quota current policy | exact Identity registration + BFF OIDC + Authorization admin cost values, atomic dual-clock/HMAC/no-TTL-reset/outage tests + profile-aware Redis persistence/failover behavior | PR/release | block |
| AFF-026 | Compromised Password self-contained SQLite reference-data boundary | SHA-256 20-bit-prefix contract + exact suffix reconstruction; immutable read-only/query-only SQLite; server-owned path/URI; no runtime write/DDL/ATTACH/extension loading; dataset compiler schema/integrity/cardinality/response bounds; no external provider/Internet lookup or full-dataset JVM cache; Identity-only workload; fail-closed corruption/unavailability; Xerial/native SBOM/advisory + representative load/rebuild evidence | PR/release | block |
| AFF-027 | Reference Data closed immutable standard-reference boundary | implementation trigger evidence; exactly Country/Currency/TimeZone/SupportedLocale and no generic registry; reviewed offline source provenance/integrity/license inputs; deterministic immutable bundle; no DB/Redis/Kafka/runtime source sync; typed bounded gRPC; Web-BFF-only initial workload; BFF safe read/cache behavior; Class-B/load/rebuild evidence once implemented | PR/release when implementation/release scope exists | block affected Reference Data feature |
| AFF-028 | Production profile topology consistency | render/static checks that `production-single-server` has one K3s node, one app replica, HPA/availability-PDB off, shared physical PostgreSQL with logical isolation, Redis AOF single instance, Kafka combined KRaft RF1/minISR1, one-replica fail-closed Kyverno, no false HA claims; HA profile keeps its redundant topology | PR/release | block |
| AFF-029 | Single-server complete-stack capacity | representative full-stack load/soak/reboot evidence: no OOM/sustained swap/MemoryPressure, >=30% CPU+memory headroom, applicable >=2x critical/security peak, safe WAL+AOF+Kafka+telemetry IO, safe MTU/conntrack/FD/ephemeral-port headroom, Ambient/Kyverno fit | release | block production approval |
| AFF-030 | Human privileged-access profile | single-server WireGuard management-only/public-SSH-denial + independent peers + OpenSSH FIDO2 user-presence/user-verification + no root/password/shared keys + JIT expiry/two-reviewer + OS/`sudo`/boundary/off-host audit; HA Teleport SSO/WebAuthn/JIT/session recording | release/scheduled | block/escalate |
| AFF-031 | OpenBao and MFA invariance across infrastructure profiles | diff/render/security tests proving ADR-0042/ADR-0043 do not remove/replace/bypass OpenBao and do not permit Email/SMS downgrade around required active TOTP | PR/release | block |
| AFF-032 | Public client-address authority | external-L4 PROXY-v2 source preservation + exact Traefik trusted CIDRs + insecure-mode denial + Caddy strict trusted-proxy parsing/internal-header overwrite + BFF one-IP parsing + forged-header/untrusted-PROXY/proxy-address/IPv4/IPv6/mapped-address negatives + raw-IP leak negatives | PR/release | block public quota-protected traffic |
| AFF-033 | Full cold disaster recovery | executable `production-cold-dr.md` exercise restoring management, K3s/Calico, OpenBao, PostgreSQL, Redis/Kafka, security controls, erasure/legal-hold state and traffic gate; measured ADR-0004 RPO/RTO | quarterly/release when materially affected | block production promotion/escalate |
| AFF-034 | Repository implementation/evidence status accuracy | tree/index checks that planned target presence is not described as implemented without actual files and executed evidence; `implementation-status.md` updated when material presence changes | PR | block misleading readiness claim |

## Rules

- `Not applicable` is valid only when the corresponding technology/surface genuinely does not exist.
- A configured-but-failing check is never `Not applicable`.
- ADR-0040 SQLite is a narrow immutable reference-data exception; AFF-002/AFF-008 PostgreSQL/Flyway evidence remains required for mutable relational service state.
- ADR-0041 Reference Data uses no database. AFF-027 becomes executable/release-blocking only after ADR-0041's implementation trigger and release scope exist.
- ADR-0042 permits physical PostgreSQL consolidation only in `production-single-server`; AFF-002 still requires distinct service DB/role/Flyway ownership and cross-service privilege denial.
- ADR-0043 defines client-address and management-network trust. Network/proxy failure is not permission to use caller forwarding headers or public SSH.
- RF=1/single Redis/one replica/non-HA in `production-single-server` are availability decisions only. They do not weaken AFF-006, AFF-009, AFF-010, AFF-012, AFF-015, AFF-025, AFF-032 or other correctness/security properties.
- OpenBao and MFA invariance are explicit fitness properties. Capacity pressure is not a valid reason to remove OpenBao, disable Kyverno/Ambient security or offer a weaker MFA downgrade.
- Every blocking fitness function must have a concrete CI/release job before implementation compliance is claimed.
- New architectural constraints SHOULD add or extend a fitness function when reliable automation is possible.
- Quality/security gates MUST NOT be weakened merely to make a change pass.
