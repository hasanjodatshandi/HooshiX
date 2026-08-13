# ADR-0035: Enable the Identity Registration Runtime

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Identity enables the registration runtime composition:

- `IdentityRegistrationGrpcServiceAdapter` is served on a configurable internal gRPC port; local development uses port 9090;
- `IdentityNotificationResultGrpcServiceAdapter` is isolated on the ADR-0029 callback port 9091;
- Identity generates its consumer stub from Notification's canonical provider-owned Protobuf source rather than copying the contract;
- `SubmitNotification` uses a 900ms overall deadline, one invocation, wait-for-ready disabled, and no gRPC retry;
- the durable dispatcher uses the current ADR-0029 batch/lease/cutoff/replay/backoff rules;
- registration and callback gRPC servers have independent 64-KiB inbound message and 16-KiB metadata limits;
- caller-side key rings load from read-only filesystem paths, refresh without replacing a valid snapshot on failure, and participate in readiness;
- dispatcher/key-refresh telemetry contains no business identifier or PII.

The runtime uses gRPC Java 1.81.0 aligned across transport, stubs, services, Protobuf generation, dependency locks, and verification metadata.

Production explicitly configures key staleness/refresh policies and mounted key directories. Tests disable default runtime composition except where the runtime itself is under integration test.

This enables the Identity caller side only. Notification provider dispatch, production SMS, edge routing, semantic quotas, GitOps, Istio/NetworkPolicy, and supply-chain controls remain independent production gates. `LoggingSmsProviderAdapter` is local-development-only and cannot satisfy production SMS readiness; production Iran SMS follows ADR-0049.

## Security and verification requirements

Tests prove both gRPC servers bind distinct ports, malformed calls fail closed, usable key-ring state participates in readiness, accepted typed Notification responses map correctly, retryable/non-retryable status classification matches ADR-0029, one logical submission creates one transport invocation, and corrupt caller escrow becomes a permanent handoff failure without provider invocation.

Dependency locking/verification metadata covers the transport. Logs/metrics/traces/errors MUST NOT expose recipient, code, ciphertext, `request_id`, or `notification_id`.

## Rollback considerations

Runtime exposure may be disabled with `IDENTITY_REGISTRATION_RUNTIME_ENABLED=false` without schema rollback. Already committed handoffs remain durable, retain their stable `request_id`, and resume from the current lease/cutoff rules when dispatch is re-enabled.
