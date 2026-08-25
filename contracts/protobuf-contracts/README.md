# HooshiX Inter-service Contract Package

This module produces the versioned transport contract JAR distributed to teams that build HooshiX-compatible microservices.

## Artifact

Maven coordinates:

    com.sajtech.hooshix:protobuf-contracts:<version>

Example:

    implementation("com.sajtech.hooshix:protobuf-contracts:1.5.0")

The JAR contains generated Protobuf and gRPC transport classes, Protovalidate rules, and the
`ContractValidationServerInterceptor` used to enforce those rules at a server boundary.

The current 1.5.0 release adds the backward-compatible Identity MFA lifecycle and authentication
challenge contracts, including type-specific TOTP/recovery-code validation and an executable
consumer example. It retains all 1.4.0 operations and wire field numbers.

## Validation

Servers must install the contract interceptor before requests reach application code. Invalid
messages are rejected with gRPC `INVALID_ARGUMENT`; rule failures do not echo request values.
Unexpected validator failures return the generic `CONTRACT_VALIDATION_UNAVAILABLE` internal error.

```java
var rejected = meterRegistry.counter("hooshix.contract.validation.rejections");
var validation =
    new ContractValidationServerInterceptor(ignored -> rejected.increment());
var validatedService = ServerInterceptors.intercept(service, validation);
```

Consumers may also validate before sending a request:

```java
var validator = ValidatorFactory.newBuilder().build();
var result = validator.validate(request);
if (!result.isSuccess()) {
  throw new IllegalArgumentException("request does not conform to the v1 contract");
}
```

Contract validation protects the transport boundary. Service-owned domain validation remains
authoritative for business invariants and canonicalization.

## Consumer examples

Copyable protobuf-JSON examples are under [`examples`](examples). There is one valid request for
every published service contract, plus an intentionally invalid Compromised Password request that
demonstrates rejection. `ContractExamplesTest` parses and validates every documented fixture, so an
example cannot silently drift away from its schema.

Before running Buf directly, prepare its pinned local validation schema input:

```bash
../../services/identity-service/gradlew prepareBufDependencies
buf lint
buf build
```

The build uses `build.buf:protovalidate:1.2.2` (Apache-2.0) and extracts its matching
`buf/validate/validate.proto` only into `build/` for offline Buf checks. The generated dependency
schema is not published as a duplicate contract class.

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
