# ADR-0026: Use Git and Buf Without a Runtime Schema Registry in v1

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR supersedes the canonical architecture's v1 requirement to deploy a
runtime Schema Registry for Protobuf contracts. It does not change Protobuf as
the internal gRPC contract format or permit incompatible field changes.

A runtime Schema Registry may be introduced only through a later ADR when an
actual runtime or dynamic schema-discovery consumer, such as multi-team Kafka
event streaming, justifies it.

## Context

The current phase uses repository-versioned Protobuf contracts and generated
code. gRPC alone does not require a runtime schema-discovery service. Deploying
a registry without a runtime consumer would add topology, authentication,
backup, availability, and upgrade responsibilities without serving the current
contract workflow.

## Decision

### v1 registry topology

For v1:

```text
Runtime Schema Registry product  = none
Runtime Schema Registry topology = none
Runtime Schema Registry auth     = none
```

No runtime Schema Registry is deployed.

### Contract source of truth and CI policy

The Protobuf source of truth is the Git repository. Buf CLI runs in CI.

The required policy is:

```yaml
lint:
  use:
    - STANDARD

breaking:
  use:
    - FILE
```

Every pull request must run the equivalent of:

```text
buf lint
buf breaking --against <main>
```

Protobuf compatibility for this phase is therefore `Buf FILE`. Protobuf field
numbers must never be reused, and generated-code structure protected by the
selected policy must not be broken.

### Future runtime registry gate

A future registry requires a new decision defining its real consumers,
product, topology, availability, authentication, authorization, compatibility,
schema ownership, migration, and rollback. The existence of gRPC contracts by
itself is not sufficient justification.

Production activation of event flows that require runtime or dynamic schema
discovery remains gated until that decision is accepted.

## Consequences

- v1 has no registry runtime, credential, or availability dependency.
- Contract compatibility remains mandatory and is enforced against Git in CI.
- Contract review and repository protection become the release boundary.
- Runtime/dynamic discovery and multi-team event governance remain unavailable
  until a justified registry ADR is accepted.

## Alternatives Considered

### Deploy a registry because Protobuf is used by gRPC

Rejected because generated gRPC contracts are governed by Git and Buf and do
not require runtime discovery.

### Use a weaker compatibility category

Rejected in favor of the approved `FILE` policy.

### Leave compatibility checking optional

Rejected because every pull request must prove lint and compatibility against
the main-line contract baseline.

## Rollback or Migration Considerations

This ADR removes no deployed registry because v1 has none.

Introducing a future registry is an additive migration and must not replace Git
as the reviewed source of truth without another explicit decision. Rollback of
CI changes must not remove `STANDARD` lint or `FILE` compatibility checks.
