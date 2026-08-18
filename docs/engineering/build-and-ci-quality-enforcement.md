# Build and CI Quality Enforcement — Current Standard

This document defines executable quality gates for independently deployable services and platform artifacts. Repository workflow governs PR-first delivery. Documentation alone never proves source/runtime compliance.

ADR-0045 defines the current DevSecOps tool responsibility map. ADR-0017/0035/0038/0039 remain authoritative for signing/admission, final-artifact vulnerability policy, exception/threat-intelligence behavior, and Java executable-quality semantics. ADR-0046 defines repository Agent Context Engine governance. ADR-0047 defines the approved ChatGPT Web bridge to the unchanged read-only stdio Context MCP. ADR-0048 defines the separate policy-gated developer-host Ops MCP and its repository verification boundary.

## 1. Required Java PR gates

Every Java service exposes repository-defined tasks/checks for applicable:

```text
compile
test
integrationTest
architectureTest
spotlessCheck
spotbugsMain
repository Semgrep/SAST
Gitleaks current-tree/history secret scan
Gradle dependency verification/lock checks
OSV-Scanner declared/locked dependency advisory scan
contract/schema checks
```

Additional gates apply by changed capability. A mandatory check is not changed to warning/`ignoreFailures` merely to obtain a green pipeline.

Gitleaks, Semgrep, Gradle verification, and OSV-Scanner protect different failure classes:

- Semgrep — first-party source SAST/policy;
- Gitleaks — committed-secret detection;
- Gradle verification/locks — expected dependency integrity/reproducibility;
- OSV-Scanner — early known-vulnerability advisory feedback for declared/locked dependencies.

None replaces final-image Syft/Grype release evidence.

## 2. Architecture/code gates

CI enforces at least:

- Domain/Application inward dependency direction;
- package regex and forbidden dumping-ground package names;
- no Spring/JPA/jOOQ/gRPC/Redis/Kafka/SQLite/provider types in Domain/Application;
- no field injection/service locator/circular dependency hiding;
- no remote I/O inside annotated/known DB transaction boundaries where static/test enforcement is feasible;
- no production local/test backdoor profile;
- service boundary and contract ownership checks.

## 3. Secret, dependency, supply-chain, and artifact gates

### Secret scanning

- Gitleaks CLI version comes from Technology Baseline and the exact downloaded native artifact/checksum or immutable image digest is pinned in CI metadata;
- current repository files/tree are scanned;
- protected Git history is scanned so deleting a secret from the latest tree does not erase detection evidence;
- scanner output is fully redacted and must not publish the detected secret in logs/annotations/artifacts;
- a real exposed credential is revoked/rotated before the finding can be considered remediated;
- ignore/allow-list entries are exact, justified, owned, reviewed, and bounded; a real active credential is not suppressible as a false positive.

### Dependency integrity

- Gradle dependencies/plugins/tools are pinned/verified; dynamic versions are prohibited;
- dependency verification/locks prove expected artifact integrity/reproducibility; they are not vulnerability/CVE authority.

### Early dependency advisory scanning

- OSV-Scanner version comes from Technology Baseline and the exact downloaded binary/checksum is pinned in CI metadata;
- applicable declared/locked dependency evidence is scanned on blocking service CI;
- repository scheduled security verification reruns the implemented service advisory scan so newly disclosed dependency findings can surface without a source change;
- a passing lockfile scan is early feedback, not final-image vulnerability proof;
- OSV-Scanner does not replace Syft/Grype final-artifact coverage.

### Final artifact

- final image is built once from reviewed source;
- Syft generates the required CycloneDX JSON SBOM from the exact final releasable image digest;
- Grype performs final-image/SBOM vulnerability correlation under ADR-0035/0038 severity, freshness, ownership, and exception policy;
- Cosign signs the exact image digest and creates the required provenance and signed SBOM attestation;
- deployment promotes the exact signed digest from staging to production;
- Kyverno verifies required digest/signature/provenance/SBOM and workload policy at admission;
- privileged workflow contexts never execute unreviewed PR-controlled code/config with write secrets/tokens;
- downloaded/vendored platform and security-tool artifacts verify digest/signature/checksum before use.

Trivy and OWASP Dependency-Check are not current default HooshiX gates. They may be introduced only through the ADR-0045 distinct-coverage review. Semgrep CLI use does not imply separate Semgrep Secrets/Supply Chain product enablement.

## 4. Contract and persistence gates

Applicable:

- Buf lint/breaking for Protobuf;
- OpenAPI compatibility/bounds/security behavior;
- Flyway validation/migration compatibility;
- PostgreSQL role/RLS/cross-service negative tests;
- transaction-local tenant-context pool-reuse tests;
- query bounds/index/representative plans;
- Outbox/Inbox/idempotency/replay tests;
- provider ambiguity/idempotency tests.

ADR-0040 Compromised Password additionally gates:

- official HIBP SHA-1 corpus acquisition/provenance identity;
- SHA-1 screening-only rule and Argon2id credential-storage rule;
- zero-count padding rejection;
- complete-corpus prefix-cardinality/serialized-size measurement;
- dataset freshness <=35 days at production readiness;
- immutable 20-byte SHA-1 SQLite format and read-only/query-only runtime;
- no runtime HIBP/provider egress;
- false-clean prevention on corrupt/stale/oversized/incompatible data.

ADR-0041 blocks creation of `reference-data-service` until an explicit independent-deployable trigger record exists.

## 5. Semantic quota gates

Changes affecting ADR-0024 run executable checks for:

- atomic multi-dimension consumption/no partial commit;
- exact `/32` IPv4 and `/128` IPv6 hard client identity;
- separate `/24`/`/64` aggregate pressure and proof it is not the sole v1 429 gate;
- trusted ADR-0043 context and forged-header/proxy negatives;
- app/Redis skew and one-clock jump;
- common-mode app+Redis host clock step through wall-vs-monotonic Clock Safety Guard;
- boot synchronization and 60-second safe re-arm;
- no TTL security reset;
- `noeviction` and >=30% memory reserve;
- adversarial high-cardinality new-bucket flood;
- bounded low-cardinality allocation guard returning `QUOTA_CAPACITY_UNHEALTHY` before OOM/eviction;
- PII-safe low-cardinality quota telemetry.

A quota time/capacity failure never becomes success or fabricated subject quota denial.

## 6. Day-One observability gates

ADR-0044 applies to the first executable service PR, not a later observability project.

Applicable service PRs must prove:

- structured allow-listed JSON logs;
- Micrometer request/operation/dependency/saturation metrics;
- OpenTelemetry tracing to the internal Collector;
- safe W3C propagation and bounded baggage;
- no trace/baggage value becomes authN/authZ/tenant/quota/idempotency/audit authority;
- no secret/credential/contact/raw-IP/prohibited identifier enters logs/metrics/traces;
- metric label cardinality is bounded;
- management Prometheus endpoint and OTLP receiver are not public;
- telemetry backend/exporter outage does not fail ordinary business processing;
- required audit evidence remains on its authoritative durable/off-host path;
- at least one synthetic integration path produces correlated safe logs, metrics, and traces when the involved services exist.

Collector/platform checks prove:

- pinned `otelcol-contrib` image/digest;
- dedicated ServiceAccount/RBAC/NetworkPolicy;
- exact read-only pod/container log `hostPath` only; no broad host mount/host network/privilege escalation;
- memory limiter/batch/finite queue configuration;
- redaction/filtering before export;
- exporter loss/drop/backpressure telemetry;
- Loki/Tempo/Prometheus retention/cardinality/storage quotas render as bounded configuration;
- single-server external black-box monitor is configured outside the host failure domain before production.

## 7. Kyverno CEL-only policy gate

Kyverno 1.18.x new production controls use stable CEL-based `policies.kyverno.io/v1` policy APIs.

Repository/render policy checks fail when a new production manifest uses legacy:

```text
apiVersion: kyverno.io/v1
kind: ClusterPolicy | Policy

apiVersion: kyverno.io/v2
kind: CleanupPolicy | ClusterCleanupPolicy
```

unless a separately reviewed migration-only exception identifies owner/removal deadline. Greenfield HooshiX production policy authoring has no default exception.

Signature/provenance/SBOM verification uses `ImageValidatingPolicy` where applicable. Resource validation/mutation/generation/deletion uses the corresponding stable CEL policy type.

## 8. Kubernetes/GitOps gates

Applicable checks include:

- Helm lint/render/schema;
- Kubernetes schema/API deprecation checks;
- Kyverno CEL policy validation/tests;
- NetworkPolicy/Istio identity positive/negative tests;
- `istioctl analyze`;
- security-context negatives;
- no default ServiceAccount;
- no public dashboard/insecure edge;
- ADR-0043 origin/client-address/WAF path negatives;
- secret/render scans;
- profile-correct replica/HPA/PDB/topology.

## 9. Documentation/governance, Agent Context, and developer-host Ops gates

CI SHOULD enforce when implemented:

- no broken relative links;
- Decision Register/source/task/index coverage;
- ADR filename/heading ID match;
- no duplicate/reused/renumbered merged ADR identifiers;
- no deletion of a merged ADR without explicit repository-owner exception;
- superseded ADRs not treated as current authority;
- no stale baseline version copies outside permitted contexts;
- dependency YAML/schema/render consistency;
- `context/bootstrap.json`, `context/routes.json`, and checkpoint contracts are valid/current and all referenced authority/source paths exist;
- post-merge checkpoints have path-confined standalone work sources, same-PR linkage, main-reachable subject commits, and Git-derived `base..merge` changed paths; repository CI fetches the history needed to re-verify this evidence;
- `docs/architecture/TASK-REVIEW-MATRIX.md` exactly matches canonical `context/routes.json` generation;
- unknown/ambiguous/full-read-trigger task routing cannot silently become targeted review;
- dirty configured authority state cannot be reported as verified targeted-review context;
- Context Engine retrieval remains tracked-file-only, repository-root confined, bounded, provenance-bearing, and configured sensitive filenames are excluded;
- caller-controlled revision/query input cannot become shell execution or arbitrary filesystem access;
- Context MCP modern discovery/tool calls and bounded legacy initialize compatibility remain tested;
- Context MCP exposes no file/Git/checkpoint/deployment/write mutation tool;
- the Context MCP entry point starts correctly from a working directory outside the repository and resolves HooshiX from the tracked script path;
- ADR-0047 ChatGPT Web access remains an external OpenAI tunnel-client bridge to the unchanged stdio child and does not add a HooshiX HTTP/SSE/TCP listener, public MCP port, shell, arbitrary filesystem, Git mutation, credential-read, or deployment tool;
- ADR-0047 documentation/pins require official tunnel-client release integrity verification and a restricted Tunnels `Read` + `Use` runtime credential; an admin key is not the long-lived daemon credential.
- ADR-0048 Ops MCP remains a separate stdio server and does not change the exact five-tool Context MCP surface;
- Ops server startup requires a local fail-closed policy and tests cover missing/malformed/unknown/duplicate/relative policy state, path/denied-root/symlink escape, allowed-root deletion denial, bounded atomic UTF-8 file mutation, process alias/cwd/timeout/output controls, child credential-environment exclusion, bounded audit metadata/rotation, CWD-independent startup, and explicit UTF-8 response bytes;
- Ops process execution accepts only policy aliases to absolute executables and argv arrays; no caller-selected executable path, arbitrary environment map, or stdin secret channel is added;
- elevated Ops mutation/process execution requires explicit local policy opt-in and remains developer-host only; no test or tunnel setup may treat it as ADR-0030 production privilege;
- repository examples contain no real Ops policy secret or tunnel credential.

These gates do not replace Gitleaks. Tracked-file-only retrieval is not evidence that Git contains no committed secret.

`make baseline-verify` includes repository Context Engine tests/verification and Ops MCP tests. Context/route/documentation or Ops policy/stdio boundary drift therefore blocks the same repository-governance boundary.

Repository CI proves only repository-side stdio/policy/governance behavior. Real tunnel-client installation, runtime credential permissions, local `/readyz`, ChatGPT Plugin discovery, Windows ACL/elevation/background state, Context `project.bootstrap`, and Ops `ops.status`/mutation/execution are environment integration evidence and remain `NOT VERIFIED` until executed on the operator PC for the relevant surface.

## 10. Heavy release/scheduled evidence

Not every edit runs full platform work. Release/scheduled gates retain:

- scheduled OSV-Scanner locked-dependency advisory scan for implemented services;
- staging critical journeys/Playwright;
- deployed-digest Grype rescanning with refreshed approved data at the ADR-0035 cadence;
- load/soak and complete-stack single-server benchmark;
- chaos/failure behavior;
- backup/PITR/restore/cold DR;
- certificate/key rotation;
- provider integration evidence;
- external host-down monitoring exercise.

OSV scheduled dependency scanning is not a replacement for deployed-digest Grype rescanning. Heavy checks may move out of every-PR cadence only when a faster deterministic PR gate protects the regression class and the heavy gate remains mandatory before the release/evidence boundary that depends on it.

## 11. DevSecOps gate ordering and evidence

Logical authority order is:

```text
Gitleaks
-> Semgrep/static/architecture/format/dependency integrity
-> OSV-Scanner dependency advisory
-> tests/contracts/security
-> final immutable image
-> Syft CycloneDX SBOM
-> Grype final-artifact vulnerability decision
-> Cosign signature/provenance/signed SBOM
-> Helm/Kubernetes/Istio/Kyverno
-> staging
-> same-digest promotion
```

Parallel execution is allowed only when dependency ordering/evidence remains correct.

Executable verification includes positive and negative Gitleaks history fixtures, Semgrep fixtures, pinned OSV locked-dependency scanning, separate Gradle/OSV/Grype failure classes, Syft final-image binding, Grype policy/freshness behavior, Cosign signer/provenance/SBOM cases, and Kyverno missing/wrong evidence rejection.

## 12. CI result language

A check is `Passed` only when it ran and succeeded. Empty/unconfigured status, missing workflow, documentation assertion, or unavailable environment is `Not run`/`Not verified`, never green CI.
