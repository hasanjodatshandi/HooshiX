# ADR-0015: Kafka Production Durability and Rebuildable DR v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

Kafka is durable asynchronous transport, not the business source of truth. Production uses the approved Kafka 4.2.x KRaft line with exact patch/image identity in the Technology Baseline and deployment metadata.

### `production-single-server` topology

The selected initial profile under ADR-0042 uses one Kafka process with combined KRaft broker/controller roles:

```text
1 broker
1 controller combined with broker
critical topic replication.factor=1
critical min.insync.replicas=1
producer acks=all
idempotent producer enabled
unclean leader election disabled
```

Internal Kafka topics/features that otherwise require a replication factor greater than one are explicitly configured for the one-broker topology when those features are enabled.

This is a formal non-HA exception. Apache Kafka recommends separated/redundant broker/controller roles for critical deployments. In this profile, broker/node/disk loss stops asynchronous transport and can lose broker-local data. The exception is acceptable only because Kafka remains rebuildable transport and critical publication/consumer evidence exists outside broker-local storage.

### `production-ha` topology

When the HA profile is selected:

```text
3 brokers
3 dedicated KRaft controllers
critical topic replication.factor=3
critical min.insync.replicas=2
producer acks=all
idempotent producer enabled
unclean leader election disabled
```

Brokers/controllers are spread across available failure domains.

### Security and ownership

Both profiles require native TLS, authenticated per-service principals, ACLs, quotas, bounded partitioning, and service/topic ownership even when clients participate in the mesh.

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

For `production-single-server`, verify combined KRaft operation, one-broker internal-topic settings, RF1/minISR1/acks-all/idempotence, ACL/TLS/quota controls, clean-broker rebuild, representative replay/reconstruction, and explicit outage/data-loss acceptance.

For `production-ha`, verify broker/controller loss, RF3/minISR2/acks/idempotence, placement and quorum behavior.

Both profiles verify topic classifications, 35-day replay/dedup retention for critical flows, duplicate/restart consumer behavior, clean-cluster rebuild from GitOps, representative replay/reconstruction, and quarterly recovery exercise evidence.

## Rollback considerations

Rollback MUST NOT reduce the durability settings defined for the selected profile, enable unclean leader election, remove required replay/dedup evidence, turn Kafka into business source of truth, or introduce non-idempotent replay behavior. Moving to the single-server profile requires explicit non-HA acceptance and MUST NOT be described as broker-failure tolerant.
