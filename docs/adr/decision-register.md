# Architecture Decision Register — Current Effective Decisions

- **Mode:** current-only
- **Effective policy:** `../engineering/current-only-documentation-policy.md`
- **Last normalized:** 2026-08-13

This register indexes only ADRs that still contain effective architecture scope. Completely obsolete decision records are not retained. Current-state architecture documents remain the implementation-facing source of truth; retained ADRs provide the decision constraints/rationale still needed to implement and review that state.

When a retained ADR and current-state document disagree, correct the stale source in the same PR before implementation depends on it. Do not reconstruct behavior from deleted decision history.

## Core service, tenancy, identity, and engineering

| ADR | Current scope |
| --- | --- |
| [ADR-0001](0001-bootstrap-identity-service-first.md) | Independent deployable service ownership/bootstrap discipline |
| [ADR-0002](0002-select-initial-multi-tenancy-model.md) | Tenant isolation model and tenant-scoped persistence/access rules |
| [ADR-0003](0003-define-logical-deletion-retention-and-erasure.md) | Logical deletion, retention, legal hold, and erasure baseline |
| [ADR-0004](0004-define-authorization-ownership-and-evaluation.md) | Resource-owner authorization responsibilities and decision semantics |
| [ADR-0006](0006-define-identity-registration-external-identity-and-mfa.md) | Registration, external identity, password, and MFA architecture |
| [ADR-0007](0007-standardize-feature-first-nature-separated-packages.md) | Feature-first/nature-separated package architecture |
| [ADR-0008](0008-define-secure-registration-verification-delivery.md) | Secure verification/recovery delivery invariants |
| [ADR-0009](0009-define-provider-neutral-secret-delivery-and-key-lifecycle.md) | Provider-neutral secret-delivery/key-lifecycle invariants |
| [ADR-0034](0034-persist-registration-locale-and-reuse-it-for-resend.md) | Registration locale persistence/resend semantics |
| [ADR-0035](0035-enable-identity-registration-runtime.md) | Identity registration runtime enablement/current boundaries |
| [ADR-0038](0038-define-identity-tenant-session-external-and-mfa-v1.md) | Identity tenant/session/external-login/MFA v1 runtime |
| [ADR-0052](0052-define-identity-jwt-signing-key-lifecycle-v1.md) | JWT signing/verifier key lifecycle |
| [ADR-0058](0058-define-data-subject-erasure-execution-and-evidence-v1.md) | End-to-end erasure execution/evidence |
| [ADR-0069](0069-standardize-java-coding-and-executable-quality-gates-v1.md) | Java coding rules and executable quality-gate architecture |

## Notification

| ADR | Current scope |
| --- | --- |
| [ADR-0010](0010-extract-human-channel-delivery-into-notification-service.md) | Notification bounded-context ownership |
| [ADR-0012](0012-define-durable-notification-handoff-and-result-callback.md) | Durable idempotent handoff, encrypted responsibility transfer, result callback |
| [ADR-0013](0013-define-notification-lifecycle-and-delivery-evidence.md) | Canonical Notification lifecycle and evidence semantics |
| [ADR-0014](0014-define-notification-provider-outcomes.md) | Provider outcome taxonomy/ambiguity semantics |
| [ADR-0015](0015-define-notification-retry-and-replay-policy.md) | Provider retry/replay policy |
| [ADR-0016](0016-anchor-notification-deadlines-at-acceptance.md) | Immutable delivery deadlines anchored at acceptance |
| [ADR-0017](0017-use-postgresql-authoritative-notification-time.md) | PostgreSQL-authoritative Notification lifecycle time |
| [ADR-0029](0029-finalize-notification-v1-handoff-and-semantic-contract.md) | Current semantic contract, fingerprinting, callbacks, locale/template/recipient/retention rules |
| [ADR-0030](0030-define-notification-v1-runtime.md) | Current provider/persistence/dispatch/HA/operations runtime |
| [ADR-0036](0036-use-versioned-database-notification-templates-and-liara-email.md) | PostgreSQL versioned templates and Liara Email |
| [ADR-0043](0043-use-local-notification-delivery-key-ring-in-v1.md) | Local AES-GCM delivery key-ring runtime |
| [ADR-0047](0047-simplify-notification-clock-safety-and-remove-dispatch-fence-v1.md) | Current clock/time/dispatch-commit safety model |
| [ADR-0049](0049-select-ippanel-webservice-sms-for-iran.md) | IPPanel Webservice production SMS for Iran |

## Authorization and synchronous dependency failure

| ADR | Current scope |
| --- | --- |
| [ADR-0039](0039-use-online-authorization-without-cache-or-kafka-v1.md) | One online no-cache/no-Kafka/no-retry `CheckPermission` model |
| [ADR-0055](0055-define-synchronous-dependency-failure-containment-v1.md) | Synchronous dependency timeout/bulkhead/breaker/fallback semantics |
| [ADR-0056](0056-harden-online-authorization-overload-and-slo-v1.md) | Authorization runtime SLO, capacity, deployment, overload, pre-checks, breaker baseline |
| [ADR-0062](0062-finalize-authorization-slo-alerting-and-breaker-recovery-v1.md) | Authorization burn alerts and breaker recovery criteria |
| [ADR-0063](0063-define-operation-level-dependency-criticality-and-degradation-v1.md) | Operation-level criticality/degradation/fallback registry semantics |
| [ADR-0066](0066-refine-authorization-breaker-recovery-and-dependency-policy-governance-v1.md) | Authorization breaker de-correlation/recovery and dependency-policy governance |

## Security quotas, browser security, logging, edge, and human access

| ADR | Current scope |
| --- | --- |
| [ADR-0041](0041-define-semantic-quotas-and-service-owned-redis-enforcement-v1.md) | Service-owned atomic semantic quota enforcement |
| [ADR-0054](0054-harden-semantic-quota-time-safety-v1.md) | Current quota time/skew/TTL safety hardening |
| [ADR-0045](0045-define-web-bff-browser-oidc-and-session-security-v1.md) | BFF browser OIDC/session/CSRF/CORS security |
| [ADR-0024](0024-select-dedicated-caddy-coraza-edge-waf.md) | Dedicated Caddy/Coraza WAF topology |
| [ADR-0059](0059-require-upstream-volumetric-ddos-protection-v1.md) | Upstream volumetric DDoS protection requirement |
| [ADR-0060](0060-define-production-human-jit-access-v1.md) | Teleport JIT production human access |
| [ADR-0061](0061-enforce-pii-safe-logging-detection-pipeline-v1.md) | PII-safe logging, redaction, canary/runtime detection |

## Data, Kafka, recovery, and database fleet

| ADR | Current scope |
| --- | --- |
| [ADR-0026](0026-use-git-and-buf-without-runtime-schema-registry-in-v1.md) | Git + Buf compatibility; no runtime Schema Registry in v1 |
| [ADR-0027](0027-define-initial-cold-disaster-recovery.md) | Cold-DR objectives and cross-component recovery model |
| [ADR-0044](0044-define-kafka-production-durability-and-rebuildable-dr-v1.md) | Kafka KRaft production durability and rebuildable DR |
| [ADR-0048](0048-adopt-cloudnativepg-ha-and-barman-backups-v1.md) | CloudNativePG HA, synchronous durability, Barman backups/PITR |
| [ADR-0053](0053-enforce-dedicated-postgresql-database-per-microservice-v1.md) | Per-service database/credential/Flyway ownership and cross-service DB prohibitions |
| [ADR-0057](0057-require-production-postgresql-physical-isolation-and-tenant-rls-v1.md) | Dedicated production physical clusters + forced tenant RLS/runtime-role restrictions |
| [ADR-0064](0064-standardize-dedicated-cloudnativepg-fleet-operations-v1.md) | Reusable dedicated CloudNativePG fleet operations |
| [ADR-0067](0067-standardize-postgresql-restore-evidence-and-upgrade-safety-v1.md) | Restore evidence and database upgrade/rollback safety |

## Platform, GitOps, mesh, supply chain, and production topology

| ADR | Current scope |
| --- | --- |
| [ADR-0025](0025-define-production-istio-trust-and-enrollment.md) | Istio trust domain/CA hierarchy/enrollment model |
| [ADR-0037](0037-keep-v1-gitops-in-platform-and-pin-openbao.md) | Current in-repository GitOps, Argo CD, OpenBao topology/secret model |
| [ADR-0046](0046-enforce-signed-artifacts-and-provenance-at-admission-v1.md) | Signed image/provenance/SBOM admission |
| [ADR-0050](0050-pin-production-platform-compatibility-and-cni-v1.md) | Production compatibility baseline and Calico selection |
| [ADR-0051](0051-define-self-hosted-kubernetes-ha-topology-v1.md) | Self-hosted Kubernetes active-cluster HA topology |
| [ADR-0065](0065-automate-sbom-vulnerability-response-and-deployment-gates-v1.md) | Continuous SBOM/vulnerability response and deployment gates |
| [ADR-0068](0068-harden-vulnerability-exceptions-threat-intelligence-and-ownership-v1.md) | Vulnerability exception expiry, threat intelligence, and remediation ownership |

## SLO/release policy

| ADR | Current scope |
| --- | --- |
| [ADR-0028](0028-define-production-slo-classes-and-error-budgets.md) | Production SLO classes, error budgets, and release-freeze policy |

## Review rule

For implementation, use this register to select only the current ADRs that affect the task, then read the matching current-state architecture documents and executable engineering/CI rules. Deleted historical ADRs are not part of the architecture contract and MUST NOT be referenced by new code, documentation, tests, or runbooks.
