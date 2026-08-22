# HooshiX Inter-service Contract Package

This module produces the versioned transport contract JAR distributed to teams that build HooshiX-compatible microservices.

## Artifact

Maven coordinates:

    com.sajtech.hooshix:protobuf-contracts:<version>

Example:

    implementation("com.sajtech.hooshix:protobuf-contracts:1.1.0")

The JAR contains generated Protobuf and gRPC transport classes produced from canonical proto schemas.

The current 1.1.0 release adds the backward-compatible IdentityNotificationResultService terminal callback used by Notification Delivery Runtime v1.

## Versioning

- Patch version: backward-compatible implementation or generated-code packaging changes.
- Minor version: backward-compatible contract additions.
- Major version: breaking wire contract changes.

A team that owns a bounded-context API submits the approved schema change. The contract package release publishes the new version to consumers.

## Ownership boundary

The package is a transport contract distribution unit. It is not:

- a shared domain model;
- a shared persistence model;
- a runtime dependency between services.

Bounded contexts keep ownership of the meaning and lifecycle of their APIs.
