# ADR-0015: Kafka Production Durability and Rebuildable DR v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Kafka is durable asynchronous transport, not the business source of truth. Production uses the approved Kafka 4.2.x KRaft line with exact patch/image identity in the Technology Baseline and deployment metadata.

### Production topology

```text
3 brokers
3 dedicated KRaft controllers
critical topic replication.factor=3
critical min.insync.replicas=2
producer acks=all
idempotent producer enabled
unclean leader election disabled
```

Brokers/controllers are spread across available failure domains. Native TLS, authenticated per-service principals, ACLs, quotas, bounded partitioning, and service/topic ownership remain mandatory even when clients participate in the mesh.

### Event durability classes

Every production event class is explicitly classified as one of:

- `OUTBOX_REPLAYABLE` — the service retains immutable publication/outbox evidence sufficient to replay the event;
- `RECONSTRUCTABLE` — the event can be deterministically reconstructed from authoritative service state;
- `NON_CRITICAL` — loss during disaster recovery is explicitly acceptable and does not violate business/security correctness.

Critical replayable publication evidence is retained for at least the current 35-day PostgreSQL PITR/recovery horizon, subject to privacy/erasure/legal-hold policy. Participating consumer Inbox/dedup evidence covers the same required replay horizon.

### Cold DR

Kafka broker disks are not the off-site business backup. Cold DR:

1. rebuilds Kafka/controller/topic/ACL configuration from reviewed GitOps;
2. restores authoritative service databases;
3. replays retained publication evidence or reconstructs current events from service-owned state;
4. lets idempotent consumers reconcile using stable event/business identifiers;
5. verifies critical consumer lag/state before traffic opens.

Broker offsets may be new after reconstruction; business/event identities remain stable.

### Retry and replay

Consumers assume at-least-once delivery. Retry/DLQ behavior is finite, explicit, observable, owned, and replayable. Secrets are prohibited from Kafka, retry, DLQ, and publication-evidence payloads. PII requires explicit classification/minimization/retention approval.

## Verification requirements

Verify broker/controller loss, RF/minISR/acks/idempotence, ACL/TLS/quota controls, topic classifications, 35-day replay/dedup retention for critical flows, duplicate/restart consumer behavior, clean-cluster rebuild from GitOps, representative replay/reconstruction, and quarterly recovery exercise evidence.

## Rollback considerations

Rollback MUST NOT reduce critical replication/minISR/producer durability, enable unclean leader election, remove required replay/dedup evidence, turn Kafka into business source of truth, or introduce non-idempotent replay behavior.