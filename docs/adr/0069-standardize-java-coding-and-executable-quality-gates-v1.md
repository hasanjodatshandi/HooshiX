# ADR-0069: Java Coding Rules and Executable Quality Gates v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

### Canonical coding standard

`docs/engineering/coding-standards.md` is the canonical implementation-level Java standard. It defines current rules for:

- DDD/Hexagonal inward dependency direction;
- feature-first/nature-separated packages and package-segment naming;
- file/type/naming/responsibility discipline;
- constructor DI and no service locator/field injection/cycle hiding;
- Domain/Application framework purity;
- REST RFC 9457 and bounded gRPC error mapping;
- persistence/transaction/query/JPA behavior;
- Virtual Thread/downstream-capacity safety;
- dependency/configuration hygiene;
- PII/log-injection-safe structured telemetry;
- comments/JavaDoc and testability;
- hardened container/Kubernetes runtime settings;
- Helm/GitOps migration discipline;
- immutable same-digest artifact promotion tied to reviewed Git source identity.

Current package/coding rules live in that canonical document; deleted predecessor ADRs are not required to interpret them.

### Independent service build ownership

Every independently deployable Java service owns its own Gradle Wrapper, `settings.gradle.kts`, `build.gradle.kts`, dependency verification/locks, source/test sets, contracts, container build, and deployment package. The root build is repository governance only and does not collapse independent service release ownership.

### Executable quality baseline

`docs/engineering/build-and-ci-quality-enforcement.md` defines executable implementation/evidence requirements.

Each Java service is covered by applicable:

- Spotless formatting checks;
- SpotBugs production-code analysis;
- ArchUnit architecture tests;
- repository-owned Semgrep/source security/logging rules;
- Gradle dependency verification/locking;
- unit/integration/contract/schema compatibility tasks;
- container/Kubernetes/Helm policy validation where affected;
- GitHub Actions required checks;
- immutable artifact/SBOM/signature/provenance release gates.

Exact tool/plugin patch versions are pinned in build/CI metadata and technology/tool locks rather than guessed in architecture prose.

### Evidence-based compliance

Documentation is not implementation proof:

```text
Architecture: DECIDED
Implementation: REQUIRED
Evidence: NOT VERIFIED until actual source/build/checks exist and pass
```

A service claims compliance only from current source/build/static-analysis/test/CI/release evidence for the reviewed commit and promoted artifact digest.

### High-signal enforcement only

Use automation where reliable:

- ArchUnit — package/layer/dependency/cycle/forbidden package rules;
- Spotless — deterministic formatting;
- SpotBugs — Java bytecode bug patterns;
- Semgrep — high-signal source/security/logging/framework misuse;
- Gradle/contract tools — dependency/test/schema/contract behavior;
- Kubernetes/Helm/policy tools — workload security/deployment invariants;
- release pipeline — required checks, SBOM/signature/provenance, same-digest promotion.

Conceptual design rules such as “controller contains no business logic” remain explicit review responsibilities unless a precise low-noise rule can enforce a specific violation pattern.

### Gate integrity

Broad exclusions, disabled tests, `ignoreFailures`, blanket analyzer suppressions, floating production dependencies/actions, or removal/weakening of required checks merely to obtain green CI are prohibited. Suppressions/exceptions are narrow, justified, owned, reviewed, and expired/removed when no longer valid.

Third-party CI actions are pinned to immutable commit SHAs; workflow permissions are least privilege; privileged secrets are unavailable to untrusted PR execution. Staging and production promote the same signed immutable artifact digest; production rebuild is prohibited.

## Verification requirements

For every executable Java service:

- clean-checkout service Gradle Wrapper/build works with Java 25;
- dependency verification/locks pass;
- `spotlessCheck`, `spotbugsMain`, `architectureTest`, blocking Semgrep, and applicable unit/integration/contract/schema checks pass;
- positive/negative fixtures prove custom analyzer/policy rules;
- container/manifest security policy passes where applicable;
- GitHub Actions required checks cannot be skipped by normal PR merge;
- final image has current required SBOM/signature/provenance evidence;
- staging and production use the same immutable digest;
- no broad suppression/disabled mandatory gate is present;
- actual current source is inspected rather than inferring compliance from documentation.

## Relationship to current decisions

This decision operates together with current supply-chain/vulnerability controls (ADR-0046/ADR-0065/ADR-0068), PII-safe logging enforcement (ADR-0061), runtime/deployment architecture, and the repository PR-first/current-only governance documents. It does not change Kafka, Authorization, PostgreSQL, OpenBao, or service-domain semantics.

## Rollback considerations

A tool may be replaced only when equivalent or stronger enforcement/evidence is preserved. Rollback MUST NOT silently weaken coding, security, workload-hardening, artifact-integrity, or release gates.
