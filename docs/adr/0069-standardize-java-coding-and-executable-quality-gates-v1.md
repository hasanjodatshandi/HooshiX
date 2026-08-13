# ADR-0069: Standardize Java Coding Rules and Executable Quality Gates v1

## Status

Accepted

## Date

2026-08-11

## Context

The platform already defines DDD/Hexagonal boundaries, ADR-0007 package rules,
constructor injection, test categories, PII-safe logging, supply-chain gates,
and Gradle-per-service ownership. However, several useful implementation rules
were distributed across source notes or remained prose-only, and the repository
does not yet contain executable service builds/source/CI that prove compliance.

A production-oriented coding standard must distinguish:

1. the rule itself;
2. the automated mechanism that can enforce it;
3. the evidence showing the mechanism actually ran against the code being
   promoted.

The decision must not revive superseded architecture such as ADR-0005 cached
Authorization, runtime Schema Registry, global `adapters/in|out` package layout,
or shared physical PostgreSQL clusters in production.

## Decision

### Canonical coding standard

`docs/engineering/coding-standards.md` is the canonical implementation-level
Java coding standard, subordinate to accepted ADR precedence.

It consolidates and makes explicit the useful current rules for:

- dependency direction and feature-first packaging;
- file/type/naming discipline;
- constructor DI and no service locator/field injection;
- Domain/Application purity;
- REST RFC 9457 and bounded gRPC error mapping;
- persistence/transaction/query/JPA behavior;
- Virtual Thread/downstream capacity safety;
- configuration/dependency hygiene;
- PII/log-injection-safe structured logging;
- comments/JavaDoc;
- testability and prohibited coding practices.

### Executable quality baseline

`docs/engineering/build-and-ci-quality-enforcement.md` defines the required
implementation/evidence baseline.

Each independently deployable Java service keeps its own Gradle Wrapper and
`build.gradle.kts` under ADR-0001 and must be covered by:

- Spotless formatting checks;
- SpotBugs production-code analysis;
- ArchUnit architecture tests;
- repository-owned Semgrep rules;
- Gradle dependency verification/locking;
- applicable unit/integration/contract/schema tests;
- GitHub Actions required checks and immutable-artifact promotion.

Exact plugin/tool patch versions are pinned in build/CI tool metadata and the
Technology Baseline/tool locks. Architecture documents do not guess a patch.

### Enforcement is evidence-based

Documentation does not imply implementation. The quality baseline has this
state until code/CI exists and passes:

```text
Architecture: DECIDED
Implementation: REQUIRED
Evidence: NOT VERIFIED
```

A service can claim compliance only from actual source/build/static-analysis/
test/CI evidence for the reviewed commit/artifact.

### High-signal enforcement only

Automated enforcement is used where it is reliable:

- ArchUnit for package/layer/dependency/cycle rules;
- Spotless for deterministic formatting;
- SpotBugs for Java bytecode bug patterns;
- Semgrep for high-signal source/security/logging/framework misuse;
- Gradle/contract tools for dependency/test/schema behavior;
- CI/release policy for required checks and artifact promotion.

Rules that are not reliably machine-provable, such as "a controller contains no
business logic" or "a file is conceptually too large", remain review/design
rules rather than brittle pseudo-enforcement.

### No gate weakening to obtain green builds

Broad exclusions, disabled tests, `ignoreFailures`, blanket analyzer
suppressions, or removal of required checks merely to pass CI are prohibited.
Suppressions are narrow, justified, reviewed, and removed when no longer valid.

## Consequences

### Positive

- Coding expectations are centralized and easier for humans/agents to apply.
- Machine-checkable rules become executable gates instead of documentation-only
  conventions.
- Formatting, architecture, bug-pattern, logging/security, dependency, test,
  and CI evidence have explicit owners.
- The developer inner loop remains smaller than heavy platform/DR validation.

### Negative

- Each Java service carries more build/test/static-analysis setup.
- Strict gates initially expose existing violations and may require cleanup.
- Custom Semgrep/ArchUnit rules require maintenance as packages/frameworks
  evolve.

## Verification Requirements

For the first executable service and every later Java service:

- clean-checkout Gradle Wrapper/build works on Java 25;
- `spotlessCheck`, `spotbugsMain`, `architectureTest`, Semgrep blocking rules,
  dependency verification, and applicable tests pass;
- representative positive/negative fixtures prove custom analyzer rules;
- GitHub Actions required checks are enabled and cannot be skipped by normal PR
  merge;
- the same signed immutable image digest progresses from staging to production;
- no broad suppression/disabled mandatory gate is present;
- current source is inspected to prove code compliance rather than inferring it
  from documentation.

## Relationship to Earlier Decisions

This ADR extends ADR-0001 (independent service builds), ADR-0007 (package
structure), ADR-0046/ADR-0065/ADR-0068 (supply-chain/vulnerability gates), and
ADR-0061 (PII-safe logging enforcement). It does not supersede their semantic
requirements.

It does not change Kafka, OpenBao, Authorization runtime, PostgreSQL isolation,
or other service/runtime architecture.

## Rollback Considerations

A later decision may replace an individual analysis/CI tool only if equivalent
or stronger enforcement/evidence is preserved. Removing a gate without a
replacement must not silently weaken accepted coding, security, or release
requirements.
