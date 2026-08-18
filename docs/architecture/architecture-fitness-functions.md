# Architecture Fitness Functions

This catalog defines properties that should be continuously verified. A row is not proof until executable evidence exists and passes.

| ID | Property | Evidence | Cadence | Failure action |
| --- | --- | --- | --- | --- |
| AFF-001 | Domain/Application inward dependency direction | ArchUnit | PR | block |
| AFF-002 | Service database/role/Flyway ownership and no cross-service SQL | integration/privilege/schema tests | PR/release | block |
| AFF-003 | Forced tenant RLS and pooled context isolation | DB negatives | PR/release | block |
| AFF-004 | No remote I/O inside DB transactions | architecture/integration tests | PR | block |
| AFF-005 | Transactional Outbox/Inbox/idempotency when required | integration/replay tests | PR/release | block |
| AFF-006 | Authorization is one online fail-closed no-cache/no-retry decision | contract/failure tests | PR/release | block |
| AFF-007 | Browser token custody remains BFF-only | E2E/security tests | PR/release | block |
| AFF-008 | OIDC/MFA/external identity rules remain current | security regression | PR/release | block |
| AFF-009 | NetworkPolicy/Istio workload identity least privilege | positive/negative connectivity/authz | PR/release | block |
| AFF-010 | Public traffic always traverses approved L4->Traefik->WAF->BFF path | edge render/network negatives | PR/release | block |
| AFF-011 | Trusted client address ignores caller forwarding headers | PROXY/header/address-form negatives | PR/release | block public quota paths |
| AFF-012 | Traefik origin accepts only external-L4 sources | firewall/routing negative | release | block public traffic |
| AFF-013 | Human privileged access separates network/FIDO2/JIT | access/audit exercise | release/scheduled | block privileged access |
| AFF-014 | OpenBao remains secret authority | diff/render/recovery tests | PR/release | block |
| AFF-015 | Supply-chain artifact is signed/provenanced/SBOM verified | CI/admission | PR/release | block |
| AFF-016 | Kyverno new production policies are CEL v1 only | manifest scan/schema/CLI tests; legacy fixture rejection | PR | block |
| AFF-017 | Notification ambiguity never becomes blind duplicate send | provider/reconciliation tests | PR/release | block affected channel |
| AFF-018 | PostgreSQL physical backup/PITR/recovery is real evidence | restore records | monthly/quarterly | block promotion/escalate |
| AFF-019 | Single-server non-HA claims remain explicit | render/docs/evidence checks | PR/release | block false readiness claim |
| AFF-020 | Complete-stack single-server capacity preserves >=30% headroom | simultaneous load/IO/network evidence | release | block production approval |
| AFF-021 | Compromised Password corpus is official HIBP SHA-1, fresh and complete | source/provenance/freshness/full-corpus build evidence | dataset release | block password-write promotion |
| AFF-022 | SHA-1 is screening-only; Argon2id stores credentials | code/contract/security tests | PR | block |
| AFF-023 | Compromised Password SQLite is immutable/read-only/query-only | adapter/container/SQL negatives | PR/release | block |
| AFF-024 | Reference Data independent service exists only after ADR-0041 trigger | repo/tree/trigger record check | PR | block premature service |
| AFF-025 | Reference Data before trigger stays immutable/local with no hidden network authority | module/bundle tests | PR | block |
| AFF-026 | Quota hard client identity is exact `/32`/`/128` | Redis/network tests | PR/release | block quota paths |
| AFF-027 | Aggregate `/24`/`/64` is separate pressure, not sole v1 hard 429 gate | NAT/VPN/campus/IPv6 tests | PR/release | block quota paths |
| AFF-028 | Quota common-mode host clock step cannot create refill | wall-vs-monotonic + Redis TIME jump tests | PR/release/chaos | block quota paths |
| AFF-029 | Quota unsafe time requires host sync + 60s stable re-arm | clock-health tests | release | block quota paths |
| AFF-030 | Redis high-cardinality allocation cannot reach eviction/OOM silently | adversarial unique-key load + capacity guard | release/chaos | block quota paths |
| AFF-031 | `QUOTA_CAPACITY_UNHEALTHY`/time failure stays distinct from normal denial | API/contract tests | PR | block |
| AFF-032 | Day-One observability exists with first executable service | repo/template/DoD check | PR | block implementation completion |
| AFF-033 | Logs are structured allow-list and PII/secret safe | Semgrep + canary + pipeline tests | PR/release | block |
| AFF-034 | Metrics are low-cardinality and safe | metric-scrape/cardinality tests | PR/release | block |
| AFF-035 | Traces/baggage are safe correlation only | propagation/forged-context tests | PR/release | block |
| AFF-036 | OTLP/management endpoints are private and Collector least-privileged | network/RBAC/render negatives | PR/release | block |
| AFF-037 | Collector log filesystem access is exact/read-only only | rendered mount/security-context test | PR/release | block |
| AFF-038 | Ordinary telemetry outage does not fail business processing | Collector/backend fault tests | PR/release | block affected release |
| AFF-039 | Required security audit stays durable/off-host outside ordinary telemetry | audit pipeline exercise | release/scheduled | block privileged production use |
| AFF-040 | Total host loss is detected outside local observability failure domain | external black-box exercise | release/scheduled | block production approval |
| AFF-041 | Observability fits same single-server capacity envelope | Collector/Prometheus/Loki/Tempo/Grafana/Alertmanager resource evidence | release | block production approval |
| AFF-042 | Full cold DR reconstructs controls/data/telemetry and measures RPO/RTO | cold-DR exercise | quarterly | block/escalate |
| AFF-043 | Repository implementation/evidence status is honest | tree/evidence checks | PR | block misleading claim |
| AFF-044 | Merged ADR IDs are immutable/non-reused | governance check against main history/register | PR | block |
| AFF-045 | One PR represents one coherent engineering change | diff/scope review | PR | split or re-scope |
| AFF-046 | Current-tree and committed Git-history secrets are blocked without secret disclosure in scanner output | Gitleaks current-tree/history + commit-then-delete fixture + redaction test | PR | block |
| AFF-047 | Dependency integrity, early dependency advisory, and final-artifact vulnerability scanning remain distinct authorities | Gradle verification failure + OSV locked-dependency advisory fixture + Syft/Grype vulnerable final-artifact fixture | PR/release | block misleading/bypassed evidence |
| AFF-048 | Final releasable image has digest-bound Syft SBOM, Grype decision, Cosign signature/provenance/signed-SBOM evidence | release pipeline + artifact verification | release | block promotion |
| AFF-049 | DevSecOps scanner/feed/signing/admission failure or stale evidence cannot silently permit the boundary that depends on it | Gitleaks/Semgrep/OSV/Grype/Cosign/Kyverno failure/freshness/negative fixtures under ADR-0035/0038/0045 | PR/release | block affected merge/promotion |
| AFF-050 | OSV scheduled dependency advisory scanning does not replace deployed-digest final-artifact rescanning | scheduled OSV evidence + ADR-0035 Grype deployed-digest rescan evidence | scheduled/release | block false vulnerability-readiness claim |
| AFF-051 | Agent context is Git-provenanced, conservative under uncertainty, and reachable only through approved bounded read-only interfaces | context bootstrap/router/work-checkpoint/post-merge provenance/search/MCP tests + generated matrix check + CWD-independent stdio entrypoint + ADR-0047 tunnel review proving no HooshiX network listener/write/general-shell expansion | PR | block misleading/narrow/overprivileged agent context |
| AFF-052 | Developer-host Ops MCP remains separate from Context authority, policy-gated, path/command bounded, auditable, explicit-UTF-8 stdio, and outside production administration | Ops policy/path/process/environment/elevation/audit/UTF-8 tests + operator host/tunnel evidence under ADR-0048 | PR/host | block overprivileged or misleading Ops capability |
| AFF-053 | Developer-host Desktop MCP remains separate from Context/Ops, uses reviewed WinApp/session/app/HWND/capability bounds, keeps sensitive capture/input out of audit, and cannot become a credential/UAC/production bypass | Desktop policy/version/app/HWND/capture/input/environment/audit/UTF-8/MCP-image tests + operator WinApp/tunnel/interactive/restart evidence under ADR-0049 | PR/host | block overbroad, privacy-unsafe, or misleading Desktop capability |

## Interpretation

- Fitness functions do not create implementation evidence by existing in this table.
- Single-server availability exceptions never weaken security/correctness functions.
- A capacity problem is not permission to disable OpenBao, Kyverno, Ambient, WAF, PITR, MFA, fail-closed quota/Authorization, required audit, client-address trust, secret scanning, dependency advisory scanning, or signed final-artifact gates.
- ADR-0044 ordinary telemetry is best-effort/bounded; privileged/security audit remains separate authority.
- ADR-0045 owns the selected DevSecOps tool roles. OSV-Scanner is early declared/locked dependency advisory feedback; Syft+Grype own final-image release/deployed-artifact vulnerability evidence. Trivy/OWASP Dependency-Check remain unselected unless a distinct coverage gap is reviewed.
- ADR-0046 keeps Git as project-context authority. Context routes, retrieval, work checkpoints, and post-merge checkpoints are derived support; unknown/ambiguous/dirty-authority state cannot be used to justify a narrower review. Post-merge evidence must remain same-PR, path-confined, main-reachable, and Git-diff-derived. The read-only Context MCP surface cannot mutate the repository or production state.
- ADR-0047 allows ChatGPT Web access through OpenAI tunnel-client only as an external bridge to the unchanged stdio MCP surface. It adds no HooshiX HTTP listener, public MCP port, arbitrary host filesystem/shell authority, or long-lived admin credential.
- ADR-0048 adds a separate developer-host Ops MCP. Its local policy, OS token, bounded roots/command aliases/limits, explicit elevation opt-ins, and metadata audit constrain the exposed Ops surface. Allowing an elevated general interpreter is explicit broad local-host authority, not a sandbox and not production JIT authority.
- ADR-0049 adds a third developer-host Desktop MCP. It uses a pinned WinApp runtime, intended interactive/non-elevated Windows session, fresh HWND/process authorization, semantic selectors, explicit capture/UIA/mouse/keyboard/system-key flags, bounded transient screenshots, and metadata-only audit; visible UI can still contain sensitive data and Desktop is not a credential/UAC/production bypass.
- Blocking functions need concrete executable CI/release/scheduled jobs before compliance is claimed.
