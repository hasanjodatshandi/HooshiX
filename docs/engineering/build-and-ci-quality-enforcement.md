# Build and CI Quality Enforcement

This document defines the executable quality-gate baseline for independently deployable Java services. It turns architecture/coding rules into required repository artifacts and CI evidence. Repository change delivery itself is governed by `repository-change-workflow.md`: normal changes use a task branch and Draft PR, all substantive work remains in that PR, the complete diff is reviewed against current `main`, and merge occurs only after applicable checks and blockers are resolved.

Architecture/policy status:

```text
Architecture: DECIDED
Implementation: REQUIRED
Evidence: NOT VERIFIED until the corresponding files/tasks exist and execute successfully in the repository
```

Because application services are not yet implemented in the current repository, this document MUST NOT be cited as proof that Java code already complies. Compliance becomes verified only from actual source, build, static-analysis, test, and CI evidence.

## 1. Required repository artifacts

Every independently deployable Java service contains, at minimum:

```text
services/<service>/
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
├── gradle/verification-metadata.xml
├── Dockerfile
├── contracts/
├── src/
│   ├── main/
│   ├── test/
│   ├── integrationTest/
│   ├── contractTest/
│   └── architectureTest/
└── deploy/
```

Repository governance also owns reusable quality/CI material, for example:

```text
.github/workflows/
├── java-service-ci.yml          # reusable or path-parameterized PR/service workflow
└── release.yml                  # system promotion/release workflow when implemented
quality/
├── semgrep/
│   ├── architecture.yml
│   ├── java-security.yml
│   └── logging-pii.yml
└── spotbugs/
    └── exclude.xml              # narrow reviewed exclusions only
```

Equivalent paths are allowed only when repository convention is explicit and all services use it consistently.

## 2. `build.gradle.kts` requirements

Each service build is independent under ADR-0001 and uses the Gradle Wrapper/Kotlin DSL.

The service build MUST configure or expose equivalent behavior for:

- Java 25 toolchain/release;
- UTF-8 source/test compilation;
- JUnit Platform;
- Spring Boot dependency alignment according to Technology Baseline;
- dependency locking/verification metadata;
- `test`, `integrationTest`, `contractTest`, and `architectureTest` source sets/tasks where applicable;
- Spotless formatting/check tasks;
- SpotBugs analysis for production Java code;
- schema/contract compatibility tasks used by that service;
- deterministic/reproducible archive output where Gradle produces distributable archives;
- a repository-defined aggregate quality task (for example `qualityCheck`) that makes mandatory code-quality gates discoverable without forcing expensive platform/DR tests into the local inner loop.

Dynamic dependency versions and production SNAPSHOTs are prohibited. Generated sources are kept in explicit generated directories and may receive narrowly scoped formatter/static-analysis exclusions only when regeneration—not manual editing—is authoritative.

### Reference Gradle shape

The following is illustrative structure, not a copy-paste version pin. Exact plugin aliases/versions come from the service build/tool locks:

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The real build also wires repository-approved Spotless, SpotBugs,
// architectureTest, dependency verification, and applicable contract/schema tasks.
```

The build MUST remain executable from a clean checkout through the service Wrapper. Do not copy unpinned plugin versions from documentation.

### Expected task semantics

The exact task names are repository-defined, but equivalent mandatory behavior is required:

```text
spotlessCheck          formatting is clean; never mutates CI workspace
spotlessApply          developer-only formatting fix command
spotbugsMain           production-code bug-pattern analysis
architectureTest       ArchUnit architectural rules
integrationTest        real adapter/infrastructure behavior
contractTest           gRPC/REST/event compatibility behavior
schemaCompatibilityCheck
qualityCheck           fast/medium mandatory code-quality aggregation
```

`ignoreFailures=true` or equivalent blanket suppression is prohibited for mandatory production gates.

## 3. Spotless policy

Spotless is the standard formatting gate for Java/Gradle-related source where supported.

Rules:

- one approved formatter/configuration is pinned in build/plugin metadata; floating formatter versions are prohibited;
- CI runs `spotlessCheck`, never `spotlessApply`;
- developers may run `spotlessApply` locally;
- formatting applies consistently to Java and repository-owned Gradle Kotlin scripts and may cover other text formats where stable;
- generated/vendor code is excluded narrowly by path;
- formatting failures are fixed in source, not suppressed through broad exclusions;
- formatting is not used to rewrite unrelated files during a narrow task.

The exact Spotless plugin/formatter patch is pinned in repository build metadata/lock governance rather than guessed in architecture prose.

## 4. SpotBugs policy

SpotBugs is the baseline Java bytecode bug-pattern analyzer for production code.

Rules:

- `spotbugsMain` (or equivalent) is a required PR gate for Java services;
- analysis uses a reviewed strict configuration and fails the gate on findings above the repository-approved threshold;
- generated code is excluded only when it is reproducible and not manually maintained;
- suppression is narrow: specific bug pattern + specific scope + written justification;
- security/correctness findings are not globally excluded to make CI green;
- suppressions that carry ongoing risk require owner/review and are removed when no longer needed;
- reports are retained as CI artifacts/SARIF or equivalent so developers can locate findings.

SpotBugs complements, and does not replace, compiler warnings, tests, SAST/Semgrep, dependency scanning, or architecture tests.

## 5. ArchUnit policy

Every Java service has `architectureTest` source containing ArchUnit tests for applicable architectural invariants.

Mandatory baseline rules include:

- Domain has no dependency on Spring/JPA/Hibernate/jOOQ/Kafka/Redis/gRPC/Protobuf/Infrastructure/Interfaces;
- Application has no dependency on Infrastructure/Interfaces/concrete adapters;
- persistence entities and Spring Data repositories remain under Infrastructure persistence packages;
- Domain/Application do not access `ApplicationContext`, `BeanFactory`, service-locator patterns, or concrete adapters;
- field injection in application-owned code is prohibited;
- package dependency cycles are absent;
- prohibited dumping-ground package names are absent;
- Java package naming rules are respected;
- interfaces/controllers/listeners remain in inbound interface packages and dependency direction stays inward;
- service-specific forbidden dependencies are added when a bounded-context ADR requires them.

ArchUnit does not reliably prove that a controller has "no business logic" or that every class is small. Those remain code-review/design rules and may be supplemented with high-signal Semgrep checks; do not create brittle fake enforcement merely to claim coverage.

A blanket ArchUnit prohibition on `synchronized` is prohibited on Java 25; Virtual Thread safety is tested with JFR/load evidence as defined elsewhere.

## 6. Semgrep policy

Repository-owned Semgrep rules enforce high-signal source patterns that are difficult or inappropriate for ArchUnit/SpotBugs.

The baseline custom rule set covers, where technically reliable:

- field injection / disallowed dependency lookup patterns;
- `ApplicationContext`/`BeanFactory` lookup from Domain/Application;
- raw `Authorization`, cookie, token, password, OTP, secret, connection-string, request/response-body, SQL-bind, or complete metadata logging patterns;
- unsafe string-concatenated logging of request/domain/payload objects;
- direct body/bind/metadata debug logging in production configuration;
- `Thread.sleep` in application/test synchronization paths, with narrow infrastructure/tooling exceptions when justified;
- known prohibited production APIs/configuration patterns such as backend WebFlux/Reactor introduction without approved ADR;
- accidental secret literals and unsafe security configuration patterns that are precise enough to avoid noisy generic scanning.

Rules have positive and negative fixtures. A new custom rule is not enabled as a blocking gate until its false-positive behavior is reviewed against representative source.

Suppressions:

- use the narrowest inline/path/rule suppression supported;
- include rationale where non-obvious;
- never suppress a whole package/repository merely because one match is inconvenient;
- sensitive/logging-policy suppressions receive security review.

Semgrep output may be uploaded as SARIF, but CI logs MUST NOT echo raw secrets/PII discovered by test fixtures.

## 7. GitHub Actions CI baseline

GitHub Actions is the repository CI orchestrator for this project unless a later ADR changes the CI platform. Workflows run on the PR head revision established by `repository-change-workflow.md`; they do not substitute direct changes to `main` for review.

### Security and reproducibility

- third-party actions are pinned to immutable commit SHAs; floating `@main`/unbounded tags are prohibited for production-required workflows;
- workflow/job permissions use least privilege (`contents: read` by default); write permissions are granted only to the job that needs them;
- long-lived cloud/platform credentials are avoided; OIDC/short-lived identity is preferred where supported;
- secrets are never printed and fork/untrusted PRs do not receive privileged secrets;
- PR checks build/test the source revision under review;
- production promotion uses the exact previously built/signed image digest; production rebuild is prohibited;
- caches use keys that do not allow untrusted artifacts to overwrite privileged release state;
- required workflow/config files themselves are code-reviewed and protected by branch rules/CODEOWNERS where implemented.

### Pull-request/service quality workflow

Independent safe jobs SHOULD run in parallel. A typical dependency graph is:

```text
checkout / tool bootstrap
├── format: Spotless
├── compile + unit
├── ArchUnit
├── SpotBugs
├── Semgrep / SAST / secret scan
├── dependency verification + vulnerability/license checks
├── contract/schema compatibility
└── focused integration tests
        ↓
required quality aggregation
        ↓
Helm/Kubernetes validation (when affected)
        ↓
container build
        ↓
SBOM + vulnerability scan + signature/provenance
```

No downstream mandatory stage proceeds when a required predecessor fails.

### Release workflow

```text
signed immutable image digest
-> deploy staging
-> backend smoke
-> critical BDD/API acceptance
-> critical Playwright
-> production-readiness/evidence gates
-> promote same digest to production
-> production smoke
```

Smoke failure stops the rollout and uses the deployment rollback policy only when rollback is safe for the corresponding database/schema state.

### Required-check governance

Protected branches require the repository-defined mandatory checks. Removing/weakening a required check, broadening a suppression, or changing a quality threshold is a governance/security change and must be reviewed like code—not used as a shortcut to merge.

## 8. Source-code compliance evidence

"Coding standards documented" is not equivalent to "code compliant".

For each service, compliance evidence includes at least:

- service `build.gradle.kts`/Wrapper/verification metadata present and reviewed;
- `spotlessCheck` passes;
- `spotbugsMain` passes;
- Semgrep blocking policy passes;
- `architectureTest` passes;
- unit/integration/contract/schema checks required by the change pass;
- no required check is disabled/suppressed without approved narrow rationale;
- the actual GitHub Actions required-check set passes on the commit/digest being promoted.

Until service source code and CI workflows exist, this evidence status is **NOT VERIFIED**.

## 9. Definition of Done for engineering enforcement

A new Java service is not implementation-complete until:

- its independent Gradle Wrapper/build is executable from a clean checkout;
- Java 25/toolchain/dependency verification are enforced;
- Spotless, SpotBugs, ArchUnit, and repository Semgrep gates cover the service;
- applicable test source sets/tasks exist and run;
- CI runs the required gates and branch protection treats them as required;
- container build is reproducible enough to promote one immutable digest through staging/production;
- security/supply-chain output is produced without leaking sensitive test fixtures;
- no broad suppression or disabled mandatory task is used to claim success.
