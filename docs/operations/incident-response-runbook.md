# Production Incident Response Runbook

## Purpose

This runbook defines minimum production incident behavior. Service-specific runbooks may add detail but cannot weaken it. ADR-0042 selects `production-single-server`; incidents distinguish expected non-HA outage from unsafe security/correctness behavior.

Full-host recovery uses `../runbooks/production-cold-dr.md`. ADR-0043 owns network/client trust. ADR-0044 owns ordinary observability behavior. ADR-0045 owns DevSecOps secret/source/dependency-advisory/final-artifact security control responsibilities.

## 1. Incident priorities

Protect in this order:

1. human safety/legal/security obligations;
2. identity/authorization/tenant/privacy correctness;
3. data durability/recoverability;
4. containment of compromise/unsafe change;
5. authoritative audit/recovery evidence;
6. service availability/performance;
7. ordinary observability/developer velocity.

Never restore availability by enabling a prohibited security/correctness bypass.

## 2. Severity examples

**SEV-1** includes credible credential/private-key/secret compromise, cross-tenant exposure, unauthorized admin activity, widespread irreversible business-data corruption/loss, loss of critical recoverability, unbounded/unsafe quota bypass, forged client-address trust, public SSH exposure, or a failure that appears to require disabling OpenBao/Kyverno/Ambient/WAF/MFA/fail-closed controls.

A total single-server outage with recovery outside tested bounds can also be SEV-1.

**SEV-2** includes major dependency/service outage, significant SLO burn, Redis/Kafka/PostgreSQL degradation, WAF/edge degradation, local observability outage, or recovery degradation without confirmed security/data compromise.

Severity may increase as evidence changes.

## 3. Immediate workflow

1. declare incident/commander;
2. record start/profile/surfaces/impact;
3. preserve authoritative audit/forensics/backup evidence;
4. stop unsafe deploy/migration/automation;
5. contain compromise/data-loss blast radius;
6. determine expected profile outage vs violated security/correctness contract;
7. use local observability when available and external host monitor when local stack is unavailable;
8. restore through documented safe path;
9. verify security/data/telemetry state before traffic;
10. record timeline/decisions/evidence/follow-up owners.

Do not delete/rotate evidence before retention/forensic obligations are understood.

## 4. Whole-host incident

Single-server host/node/kernel/storage failure may stop the complete platform **and local observability stack**.

Use `production-cold-dr.md`. Required properties include:

- external black-box monitor detects the host/public-path outage while local monitoring is unavailable;
- clean host + WireGuard/FIDO2/JIT management path; no public SSH fallback;
- K3s/Calico/security/observability reconstruction;
- unchanged OpenBao recovery;
- PostgreSQL PITR + DB/RLS/role validation;
- Redis/Kafka recovery semantics;
- quota host-time/common-clock/cardinality state revalidation;
- edge/WAF/client-address trust;
- HIBP-derived Compromised Password corpus freshness/integrity;
- erasure/legal-hold reconciliation;
- measured RPO/RTO.

If clean GitOps rebuild is safer than restoring Kubernetes operational state, prefer it.

## 5. PostgreSQL

For single-server physical recovery:

1. stop writes/traffic as needed;
2. select approved Barman base backup/WAL target;
3. restore complete shared cluster in isolation;
4. verify integrity/Flyway/roles/RLS/erasure/legal-hold;
5. for service-specific recovery, extract only required DB;
6. import under controlled maintenance;
7. prove other current DBs were not destructively restored;
8. reopen only after application/security validation.

`pg_dump + cron` is not primary recovery. Failed shared-cluster restore triggers current promotion freeze/escalation.

HA uses current dedicated cluster procedures.

## 6. Redis / semantic quota incident

Single-server Redis has no failover.

During outage, corruption, clock anomaly, or capacity/cardinality pressure:

- never bypass quota/session fail-closed behavior;
- never reconstruct authenticated state from cookies;
- never replace trusted client address with caller forwarding header/proxy address;
- preserve exact `/32`/`/128` hard identity and separate `/24`/`/64` aggregate pressure semantics;
- use AOF/restart only when safe;
- reauthenticate when session state is uncertain/lost;
- verify TLS/ACL/`noeviction`/AOF after recovery;
- verify host time synchronization + app/Redis skew;
- if Clock Safety Guard tripped, require ADR-0024 60-second stable re-arm before quota-protected traffic;
- inspect active-bucket cardinality/new-allocation/cleanup rates and Redis memory reserve;
- if allocation/memory is unsafe, return `QUOTA_CAPACITY_UNHEALTHY`; do not evict existing security state or fabricate a normal 429 denial;
- investigate upstream coarse controls and shared-host IO/memory pressure.

A high-cardinality attack may cause intentional fail-closed unavailability; it may not justify fail-open behavior.

## 7. Kafka

Single-server RF1 has no broker redundancy.

- Kafka outage is transport outage, not business-state loss;
- Outbox/service state remains authority;
- do not disable idempotency/Inbox to catch up;
- rebuild desired broker/topic/ACL config from GitOps;
- replay/reconstruct critical events from service-owned evidence;
- verify lag/consumer state before reopening dependent async workflows;
- record broker-local loss window.

## 8. Istio/Kyverno

Ambient failure/pressure does not authorize plaintext/direct bypass. Restore component or increase capacity; if required security cannot fit, production stays blocked.

Kyverno outage does not authorize protected new/update admission. Do not switch critical policy to audit-only. Restore current CEL-based policy engine/controls or use a separately reviewed emergency path if one exists.

A legacy `ClusterPolicy`/`CleanupPolicy` manifest discovered in greenfield production desired state is a deployment-policy defect and blocks the affected change until corrected or explicitly migration-excepted.

## 9. Public edge/client address

When L4/Traefik/WAF/client-address trust is uncertain:

- do not enable insecure PROXY/forwarded trust;
- do not trust public forwarding/private client-IP headers;
- do not bypass WAF;
- verify external-L4 source CIDRs/PROXY v2;
- verify Caddy strict proxy parsing/internal overwrite;
- verify BFF exact client address;
- keep quota-required operations fail-closed until trusted identity valid;
- preserve only PII-safe diagnostics; raw client IP requires explicit forensic purpose.

This is a security-control incident, not only telemetry degradation.

## 10. Observability incident

Ordinary telemetry components are not business/security authority.

If Collector, Prometheus, Loki, Tempo, Grafana, or Alertmanager is degraded/unavailable:

- do not make ordinary business requests fail solely because telemetry export/storage is down;
- do not switch applications to synchronous direct remote logging;
- keep Collector queues/memory bounded; allow defined best-effort drop/sampling behavior rather than unbounded memory/disk growth;
- alert on exporter/queue/drop/cardinality/storage pressure through still-available paths;
- reduce only safe ordinary telemetry retention/sampling/cardinality when necessary;
- required security/privileged audit remains separate durable/off-host evidence and must not be abandoned;
- do not expose OTLP or management endpoints publicly as a recovery shortcut;
- do not broaden Collector host filesystem/network/privilege;
- if the complete host is lost, rely on the independent external black-box monitor for initial availability signal.

If the external monitor also fails during a total-host incident, record a monitoring control failure and block production readiness until restored/tested.

## 11. OpenBao — unchanged

Use current OpenBao 2.6.1 Shamir/Raft/PVC/encrypted-snapshot/restore/unseal/ESO procedures. Normal workloads continue only within existing validated local-key/stale-source bounds.

Never respond to OpenBao outage by removing/replacing OpenBao, putting secrets in Git/values/images/logs/traces/metrics, disabling validation, or creating plaintext fallback.

## 12. Compromised Password corpus incident

If corpus source/provenance/freshness/integrity/schema/cardinality compatibility is uncertain:

- stop treating the dataset as valid;
- keep password screening unavailable/fail closed;
- do not call HIBP at runtime as fallback;
- rebuild/redeploy only from approved official HIBP SHA-1 acquisition evidence;
- verify dataset age <=35 days, positive-count semantics, complete-corpus compatibility bound, and content digest before readiness;
- verify SHA-1 remains screening-only and no password/full screening digest entered telemetry.

## 13. Human privileged access

Single-server normal path remains:

```text
approved device -> WireGuard -> management address -> OpenSSH/FIDO2 -> JIT
```

Public SSH remains denied. No shared peer/password/root/shared SSH fallback. Emergency elevation is attributable/incident-linked/time-bounded; provider console is break glass only. OS/`sudo`/Kubernetes/database privileged actions remain audited off-host. Shell history is not authoritative evidence.

If authoritative audit export is impaired, declare it; do not silently abandon audit requirements.

## 14. Security/privacy/committed-secret/host compromise

For suspected credential/token/private-key/PII/tenant/host compromise:

- contain first;
- preserve audit/forensics;
- rotate/revoke affected peers/SSH/JIT/sessions/credentials/tokens/keys through owners;
- do not attach raw secrets/unnecessary PII to incident tools;
- verify tenant/Authorization/RLS after containment;
- treat single-server root compromise as broad local trust failure and rebuild from trusted artifacts;
- coordinate legal/privacy obligations;
- apply erasure/legal-hold on restored history.

For a Gitleaks or equivalent committed-secret finding:

1. treat the credential as exposed when a real secret may have entered any pushed/shared Git history;
2. revoke/rotate before relying on source/history cleanup;
3. preserve exact commit/path/rule/fingerprint evidence without copying the secret value into incident systems;
4. determine repository clones/forks/CI logs/artifacts/caches or downstream systems that may have retained the secret;
5. remove the secret from current source and perform approved Git-history rewrite only when operational/legal/audit impact is understood;
6. coordinate force-push/history replacement when required; do not destroy required forensic evidence;
7. verify Gitleaks current-tree and protected-history scans pass afterward;
8. verify the replacement credential never entered Git, CI output, logs, traces, metrics, images, or values.

A Gitleaks allow-list is not remediation for a live credential.

## 15. DevSecOps/supply-chain incident

If Semgrep, Gitleaks, OSV-Scanner, Syft, Grype, Cosign, or Kyverno required evidence is unavailable, stale where freshness applies, corrupt, or inconsistent:

- stop the merge/promotion/release boundary that depends on the failed evidence;
- do not disable the required gate, broaden suppression, or substitute stale evidence beyond policy;
- preserve exact tool version/checksum/digest, scanner/feed/database version/timestamp, dependency evidence, image digest, SBOM digest, signature/provenance, and finding/exception records;
- distinguish an OSV declared/locked dependency finding from a Grype final-image finding; both route to the owning artifact/service team, but Grype remains the release/deployed-artifact vulnerability authority under ADR-0035/0038;
- use ADR-0035/0038 for final-artifact vulnerability-feed/scanner exceptions and response;
- use ADR-0017 for signature/provenance/SBOM/admission failures;
- use ADR-0045 for tool responsibility and secret/SAST/dependency-advisory/final-artifact chain;
- do not claim final-image safety because OSV lockfile scanning passed;
- do not add Trivy/OWASP Dependency-Check as an emergency competing authority without a reviewed distinct-coverage decision;
- do not infer Semgrep Secrets/Supply Chain product coverage from repository Semgrep CLI.

A newly disclosed OSV dependency finding can block the applicable source/merge boundary or trigger remediation even when the source did not change. It does not remove the requirement for final-image Syft/Grype release evidence and continuous deployed-digest correlation.

## 16. Deployment/migration incident

- stop further rollout;
- preserve exact artifact/config/schema/telemetry-policy versions;
- determine safe state-compatible rollback/fail-forward;
- never edit executed Flyway migrations;
- never perform unsupported DB downgrade;
- verify Kyverno CEL/render policy and signed-artifact gates;
- verify telemetry changes did not introduce public endpoints, broad hostPath, secret leakage, or cardinality explosions.

## 17. Recovery verification before traffic

Verify applicable:

- exact artifact/profile versions;
- health/readiness without masking;
- authentication/MFA/Authorization;
- workload identity/mTLS/NetworkPolicy;
- PostgreSQL/Flyway/RLS/role isolation;
- Redis/Kafka + quota time/capacity/network semantics;
- Gitleaks/Semgrep source-secret gates, current OSV declared/locked dependency advisory state, and final-image Syft/Grype/Cosign evidence when a release is involved;
- Kyverno admission + edge/WAF/client-address anti-spoofing;
- OpenBao/secret delivery;
- HIBP corpus freshness/integrity;
- WireGuard/FIDO2/JIT + off-host audit;
- Day-One logs/metrics/traces privacy/correlation/backend-failure behavior;
- external host monitor health and evidence it detected total-host loss;
- erasure/legal-hold when restored state involved;
- critical smoke/browser journey;
- no unresolved replay/data/backlog corruption.

Full cold recovery requires the traffic-enable record in the cold-DR runbook.

## 18. Post-incident

Record root cause/contributors, expected non-HA outage vs contract violation, detected/actual RPO/RTO/downtime, detection source, safe recovery evidence, missed threat/alert/runbook/test/network/quota/telemetry/capacity/supply-chain assumption, remediation owner/deadline, and whether single-server remains acceptable.

Repeated host incidents, unacceptable downtime, unsafe quota/telemetry capacity pressure, broad root risk, repeated secret/dependency-advisory/supply-chain control failures, or unacceptable recovery RTO trigger architecture/process/capacity review.