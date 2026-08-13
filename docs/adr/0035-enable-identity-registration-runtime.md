# ADR-0035: Enable the Identity Registration Runtime

## Status

Accepted

## Date

2026-08-10

## Context

ADR-0008 kept the registration gRPC adapter unregistered until durable secret
handoff could be implemented without placing provider I/O or plaintext delivery
secrets in the registration transaction. ADR-0029 subsequently fixed the
Identity-to-Notification handoff, callback, deadline, retry, idempotency,
escrow-erasure, and workload-authorization rules. The corresponding Identity
persistence and application ports now exist.

Keeping the adapters inactive after those caller-side requirements are
implemented leaves the tested registration flow unreachable and leaves durable
handoffs without a runtime dispatcher.

## Decision

Identity enables the registration runtime composition:

- `IdentityRegistrationGrpcServiceAdapter` is served on a configurable internal
  gRPC port; local development uses port 9090;
- `IdentityNotificationResultGrpcServiceAdapter` is isolated on the
  ADR-0029 callback port 9091;
- Identity generates its consumer stub from Notification's canonical,
  provider-owned Protobuf source rather than copying the contract;
- `SubmitNotification` uses a 900-millisecond overall deadline, one invocation,
  wait-for-ready disabled, and gRPC retry disabled;
- the durable dispatcher retains the ADR-0029 batch, lease, cutoff, replay, and
  backoff behavior;
- registration and callback gRPC servers have independent 64-KiB inbound
  message and 16-KiB metadata limits;
- caller-side key rings load from read-only filesystem paths, refresh without
  replacing a valid snapshot on failure, and participate in readiness;
- dispatcher and key-refresh telemetry contains no business identifier or PII.

The runtime uses gRPC Java 1.81.0, aligned across transport, stubs, services,
Protobuf code generation, dependency locking, and verification metadata. This
explicit dependency-baseline decision replaces the temporary 1.80.0 runtime
pin that was retained when this ADR was first accepted.

The runtime is enabled in application configuration. Production must explicitly
provide the key staleness and refresh policies and mounted key directories.
Tests disable the default composition except for the dedicated runtime
integration test.

This decision activates the Identity caller side only. It does not claim that
Notification provider dispatch, production SMS, edge routing, semantic rate
limits, GitOps manifests, or Istio/NetworkPolicy enforcement are complete.
Those controls remain production traffic gates. The local logging SMS adapter
from ADR-0033 remains non-production and cannot satisfy readiness for production
SMS delivery.

## Security and Verification Requirements

Tests must prove:

- both Identity gRPC servers start on distinct ports;
- malformed calls reach the intended service and fail closed;
- usable key rings are reflected in readiness;
- an accepted typed Notification response is mapped correctly;
- retryable and non-retryable gRPC status classifications match ADR-0029;
- one logical submission causes one gRPC invocation; and
- corrupt caller escrow becomes a permanent handoff failure without provider
  invocation.

Dependency locking and verification metadata include the runtime transport.
Logs, metrics, traces, and errors must not include recipient, code, ciphertext,
`request_id`, or `notification_id`.

## Consequences

- The implemented registration slice is executable rather than adapter-only.
- Unknown gRPC outcomes are recovered only by replaying the stable durable
  request, never by an automatic transport retry.
- Registration and callback authorization can be enforced on distinct ports.
- Production exposure remains fail-closed until the separate deployment and
  security controls are present.

## Rollback or Migration Considerations

Runtime exposure can be disabled with
`IDENTITY_REGISTRATION_RUNTIME_ENABLED=false` without rolling back schema.
Already committed handoffs remain durable and must not be deleted or assigned a
new `request_id`. Re-enabling the dispatcher resumes claims under the existing
lease and cutoff rules.
