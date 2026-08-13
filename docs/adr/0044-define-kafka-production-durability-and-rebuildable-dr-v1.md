# ADR-0044: Define Kafka Production Durability and Rebuildable DR v1

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR extends ADR-0026 and ADR-0027. Git + Buf remains the Protobuf source/compatibility model and no runtime Schema Registry is introduced.

## Decision

Production uses Apache Kafka 4.2.x KRaft:

- 3 dedicated controllers;
- 3 dedicated brokers;
- failure-domain/rack awareness;
- TLS + authenticated per-service principals + ACLs + quotas.

Critical topics use:

```text
replication.factor = 3
min.insync.replicas = 2
unclean.leader.election.enable = false
acks = all
idempotent producer = enabled
```

Development/integration tests use Testcontainers or a lightweight local broker; developers do not reproduce the six-node production topology.

### Rebuildable cold DR

Kafka transport is not the sole DR source for critical business integration intent.

Every production event class is classified:

```text
OUTBOX_REPLAYABLE
RECONSTRUCTABLE
NON_CRITICAL
```

For `OUTBOX_REPLAYABLE`, the producer retains the published transactional outbox record or equivalent immutable publication evidence for 35 days, aligned with PITR. It preserves stable event ID/type/version/key and the approved payload or deterministic reconstruction reference. Privacy/erasure/legal-hold policy still applies; secrets are never retained just for replay.

For `RECONSTRUCTABLE`, the owning service documents deterministic reconstruction from authoritative service-owned state while preserving duplicate safety.

`NON_CRITICAL` explicitly accepts loss after total Kafka-cluster disaster; no critical flow may receive this classification by default.

Consumer inbox/dedup evidence is retained sufficiently to make replay within the recovery horizon idempotent.

Cold DR:

1. restore OpenBao and PostgreSQL/PITR;
2. create fresh Kafka from Git desired state;
3. recreate topics/ACLs/quotas;
4. replay retained outboxes or reconstruct events;
5. process with idempotent consumers;
6. reconcile DLQ/outbox/inbox/business counts;
7. enable normal traffic after verification.

Consumer offsets are normal-operation state, not authoritative business recovery evidence.

Quarterly DR exercises include Kafka replay.

## Consequences

Production Kafka is durable without requiring a hot second cluster. Bounded retained outbox history replaces a separate event-journal service and keeps DR ownership in the producing bounded context.
