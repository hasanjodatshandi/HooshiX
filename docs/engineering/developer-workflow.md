# Developer Workflow and Fast Feedback

This document defines development ergonomics without weakening production architecture.

## 1. Repository change workflow

All repository changes follow `repository-change-workflow.md`.

Mandatory sequence: branch -> Draft PR -> task changes -> complete diff review against current `main` -> applicable verification -> merge. Normal agent/developer work MUST NOT commit directly to `main`. A PR is not ready while a known Critical/High security finding, unresolved current-state contradiction, merge conflict, or required verification blocker remains.

GitHub cannot open a PR whose head has no commit different from base. The workflow therefore permits only the smallest legitimate task/governance scaffolding commit needed to establish the Draft PR; all substantive task changes remain inside it.

Documentation/ADR work also follows `current-only-documentation-policy.md`.

## 2. Inner-loop principle

The normal edit/test loop exercises the smallest trustworthy scope. Developers/agents do not need a complete Kubernetes/Istio/WAF/Argo CD environment to test pure Domain/Application behavior.

Preferred order:

1. Domain/Application unit tests without Spring;
2. focused adapter tests;
3. Testcontainers integration tests for real infrastructure behavior;
4. contract/architecture/static checks (`spotlessCheck`, ArchUnit, SpotBugs, Semgrep as applicable);
5. service-level runtime tests;
6. staging/system checks for production-only infrastructure.

## 3. Local-only substitutions

Local-only adapters/fakes are allowed only where current architecture explicitly permits them. They MUST be impossible to activate in `staging` or `production`.

Use profile expressions equivalent to `local & !staging & !production` for local-only substitutes when Spring composition is involved. A local adapter is never a production fallback and never satisfies production readiness.

## 4. CI execution efficiency

Gate ordering expresses blocking dependencies; independent checks SHOULD run in parallel when correctness/security are preserved, for example:

- unit + architecture + static analysis;
- contract compatibility + dependency verification + secret scan;
- independent integration-test shards;
- Helm/Kubernetes render validation in parallel with container-independent checks.

Use Gradle build/configuration cache and CI caching where compatible with reproducibility and security. Do not skip a mandatory gate merely to reduce duration.

## 5. Heavy verification

Load, chaos, DR restore, failover, certificate-rotation, provider, and full-platform exercises are expensive. Run them at the cadence required by current SLO/release/operations policy rather than every edit cycle. Critical-path changes still run applicable heavy verification before the release gate that depends on it.

## 6. Microservice startup discipline

Do not start unrelated services for a narrow task. Use explicit contracts and fakes/test doubles at service boundaries for unit/application tests; use real downstream services only when the integration behavior itself is under test.

## 7. Performance guardrails

Virtual Threads simplify blocking-I/O concurrency but do not remove downstream limits. Keep database pools, gRPC concurrency, Kafka consumers, Redis operations, queues, workers, and provider calls bounded/observable.

Do not add caches, asynchronous boundaries, retries, new services, proxies, or distributed coordination merely as speculative optimization. First identify the bottleneck, confirm compatibility with current architecture/SLO/dependency policy, and define measurable success criteria.

## 8. Local code-quality baseline

For Java services, normal pre-push work SHOULD run the repository-defined equivalent of:

```bash
./gradlew spotlessCheck test architectureTest spotbugsMain
# focused integration/contract/schema tasks for the change
# repository Semgrep blocking rules
```

Use `spotlessApply` only as a local formatting action. Do not disable a gate to speed up the loop. See `coding-standards.md` and `build-and-ci-quality-enforcement.md`.

## 9. Code-generation preflight

Before implementation starts, complete the mandatory 20-item preflight in `AGENTS.md` §15 and `coding-standards.md` §16.

It is a preflight, not post-hoc documentation: ownership/ports, sync-vs-event semantics, transactions, deadlines/retry/idempotency/cancellation/concurrency, workload identity/policy, migrations, observability, deployment/securityContext, artifact promotion, logging/PII, and required tests are decided before concrete adapter code is generated.

AI-generated code follows exactly the same gates as handwritten code. Compilation alone is never evidence of completion.

## 10. Local platform foundation

Pure Domain/Application work does not require Kubernetes. When a change needs real local mesh, edge, Gateway API, NetworkPolicy, or platform integration, use the pinned local foundation rather than ad-hoc cluster commands.

Required sources:

- `../technology/local-development-baseline.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/local-traefik-edge.md`

Expected repository interface:

```bash
make baseline-verify
make local-cluster-verify
make verify-local-istio-ambient
make verify-local-traefik-edge
```

These targets are executable evidence only after their implementation exists and passes. Do not report local platform compliance from documentation alone.
