# Production Cold Disaster Recovery Runbook

## Purpose

This runbook defines the repeatable cold-DR sequence for the current production architecture. ADR-0004 owns RPO/RTO targets. ADR-0042 owns the selected `production-single-server` topology.

This runbook does not claim that recovery automation or environments exist. Exact commands, provider identifiers, credentials, bucket names, host images, and GitOps paths remain implementation/environment artifacts.

## Status

```text
Architecture:       DECIDED
Runbook procedure:  DEFINED
Implementation:     NOT PRESENT
Exercise evidence:  NOT VERIFIED
Production gate:    BLOCKED until required exercise passes
```

## Recovery objectives

Current targets:

```text
PostgreSQL RPO: <=5 minutes
OpenBao RPO:    <=1 hour
Platform RTO:   <=4 hours
```

A backup job marked successful is not recovery proof. Measured restore evidence is required.

## 1. Declare and contain

Before rebuilding:

1. declare the incident and assign an incident commander;
2. record the last known safe production state and time window;
3. stop unsafe deployment, migration, reconciliation, and credential automation;
4. preserve available host/provider/audit/backup evidence;
5. determine whether compromise is suspected, not only hardware failure;
6. if compromise is possible, revoke/rotate affected access and secret material before normal traffic resumes;
7. keep public application traffic closed until the final traffic gate in this runbook.

Do not reuse a potentially compromised host image, kubeconfig, database credential, WireGuard peer key, SSH key, OpenBao token, or signing material merely to reduce RTO.

## 2. Select the recovery point

Record:

```text
incident start:
last known safe application time:
selected PostgreSQL PITR target:
selected OpenBao snapshot:
selected Git commit / immutable artifact set:
expected data-loss window:
RPO owner approval:
```

For PostgreSQL, select a target that is supported by the continuous WAL/base-backup evidence. For OpenBao, select a verified encrypted snapshot and required Shamir/recovery material.

If required recovery artifacts cannot be validated, stop and escalate. Do not invent state.

## 3. Provision a clean host

For `production-single-server`:

1. provision a replacement physical/virtual host from the approved host baseline;
2. validate CPU, RAM, SSD/storage, firmware/virtualization, clock, and network health;
3. apply reviewed host firewall and kernel/system hardening;
4. restore the ADR-0043 management overlay without placing WireGuard private keys in Git;
5. prove public-interface TCP/22 is closed and management-only SSH reachability works;
6. pin/verify the approved K3s, host OpenSSH, WireGuard, and other host packages/artifacts from provisioning metadata;
7. establish off-host audit export before ordinary privileged recovery work continues when the audit design requires it.

If the original host is suspected compromised, it is not reintroduced into the trusted recovery path until forensic disposition permits it.

## 4. Restore privileged management access

Prove the separation of controls:

```text
WireGuard peer -> network reachability only
FIDO2 OpenSSH -> attributable human session
JIT approval -> bounded privileged authority
```

Required checks:

- revoked/unapproved WireGuard peer denied;
- root/password/keyboard-interactive/shared/non-FIDO SSH denied;
- FIDO2 user presence and user verification required;
- JIT privilege expires automatically;
- privileged activity is captured by OS/`sudo`/boundary audit;
- break glass remains exceptional and incident-linked.

Do not enable public SSH as a DR shortcut.

## 5. Rebuild K3s and base network

Use the exact approved `production-single-server` platform line.

Required state:

- K3s server installed with integrity-verified artifact;
- embedded SQLite control-plane datastore configured according to ADR-0042;
- K3s secrets encryption enabled;
- K3s Flannel disabled;
- K3s network-policy controller disabled;
- bundled K3s Traefik and ServiceLB disabled;
- Calico installed as the CNI/NetworkPolicy authority;
- cluster DNS and time synchronization healthy;
- required namespace and ServiceAccount baseline available.

A clean GitOps rebuild is preferred over restoring stale Kubernetes operational state when it is safer. Restore the K3s datastore/token only through the reviewed recovery path and only when that path is justified.

## 6. Restore OpenBao and secret delivery

ADR-0011 and current OpenBao procedures remain authoritative.

Sequence:

1. restore the approved OpenBao runtime/storage topology;
2. restore the selected encrypted snapshot;
3. perform the approved Shamir/unseal/recovery procedure;
4. verify audit/health/storage state;
5. rotate/revoke credentials if compromise is suspected;
6. restore Kubernetes Auth/External Secrets integration;
7. verify required secret material can be materialized only to approved workloads/paths;
8. verify no secret entered Git, values, logs, traces, metrics, or incident attachments.

Record the measured OpenBao recovery point and compare it to the <=1h RPO target.

OpenBao recovery is not bypassed by plaintext or Git-managed substitutes.

## 7. Reconcile GitOps security/control-plane state

Before application traffic:

- reconcile reviewed namespaces/RBAC/ServiceAccounts;
- reconcile Calico NetworkPolicy;
- reconcile Istio Ambient, trust configuration, strict mTLS, and authorization;
- reconcile Kyverno blocking policies;
- reconcile Traefik and Caddy/Coraza WAF;
- reconcile observability and required audit export;
- reconcile storage/operator components required for PostgreSQL recovery;
- verify immutable image/signature/provenance/SBOM admission controls.

Do not open a direct edge route while WAF, mesh, or admission controls are unavailable.

## 8. Restore PostgreSQL

For the selected single-server profile, physical recovery is cluster-wide.

Sequence:

1. select the verified Barman base backup and WAL target;
2. restore the complete shared physical PostgreSQL cluster to the approved recovery environment/target;
3. validate PostgreSQL integrity and recovery completion;
4. validate every service database expected at the recovery point;
5. validate Flyway schema versions/compatibility;
6. validate distinct runtime/migration roles and negative cross-service privileges;
7. validate forced RLS and tenant-context behavior;
8. record achieved RPO and restore duration;
9. keep traffic closed.

For service-specific recovery, do not overwrite unrelated current databases. Restore the full physical cluster in isolation, validate it, extract only the required service database through the approved logical transfer, then import it during controlled maintenance with compatibility and erasure/legal-hold checks.

`pg_dump + cron` is not the primary DR path.

## 9. Restore immutable reference artifacts

Compromised Password:

- deploy the exact approved immutable SQLite dataset artifact;
- verify digest/provenance, schema, integrity, version, and prefix/response bounds;
- corrupt/missing/incompatible dataset keeps the service unavailable/fail closed.

Reference Data, when implemented:

- deploy the exact approved signed image/bundle;
- verify bundle manifest/source revision/integrity and typed dataset validation.

No database restore is fabricated for immutable rebuildable reference data.

## 10. Recover Redis

Redis is not business source of truth.

Sequence:

1. restore the approved TLS/ACL/`noeviction` configuration;
2. recover AOF only when it is valid and safe;
3. verify `appendfsync everysec` and memory bounds;
4. if session state is lost/uncertain, invalidate affected session continuity and require re-authentication;
5. verify semantic quota operations fail closed until Redis/time-source state is valid;
6. verify no browser cookie or local fallback reconstructs server-side authority.

Do not delay platform recovery merely to reconstruct non-authoritative session/quota history when safe re-authentication/fail-closed behavior is the defined result.

## 11. Rebuild Kafka and replay

Kafka is rebuildable transport.

Sequence:

1. deploy the approved single combined KRaft broker/controller configuration;
2. verify RF=1/minISR=1, `acks=all`, idempotence, TLS, principals, ACLs, quotas, and internal-topic settings;
3. recreate topics/configuration from reviewed desired state;
4. do not treat broker-local recovery as business truth;
5. identify retained service-owned Outbox/publication/Inbox/dedup evidence;
6. replay/reconstruct critical events with stable event/request identities;
7. verify consumers remain idempotent and offsets advance only after durable effects;
8. record any broker-local loss window and reconciliation result.

Do not disable deduplication or idempotency to accelerate catch-up.

## 12. Replay erasure and legal-hold state

Before restored application data receives user traffic:

1. reconcile current erasure requests/evidence against restored state;
2. reconcile legal-hold state;
3. ensure erased Users do not regain authentication/session/Authorization authority;
4. ensure participant completion/replay is idempotent;
5. record non-PII evidence of reconciliation.

Historical backup state is not current authority until this step passes.

## 13. Restore application workloads

Deploy only approved signed immutable artifact digests.

For each service:

- correct ServiceAccount/workload identity;
- correct secret mounts/key snapshots;
- expected database/Redis/Kafka/provider access only;
- readiness does not hide failed mandatory local/security prerequisites;
- liveness does not fail only because a downstream is unavailable;
- profile render is one replica, HPA off, availability PDB off unless a reviewed exception exists;
- logs/metrics/traces remain PII/secret safe.

Application order follows dependency-safe readiness. Startup order alone is not authority; services stay unready/fail closed until required local/security state is valid.

## 14. Verify public edge and client network identity

Before opening traffic, prove:

- upstream mitigation/external L4 path is active;
- external L4 preserves client address with the approved PROXY-v2 contract;
- Traefik trusts PROXY only from approved L4 CIDRs;
- insecure forwarded/PROXY trust is disabled;
- Caddy strict trusted-proxy parsing is active;
- BFF receives only server-derived `X-HooshiX-Client-IP`;
- forged forwarding/client-IP headers do not change quota identity;
- direct Internet -> BFF and Traefik -> BFF bypasses are denied;
- WAF blocking configuration is the approved version.

A missing client-address trust path blocks public quota-protected traffic.

## 15. Security and correctness gate

Run the applicable critical checks:

- Identity login/password/MFA/session/token/erasure flows;
- active TOTP downgrade-prevention;
- Authorization allow/deny/error/timeout fail-closed behavior;
- tenant RLS and cross-tenant negative tests;
- workload mTLS/identity/NetworkPolicy positive and negative tests;
- Redis quota/time failure behavior;
- Kafka replay/idempotency state;
- OpenBao/secret-delivery state;
- Kyverno signature/provenance/SBOM/security-context negatives;
- edge/WAF/client-IP spoof negatives;
- privileged management/audit state;
- critical BFF/browser smoke flows when implemented.

A health endpoint alone is not sufficient recovery evidence.

## 16. Traffic-enable gate

Traffic may open only when all mandatory recovery gates are `PASS` and the incident commander records:

```text
selected recovery point:
measured PostgreSQL RPO:
measured OpenBao RPO:
measured platform RTO:
data/schema/RLS integrity:
erasure/legal-hold replay:
secret/workload identity:
edge/WAF/client-IP trust:
Authorization/MFA/security negatives:
Kafka/Redis reconciliation:
audit/observability health:
known residual risk:
approver:
```

If platform RTO exceeds four hours, record the miss and trigger reliability/profile review. Do not hide the miss by redefining the start/end time after the exercise.

## 17. Post-recovery actions

After traffic is stable:

- rotate temporary/break-glass/recovery credentials;
- revoke unused WireGuard peers and JIT grants;
- verify off-host backups/snapshots resume;
- verify WAL archive freshness;
- verify Redis AOF and Kafka state;
- verify audit/telemetry retention;
- record root cause, measured RPO/RTO, failed/slow steps, and owners;
- update this runbook when evidence shows an unsafe or ambiguous step;
- review whether `production-single-server` remains acceptable.

## Exercise cadence

- PostgreSQL backup verification: every backup cycle;
- isolated PostgreSQL restore: monthly;
- full shared-cluster PITR plus service-specific non-destructive recovery: current monthly restore cadence when applicable;
- OpenBao snapshot/restore evidence: according to current OpenBao recovery cadence and before material changes;
- Compromised Password artifact recovery: quarterly and before material dataset changes;
- single-server host/K3s rebuild: quarterly or before material platform changes;
- full cold DR: quarterly.

Any mandatory failed/overdue recovery evidence blocks affected ordinary production promotion until remediation and revalidation.
