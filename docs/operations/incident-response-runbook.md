# Production Incident Response Runbook

## Purpose

This runbook defines the minimum production incident workflow. Service-specific runbooks may add detail but must not weaken these rules. ADR-0042 selects `production-single-server`; incident handling must distinguish expected non-HA outage from unsafe security/correctness behavior.

`../runbooks/production-cold-dr.md` is the full-platform cold-recovery procedure. ADR-0043 and `../architecture/network-architecture.md` define trusted client-address and management-network behavior during incidents.

## 1. Incident priorities

Protect in this order:

1. human safety/legal/security obligations;
2. identity/authorization/tenant/privacy correctness;
3. data durability/recoverability;
4. containment of compromise or unsafe change;
5. service availability/performance;
6. restoration of ordinary delivery/developer velocity.

Never restore availability by enabling a security/correctness bypass that the current architecture prohibits.

## 2. Severity examples

**SEV-1** examples:

- confirmed or credible credential/private-key/secret compromise;
- cross-tenant authorization/data exposure;
- unauthorized production administrator activity;
- irreversible or widespread mutable business-data corruption/loss;
- loss of recoverability/off-site backup evidence for critical mutable state;
- single-server host/storage failure causing complete platform outage when recovery is not immediately within tested bounds;
- forged/ambiguous client-address trust that can bypass required network abuse controls;
- public exposure of the single-server SSH management port;
- failure that requires disabling OpenBao/Kyverno/Ambient/WAF/MFA/fail-closed controls to continue service — such bypass is not approved.

**SEV-2** examples include major service/dependency outage, significant SLO burn, PostgreSQL/Redis/Kafka partial failure, WAF/edge impact, or recovery degradation without confirmed security/data compromise.

Severity can increase as evidence changes.

## 3. Immediate workflow

1. declare incident and assign incident commander;
2. record start time, affected profile/services/users/regions/surfaces;
3. preserve relevant logs/audit/evidence;
4. stop unsafe deployments/migrations/automations;
5. contain security/data-loss blast radius;
6. identify whether the symptom is expected profile availability loss or a correctness/security failure;
7. restore through the documented safe path;
8. verify service/security/data state before reopening traffic;
9. document timeline, decisions, evidence and follow-up owners.

Do not delete/rotate evidence until retention/forensics requirements are understood.

## 4. Single-server whole-host incident

A host/node/kernel/storage failure in `production-single-server` can stop the complete platform. This is an accepted availability risk, not evidence that failover should have occurred.

For whole-host loss or a recovery that requires a replacement/clean host, use `../runbooks/production-cold-dr.md`. Do not maintain a second divergent whole-host sequence in this incident runbook.

The cold-DR procedure must preserve at least:

- clean host and management-only WireGuard reachability;
- no public SSH fallback;
- K3s/Calico/security-control reconstruction;
- unchanged OpenBao recovery;
- PostgreSQL physical PITR and service ownership/RLS validation;
- Redis/Kafka profile-specific recovery/rebuild semantics;
- edge/WAF/client-address trust restoration;
- erasure/legal-hold reconciliation before traffic;
- measured ADR-0004 RPO/RTO evidence.

If clean GitOps rebuild is safer/faster than restoring Kubernetes operational state, prefer the clean rebuild while restoring business persistence/secrets through their owning recovery procedures.

## 5. PostgreSQL incident handling

### Single-server

One PostgreSQL process/host/storage failure may affect all PostgreSQL-backed services.

If physical recovery is required:

1. stop writes/traffic as required to preserve a consistent recovery boundary;
2. select the approved Barman base backup/WAL target;
3. restore the **complete shared physical cluster into an isolated recovery environment**;
4. verify PostgreSQL integrity, every affected service Flyway version, roles, RLS and applicable erasure/legal-hold state;
5. for service-specific recovery, extract only the required service database through the approved logical recovery procedure;
6. import/restore it during controlled maintenance with compatibility checks;
7. prove unrelated current service databases were not destructively restored;
8. reopen traffic only after application/security validation.

`pg_dump + cron` is not the primary production recovery path.

A failed shared-cluster restore triggers the ADR-0037 promotion freeze and reliability escalation.

### HA

Use current per-service CloudNativePG failover/restore runbooks and independent backup identities.

## 6. Redis incident handling

Single-server Redis has no failover. During Redis outage/corruption:

- do not bypass semantic quota/session fail-closed behavior;
- do not reconstruct authenticated authority from browser cookies;
- do not replace missing trusted client-network context with public forwarding headers or a proxy address;
- use AOF/restart recovery when safe;
- invalidate/re-authenticate sessions when authoritative state is lost/uncertain;
- verify TLS/ACL/`noeviction`/time-source configuration after recovery;
- investigate host/disk/AOF pressure as shared-host root cause.

HA uses current Sentinel failover behavior.

## 7. Kafka incident handling

Single-server Kafka RF=1 has no broker/controller redundancy.

During broker/node/disk loss:

- treat Kafka as unavailable transport, not lost business truth;
- keep application state/outbox records authoritative;
- do not disable idempotency or skip Inbox/dedup checks to catch up faster;
- rebuild broker/topic/ACL configuration from reviewed GitOps when required;
- replay/reconstruct critical events from retained service-owned evidence;
- verify consumer state/lag before reopening dependent async workflows;
- record any broker-local data-loss window.

HA uses current broker/controller quorum procedures.

## 8. Istio/Kyverno incident handling

If Ambient resource pressure or failure occurs:

- preserve strict mTLS/workload identity semantics;
- do not bypass service authorization by sending direct plaintext traffic;
- reduce safe workload pressure/add host capacity/recover components;
- if the single host cannot run required security controls safely, keep production blocked and escalate capacity/profile decision.

If Kyverno is unavailable:

- existing workloads may continue according to Kubernetes behavior;
- protected new/updated workloads MUST NOT be admitted through an allow/bypass configuration;
- restore admission health or use the separately reviewed signed emergency path if one exists;
- do not switch production to audit-only merely to deploy.

## 9. Public edge/client-address incident handling

If the external-L4/Traefik/WAF client-address chain is wrong or uncertain:

- do not enable Traefik `proxyProtocol.insecure` or `forwardedHeaders.insecure`;
- do not trust public `Forwarded`, `X-Forwarded-*`, `X-Real-IP`, or private client-IP headers;
- do not route Traefik directly to BFF to bypass WAF;
- verify the external-L4 source CIDRs and PROXY-v2 preservation;
- verify Caddy strict trusted-proxy parsing and internal client-IP overwrite;
- keep quota-required public operations blocked/fail closed until trusted client identity is valid;
- preserve only PII-safe diagnostics; raw client IP is not copied into ordinary incident logs without an approved forensic need.

A source-address trust failure is a security-control incident, not merely a telemetry defect.

## 10. OpenBao incident handling — unchanged

ADR-0042/ADR-0043 do not change OpenBao.

Use the existing OpenBao 2.6.1 Shamir/Raft/PVC/encrypted-snapshot/restore/unseal and External Secrets recovery procedures. Normal workloads continue only within their existing validated local-key/stale-source bounds.

Never respond to an OpenBao outage by:

- removing/replacing OpenBao without a separate current security decision;
- placing secrets in Git/values/images/logs;
- disabling key validation;
- creating an unbounded plaintext fallback.

## 11. Human privileged access during incidents

### Single-server

Normal reachability remains:

```text
approved operator device -> WireGuard -> management address -> OpenSSH/FIDO2 -> JIT privilege
```

- public-interface/Internet TCP/22 stays denied;
- WireGuard outage does not authorize public SSH;
- no shared WireGuard peer, password/root/shared-SSH-key fallback;
- WireGuard network admission alone is not human authentication or privilege;
- emergency elevation remains attributable, incident-linked and time-bounded;
- normal write elevation retains the required reviewers unless the separately protected break-glass condition applies;
- provider emergency console, if available, is break-glass only and must be declared as such;
- break-glass use is explicitly declared, audited and reviewed/rotated after use;
- `sudo`, OS, Kubernetes and database privileged activity remains audited;
- required audit is exported off-host;
- `.bashrc`/shell history is not accepted as incident-session evidence.

If audit export is impaired, declare that as part of the incident and follow the approved continuity rule; do not silently abandon audit requirements.

### HA

Use current Teleport JIT/break-glass procedures.

## 12. Security/privacy incident rules

For suspected credential/token/private-key/PII/tenant-isolation/host compromise:

- contain before broad recovery;
- preserve relevant audit/forensics evidence;
- rotate/revoke affected WireGuard peers, SSH credentials, JIT grants, sessions, application credentials, tokens, or keys through their owning authority;
- do not log/attach raw secrets or unnecessary PII to incident systems;
- verify cross-tenant/authorization/RLS boundaries after containment;
- treat a compromised single-server root as a broad local trust failure and rebuild from trusted artifacts rather than assuming workload isolation protected secrets;
- coordinate legal/privacy obligations through responsible owner;
- use erasure/legal-hold rules when restoring historical data.

## 13. Deployment/migration incident rules

- stop further rollout;
- preserve exact image/config/schema versions;
- determine whether Git/config rollback is state-compatible;
- never edit executed Flyway migrations;
- never perform unsupported PostgreSQL downgrade for speed;
- use expand/compatible rollback/fail-forward rules;
- in single-server, a PostgreSQL/CloudNativePG upgrade is platform-wide maintenance and must validate every service database.

## 14. Recovery verification before traffic

Verify as applicable:

- expected artifact/config/profile version;
- health/readiness without dependency masking;
- authentication/MFA/Authorization fail-closed behavior;
- workload identity/mTLS/NetworkPolicy;
- PostgreSQL integrity/Flyway/RLS/role isolation;
- Redis/Kafka recovery semantics;
- Kyverno admission and edge/WAF path;
- external-L4/Traefik/WAF/BFF client-address anti-spoofing contract;
- OpenBao/secret delivery;
- WireGuard management isolation/public-SSH denial and privileged access/audit;
- logging/audit/telemetry;
- erasure/legal-hold reconciliation where restored historical state is involved;
- critical smoke/browser flows;
- no unresolved data/replay/backlog corruption.

For a full cold recovery, the traffic-enable record in `../runbooks/production-cold-dr.md` is mandatory.

## 15. Post-incident requirements

Record:

- root cause and contributing factors;
- whether the event was expected non-HA availability loss or violated a correctness/security contract;
- detected/actual RPO/RTO and downtime;
- evidence of safe recovery;
- missed threat, alert, runbook, test, network-trust, or capacity assumption;
- remediation owner/deadline;
- whether the single-server profile remains acceptable.

Repeated node incidents, unacceptable downtime, persistent shared IO/capacity pressure, broad host-compromise concerns, or unacceptable recovery RTO trigger review/migration to `production-ha`.
