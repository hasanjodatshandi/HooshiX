# Production Architecture Review — Current State

- **Reviewed:** 2026-08-14
- **Status:** architecture target accepted; implementation/runtime evidence is not implied
- **Documentation mode:** current-only
- **Selected initial profile:** `production-single-server`
- **Availability posture:** explicit non-HA

## Outcome

The single-server simplification is architecturally acceptable **only** as a named non-HA production profile with security/correctness invariants preserved.

The following decisions are accepted:

- one K3s server/workload node;
- one physical PostgreSQL instance with distinct service databases/roles/Flyway histories;
- one Redis instance with TLS/ACL/`noeviction`/AOF/fail-closed behavior;
- one combined KRaft Kafka broker/controller with RF=1/minISR=1 and formal non-HA acceptance;
- one application replica per service with HPA/availability PDB disabled by default;
- Istio Ambient retained behind a complete-stack benchmark gate;
- Kyverno retained with a smaller high-value policy set but blocking enforcement;
- Teleport omitted only in this profile and replaced by hardened OpenSSH + hardware FIDO2 + real JIT privilege/audit controls;
- evidence-based host sizing rather than assuming 2 vCPU / 3-4 GiB RAM.

The following proposals are **not** accepted:

- replacing physical WAL/PITR/off-site recovery with `pg_dump + cron`;
- removing Kyverno enforcement;
- using `.bashrc`/shell history as privileged-session audit;
- allowing Email/SMS/TOTP as freely interchangeable MFA factors when current Identity policy requires active TOTP;
- treating a one-node/one-broker/one-Redis/one-PostgreSQL profile as HA;
- weakening fail-closed security/correctness controls to fit a smaller host.

**OpenBao is unchanged and outside the simplification scope.**

## 1. Why a named profile is required

The previous architecture expressed the high-availability target as one universal production topology. Replacing it silently with one node would make service docs, SLOs, failure tests and recovery assumptions misleading.

ADR-0042 therefore defines two explicit profiles:

- `production-single-server` — selected initial cost-optimized profile, non-HA;
- `production-ha` — expansion profile for maintenance without full outage, redundancy and larger capacity.

This keeps service/domain/security contracts stable while making topology/availability trade-offs explicit.

## 2. Kubernetes review

K3s is appropriate for the selected one-server profile when the repository platform controls remain authoritative.

Required single-server shape:

- K3s on the approved Kubernetes line;
- embedded SQLite control-plane datastore;
- secrets encryption;
- Flannel and K3s network-policy controller disabled;
- Calico retained;
- bundled K3s Traefik/ServiceLB disabled;
- repository Traefik/Gateway/WAF retained;
- encrypted off-host recovery copy of K3s datastore + server token;
- one application replica; no false availability PDB/HPA.

Review conclusion: acceptable with explicit whole-platform downtime acceptance and tested clean rebuild/recovery.

## 3. PostgreSQL review

Physical consolidation is acceptable because logical ownership remains strict:

- distinct service DB;
- distinct runtime role;
- distinct migration/owner role;
- distinct Flyway history/release lifecycle;
- forced tenant RLS where applicable;
- no cross-service DB access/joins/FKs/shared models/credentials.

The cost is a larger process/host/storage/superuser/recovery blast radius. The shared instance needs a global connection budget and noisy-neighbor IO/WAL/checkpoint evidence.

Review conclusion: acceptable for the single-server profile; not a relaxation of service database ownership.

## 4. PostgreSQL backup/recovery review

`pg_dump + cron` is rejected as the primary recovery strategy because it does not preserve the current continuous WAL/PITR/off-site physical recovery model.

The selected profile retains continuous WAL archive, daily physical base backup, <=5m RPO evidence, 35-day PITR, monthly retained artifacts, monthly restore exercises and quarterly cold DR.

Physical recovery now covers the shared cluster. Service-specific recovery therefore begins with an isolated whole-cluster PITR restore, then extracts/transfers only the required service DB through a controlled compatibility-aware procedure.

Review conclusion: PITR/WAL/off-site recovery remains mandatory.

## 5. Redis review

One Redis instance is acceptable only with:

- TLS;
- independent ACL identities/key namespaces;
- `noeviction`;
- AOF `appendfsync everysec`;
- fail-closed semantic quota/session behavior;
- >=30% measured memory headroom;
- explicit no-failover claim.

AOF is restart durability assistance, not HA. Lost session state causes re-authentication.

Review conclusion: acceptable with explicit availability loss and unchanged security semantics.

## 6. Kafka review

One combined KRaft broker/controller is acceptable only as a formal non-HA exception:

```text
RF=1
minISR=1
acks=all
producer idempotence enabled
unclean leader election disabled
```

Kafka remains transport, not authority. Outbox/Inbox/idempotency, stable event identities and critical replay evidence remain mandatory.

Review conclusion: acceptable when broker-local loss/outage is explicitly accepted and replay/rebuild evidence passes.

## 7. Istio Ambient review

Ambient is a security control because it provides workload identity/mTLS/authorization context. Removing it only for RAM savings would be a security-architecture change, not a topology simplification.

Single-server production must benchmark the complete stack, including `istiod`, CNI and `ztunnel`, under representative traffic and storage load. Waypoints are absent by default.

Review conclusion: retain Ambient and block production if the profile cannot fit it with >=30% validated resource headroom. Increase host capacity or approve a separate reviewed security design; do not silently disable it.

## 8. Kyverno review

Removing Kyverno is rejected. Reducing policy count is acceptable when the remaining set still blocks high-value supply-chain/workload-security failures.

Mandatory single-server admission still covers digest, signature, provenance, signed SBOM and critical unsafe workload identity/security-context patterns. One Kyverno replica is permitted because same-host replicas do not create physical HA.

Review conclusion: reduce policy inventory if evidence supports it; never reduce production enforcement to audit-only or bypass.

## 9. Human access review

Teleport may be omitted in the single-server profile only because equivalent security properties are preserved through a simpler implementation.

Required replacement:

- hardened OpenSSH;
- hardware-backed FIDO2 with user presence + verification;
- no root/password/shared-key access;
- time-bounded JIT privilege separate from SSH authentication;
- two reviewers for write/admin elevation;
- `sudo` I/O + OS + Kubernetes/database audit;
- off-host append-only/tamper-resistant retention;
- protected break glass.

`.bashrc`/shell history is rejected as authoritative audit because it is user-controlled/incomplete and does not provide equivalent tamper resistance/session evidence.

Review conclusion: OpenSSH/FIDO2/JIT/audit replacement is acceptable only after executable evidence passes.

## 10. OpenBao review

**No OpenBao change is approved.**

OpenBao 2.6.1 remains the production secret authority with the existing topology, Shamir/recovery, encrypted snapshots, External Secrets/Kubernetes Auth and mounted/local key workflows.

Review conclusion: out of scope; profile simplification MUST NOT remove, replace, bypass or weaken it.

## 11. MFA review

Arbitrary user choice among Email/SMS/TOTP is rejected. The current model keeps active TOTP as the required factor where Identity requires it. Verification/recovery channels do not become a downgrade bypass.

Review conclusion: no MFA change.

## 12. Capacity review

A fixed `2 vCPU / 3-4 GiB RAM` recommendation is not credible for the complete stack without evidence.

One host must carry application JVMs, K3s system processes, PostgreSQL+WAL+backup, Redis+AOF, Kafka, Istio, Kyverno, edge/WAF and observability. Storage contention is as important as RAM.

Production gate:

- no OOM/sustained swap/MemoryPressure;
- >=30% validated CPU+memory headroom at approved peak;
- applicable >=2x projected peak evidence for critical/security paths;
- safe WAL+AOF+Kafka+telemetry IO/free space;
- safe reboot/recovery order;
- no disabled security/backup control needed to pass.

Review conclusion: host size remains `Not verified` until complete-stack benchmark exists.

## 13. Key residual risks

| Risk | Single-server consequence | Required mitigation/trigger |
| --- | --- | --- |
| Host/node loss | complete platform outage | tested rebuild/recovery; move to HA if downtime unacceptable |
| Shared PostgreSQL failure | all PostgreSQL-backed services affected | WAL/PITR/off-site backup, isolation tests, noisy-neighbor monitoring |
| Kafka broker loss | async transport outage/local data exposure | Outbox/replay/rebuild; move to HA if unacceptable |
| Redis loss | session/quota availability impact | AOF, fail closed, re-authentication; move to HA if unacceptable |
| Admission outage | new/updated workload blocked | fail closed; add capacity/HA, never bypass |
| Ambient overhead | host resource pressure | benchmark; add resources/HA, never silently disable security |
| Single-host audit loss | loss/tampering risk | off-host append-only/tamper-resistant audit |
| Platform upgrade | larger maintenance window | tested rollback/fail-forward/recovery; move to HA if unacceptable |

## 14. Production-readiness conclusion

The architecture change is accepted. **Runtime production readiness is not yet proven by documentation.**

Approval requires the profile-specific checklist: K3s/Calico render+recovery, shared PostgreSQL isolation/PITR, Redis AOF, Kafka RF1 rebuild/replay, Ambient benchmark, Kyverno blocking tests, OpenSSH/FIDO2/JIT/audit, unchanged OpenBao/MFA regressions, full-stack load/soak/reboot, security/vulnerability review and explicit non-HA risk sign-off.
