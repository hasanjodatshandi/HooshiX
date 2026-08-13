# ADR-0063: Define Operation-Level Dependency Criticality and Degradation v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR operationalizes ADR-0055's requirement that resilience behavior be
selected from dependency semantics. It does not change the failure/retry rules
of the owning service or dependency-specific ADRs.

## Decision

Criticality is assigned to an **operation-dependency edge**, not to a service
name globally. A service can be authoritative for one operation, absent from
another request path, and asynchronously degradable for a third.

The canonical matrix is maintained in:

```text
docs/architecture/dependency-criticality-matrix.md
```

Every new synchronous remote dependency must add or update a matrix entry
before production implementation.

### Dependency classes

1. **AUTHORITATIVE_SECURITY** — failure must not grant access or bypass a
   security rule. Fail closed with an availability/security-dependency error.
2. **AUTHORITATIVE_STATE** — required source of truth. Failure aborts or leaves
   the local transaction uncommitted; no fabricated default is permitted.
3. **DURABLE_COMMAND** — side effect is represented by durable local intent.
   Dependency outage keeps the durable intent pending; the durable owner
   retries/reconciles under its idempotency rules.
4. **EXTERNAL_SIDE_EFFECT** — external provider may be ambiguous. Breaker can
   suppress new dispatch, but unknown results stay unknown and are reconciled;
   no blind retry or fabricated success.
5. **OPTIONAL_READ** — explicitly non-authoritative enrichment. A degraded
   result is allowed only when the owning bounded context defines it.
6. **OBSERVABILITY** — non-audit telemetry must never make a business request
   fail merely because a telemetry backend is unavailable. Bounded local
   buffering/drop is allowed. Security/audit evidence follows its own durable
   requirements and is not ordinary optional telemetry.

### Prohibited simplifications

The architecture must not declare whole services simply `critical` or
`degradable` without operation context. In particular:

- Identity is critical for login/token/MFA operations but is deliberately not a
  synchronous dependency of every authenticated business request;
- Notification provider unavailability is handled through durable delivery
  state and reconciliation, not a fake successful notification;
- Authorization is authoritative for protected resource operations and always
  fails closed;
- optional analytics must not be inserted synchronously into critical security
  paths.

### Matrix ownership

The caller owns the operation-level failure contract. The provider owns its
service SLO and stable error taxonomy. Both sides must agree on:

- deadline;
- retry owner;
- breaker/bulkhead behavior;
- overload mapping;
- idempotency/ambiguity semantics;
- permitted fallback, if any;
- telemetry and test evidence.

An unspecified fallback means **no fallback**.

## Verification Requirements

Architecture/contract tests must prove that:

- every production synchronous dependency is represented in the matrix;
- authoritative-security edges cannot return cached/default allow results;
- durable-command failures leave retriable durable intent rather than losing
  the command;
- external side-effect ambiguity is preserved;
- optional degradation is explicit and cannot silently enter a security path;
- no retry is implemented at more than one layer.

## Consequences

ADR-0055 becomes directly actionable without pretending that an entire service
has one resilience meaning. Reviewers can trace each critical request path and
see exactly where fail-closed, durable retry, or explicit degradation applies.

## Rollback Considerations

Removing a matrix entry or changing an edge from authoritative to degradable is
an architectural behavior change and requires explicit review/ADR when it
changes correctness or security semantics.
