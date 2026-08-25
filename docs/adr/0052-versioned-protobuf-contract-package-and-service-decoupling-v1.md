# ADR-0052: Versioned Protobuf Contract Package and Service Decoupling v1

## Status

Accepted

## Context

Microservices require stable transport contracts without source-tree coupling between services.

The previous implementation stored Protobuf schemas inside service repositories and allowed other services to consume those paths. This created an implicit build dependency between bounded contexts.

## Decision

The canonical inter-service Protobuf schemas are maintained in contracts/protobuf-contracts.

The module publishes a versioned Maven artifact:

    com.sajtech.hooshix:protobuf-contracts:<version>

The artifact contains generated Protobuf and gRPC transport classes, schema-level Protovalidate
annotations, and the neutral gRPC request-validation interceptor needed to enforce those annotations
before application code runs. Validation failures return a generic `INVALID_ARGUMENT` response and
must not echo request field values.

Services consume released contract versions. Services must not consume another service source tree, generated output, or domain implementation.

## Versioning

- Patch versions contain compatible packaging changes.
- Minor versions contain backward-compatible schema additions.
- Major versions contain breaking wire changes.

Buf lint and breaking checks are executed from the contract package authority. Every published
service contract includes at least one protobuf-JSON consumer example. Executable contract tests
parse and validate those examples so documentation cannot silently drift from the schema.

## Consequences

Positive:

- Microservices become independently buildable.
- External teams can consume a stable contract artifact.
- Contract compatibility has one governance location.
- Consumers receive executable request constraints and copyable, tested examples.

Negative:

- Contract releases require version management.
- Breaking changes require migration planning.

## Enforcement

Architecture fitness rules block service-local canonical Protobuf schemas and cross-service source
references. Repository gates also enforce semantic artifact versions, versioned proto/Java packages,
validation on every RPC request, exact consumer-version alignment, example presence, and runtime
interceptor installation in every gRPC-serving service.
