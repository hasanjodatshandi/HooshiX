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

The artifact contains generated Protobuf and gRPC transport classes.

Services consume released contract versions. Services must not consume another service source tree, generated output, or domain implementation.

## Versioning

- Patch versions contain compatible packaging changes.
- Minor versions contain backward-compatible schema additions.
- Major versions contain breaking wire changes.

Buf lint and breaking checks are executed from the contract package authority.

## Consequences

Positive:

- Microservices become independently buildable.
- External teams can consume a stable contract artifact.
- Contract compatibility has one governance location.

Negative:

- Contract releases require version management.
- Breaking changes require migration planning.

## Enforcement

Architecture fitness rules block service-local canonical Protobuf schemas and cross-service source references.
