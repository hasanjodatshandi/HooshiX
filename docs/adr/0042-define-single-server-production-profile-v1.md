# ADR-0042: Single-Server Production Profile v1

## Status

Accepted — current effective decision

## Date

2026-08-14

## Decision

The selected initial production deployment profile is `production-single-server`.

This profile reduces infrastructure cost and operational surface by running the platform on one physical server while preserving security, data ownership, correctness, backup, identity, and fail-closed requirements. It is deliberately **non-HA**. Host, node, storage, kernel, or maintenance failure can stop the complete platform.

The existing multi-node topology remains the `production-ha` expansion profile. It is used only when availability/capacity evidence or business requirements justify the additional nodes. Profile selection changes topology and availability only; it does not create a weaker security or domain model.

The following controls are explicitly unchanged in both profiles:

- DDD + Hexagonal service boundaries and independent deployability;
- separate service databases, runtime roles, migration roles, Flyway histories, and prohibited cross-service SQL;
- forced tenant RLS and transaction-local tenant context;
- Transactional Outbox, Inbox/idempotency, replay, DLQ, and event-ownership semantics;
- browser/BFF/OIDC/session/CSRF/CORS security;
- current MFA semantics from ADR-0012; SMS/email are not user-selectable downgrades around active TOTP;
- Istio workload identity, strict mTLS, NetworkPolicy, and least-privilege authorization when the profile passes its required mesh benchmark;
- signed immutable artifacts, provenance, SBOM verification, vulnerability gates, and Kyverno admission enforcement;
- upstream volumetric protection, Traefik, Caddy/Coraza WAF, and direct-edge-bypass prohibitions;
- OpenBao 2.6.1 as the production secret authority and all current OpenBao/External Secrets/local-key workflows.

**OpenBao is outside the simplification scope and MUST NOT be removed, replaced, or weakened by this profile.**

## 1. Kubernetes and host topology

`production-single-server` uses one K3s server node that is also the only schedulable workload node.

Approved Kubernetes line remains `1.35.6`; K3s uses the matching pinned `v1.35.6+k3s1` distribution artifact. Exact binary checksum/digest is deployment metadata and is verified before installation.

K3s configuration preserves the current platform components instead of accepting bundled substitutes:

- K3s Flannel is disabled;
- the K3s network-policy controller is disabled to avoid policy-engine conflict;
- Calico OSS 3.32.1 remains the CNI/NetworkPolicy implementation;
- bundled K3s Traefik and ServiceLB are disabled; the repository-pinned Traefik/Gateway API deployment remains authoritative;
- K3s secrets encryption is enabled;
- the single-server control-plane datastore is embedded SQLite unless a later measured current decision selects embedded etcd;
- K3s datastore directory plus the server token are encrypted and copied off-host as recovery artifacts; GitOps remains desired-state authority.

The K3s control-plane SQLite datastore is Kubernetes operational state. It is not application business persistence and does not change service PostgreSQL ownership.

One-node consequences are accepted explicitly:

- no Kubernetes control-plane quorum;
- no node-level workload failover;
- planned host maintenance causes service downtime unless an external migration/rebuild is performed;
- topology spread/anti-affinity cannot create physical fault isolation;
- a one-replica PDB cannot create availability and MUST NOT block necessary single-node maintenance.

## 2. Application replicas and scheduling

Application/service workloads use exactly one replica in this profile unless a later profile revision records measured need for local concurrency replicas.

For the initial profile:

```text
replicas: 1
HPA: disabled
PDB requiring availability across pods/nodes: disabled
physical node-failure tolerance: none
```

Multiple replicas on the same server MUST NOT be described as HA. Security components may use one replica where their upstream architecture supports it; admission or identity controls fail closed rather than being bypassed when unavailable.

## 3. PostgreSQL physical consolidation

All mutable PostgreSQL-backed microservices use one physical CloudNativePG cluster with one PostgreSQL instance in `production-single-server`.

Physical consolidation changes only the server/cluster failure domain. Every service still owns:

- one distinct database;
- one distinct runtime role;
- one distinct migration/owner role;
- one independent Flyway history and release lifecycle;
- no `CONNECT`, object, schema, table, sequence, function, or role privilege into another service database;
- no cross-service foreign keys, joins, views, FDW, dblink, logical-replication integration, shared ORM/jOOQ models, or shared credentials;
- forced tenant RLS where applicable.

The shared PostgreSQL instance therefore has a larger physical blast radius. A PostgreSQL process, host, disk, or cluster-level recovery event can affect all PostgreSQL-backed services. This is an accepted availability trade-off, not an ownership relaxation.

Connection capacity is global for the shared instance. The sum of every application pool maximum across all service pods MUST remain <=70% of PostgreSQL `max_connections`; >=30% remains reserved for migrations, backup/recovery, administration, and emergency headroom. Pool changes require measured load evidence.

## 4. PostgreSQL backup, PITR, and restore

`pg_dump + cron` is **not** the production backup strategy.

The single shared CloudNativePG cluster keeps the existing Barman Cloud physical recovery model:

- encrypted off-site backup in a separate failure domain;
- continuous WAL archive monitored for PostgreSQL DR RPO <=5 minutes;
- online physical base backup at least daily;
- 35-day PITR window;
- monthly retained recovery artifact for 12 months;
- verification for every backup cycle;
- monthly isolated restore exercise;
- quarterly platform cold-DR exercise.

Because physical WAL/base backup covers the shared PostgreSQL cluster, physical backup identity and PITR restore are cluster-wide in this profile. A service-specific recovery that must not overwrite unrelated current databases uses this sequence:

1. restore the complete shared PostgreSQL cluster to an isolated recovery environment at the required PITR point;
2. verify PostgreSQL/Flyway integrity and applicable tenant/RLS/security checks;
3. extract only the required service database through an approved logical transfer procedure;
4. restore/import it through a controlled maintenance/recovery operation with compatibility and erasure/legal-hold checks;
5. verify the other service databases were not destructively restored.

A logical export may be part of this isolated recovery procedure. It does not replace WAL/PITR/off-site physical backup.

## 5. Redis single-instance security state

`production-single-server` uses one Redis 8.2.8 instance for BFF session and semantic security state.

Mandatory controls remain:

- TLS;
- independent ACL identities and domain-separated key namespaces;
- `noeviction`;
- fail-closed behavior for authoritative session/quota decisions;
- no raw user/contact/session identifiers in keys or telemetry;
- AOF persistence enabled with `appendfsync everysec`;
- bounded memory and >=30% measured memory headroom at the approved peak.

Redis remains ephemeral security state, not business source of truth. AOF reduces restart loss but does not make the instance HA. Redis loss may invalidate sessions and requires re-authentication; covered security operations remain unavailable/fail closed until a valid Redis decision is possible.

## 6. Kafka single-broker KRaft

`production-single-server` uses one Kafka 4.2.1 process with combined KRaft `broker,controller` roles.

The profile formally accepts that Apache Kafka recommends separated/redundant controller/broker roles for critical deployments; the single combined process is a cost/operability trade-off and is not HA.

Required settings/semantics include:

```text
broker count: 1
controller count: 1 combined with broker
critical topic replication.factor: 1
min.insync.replicas: 1
producer acks: all
producer idempotence: enabled
unclean leader election: disabled
```

Internal Kafka topics that otherwise default to replication above one MUST be explicitly configured for the one-broker topology where required for the enabled Kafka features.

Broker/node/disk outage can stop asynchronous transport and may lose broker-local data. Kafka therefore remains rebuildable transport, not business authority. Transactional Outbox, retained publication evidence, Inbox/idempotency, 35-day critical replay evidence, reconstruction rules, and clean-cluster GitOps rebuild remain mandatory.

## 7. Istio Ambient benchmark gate

Istio Ambient is retained because workload identity, strict mTLS, and least-privilege service authorization are security controls, not optional observability features.

The single-server profile does not assume Ambient fits a low-memory server. Before production approval, a representative benchmark MUST measure at least:

- idle and peak `istiod`, Istio CNI, and `ztunnel` CPU/memory;
- end-to-end p95/p99 latency impact on critical synchronous paths;
- connection and request throughput;
- memory pressure, OOM/restart behavior, and node allocatable capacity;
- interaction with Calico policy and all positive/negative workload-identity tests;
- complete-stack capacity at >=2x projected critical-path peak with >=30% validated resource headroom.

Waypoints are absent by default and are added only for an explicit L7 requirement with separate capacity/security evidence.

If this benchmark fails, production readiness is blocked. The operator MUST scale the host or approve a different reviewed security architecture. Ambient MUST NOT be silently disabled to fit the server.

## 8. Kyverno remains enforced

Kyverno is not removed. The single-server profile uses a reduced, high-value policy set to lower control-plane cost while keeping production deny/fail-closed enforcement.

The retained policy set covers at least:

- immutable digest-only production images;
- approved signature/signer identity;
- build provenance/attestation;
- signed CycloneDX SBOM attestation;
- prohibited privileged/host-network/unsafe `hostPath`/unsafe security-context patterns unless a specific current exception exists;
- dedicated ServiceAccount and critical deployment policy invariants that can be reliably enforced at admission.

Policy authoring remains restricted to controlled GitOps/CI identities. Unneeded external HTTP context stays disabled and the existing SSRF/egress controls remain mandatory.

Kyverno may run one replica in this non-HA profile. Admission unavailability MUST NOT become an allow/bypass path. Existing running workloads are not terminated merely because admission is unavailable.

## 9. Human production access without Teleport

`production-single-server` does not deploy Teleport. It uses hardened OpenSSH plus hardware-backed FIDO2 authentication and real system/privilege auditing under ADR-0030.

This is not permission to use shell-history or `.bashrc` logging as an audit system. `.bashrc`, shell history, or equivalent user-controlled logging is explicitly insufficient and prohibited as the authoritative production-access audit trail.

Mandatory controls are:

- SSH is reachable only from the approved management path/network; no general public SSH exposure;
- direct root login, password authentication, keyboard-interactive authentication, empty passwords, shared accounts, and shared SSH keys are prohibited for privileged human access;
- each human has an attributable identity;
- privileged host authentication accepts only the approved hardware-backed OpenSSH FIDO2 security-key algorithms and requires user presence plus user verification;
- effective OpenSSH configuration enforces `PubkeyAuthOptions touch-required,verify-required` or a strictly equivalent reviewed control so per-key configuration cannot silently remove presence/verification;
- unnecessary SSH agent/TCP/X11/tunnel/gateway-port forwarding is disabled for privileged human access unless a separately reviewed operation requires a narrowly scoped exception;
- generated/effective `sshd` configuration must pass the pinned host `sshd -t` plus `sshd -T`/equivalent validation before activation;
- no standing root, unrestricted Kubernetes, or PostgreSQL-superuser access;
- production write/admin elevation has explicit reason/ticket, at least two authorized reviewers, maximum 30-minute lifetime, and automatic expiry;
- read-only elevation is separately scoped and maximum one hour;
- `sudo` privilege use has protected I/O/session audit where applicable;
- host authentication/exec/privilege events are captured by OS audit (`auditd` or an approved equivalent);
- required access/audit records are shipped off-host to append-only/tamper-resistant storage inaccessible to the ordinary requester;
- static shared kubeconfigs/database passwords and permanent `cluster-admin` grants are prohibited;
- a separately protected hardware-backed break-glass identity is offline by default, audited, incident-linked, and rotated/reviewed after use.

The current zero-standing-privilege/JIT policy is therefore preserved while the Teleport control plane is removed from this profile.

## 10. MFA is unchanged

This infrastructure profile does not change end-user authentication semantics.

Active TOTP remains a required second factor where current Identity rules require it. Email/SMS verification or recovery is not a freely selectable weaker substitute for active TOTP. Any future MFA-factor-policy change requires its own security decision and threat-model review.

## 11. Capacity and production-readiness gate

A `2 vCPU / 3-4 GiB RAM` full-stack server is **not an approved production capacity claim**.

No fixed minimum production host size is approved until complete-stack measurement exists. The selected server must prove, under representative traffic and background work:

- no OOM kill, sustained swap pressure, or node memory-pressure eviction;
- >=30% validated CPU and memory headroom at the approved projected peak;
- >=2x projected peak evidence for critical paths/security dependencies where the current readiness program requires it;
- PostgreSQL connection/IO/WAL/backup latency inside the defined budgets;
- Redis AOF/rewrite and Kafka disk activity do not create unsafe noisy-neighbor latency;
- Istio Ambient, Kyverno, WAF, observability, and application workloads fit simultaneously;
- disk latency/IOPS and free-space alerts remain inside tested safe thresholds;
- restart/reboot recovery order does not turn dependency failure into a security bypass.

If evidence does not pass, increase CPU/RAM/SSD capacity or move to `production-ha`; do not weaken security/admission/backup/identity controls to fit the host.

## 12. SLO and availability interpretation

Application/service SLOs and SLIs remain active in `production-single-server`. Real user-visible errors, latency, planned-maintenance downtime, and host/node outage remain in the applicable measurements and error-budget accounting under ADR-0005.

What is not claimed in this profile is **redundancy-dependent infrastructure failure tolerance**: node-loss failover, broker-loss tolerance, Redis failover, PostgreSQL-primary failover, control-plane quorum, or equivalent objectives that physically require an alternate node/process/replica.

A missing redundancy-dependent failover objective never turns real service downtime into an exclusion. If a service/capability repeatedly cannot meet its applicable availability objective because the one-host topology lacks redundancy, that is evidence to increase capacity or migrate to `production-ha`; it is not permission to weaken security, inflate timeouts/retries, or relabel failures as unavailable evidence.

Monitoring records user-visible availability, latency, error-budget burn, host resource pressure, and recovery evidence so the operator can decide when the cost of downtime justifies migration to `production-ha`.

Triggers for moving to `production-ha` include any of:

- business requirement for maintenance without full-platform outage;
- repeated node/host incidents or unacceptable downtime;
- sustained inability to keep >=30% resource headroom;
- persistent storage/IO contention among PostgreSQL, Redis, Kafka, observability, and workloads;
- security/control-plane components cannot meet their tested latency/availability budgets on one host;
- recovery tests show the single-server RTO is no longer acceptable.

## 13. Verification requirements

Before `production-single-server` is production-ready, verify at minimum:

- exact pinned K3s/Kubernetes/Calico/Istio/Kyverno/Kafka/Redis/PostgreSQL artifacts and compatibility;
- K3s custom-CNI setup, no bundled Traefik/ServiceLB conflict, secrets encryption, off-host datastore+token recovery artifact, and clean GitOps rebuild;
- one-replica/HPA/PDB render for all application services without false HA claims;
- shared PostgreSQL database/role/Flyway isolation and negative cross-service privilege tests;
- forced RLS and pooled transaction-local tenant-context tests;
- continuous WAL/daily base backup/off-site/PITR <=5m RPO evidence and isolated shared-cluster restore;
- service-specific recovery from an isolated whole-cluster PITR restore without destructive restoration of another current database;
- Redis TLS/ACL/noeviction/AOF/restart/fail-closed/time-source/quota/session tests;
- Kafka combined KRaft/RF1/minISR1/acks-all/idempotence/ACL/TLS/rebuild/replay tests;
- Ambient full-stack capacity benchmark plus mTLS/workload-identity positive/negative tests and `istioctl analyze`;
- reduced Kyverno policy render plus signature/provenance/SBOM/security-context negative admission tests;
- OpenSSH effective-config validation, approved-FIDO-only authentication, `touch-required` + `verify-required`, root/password/keyboard-interactive/shared/non-FIDO-key/forwarding negatives, JIT expiry, two-reviewer flow, key revocation, sudo I/O audit, OS audit, off-host audit integrity, and break-glass exercise;
- unchanged OpenBao flows and proof that no profile change introduced a hot-path OpenBao dependency or Git secret;
- unchanged Identity/MFA downgrade-prevention tests;
- complete-stack load/soak/reboot/recovery test with >=30% validated resource headroom;
- explicit operator sign-off accepting whole-platform downtime on server/node failure while retaining normal service SLI/error-budget accounting.

## Rollback considerations

Rollback from `production-single-server` to `production-ha` is a topology expansion, not a semantic rollback. Preserve service database/Flyway ownership, RLS, WAL/PITR evidence, event identities/idempotency, OpenBao secret authority, signed-artifact enforcement, workload identity, MFA rules, and audit evidence.

A rollback or cost reduction MUST NOT replace WAL/PITR with `pg_dump + cron`, disable Kyverno enforcement, replace real access auditing with shell history, permit root/password/keyboard-interactive/shared/non-FIDO-key SSH or missing FIDO presence/verification enforcement, weaken Redis fail-closed semantics, change MFA to a user-selectable downgrade, disable Ambient without a reviewed replacement security design, or remove/change OpenBao.
