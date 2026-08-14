# Developer Workflow and Fast Feedback

This document defines development ergonomics without weakening production architecture.

## 1. Repository change workflow

All changes follow `repository-change-workflow.md`:

```text
current main -> task branch -> Draft PR -> coherent change -> complete diff review -> applicable evidence -> merge -> verify main
```

One PR represents one coherent engineering change, not one conversation prompt. Unrelated changes stay separate. A material post-merge defect may use a focused follow-up PR.

Normal work does not commit directly to `main`. A PR is not ready with a known Critical/High security finding, unresolved current-state contradiction, merge conflict, or required verification blocker.

ADR/documentation work also follows stable-ID/current-only policy.

## 2. Inner-loop principle

Use the smallest trustworthy scope:

1. Domain/Application unit tests without Spring;
2. focused adapter tests;
3. Testcontainers for real dependency behavior;
4. contract/architecture/static checks;
5. service runtime tests including Day-One observability;
6. staging/system/mesh/WAF/security checks;
7. scheduled/release load/chaos/DR/full-stack evidence.

Pure Domain/Application work does not require a complete Kubernetes stack.

## 3. Day-One observability development rule

ADR-0044 is part of implementation, not a later platform phase.

When a service/critical path is first implemented, the same coherent change includes applicable:

- structured allow-listed JSON logs;
- Micrometer operation/dependency/saturation metrics;
- OpenTelemetry spans/propagation to the approved local/internal Collector path;
- correct health/readiness behavior;
- PII/secret/cardinality tests;
- trace/baggage non-authority tests;
- telemetry backend/exporter failure test;
- alert/dashboard ownership for defined SLO/security signals where the signal becomes meaningful.

Do not merge a feature with TODO text such as “add observability later” when the path already needs logs/metrics/traces for diagnosis or evidence.

For local development, telemetry backends may be optional when the code path is testable with an in-memory/test exporter or local Collector fixture. This does not remove staging/release evidence against the real Collector/backends.

## 4. Local-only substitutions

Local adapters/fakes are allowed only where architecture permits and MUST be impossible to activate in staging/production. Use profile constraints equivalent to `local & !staging & !production` where applicable.

A local fake is never a production fallback or production-readiness evidence.

Reference Data may use its approved immutable local bundle before ADR-0041 independent-service trigger; that is current architecture, not a test fake.

Compromised Password normal PR tests use deterministic generated SHA-1 corpus fixtures. They do not require downloading the production HIBP corpus for every edit. Release/dataset evidence still uses the complete approved HIBP corpus.

## 5. CI efficiency

Independent checks SHOULD run in parallel where safe, such as:

- unit + ArchUnit + static analysis;
- contract + dependency verification + secret scan;
- independent integration shards;
- quota clock/cardinality tests separate from unrelated service tests;
- observability config/privacy/context tests separate from heavy telemetry storage/load tests;
- Helm/Kubernetes/Kyverno render checks parallel to container-independent checks.

Use Gradle build/configuration cache and secure reproducible CI caching where compatible. Never skip a mandatory gate to reduce duration.

## 6. Heavy verification

These are not every-edit requirements unless the change directly demands them:

- complete-stack load/soak;
- chaos/fault injection;
- cold DR/PITR restore;
- certificate/key rotation;
- provider integration;
- full observability storage/cardinality pressure;
- external total-host-loss detection.

They remain mandatory at the current release/scheduled cadence. A fast gate may protect a regression class at PR time, but it does not delete the heavy evidence gate.

## 7. Service startup discipline

Do not start unrelated services for a narrow task. Use explicit contracts/test doubles for unit/application tests and real downstreams when integration behavior is the subject under test.

Do not create a new network service merely to make local composition look uniform. ADR-0041 Reference Data remains local until its deployable trigger is evidenced.

## 8. Performance guardrails

Virtual Threads do not remove downstream resource limits. Keep DB pools, gRPC concurrency, Kafka consumers, Redis work, telemetry queues, workers, and provider calls bounded/observable.

Do not add cache/broker/proxy/retry/service/distributed coordination as speculative optimization. Measure bottleneck, verify SLO/security impact, and define success criteria first.

Semantic quota development also measures new security-bucket cardinality and common-mode clock behavior; normal-traffic latency alone is not enough.

## 9. Local code-quality baseline

Java pre-push SHOULD run the repository-defined equivalent of:

```bash
./gradlew spotlessCheck test architectureTest spotbugsMain
# focused integration/contract/schema/dataset/quota/observability tasks
# repository Semgrep blocking rules
```

Use `spotlessApply` only for formatting. Do not weaken gates for speed.

## 10. Code-generation preflight

Before implementation, complete the mandatory preflight in `AGENTS.md` and `coding-standards.md`.

Decide ownership/ports, sync-vs-event, transactions, deadlines/retry/idempotency/cancellation/concurrency, workload identity, migrations/reference datasets, logs/metrics/traces/alerts, deployment/securityContext, artifact promotion, PII/cardinality, and required tests before concrete adapter code.

AI-generated and handwritten code use the same gates. Compilation alone is not completion evidence.

## 11. Local platform foundation

Use the pinned local foundation only when real mesh/edge/Gateway/NetworkPolicy/telemetry integration is under test.

Expected interface after implementation exists:

```bash
make baseline-verify
make local-cluster-verify
make verify-local-istio-ambient
make verify-local-traefik-edge
make verify-local-observability   # when ADR-0044 local target exists
```

A documented target is not evidence until the target actually exists and passes.