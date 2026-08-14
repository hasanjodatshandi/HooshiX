# Architecture Fitness Functions

This catalog defines architecture properties that should be continuously verified. A row is not proof until its executable evidence exists and passes.

| ID | Property | Expected evidence | Frequency | Failure behavior |
| --- | --- | --- | --- | --- |
| AFF-001 | Domain/Application dependency purity | ArchUnit forbidden-import/dependency tests | PR | block |
| AFF-002 | Service/database ownership isolation | config/source policy checks + negative DB privilege tests | PR/release | block |
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
| AFF-014 | SLO/capacity critical paths | load tests + SLI/burn/saturation evidence | release/continuous | release policy |
| AFF-015 | Backup/PITR/restore | queryable restore evidence | monthly/quarterly | production promotion freeze per policy |
| AFF-016 | Kafka durability/rebuildability | broker/topic policy + replay/rebuild exercise | release/scheduled | block/escalate |
| AFF-017 | Dependency-criticality registry | schema, duplicate/orphan, policy-ref, coverage and Markdown-render checks including Compromised Password and BFF session/quota/Google/evidence/audience-token/Authorization-management/Reference-Data/resource-dispatch edges | PR | block |
| AFF-018 | Frontend type/module boundaries | TypeScript/ESLint/import-boundary checks | PR | block |
| AFF-019 | Web BFF browser/session/token/reference isolation | `/api/v1` OpenAPI/error/request-bound tests + exact OIDC/pre-auth entropy/replay/redirect tests + server-owned audience brokerage/arbitrary-audience rejection + HMAC session/pre-auth locators + atomic no-grace session rotation + refresh AES-GCM/key-staleness + CSRF/Origin/Fetch-Metadata/same-origin-CORS/CSP/private-no-store + Reference Data anonymous-safe-read/explicit-locale/ETag-public-cache/no-stale-fallback/browser-storage tests | PR/release | block |
| AFF-020 | Accessibility/RTL/browser critical journeys | accessibility + keyboard + RTL/LTR + Playwright evidence | PR/release | block when affected |
| AFF-021 | Documentation current-only integrity | link/dead-ADR/authority/index/version checks | PR | block |
| AFF-022 | PR-first repository workflow | branch protection/required checks + PR review evidence | PR | block |
| AFF-023 | Web BFF exact network/runtime boundary | rendered ServiceAccount/replica/PDB/HPA/security-context + public-edge ingress + deny-by-default egress allow-list including Reference Data when active + wrong-workload/arbitrary-Internet negatives | PR/release | block |
| AFF-024 | Web BFF revocation/erasure continuity | one-session-to-RefreshFamily binding, pseudonymous User->sessions index, logout-all/suspension/deleting/family-reuse/erasure cleanup and non-PII receipt tests | PR/release | block |
| AFF-025 | Semantic-quota current policy | exact Identity registration + BFF OIDC + Authorization admin cost values, atomic dual-clock/HMAC/no-TTL-reset/outage tests | PR/release | block |
| AFF-026 | Compromised Password self-contained SQLite reference-data boundary | SHA-256 20-bit-prefix contract + exact suffix reconstruction; immutable read-only/query-only SQLite; server-owned path/URI; no runtime write/DDL/ATTACH/extension loading; dataset compiler schema/integrity/cardinality/response bounds; no external provider/Internet lookup or full-dataset JVM cache; Identity-only workload; fail-closed corruption/unavailability; Xerial/native SBOM/advisory + representative multi-million-row load/rebuild evidence | PR/release | block |
| AFF-027 | Reference Data closed immutable standard-reference boundary | implementation trigger evidence; exactly Country/Currency/TimeZone/SupportedLocale and no generic registry; reviewed offline ISO/IANA/stable-CLDR provenance/integrity/license inputs; deterministic lifecycle-safe immutable bundle/manifest/digest; no DB/Redis/Kafka/runtime standards-source sync; typed bounded gRPC/page/128KiB; Web-BFF-only initial workload; anonymous same-origin/WAF-protected BFF GET/HEAD with explicit fa/en + ETag/public one-hour cache and no stale/fabricated server fallback; Class-B/load/rebuild evidence once implemented | PR/release when implementation/release scope exists | block affected Reference Data feature |

## Rules

- `Not applicable` is valid only when the corresponding technology/surface genuinely does not exist.
- A configured-but-failing check is never `Not applicable`.
- ADR-0040 SQLite is a narrow immutable reference-data exception; AFF-002/AFF-008 PostgreSQL/Flyway evidence remains required for mutable relational service state and is not weakened by AFF-026.
- ADR-0041 Reference Data uses no database and therefore creates no second persistence exception. AFF-027 becomes executable/release-blocking only after ADR-0041's implementation trigger and corresponding release scope exist; before then architecture remains decided while implementation evidence is intentionally not applicable/not verified.
- Every blocking fitness function must have a concrete CI/release job before implementation compliance is claimed.
- New architectural constraints SHOULD add or extend a fitness function when reliable automation is possible.
- Quality/security gates MUST NOT be weakened merely to make a change pass.
