# Developer Workflow and Fast Feedback

This document defines development ergonomics without weakening production architecture.

## 1. Inner-loop principle

The normal edit/test loop should exercise the smallest trustworthy scope. Developers and agents should not need a complete Kubernetes/Istio/WAF/Argo CD environment to test pure Domain/Application behavior.

Preferred order:

1. Domain/Application unit tests without Spring;
2. focused adapter tests;
3. Testcontainers integration tests for real infrastructure behavior;
4. contract/architecture/static checks (`spotlessCheck`, ArchUnit, SpotBugs, Semgrep as applicable);
5. service-level runtime tests;
6. staging/system checks for production-only infrastructure.

## 2. Local-only substitutions

Local-only adapters/fakes are allowed only where an accepted ADR or current architecture explicitly permits them. They must be impossible to activate in `staging` or `production`.

Use profile expressions equivalent to `local & !staging & !production` for local-only production substitutes when Spring composition is involved.

A local adapter is never a production fallback and never satisfies production readiness.

## 3. CI execution efficiency

Quality-gate ordering expresses dependency and blocking semantics; it does not require independent checks to run serially.

Independent gates SHOULD execute in parallel when this preserves correctness, for example:

- unit + architecture + static analysis;
- contract compatibility + dependency verification + secret scan;
- independent integration-test shards;
- Helm/Kubernetes render validation in parallel with container-independent checks.

Use Gradle build cache/configuration cache and CI caching where compatible with reproducibility and security.

Do not skip a mandatory gate merely to reduce duration.

## 4. Heavy verification

Load, chaos, DR restore, failover, certificate-rotation, and full platform exercises are expensive. Run them at the frequency required by the applicable ADR/SLO/release policy rather than in every developer edit cycle.

Critical-path changes still run the applicable heavy verification before the release gate that depends on it.

## 5. Microservice startup discipline

Do not start unrelated services for a narrow task. Use explicit contracts and fakes/test doubles at service boundaries for unit/application tests; use real downstream services only when the integration behavior itself is under test.

## 6. Performance guardrails

Virtual Threads simplify blocking I/O concurrency but do not remove downstream limits. Keep database pools, gRPC concurrency, Kafka consumers, Redis operations, and provider calls bounded and observable.

Do not optimize by adding caches, asynchronous boundaries, retries, new services, or distributed coordination until evidence identifies the bottleneck and the change is compatible with accepted ADRs.


## 7. Local code-quality baseline

For Java services, the normal pre-push path SHOULD run the repository-defined equivalent of:

```bash
./gradlew spotlessCheck test architectureTest spotbugsMain
# focused integration/contract tasks for the change
# repository Semgrep blocking rules
```

Use `spotlessApply` only as a local formatting action. Do not disable a gate to speed up the loop; select the smallest applicable test scope and let CI/release pipelines run the heavier mandatory gates. See `coding-standards.md` and `build-and-ci-quality-enforcement.md`.

## 8. Code-generation preflight

Before implementation starts, complete the mandatory 18-item Code-Generation Checklist in `AGENTS.md` §8.1 / `coding-standards.md` §15. The checklist is a preflight, not a post-hoc documentation exercise: ports, interaction model, transaction/failure semantics, identity/policy impact, migrations, observability, deployment alignment, and required tests are decided before the concrete adapter code is generated.

AI-generated code follows exactly the same quality gates as handwritten code. Compilation alone is never sufficient evidence of completion.


## 9. Local platform foundation

Pure Domain/Application work does not require Kubernetes. When a change needs
real local mesh, edge, Gateway API, NetworkPolicy, or platform integration,
use the pinned local foundation rather than ad-hoc cluster commands.

Required sources:

- `docs/technology/local-development-baseline.md`
- `docs/runbooks/local-istio-ambient.md`
- `docs/runbooks/local-traefik-edge.md`

Expected repository interface:

```bash
make baseline-verify
make local-cluster-verify
make verify-local-istio-ambient
make verify-local-traefik-edge
```

These targets are executable evidence only after their implementation exists and
passes. Do not report local platform compliance from documentation alone.
