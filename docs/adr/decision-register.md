# Architecture Decision Register — Current Effective Decisions

- **Mode:** current-only
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Normalized:** 2026-08-13

This register contains only ADRs that still carry effective scope. Obsolete predecessor decisions and raw historical notes are intentionally absent. Current-state architecture/service/engineering documents are implementation-facing authority; retained ADRs capture durable current decisions useful for review.

## Identity, tenancy, sessions, MFA, and erasure

| ADR | Current scope |
| --- | --- |
| [ADR-0034](0034-persist-registration-locale-and-reuse-it-for-resend.md) | Registration locale persistence/resend behavior and locale migration safety |
| [ADR-0035](0035-enable-identity-registration-runtime.md) | Identity registration runtime/composition |
| [ADR-0038](0038-define-identity-tenant-session-external-and-mfa-v1.md) | Current tenant lifecycle, token/refresh, external identity, TOTP/SMS MFA runtime |
| [ADR-0052](0052-define-identity-jwt-signing-key-lifecycle-v1.md) | JWT signing/verifier key lifecycle |
| [ADR-0058](0058-define-data-subject-erasure-execution-and-evidence-v1.md) | Cross-service irreversible erasure execution/evidence + logical-deletion/retention baseline |

Core global-user/tenant-membership, logical deletion/legal hold, credential, and package rules are represented directly in current-state architecture/coding documents rather than predecessor ADRs.

## Notification

| ADR | Current scope |
| --- | --- |
| [ADR-0029](0029-finalize-notification-v1-handoff-and-semantic-contract.md) | Durable semantic contract, fingerprinting, exact-content escrow, callbacks, locale/templates/recipient/retention |
| [ADR-0030](0030-define-notification-v1-runtime.md) | Provider/persistence/dispatch/HA/operations runtime |
| [ADR-0036](0036-use-versioned-database-notification-templates-and-liara-email.md) | PostgreSQL versioned templates and Liara Email |
| [ADR-0043](0043-use-local-notification-delivery-key-ring-in-v1.md) | Local AES-GCM delivery key-ring runtime |
| [ADR-0047](0047-simplify-notification-clock-safety-and-remove-dispatch-fence-v1.md) | PostgreSQL-authoritative time/dispatch-commit safety; no bespoke clock/fence control plane |
| [ADR-0049](0049-select-ippanel-webservice-sms-for-iran.md) | IPPanel Webservice production SMS for Iran |

## Authorization, quotas, and dependency failure semantics

| ADR | Current scope |
| --- | --- |
| [ADR-0039](0039-use-online-authorization-without-cache-or-kafka-v1.md) | One authoritative online no-cache/no-Kafka/no-retry `CheckPermission` model |
| [ADR-0054](0054-harden-semantic-quota-time-safety-v1.md) | Complete current service-owned semantic quota model: topology, atomicity, pseudonymization, anti-lockout, policy, dual-clock/TTL safety |
| [ADR-0055](0055-define-synchronous-dependency-failure-containment-v1.md) | Synchronous dependency timeout/bulkhead/breaker/fallback rules |
| [ADR-0056](0056-harden-online-authorization-overload-and-slo-v1.md) | Authorization runtime SLO, capacity, deployment, safe prechecks, overload isolation, and fail-closed breaker baseline |
| [ADR-0062](0062-finalize-authorization-slo-alerting-and-breaker-recovery-v1.md) | Authorization SLI interpretation, paired burn alerts, breaker-opening criteria, and health-endpoint non-authority |
| [ADR-0063](0063-define-operation-level-dependency-criticality-and-degradation-v1.md) | Operation-level dependency criticality/degradation/fallback semantics and current-policy references |
| [ADR-0066](0066-refine-authorization-breaker-recovery-and-dependency-policy-governance-v1.md) | De-correlated OPEN timing, serialized real HALF_OPEN recovery, and machine-readable dependency-policy governance |

## Browser, edge, workload identity, logging, supply chain, and access

| ADR | Current scope |
| --- | --- |
| [ADR-0024](0024-select-dedicated-caddy-coraza-edge-waf.md) | Dedicated Caddy/Coraza WAF topology |
| [ADR-0025](0025-define-production-istio-trust-and-enrollment.md) | Istio trust domain/CA hierarchy/enrollment |
| [ADR-0045](0045-define-web-bff-browser-oidc-and-session-security-v1.md) | Browser OIDC/BFF session/CSRF/CORS security |
| [ADR-0046](0046-enforce-signed-artifacts-and-provenance-at-admission-v1.md) | Signed image/provenance/SBOM admission plus admission-policy authoring and policy-engine network/SSRF safety |
| [ADR-0059](0059-require-upstream-volumetric-ddos-protection-v1.md) | Upstream L3/L4 volumetric-DDoS protection |
| [ADR-0060](0060-define-production-human-jit-access-v1.md) | Teleport JIT privileged production access |
| [ADR-0061](0061-enforce-pii-safe-logging-detection-pipeline-v1.md) | PII-safe logging/redaction/canary/runtime detection |
| [ADR-0065](0065-automate-sbom-vulnerability-response-and-deployment-gates-v1.md) | Continuous SBOM/vulnerability response and deployment gates |
| [ADR-0068](0068-harden-vulnerability-exceptions-threat-intelligence-and-ownership-v1.md) | Vulnerability exception expiry, threat intelligence, remediation ownership |

## Data, Kafka, recovery, and PostgreSQL fleet

| ADR | Current scope |
| --- | --- |
| [ADR-0026](0026-use-git-and-buf-without-runtime-schema-registry-in-v1.md) | Git + Buf contract compatibility; no runtime Schema Registry in v1 |
| [ADR-0027](0027-define-initial-cold-disaster-recovery.md) | Cold-DR objectives/recovery model |
| [ADR-0028](0028-define-production-slo-classes-and-error-budgets.md) | Production SLO classes, error budgets, release-freeze policy |
| [ADR-0044](0044-define-kafka-production-durability-and-rebuildable-dr-v1.md) | Kafka KRaft durability and rebuildable DR |
| [ADR-0048](0048-adopt-cloudnativepg-ha-and-barman-backups-v1.md) | CloudNativePG synchronous HA and Barman backup/PITR mechanics |
| [ADR-0057](0057-require-production-postgresql-physical-isolation-and-tenant-rls-v1.md) | Complete current service database/cluster/role/Flyway/cross-service-SQL isolation + forced tenant RLS + backup isolation |
| [ADR-0064](0064-standardize-dedicated-cloudnativepg-fleet-operations-v1.md) | Reusable dedicated CloudNativePG fleet operations |
| [ADR-0067](0067-standardize-postgresql-restore-evidence-and-upgrade-safety-v1.md) | Restore evidence and DB upgrade/rollback safety |

## Platform/GitOps/runtime compatibility

| ADR | Current scope |
| --- | --- |
| [ADR-0037](0037-keep-v1-gitops-in-platform-and-pin-openbao.md) | Current in-repository GitOps + OpenBao topology/secret model |
| [ADR-0050](0050-pin-production-platform-compatibility-and-cni-v1.md) | Production compatibility authority/upgrade governance and Calico selection |
| [ADR-0051](0051-define-self-hosted-kubernetes-ha-topology-v1.md) | Self-hosted Kubernetes HA topology |

## Java engineering

| ADR | Current scope |
| --- | --- |
| [ADR-0069](0069-standardize-java-coding-and-executable-quality-gates-v1.md) | Canonical Java coding standard and executable quality-gate architecture |

Package structure, constructor injection, Domain/JPA separation, immutable artifact promotion, container hardening, and Helm migration discipline are canonical in `../engineering/coding-standards.md`; executable enforcement belongs in `../engineering/build-and-ci-quality-enforcement.md`.

## Review rule

For implementation, select only applicable current ADRs from this register plus matching current-state documents. Deleted predecessor ADR IDs MUST NOT appear in new code, documentation, tests, or runbooks. If a retained ADR contains a stale clause, normalize it or move the effective rule to the current canonical document in the same PR before implementation relies on it.
