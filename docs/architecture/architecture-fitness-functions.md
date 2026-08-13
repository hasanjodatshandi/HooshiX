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
| AFF-009 | Authorization current model | one-call/no-cache/no-retry/fail-closed/breaker tests | PR/release | block |
| AFF-010 | Workload identity and east-west authorization | Istio/ServiceAccount positive + negative policy tests | release | block |
| AFF-011 | Logging/PII safety | Semgrep + canary/redaction sink tests | PR/release/continuous | block/page per policy |
| AFF-012 | Supply-chain/admission integrity | dependency verification, SBOM, CVE correlation, Cosign/provenance/admission, admission-policy RBAC, policy-engine egress/SSRF negatives | PR/release/continuous | block/escalate |
| AFF-013 | Container/workload hardening | rendered manifest policy tests | PR/release | block |
| AFF-014 | SLO/capacity critical paths | load tests + SLI/burn/saturation evidence | release/continuous | release policy |
| AFF-015 | Backup/PITR/restore | queryable restore evidence | monthly/quarterly | production promotion freeze per policy |
| AFF-016 | Kafka durability/rebuildability | broker/topic policy + replay/rebuild exercise | release/scheduled | block/escalate |
| AFF-017 | Dependency-criticality registry | schema, duplicate/orphan, coverage and render checks | PR | block |
| AFF-018 | Frontend type/module boundaries | TypeScript/ESLint/import-boundary checks | PR | block |
| AFF-019 | Browser session/token isolation | source/browser/cache/security-header tests | PR/release | block |
| AFF-020 | Accessibility/RTL/browser critical journeys | accessibility + keyboard + RTL/LTR + Playwright evidence | PR/release | block when affected |
| AFF-021 | Documentation current-only integrity | link/dead-ADR/authority/index/version checks | PR | block |
| AFF-022 | PR-first repository workflow | branch protection/required checks + PR review evidence | PR | block |

## Rules

- `Not applicable` is valid only when the corresponding technology/surface genuinely does not exist.
- A configured-but-failing check is never `Not applicable`.
- Every blocking fitness function must have a concrete CI/release job before implementation compliance is claimed.
- New architectural constraints SHOULD add or extend a fitness function when reliable automation is possible.
- Quality/security gates MUST NOT be weakened merely to make a change pass.
