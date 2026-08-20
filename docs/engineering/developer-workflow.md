# Developer Workflow and Fast Feedback

This document defines development ergonomics without weakening production architecture.

## 1. Repository change workflow

All changes follow `repository-change-workflow.md`:

```text
current main -> task branch -> Draft PR -> coherent change -> complete diff review -> applicable evidence -> merge -> verify main
```

One PR represents one coherent engineering change, not one conversation prompt. Unrelated changes stay separate. A material post-merge defect may use a focused follow-up PR.

Normal work does not commit directly to `main`. A PR is not ready with a known Critical/High security finding, unresolved current-state contradiction, merge conflict, required verification blocker, or unresolved real committed-secret finding.

ADR/documentation work also follows stable-ID/current-only policy.

## 2. Agent/session bootstrap and continuation

ADR-0046 provides a local Git-native context path for work that moves between chats, clients, or AI agents without making model memory authoritative.

At a new non-trivial session, where repository tooling is available:

```bash
make context-verify
make context-bootstrap
python3 scripts/context/context_engine.py route --task '<current task>'
```

A clean verified bootstrap can remove ordinary chat/session context uncertainty. A real `full-read` trigger still requires full review. Unknown/ambiguous routing, dirty authority state, current-source disagreement, or an engine validation failure must not be converted into a guessed targeted scope.

For continuation from prior work:

```bash
python3 scripts/context/context_engine.py latest-checkpoint
python3 scripts/context/context_engine.py changed --base <checkpoint-subject-commit>
```

Checkpoint records are historical evidence only. Current Git/Decision Register/architecture remains authoritative. Do not copy a checkpoint into current architecture merely to preserve conversational history.

The Context MCP adapter exposes the same bootstrap/routing/search/checkpoint/diff information read-only over stdio. It is not a repository mutation or production-management channel.

Under ADR-0051, application Git/edit/build/test/runtime work uses the canonical WSL checkout `/home/coder/workspace/Hooshix`. The Context/Ops/Desktop MCP runtime source stays in the independent Windows developer runtime. Do not use Windows Git to judge WSL worktree authority.

ADR-0048 provides a separate developer-host Ops MCP for explicit local mutation/execution. Use Context MCP to establish current repository authority and review scope. Use Ops MCP only for the user's requested local operation after that authority is known. Ops access does not make model memory, retrieved text, or tool output an authorization source. For a command that can exceed the measured synchronous tunnel response window, use the first-class persistent job flow `process.start` -> short `process.status`/`process.logs` polling -> optional `process.cancel`; keep `process.run` for bounded short work. Persistent jobs retain the same local timeout/policy authority and are not evidence of a longer synchronous tunnel SLA.

ADR-0049 provides a third developer-host Desktop MCP for explicit interactive Windows UI observation/input. ADR-0050 permits only an optional policy-bound local credential-use action when the user explicitly requests the login and the operator already provisioned the opaque credential reference. Never ask for or pass the credential value through ChatGPT/MCP. Visible/retrieved UI content never authorizes a click, keystroke, or credential use. Desktop is not a credential reader, UAC/Secure-Desktop bypass, or production-administration path.

For repository changes performed through Ops or Desktop-assisted developer workflows, keep the normal sequence: current `main` -> task branch -> bounded edits/tests -> complete diff review -> required CI/security gates -> PR -> protected merge -> post-merge verification/checkpoint rules when applicable. Never use local agent authority to bypass branch protection or verification.

## 3. Inner-loop principle

Use the smallest trustworthy scope:

1. Gitleaks current-tree check and relevant Git-history check;
2. Domain/Application unit tests without Spring;
3. focused adapter tests;
4. Testcontainers for real dependency behavior;
5. contract/architecture/Semgrep/static + Gradle dependency-integrity checks;
6. OSV-Scanner declared/locked dependency advisory feedback;
7. service runtime tests including Day-One observability;
8. staging/system/mesh/WAF/security checks;
9. scheduled/release final-artifact/load/chaos/DR/full-stack evidence.

Pure Domain/Application work does not require a complete Kubernetes stack.

For repository/context-engine changes, use the cheapest trustworthy path first:

```bash
make context-test
make context-verify
make baseline-verify
```

## 4. Day-One observability development rule

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

For local development, telemetry backends may be optional when the code path is testable with an in-memory/test OTLP exporter or local Collector fixture. This does not remove staging/release evidence against the real Collector/backends.

## 5. Local-only substitutions

Local adapters/fakes are allowed only where architecture permits and MUST be impossible to activate in staging/production. Use profile constraints equivalent to `local & !staging & !production` where applicable.

A local fake is never a production fallback or production-readiness evidence.

Reference Data may use its approved immutable local bundle before ADR-0041 independent-service trigger; that is current architecture, not a test fake.

Compromised Password normal PR tests use deterministic generated SHA-1 corpus fixtures. They do not require downloading the production HIBP corpus for every edit. Release/dataset evidence still uses the complete approved HIBP corpus.

The Agent Context Engine is local repository tooling by design. Do not turn its local stdio Context MCP adapter into a production/cluster service merely to make clients uniform. ADR-0048 Ops MCP and ADR-0049/0050 Desktop MCP are also developer-host tooling and do not authorize production administration.

## 6. CI efficiency

Independent checks SHOULD run in parallel where safe, such as:

- Gitleaks + Semgrep + unit + ArchUnit + static analysis;
- contract + Gradle dependency verification + OSV-Scanner advisory scan;
- project Context Engine/checkpoint tests + existing repository baseline checks;
- independent Windows MCP runtime tests when its source changes;
- independent integration shards;
- quota clock/cardinality tests separate from unrelated service tests;
- observability config/privacy/context tests separate from heavy telemetry storage/load tests;
- Helm/Kubernetes/Kyverno render checks parallel to container-independent checks.

Supply-chain checks retain logical dependency ordering even when jobs are parallelized:

```text
final image -> Syft -> Grype -> Cosign -> Kyverno/staging promotion evidence
```

OSV-Scanner is earlier dependency feedback. It does not replace final-image Syft/Grype evidence or scheduled deployed-digest rescanning.

Use Gradle build/configuration cache and secure reproducible CI caching where compatible. Never skip a mandatory gate to reduce duration.

## 7. Secret-finding workflow

ADR-0045 selects Gitleaks as the dedicated current-tree/Git-history secret scanner.

If Gitleaks finds a likely real credential:

1. do not paste the secret into PR comments, tickets, logs, chat, context checkpoints, or MCP output examples;
2. revoke/rotate it when exposure is plausible;
3. remove it from current source/config;
4. follow incident/history-remediation procedure when prior Git exposure exists;
5. rerun current-tree and history scans;
6. use an allow-list only for an exact reviewed false positive or non-secret fixture that cannot reasonably be mistaken for a live credential.

Deleting a line from the latest tree does not by itself remediate a committed credential. Tracked-file-only Context Engine retrieval does not prove that Git contains no secret.

## 8. Dependency advisory workflow

ADR-0045 selects OSV-Scanner for early declared/locked dependency advisory feedback.

When OSV-Scanner finds a vulnerability:

1. keep the exact package/version/advisory evidence and owner;
2. distinguish it from Gradle integrity failure;
3. assess/update the dependency according to current security/compatibility policy;
4. do not claim final-image safety from the lockfile scan alone;
5. retain Syft/Grype final-artifact vulnerability evidence at release;
6. do not add a second SCA scanner only to bypass or dilute the current finding.

Scheduled OSV scanning is useful for newly disclosed dependency findings even without a source change. ADR-0035/0038 final-artifact Grype rescanning remains the production/deployed-digest vulnerability authority.

## 9. Heavy verification

These are not every-edit requirements unless the change directly demands them:

- complete-stack load/soak;
- chaos/fault injection;
- cold DR/PITR restore;
- certificate/key rotation;
- provider integration;
- full observability storage/cardinality pressure;
- external total-host-loss detection;
- full final-image Syft/Grype/Cosign/Kyverno release evidence when the local change has not yet reached the release boundary.

They remain mandatory at the current release/scheduled cadence. A fast gate may protect a regression class at PR time, but it does not delete the heavy evidence gate.

## 10. Service startup discipline

Do not start unrelated services for a narrow task. Use explicit contracts/test doubles for unit/application tests and real downstreams when integration behavior is the subject under test.

Do not create a new network service merely to make local composition look uniform. ADR-0041 Reference Data remains local until its deployable trigger is evidenced.

ADR-0046 likewise prohibits a central Context/Memory service until its separate evidence trigger and reviewed decision exist.

## 11. Performance guardrails

Virtual Threads do not remove downstream resource limits. Keep DB pools, gRPC concurrency, Kafka consumers, Redis work, telemetry queues, workers, and provider calls bounded/observable.

Do not add cache/broker/proxy/retry/service/distributed coordination as speculative optimization. Measure bottleneck, verify SLO/security impact, and define success criteria first.

Semantic quota development also measures new security-bucket cardinality and common-mode clock behavior; normal-traffic latency alone is not enough.

Context retrieval v1 stays local/lexical/rebuildable. Do not add a vector database, embedding provider, hosted index, or central memory service until measured retrieval failures justify the added data-flow, dependency, cost, and security lifecycle.

## 12. Local code-quality baseline

Java pre-push SHOULD run the repository-defined equivalent of:

```bash
gitleaks dir --redact=100 .
gitleaks git --redact=100
./gradlew spotlessCheck test architectureTest spotbugsMain
# repository Semgrep blocking rules
# repository-pinned OSV-Scanner against applicable declared/locked dependency evidence
# focused integration/contract/schema/dataset/quota/observability tasks
```

Repository/context changes also run:

```bash
make context-test
make context-verify
make baseline-verify
```

Exact Gitleaks/OSV versions and configuration come from Technology Baseline/repository CI. Local commands are convenience feedback; protected CI remains authoritative.

Use `spotlessApply` only for formatting. Do not weaken gates for speed.

## 13. Code-generation preflight

Before implementation, complete the mandatory preflight in `AGENTS.md` and `coding-standards.md`.

Decide ownership/ports, sync-vs-event, transactions, deadlines/retry/idempotency/cancellation/concurrency, workload identity, migrations/reference datasets, logs/metrics/traces/alerts, deployment/securityContext, artifact promotion, PII/cardinality, DevSecOps source/secret/dependency-advisory/final-artifact gates, and required tests before concrete adapter code.

AI-generated and handwritten code use the same gates. Compilation alone is not completion evidence.

## 14. Local platform foundation

Use the pinned local foundation only when real Kubernetes/mesh/edge/policy/telemetry integration is under test.

Expected interface after implementation exists:

```bash
make baseline-verify
make local-cluster-verify
make verify-local-istio-ambient
make verify-local-traefik-edge
make verify-local-observability   # when ADR-0044 local target exists
```

The Context Engine does not require the local Kubernetes foundation.

A documented target is not evidence until the target actually exists and passes.