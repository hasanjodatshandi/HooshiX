# Build and CI Quality Enforcement — Current Standard

This document defines executable quality gates for independently deployable Java services. `repository-change-workflow.md` governs PR-first delivery. Documentation alone never proves source/runtime compliance.

```text
Architecture/policy: DECIDED
Implementation: REQUIRED
Evidence: NOT VERIFIED until real files/tasks/workflows execute successfully
```

## 1. Required service/repository artifacts

Each Java service owns an independent build/release boundary with, at minimum when implemented:

```text
services/<service>/
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── gradle/verification-metadata.xml
├── Dockerfile
├── contracts/
├── src/{main,test,integrationTest,contractTest,architectureTest}/
└── deploy/
```

Repository governance owns reusable workflow/static-policy material such as GitHub Actions, Semgrep rules, SpotBugs configuration, documentation checks, and deployment policy checks. Equivalent paths are allowed only under one explicit repository convention.

## 2. Gradle/build requirements

Every service uses its own Gradle Wrapper/Kotlin DSL and exposes equivalent behavior for:

- Java 25 toolchain/release and UTF-8 compilation;
- JUnit Platform;
- Spring Boot dependency alignment from the Technology Baseline;
- dependency locking/verification metadata;
- applicable `test`, `integrationTest`, `contractTest`, `architectureTest` tasks;
- Spotless formatting/check;
- SpotBugs production analysis;
- schema/contract compatibility;
- deterministic/reproducible archive/container inputs sufficient to tie output to exact reviewed source;
- a repository-defined aggregate quality task if useful.

Dynamic versions/unbounded ranges and unapproved production SNAPSHOTs are prohibited. Generated source lives in explicit generated paths with only narrow regeneration-authoritative exclusions. Mandatory gates never use `ignoreFailures=true` or blanket suppression.

Expected semantics, names repository-defined:

```text
spotlessCheck          verify formatting; never mutate CI workspace
spotlessApply          developer-only formatting action
spotbugsMain           production bytecode bug analysis
architectureTest       ArchUnit rules
integrationTest        real adapter/infrastructure behavior
contractTest           gRPC/REST/event compatibility
schemaCompatibilityCheck
qualityCheck           mandatory fast/medium aggregate where adopted
```

A clean checkout MUST be buildable through the service Wrapper; architecture prose does not invent plugin versions.

## 3. Spotless

- one pinned approved formatter/configuration;
- CI runs `spotlessCheck`, not `spotlessApply`;
- formatting covers repository-owned Java/Gradle scripts consistently where supported;
- generated/vendor exclusion is narrow by path;
- formatting failures are fixed in source, never hidden by broad exclusions.

## 4. SpotBugs

- `spotbugsMain` or equivalent is blocking for Java production code;
- strict reviewed threshold;
- generated-code exclusion only when reproducible/non-maintained;
- suppression is specific bug pattern + specific scope + rationale;
- security/correctness patterns are not globally disabled;
- results retained as CI artifact/SARIF or equivalent.

SpotBugs complements compiler warnings, tests, SAST/Semgrep, dependency scanning, and ArchUnit.

## 5. ArchUnit

Every Java service enforces applicable architecture invariants:

- Domain has no Spring/JPA/Hibernate/jOOQ/Kafka/Redis/gRPC/Protobuf/Infrastructure/Interfaces dependencies;
- Application has no Infrastructure/Interfaces/concrete-adapter dependency;
- JPA/Spring Data/generated persistence types remain Infrastructure-only;
- Domain/Application cannot use `ApplicationContext`, `BeanFactory`, service locator, runtime bean lookup, or concrete adapter construction;
- field injection prohibited;
- package segment/feature-first/nature-separated rules and forbidden dumping-ground names enforced where reliable;
- package/dependency cycles absent;
- inbound interfaces remain boundary adapters and dependency direction stays inward;
- service-specific forbidden dependencies added when current architecture requires them.

ArchUnit does not pretend to prove semantic design qualities such as “controller has no business logic” or “class is small” when no reliable rule exists. Java 25 does not receive a blanket `synchronized` ban; JFR/load evidence covers remaining contention/native/FFM risks.

## 6. Semgrep/static source policy

Repository Semgrep rules target high-signal patterns such as:

- field injection/runtime dependency lookup;
- raw credential/token/cookie/secret/body/SQL-bind/complete-metadata logging;
- unsafe request/domain/payload string-concatenated logging;
- production debug body/bind/credential exposure;
- `Thread.sleep` coordination/test synchronization outside narrow justified tooling cases;
- unapproved WebFlux/Reactor backend introduction;
- precise secret/security misconfiguration patterns.

Rules have positive/negative fixtures and are not blocking until false-positive behavior is reviewed. Suppression is narrow, reasoned, and security-reviewed for sensitive/logging rules. CI output never echoes raw secret/PII fixtures.

## 7. GitHub Actions baseline

### Workflow security

- third-party actions pinned to immutable commit SHAs; floating `@main`/unbounded tags prohibited for required production workflows;
- `contents: read`/least privilege by default; writes only for the job that requires them;
- prefer OIDC/short-lived cloud/platform identity to long-lived credentials;
- secrets never printed and privileged secrets are unavailable to untrusted/fork PR execution;
- PR checks execute against the reviewed head revision;
- cache keys/trust boundaries prevent untrusted artifacts from poisoning privileged release state;
- workflow/quality-policy files are themselves reviewed/protected.

### PR quality graph

Independent safe jobs SHOULD run in parallel:

```text
format + compile/unit + ArchUnit + SpotBugs + Semgrep/SAST
+ dependency verification/vulnerability/license
+ contract/schema compatibility
+ focused integration
        ↓
required quality aggregate
        ↓
affected Helm/Kubernetes validation
        ↓
container build
        ↓
final-image SBOM/vulnerability + signature/provenance
```

Required predecessor failure stops dependent stages.

### Release graph

```text
signed immutable digest
-> staging
-> backend smoke
-> critical BDD/API acceptance
-> critical Playwright
-> production-readiness evidence
-> promote same digest
-> production-safe smoke
```

Rebuild between staging and production is prohibited. Smoke failure stops rollout and uses rollback only when schema/data compatibility is safe.

### Required-check governance

Protected `main` requires the repository-defined mandatory checks. Removing/weakening a check, lowering a threshold, or broadening suppression is a governance/security change, not a shortcut to merge.

## 8. Supply-chain/dependency integrity

Service dependencies/plugins/tools require purpose/owner/compatibility/security/license review as applicable plus dependency verification. Release artifacts carry exact source Git SHA, immutable digest, signed CycloneDX SBOM, provenance, and organization signature. Final-image vulnerability policy follows ADR-0065/0068 and admission policy follows current security architecture.

## 9. Evidence and Definition of Done

For each Java service, implementation compliance requires actual evidence that:

- Wrapper/build/verification metadata exist and work from clean checkout;
- Java 25/toolchain/dependency verification are enforced;
- Spotless/SpotBugs/ArchUnit/Semgrep pass;
- applicable unit/integration/contract/schema/security tests pass;
- no required check/suppression is weakened without approved narrow rationale;
- GitHub required-check set passes on the reviewed commit;
- container output can be promoted as the same immutable signed digest staging -> production;
- security/supply-chain output does not leak sensitive fixtures.

Until real service source/build/workflows exist and execute, status remains **NOT VERIFIED**.