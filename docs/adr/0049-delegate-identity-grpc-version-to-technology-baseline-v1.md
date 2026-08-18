# ADR-0049: Delegate Identity gRPC Version to Technology Baseline v1

## Status

Accepted — current effective decision

## Date

2026-08-18

## Context

ADR-0009 defined the Identity registration runtime and included an exact gRPC Java `1.81.0` version pin. The repository later standardized exact production/runtime versions in `docs/technology/technology-baseline.md` and compatible combinations in `docs/technology/production-compatibility-matrix.md`. Those current documents now require gRPC Java `1.83.1` for the implemented Java services.

Keeping a second exact gRPC patch/minor version inside the Identity runtime ADR creates two version authorities and would force a new service to use a stale transport version even when the repository-wide baseline has moved.

Accepted ADRs remain immutable historical records. ADR-0009 therefore stays unchanged. This ADR supersedes only its exact gRPC Java version pin.

## Decision

Identity Service uses the exact gRPC Java version defined by the current Technology Baseline and Production Compatibility Matrix.

For the first executable Identity registration vertical slice, that version is `1.83.1`.

This ADR supersedes only the `gRPC Java 1.81.0` version pin in ADR-0009. It does not supersede ADR-0009 registration semantics, deadlines, retry rules, message/metadata bounds, service ports, Notification handoff rules, persistence rules, security rules, or observability requirements.

The Identity build MUST keep the gRPC runtime, stubs, tests, and code generator aligned to the same approved version unless a current compatibility document explicitly defines a supported exception.

Future gRPC patch/minor changes do not require a new Identity-specific ADR when they remain within the same architecture and are approved through the Technology Baseline and Production Compatibility Matrix. A change to transport semantics, service boundaries, deadlines, retry ownership, compatibility policy, or security architecture still requires the applicable architecture decision process.

## Verification requirements

Identity verification MUST prove:

- the gRPC runtime, stubs, test libraries, and `protoc-gen-grpc-java` resolve to the Technology Baseline version;
- Gradle dependency locking and strict dependency verification remain enabled;
- Buf lint/build/breaking compatibility gates remain enabled for Identity-owned contracts;
- Compromised Password and Notification client deadlines/retry behavior remain governed by their operation-level dependency policies;
- no CI or dependency-security gate is weakened to adopt the current version.

## Rollback

A transport-version rollback must be made through the current Technology Baseline and compatibility evidence. Do not restore an Identity-only stale version pin or modify ADR-0009 historical text.
