# Architecture Decision Register

| ADR                                                                       | Decision                                                                  | Status   | Required before                                      |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------- | -------- | ---------------------------------------------------- |
| [0001](0001-bootstrap-identity-service-first.md)                          | Bootstrap Identity Service first; use `com.sajtech` namespace             | Accepted | First service build and Spring Boot patch pin        |
| [0002](0002-select-initial-multi-tenancy-model.md)                        | Global users; shared tenant schema in service-owned database              | Accepted | Identity persistence and schema design               |
| [0003](0003-define-logical-deletion-retention-and-erasure.md)             | Logical deletion, restoration, retention, purge, and legal holds          | Accepted | Deletion, restoration, and retention implementation  |
| [0004](0004-define-authorization-ownership-and-evaluation.md)             | Tenant roles, membership grants and denies, distributed enforcement       | Accepted | Role, permission, and policy persistence             |
| [0005](0005-define-authorization-context-revocation-and-freshness.md)     | Versioned snapshots, invalidation, revocation, and freshness              | Accepted | Runtime authorization propagation                    |
| [0006](0006-define-identity-registration-external-identity-and-mfa.md)    | User activation, Google OIDC, profile, verification, and MFA              | Accepted | Identity user persistence and registration           |
| [0007](0007-standardize-feature-first-nature-separated-packages.md)       | Feature-first and nature-separated Java package structure                 | Accepted | Domain and application implementation                |
| [0008](0008-define-secure-registration-verification-delivery.md)          | Secure registration verification delivery constraints and runtime gating  | Accepted | Production registration and resend gRPC runtime      |
| [0009](0009-define-provider-neutral-secret-delivery-and-key-lifecycle.md) | Provider-neutral secret delivery and cryptographic key lifecycle          | Accepted | Production key-loading and escrow foundation         |
| [0010](0010-extract-human-channel-delivery-into-notification-service.md)  | Extract human-channel delivery into internal Notification Service         | Accepted | Notification Service implementation                  |
| [0011](0011-select-openbao-and-argo-cd.md)                                | Select initial self-hosted OpenBao and Argo CD operating models           | Accepted | Production secret and GitOps automation              |
| [0012](0012-define-durable-notification-handoff-and-result-callback.md)   | Durable notification handoff, retry escrow, result callback, and erasure  | Accepted | Migrating Identity delivery to Notification Service  |
| [0013](0013-define-notification-lifecycle-and-delivery-evidence.md)       | Canonical Notification lifecycle and authenticated delivery evidence      | Accepted | Notification persistence, callbacks, and adapters    |
| [0014](0014-define-notification-provider-outcomes.md)                     | Provider outcome taxonomy, transitions, and retry-exhaustion category     | Accepted | Notification state-machine implementation            |
| [0015](0015-define-notification-retry-and-replay-policy.md)               | Notification retry, deadline, receipt observation, and replay policy      | Accepted | Notification worker and scheduler implementation     |
| [0016](0016-anchor-notification-deadlines-at-acceptance.md)               | Immutable acceptance time, effective deadline, and expiry validation      | Accepted | Notification contract and persistence implementation |
| [0017](0017-use-postgresql-authoritative-notification-time.md)            | PostgreSQL-authoritative Notification time, precision, and clock health   | Accepted | Notification contract and lifecycle implementation   |
| [0018](0018-define-notification-critical-clock-degraded-mode.md)          | Critical-clock degraded mode and deadline-safe worker recovery            | Accepted | Notification worker and scheduler implementation     |
| [0019](0019-derive-notification-clock-health-from-chrony.md)              | Chrony-derived clock health, hysteresis, and primary binding              | Accepted | Notification clock-health runtime implementation     |
| [0020](0020-use-grpc-and-istio-for-notification-clock-health.md)          | Pull gRPC clock health with monotonic freshness and Istio authorization   | Accepted | Notification clock-health contract and deployment    |
| [0021](0021-bind-notification-clock-health-to-postgresql-primary-pod.md)  | PostgreSQL primary verification, sidecar, and direct Pod-IP binding       | Accepted | Notification failover-safe clock-health integration  |
| [0022](0022-use-database-backed-notification-dispatch-fence.md)           | PostgreSQL epoch and database-backed Notification dispatch fencing        | Accepted | Notification provider dispatch implementation        |
| [0023](0023-bound-notification-clock-health-cycle-timeouts.md)            | Bounded database, agent, and overall Notification clock-health cycle      | Accepted | Notification clock-health runtime implementation     |
| [0024](0024-select-dedicated-caddy-coraza-edge-waf.md)                    | Dedicated Caddy, Coraza v3, and CRS 4.x LTS production WAF tier           | Accepted | Production edge deployment                           |
| [0025](0025-define-production-istio-trust-and-enrollment.md)              | Production Istio trust domain, CA hierarchy, and namespace enrollment     | Accepted | Production mesh deployment                           |
| [0026](0026-use-git-and-buf-without-runtime-schema-registry-in-v1.md)     | Git and Buf `FILE` governance without a runtime v1 Schema Registry        | Accepted | Protobuf contract and event governance               |
| [0027](0027-define-initial-cold-disaster-recovery.md)                     | Cold DR, RPO/RTO, encrypted backup retention, and restore exercises       | Accepted | Production data-store deployment                     |
| [0028](0028-define-production-slo-classes-and-error-budgets.md)           | Production SLO classes and error-budget release policy                    | Accepted | Production readiness                                 |
| [0029](0029-finalize-notification-v1-handoff-and-semantic-contract.md)    | Notification v1 handoff, callback, semantic contract, auth, and escrow    | Accepted | Notification contracts and caller handoff            |
| [0030](0030-define-notification-v1-runtime.md)                            | Notification providers, persistence, workers, webhooks, and operations    | Accepted | Notification provider runtime and cutover            |
| [0031](0031-finalize-notification-clock-agent-and-fence-runtime.md)       | Notification clock agent, identity query, and fence refinements           | Accepted | Notification clock/fence runtime                     |
| [0032](0032-finalize-notification-package-and-select-ippanel-sms.md)      | Notification package and IPPanel Pattern SMS for Iran                     | Accepted | Notification foundation and Iran SMS adapter         |
| [0033](0033-defer-sms-provider-and-use-local-logging-adapter.md)          | Defer production SMS provider; use a local-only safe logging adapter      | Accepted | Local Notification SMS adapter                       |
| [0034](0034-persist-registration-locale-and-reuse-it-for-resend.md)       | Persist explicit registration locale and reuse it for resend              | Accepted | Identity registration-to-Notification handoff        |
| [0035](0035-enable-identity-registration-runtime.md)                      | Enable Identity registration gRPC, callback, and durable handoff runtime  | Accepted | Identity registration runtime                         |
| [0036](0036-use-versioned-database-notification-templates-and-liara-email.md) | Versioned database templates and Liara SMTP Email                     | Accepted | Notification templates and Email runtime              |
| [0037](0037-keep-v1-gitops-in-platform-and-pin-openbao.md)                | In-repository GitOps and OpenBao 2.6.1                                    | Accepted | Deployment and secrets platform                       |
| [0038](0038-define-identity-tenant-session-external-and-mfa-v1.md)        | Tenant, sessions, Google external identity, and MFA v1                    | Accepted | Identity runtime                                      |
| [0039](0039-use-online-authorization-without-cache-or-kafka-v1.md)        | Online authorization without cache or Kafka                              | Accepted | Authorization Service and enforcement                 |
| [0040](0040-require-semantic-quota-adr-before-production.md)              | Semantic-quota gate before Identity production enablement                 | Accepted | Identity production readiness                         |

| [0041](0041-define-semantic-quotas-and-service-owned-redis-enforcement-v1.md) | Atomic service-owned semantic quotas on ACL-isolated Redis Sentinel | Accepted | ADR-0040 gated production entry points |
| [0042](0042-define-authorization-runtime-slo-capacity-and-deployment-v1.md) | Authorization online SLO, HA, capacity, and bounded single-check path | Accepted | Production online authorization |
| [0043](0043-use-local-notification-delivery-key-ring-in-v1.md) | Local mounted Notification AES-GCM key ring; remove Transit hot path | Accepted | Notification sensitive acceptance/dispatch |
| [0044](0044-define-kafka-production-durability-and-rebuildable-dr-v1.md) | Kafka KRaft durability and retained-outbox cold-DR reconstruction | Accepted | Production critical async flows |
| [0045](0045-define-web-bff-browser-oidc-and-session-security-v1.md) | BFF PKCE, session, CSRF, CORS, redirect and browser security | Accepted | Public browser production |
| [0046](0046-enforce-signed-artifacts-and-provenance-at-admission-v1.md) | Cosign signature/provenance/SBOM enforced by Kyverno admission | Accepted | Production workload admission |
| [0047](0047-simplify-notification-clock-safety-and-remove-dispatch-fence-v1.md) | Remove bespoke clock agent/fence; keep PG-authoritative deadlines and durable dispatch commit | Accepted | Notification v1 runtime |
| [0048](0048-adopt-cloudnativepg-ha-and-barman-backups-v1.md) | CloudNativePG HA, synchronous durability, Barman/WAL PITR | Accepted | Production PostgreSQL |
| [0049](0049-select-ippanel-webservice-sms-for-iran.md) | IPPanel Webservice SMS for Iran with exact Notification-rendered content | Accepted | Production SMS and SMS MFA |
| [0050](0050-pin-production-platform-compatibility-and-cni-v1.md) | Pin platform compatibility set and select Calico NetworkPolicy CNI | Accepted | Production platform release |
| [0051](0051-define-self-hosted-kubernetes-ha-topology-v1.md) | Three-node stacked Kubernetes control plane/etcd and at least three workers | Accepted | Production cluster HA |
| [0052](0052-define-identity-jwt-signing-key-lifecycle-v1.md) | Local RS256 signing key lifecycle and GitOps public verification bundle | Accepted | Production access-token issuance/verification |
| [0053](0053-enforce-dedicated-postgresql-database-per-microservice-v1.md) | Dedicated PostgreSQL database and privilege boundary per persistent microservice | Accepted | Persistent microservice production database provisioning |
| [0054](0054-harden-semantic-quota-time-safety-v1.md) | Dual-clock fail-closed semantic-quota time safety; no security reset by Redis TTL | Accepted | Production semantic quotas |
| [0055](0055-define-synchronous-dependency-failure-containment-v1.md) | Semantic circuit-breaker/bulkhead policy for synchronous dependencies | Accepted | Production synchronous service calls |
| [0056](0056-harden-online-authorization-overload-and-slo-v1.md) | Authorization prefilters, overload isolation, fail-closed breaker, revised latency SLO | Accepted | Production online authorization |
| [0057](0057-require-production-postgresql-physical-isolation-and-tenant-rls-v1.md) | Dedicated production PostgreSQL cluster per persistent service plus forced tenant RLS | Accepted | Production persistent services |
| [0058](0058-define-data-subject-erasure-execution-and-evidence-v1.md) | Cross-service irreversible erasure workflow and non-PII evidence | Accepted | Production privacy-erasure operations |
| [0059](0059-require-upstream-volumetric-ddos-protection-v1.md) | Upstream L3/L4 volumetric DDoS mitigation before origin | Accepted | Public production edge |
| [0060](0060-define-production-human-jit-access-v1.md) | Teleport Enterprise self-hosted JIT privileged human access | Accepted | Production human infrastructure access |
| [0061](0061-enforce-pii-safe-logging-detection-pipeline-v1.md) | Static, pipeline, canary, and runtime PII/secret log leakage detection | Accepted | Production telemetry and CI |
| [0062](0062-finalize-authorization-slo-alerting-and-breaker-recovery-v1.md) | Authorization burn-rate alerting and real-contract half-open recovery | Accepted | Production Authorization alerting and recovery |
| [0063](0063-define-operation-level-dependency-criticality-and-degradation-v1.md) | Operation-level dependency criticality/degradation matrix | Accepted | Production synchronous dependency design |
| [0064](0064-standardize-dedicated-cloudnativepg-fleet-operations-v1.md) | Standardized dedicated CloudNativePG fleet operations without consolidation | Accepted | Production PostgreSQL fleet operations |
| [0065](0065-automate-sbom-vulnerability-response-and-deployment-gates-v1.md) | Continuous SBOM CVE response, promotion gates, and remediation SLAs | Accepted | Production supply-chain vulnerability response |
| [0066](0066-refine-authorization-breaker-recovery-and-dependency-policy-governance-v1.md) | De-correlated Authorization breaker recovery and machine-checkable dependency-policy governance | Accepted | Production Authorization resilience and synchronous dependency governance |
| [0067](0067-standardize-postgresql-restore-evidence-and-upgrade-safety-v1.md) | Standardized PostgreSQL restore evidence, drill gates, and upgrade rollback safety | Accepted | Production PostgreSQL recovery and upgrades |
| [0068](0068-harden-vulnerability-exceptions-threat-intelligence-and-ownership-v1.md) | Vulnerability exception expiry, threat-intelligence prioritization, and dependency ownership | Accepted | Production supply-chain vulnerability operations |
| [0069](0069-standardize-java-coding-and-executable-quality-gates-v1.md) | Java coding standards plus Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions executable quality gates | Accepted | Java service implementation and CI quality enforcement |


## Current supersession summary

ADR-0032 supersedes only ADR-0030's Twilio SMS selection and its universal-provider-webhook assumption. ADR-0033 then superseded all production SMS provider choices until ADR-0049. ADR-0032's `com.sajtech.notification` package decision remains current.

ADR-0036 supersedes Git-bundled Notification templates and Amazon SES with versioned PostgreSQL templates and Liara SMTP.

ADR-0037 moves v1 GitOps desired state into this repository and pins OpenBao 2.6.1.

ADR-0039 supersedes ADR-0005's cache/Kafka runtime only; ADR-0004 authorization ownership/permission semantics remain current.

ADR-0041 resolves ADR-0040's semantic-quota architecture requirement. ADR-0042 operationalizes ADR-0039 without changing its online no-cache/no-retry semantics.

ADR-0043 supersedes Notification's OpenBao Transit hot-path requirements from ADR-0012/ADR-0029 and the corresponding ADR-0011 Transit exception. Provider-neutral mounted key lifecycle from ADR-0009 remains current.

ADR-0044 closes Kafka production durability and cold-DR reconstruction through retained transactional outbox/reconstructable source state.

ADR-0045 closes Web BFF browser/OIDC/session security. ADR-0046 closes production artifact signature/provenance admission enforcement.

ADR-0047 supersedes the current-v1 application clock-health/degraded-mode/primary-agent/fence mechanisms from ADR-0018 through ADR-0023, ADR-0031, and related ADR-0030 fence fields. ADR-0017 PostgreSQL-authoritative time, precision, and immutable deadline semantics remain current.

ADR-0048 supersedes Notification's single-primary PostgreSQL assumption and ADR-0027's weekly-full/daily-differential backup shape only. ADR-0027 RPO/RTO/PITR/retention/restore-test objectives remain current.

ADR-0049 supersedes ADR-0033's SMS deferral and ADR-0032's Pattern-provider portion. Local `LoggingSmsProviderAdapter` remains local-only and is never a production fallback.

ADR-0050 closes the primary CNI/version-compatibility decision. ADR-0011's intentionally non-HA initial Argo CD and OpenBao topologies remain current because neither is an application request-path dependency after ADR-0043; HA for either requires later evidence/ADR.
ADR-0051 closes the active-cluster control-plane availability gap with three stacked control-plane/etcd nodes and at least three workers; external etcd remains intentionally deferred to avoid unnecessary host footprint.

ADR-0052 closes the RS256 signing-key lifecycle gap with OpenBao-backed Identity private keys, pre-published local GitOps public verification bundles, 90-day rotation, and no runtime JWKS dependency.

ADR-0053 makes database-per-service explicit: every persistent microservice owns a distinct PostgreSQL database, credentials, and Flyway history. ADR-0057 later supersedes only ADR-0053's production physical-cluster sharing allowance: production persistent services use dedicated CloudNativePG clusters, and tenant-owned tables require forced RLS in addition to application isolation.

ADR-0054 supersedes ADR-0041's sole Redis-wall-clock refill/TTL-reset behavior with dual trusted clocks, fail-closed skew detection, and non-authoritative cleanup.

ADR-0055 defines semantic circuit-breaker/bulkhead failure containment without blanket retries or unsafe fallbacks. ADR-0056 applies that model to online Authorization, retains ADR-0039 no-cache/no-retry semantics, adds safe local prechecks/fair-share overload protection, and revises the paging latency SLO to p95<=100ms/p99<=200ms while retaining 75/150ms as engineering targets.

ADR-0058 operationalizes ADR-0003 with a cross-service irreversible data-subject erasure workflow, legal-hold handling, non-PII completion receipts, and mandatory re-erasure after backup restore.

ADR-0059 adds upstream L3/L4 volumetric DDoS mitigation as a production hosting requirement; ADR-0024 remains the L7 WAF decision.

ADR-0060 selects Teleport Enterprise Self-Hosted for JIT human production access with SSO, phishing-resistant MFA, approval, short-lived privilege, and session/audit evidence.

ADR-0061 turns PII-safe logging from convention into enforced prevention/detection through custom Semgrep rules, telemetry-pipeline redaction, canary tests, and runtime leakage detection.

ADR-0062 keeps ADR-0056's Authorization SLO but defines exact multi-window burn alerting and real `CheckPermission` half-open recovery; health endpoints are not breaker recovery authority.

ADR-0063 operationalizes ADR-0055 with an operation-level dependency criticality/degradation matrix; resilience semantics attach to caller-operation edges, not whole service names.

ADR-0064 keeps ADR-0057 dedicated production PostgreSQL clusters and standardizes GitOps, monitoring, backup, restore, and upgrade fleet operations instead of planning reconsolidation.

ADR-0065 extends ADR-0046 so signed CycloneDX SBOMs are continuously rescanned and Critical/High findings automatically gate promotion and route remediation.

ADR-0066 refines ADR-0062/ADR-0063 by de-correlating repeated Authorization breaker recovery, serializing half-open real-contract probes, and making the dependency policy registry machine-checkable. Tenant tier does not change authorization recovery semantics.

ADR-0067 operationalizes the restore/upgrade evidence already required by ADR-0027/ADR-0064: monthly per-service restores produce queryable RPO/RTO/integrity evidence, failed drills freeze ordinary promotion for the affected service, and irreversible PostgreSQL/database changes never use an unsafe universal automatic downgrade.

ADR-0068 extends ADR-0065 with active exception-expiry escalation, CISA KEV/advisory prioritization, targeted rescans, and deterministic accountability for direct/transitive components by deployed-artifact owner. No feed is treated as guaranteed zero-day detection.

ADR-0069 consolidates current Java coding rules and makes the enforcement/evidence baseline explicit: per-service Gradle build ownership remains ADR-0001, feature-first packages remain ADR-0007, PII logging enforcement remains ADR-0061, while Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions required checks become the standard executable quality gate. It does not revive superseded Authorization/Schema-Registry/database decisions.
