# Production Cold Disaster Recovery Runbook

## Purpose and status

ADR-0004 owns RPO/RTO. ADR-0042 owns selected single-server topology. This runbook defines the repeatable recovery sequence; it does not claim automation/environment evidence exists.

```text
Architecture:       DECIDED
Runbook procedure:  DEFINED
Implementation:     NOT PRESENT
Exercise evidence:  NOT VERIFIED
Production gate:    BLOCKED until required exercise passes
```

Current targets:

```text
PostgreSQL RPO <=5m
OpenBao RPO    <=1h
Platform RTO   <=4h
```

A successful backup job is not restore proof.

## 1. Declare, contain, select recovery point

1. declare incident/commander and record start/last safe state;
2. stop unsafe deploy/migration/reconciliation/credential automation;
3. preserve provider/host/audit/backup evidence;
4. determine hardware failure vs possible compromise;
5. revoke/rotate affected access/secrets when compromise is possible;
6. keep public traffic closed;
7. select and record PostgreSQL PITR target, OpenBao snapshot, Git commit/immutable artifacts, expected loss window, approver.

Do not reuse potentially compromised images/kubeconfigs/credentials/WireGuard/SSH/OpenBao/signing material merely to reduce RTO.

## 2. Provision clean host and management path

- provision approved host baseline;
- validate CPU/RAM/storage/clock/network health;
- apply reviewed kernel/system/firewall hardening;
- establish healthy host time synchronization before quota-protected traffic can later enable;
- restore WireGuard management overlay without private keys in Git;
- prove public TCP/22 denied and management-only SSH reachable;
- pin/verify K3s/OpenSSH/WireGuard/host artifacts;
- establish required off-host privileged audit before normal recovery work.

Prove:

```text
WireGuard = network reachability only
FIDO2     = attributable human authentication
JIT       = bounded privilege
```

Root/password/shared/non-FIDO SSH remains denied. Public SSH is never a DR shortcut.

## 3. Rebuild K3s/base network

Restore exact single-server baseline:

- K3s server + embedded SQLite + secrets encryption;
- Flannel disabled;
- K3s network-policy controller disabled;
- bundled Traefik/ServiceLB disabled;
- Calico CNI/NetworkPolicy;
- healthy DNS/time;
- required namespaces/ServiceAccounts.

Prefer clean GitOps rebuild where safer. Restore K3s datastore/token only through reviewed path.

## 4. Restore OpenBao/secret delivery

Use current ADR-0011 procedures:

1. restore approved OpenBao runtime/storage;
2. restore selected encrypted snapshot;
3. execute Shamir/unseal/recovery;
4. verify health/audit/storage;
5. rotate/revoke if compromise suspected;
6. restore Kubernetes Auth/External Secrets;
7. verify secrets materialize only to approved workloads;
8. verify no secret entered Git/log/trace/metric/incident artifact.

Record achieved OpenBao RPO. No plaintext/Git fallback is allowed.

## 5. Reconcile GitOps security and observability control plane

Before application traffic reconcile:

- namespaces/RBAC/ServiceAccounts;
- Calico policies;
- Istio Ambient/strict mTLS/authorization;
- Kyverno stable CEL-based blocking policy set and supply-chain admission;
- Traefik + Caddy/Coraza edge;
- OpenTelemetry Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager;
- authoritative off-host audit path;
- required storage/operators.

Verify Collector has internal-only OTLP, dedicated SA/RBAC/NetworkPolicy, finite memory/queues, restricted telemetry egress, and only exact read-only pod/container log paths. No broad hostPath/host network/privilege.

Do not open a direct edge/admission/telemetry-management bypass while controls are unavailable.

## 6. Restore PostgreSQL

Single-server physical recovery is whole-cluster:

1. select verified Barman base backup/WAL target;
2. restore full shared physical cluster in approved isolated recovery target;
3. verify integrity/recovery completion;
4. verify every service DB/Flyway version;
5. verify distinct runtime/migration roles and cross-service privilege negatives;
6. verify forced RLS/tenant-context behavior;
7. record RPO and restore duration;
8. keep traffic closed.

Service-specific recovery extracts only required DB from validated isolated restore, then performs controlled import. Do not overwrite unrelated current databases.

`pg_dump + cron` is not primary DR.

## 7. Restore immutable reference artifacts

### Compromised Password

Deploy exact approved HIBP-derived SHA-1 SQLite artifact and verify:

- official source/provenance/tool identity;
- content digest/schema/integrity;
- 20-byte SHA-1 format and positive-count semantics;
- production age <=35 days;
- complete-corpus compatibility/cardinality bounds;
- no runtime HIBP/provider path.

Stale/corrupt/missing/incompatible dataset keeps screening unavailable/fail closed.

### Reference Data

Restore approved immutable bundle in current owning deployable. Restore independent `reference-data-service` only if ADR-0041 deployable trigger is currently satisfied and that service is part of reviewed desired state.

No fake database restore exists for immutable reference artifacts.

## 8. Recover Redis and quota time/capacity safety

1. restore TLS/ACL/`noeviction`/AOF `appendfsync everysec`;
2. recover AOF only when valid/safe;
3. verify memory reserve and AOF state;
4. if session state lost/uncertain, require reauthentication;
5. verify host time sync healthy;
6. verify app/Redis skew <=2s;
7. verify local wall-vs-monotonic Clock Safety Guard has no active trip;
8. after a clock fault, require the ADR-0024 continuous 60s safe re-arm window;
9. verify active-bucket/new-allocation/cleanup state is inside reviewed capacity envelope;
10. verify new unsafe allocations return `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM;
11. verify no browser/local fallback reconstructs authority.

Do not reconstruct non-authoritative session/quota history when reauthentication/fail-closed is the defined safe result.

## 9. Rebuild Kafka/replay

- deploy approved one combined KRaft broker/controller;
- verify RF1/minISR1/acks-all/idempotence/TLS/principals/ACLs/quotas/internal topics;
- recreate desired topics/config;
- identify service-owned Outbox/publication/Inbox/dedup evidence;
- replay/reconstruct with stable event/request identities;
- verify consumer idempotency and offsets-after-durable-effect;
- record broker-local loss/reconciliation.

Broker data is not business truth.

## 10. Replay erasure/legal hold

Before restored business data receives traffic:

- follow `docs/operations/incident-response-runbook.md` §7.1 against the isolated restored
  Identity, Authorization, Notification, and Web BFF databases;
- reconcile every non-terminal request and every active legal hold before replaying any incompatible
  destructive effect;
- recreate the versioned command/receipt topics, then replay from durable Outbox evidence with the
  original event/request identities and normal Inbox idempotency;
- require four current successful participant receipts before coordinator completion;
- ensure erased Users do not regain authentication/session/Authorization authority and retained
  Notification/audit state obeys the recorded hold/retention decision;
- record non-PII request/event identifiers, policy versions, participant terminal states, replay
  counts, and review authorization as recovery evidence.

Historical backup is not current authority before this gate.

## 11. Restore applications

Deploy only approved signed immutable digests.

For each implemented service verify:

- correct SA/workload identity;
- correct secret mounts;
- only expected DB/Redis/Kafka/provider/telemetry access;
- readiness reflects mandatory local/security prerequisites;
- liveness does not fail solely for temporary downstream outage;
- single-server one replica/HPA off/PDB off;
- structured logs/Micrometer metrics/OpenTelemetry traces are PII/secret safe;
- telemetry backend failure does not change ordinary business correctness.

Reference Data stays local unless its service trigger is currently satisfied.

## 12. Verify public edge/client quota identity

Prove:

- upstream mitigation/external L4 active;
- Traefik origin accepts only approved external-L4 source ranges;
- external L4 preserves source with approved PROXY v2;
- Traefik trusts only approved L4 CIDRs; insecure forwarded/PROXY trust off;
- Caddy strict trusted-proxy parsing active;
- BFF receives only server-derived exact client IP;
- forged forwarding/private headers do not alter identity;
- backend hard quota identity is `/32` IPv4 or `/128` IPv6;
- `/24`/`/64` aggregate pressure is separate and not sole hard 429 gate;
- direct Internet->BFF/Traefik->BFF bypass denied;
- WAF blocking config is approved.

## 13. Verify Day-One observability and external detection

Before traffic:

- one synthetic implemented journey produces expected correlated safe logs/metrics/traces;
- Prometheus scrape endpoints and OTLP receiver are not public;
- wrong workload cannot submit OTLP;
- Collector queues/memory/exporters are healthy;
- Loki/Tempo/Prometheus/Grafana/Alertmanager are usable inside the approved capacity envelope;
- ADR-0031 canaries/prohibited fields are absent;
- trace/baggage cannot alter authentication/tenant/Authorization/quota/idempotency decisions;
- authoritative security/privileged audit is healthy off-host;
- independent external black-box monitor sees the approved public path as healthy.

The external monitor MUST have detected the host outage while local monitoring was down. Otherwise total-host detection evidence fails.

## 14. Security/correctness gate

Run applicable critical checks:

- Identity password/login/MFA/session/token/erasure;
- HIBP screening source/freshness/fail-closed behavior;
- Authorization ALLOW/DENY/error/timeout/breaker behavior;
- tenant RLS/cross-tenant negatives;
- workload mTLS/NetworkPolicy/Istio positives/negatives;
- quota exact/aggregate/common-clock/cardinality behavior;
- Kafka replay/idempotency;
- OpenBao/secret delivery;
- Kyverno CEL/signature/provenance/SBOM/security-context negatives;
- edge/WAF/client-IP spoof/bypass;
- privileged management/audit;
- critical BFF/browser journey;
- observability fault/privacy/correlation tests.

A health endpoint alone is insufficient.

## 15. Traffic-enable gate

Traffic opens only after all mandatory gates are `PASS` and incident commander records:

```text
recovery point
PostgreSQL RPO
OpenBao RPO
platform RTO
data/schema/RLS integrity
erasure/legal-hold reconciliation
secret/workload identity
edge/WAF/client identity
quota clock/capacity state
Authorization/MFA/security negatives
Kafka/Redis reconciliation
Compromised Password corpus age/integrity
local observability health
external host-monitor health/audit of outage detection
authoritative audit health
known residual risk
approver
```

If platform RTO exceeds four hours, record the miss. Do not redefine timing to hide it.

## 16. Post-recovery

- rotate temporary/break-glass/recovery credentials;
- revoke unused WireGuard peers/JIT grants;
- verify backups/snapshots/WAL resume;
- verify Redis AOF/Kafka state;
- verify Collector/backends/retention and external monitor;
- verify audit durability;
- record root cause/RPO/RTO/slow or failed steps/owners;
- update runbook from evidence;
- review whether single-server remains acceptable.

## Exercise cadence

- backup verification: every cycle;
- isolated PostgreSQL restore: monthly;
- full shared-cluster PITR/service recovery: monthly when applicable;
- OpenBao recovery evidence: current cadence/before material changes;
- HIBP corpus acquisition/build validation: at least every 30 days;
- Compromised Password artifact recovery: quarterly/material dataset change;
- single-server host/K3s rebuild: quarterly/material platform change;
- external total-host detection: with quarterly full cold DR;
- full cold DR: quarterly.

Any mandatory failed/overdue evidence blocks affected production promotion.
