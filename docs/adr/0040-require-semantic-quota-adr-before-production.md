# ADR-0040: Require Semantic Quotas Before Identity Production Enablement

## Status

Accepted

## Date

2026-08-10

## Decision

Tenant creation/invitation, login, Google login/linking, MFA enrollment,
disable/recovery, and Authorization administration cannot be production-enabled
until a separate accepted semantic-quota ADR defines operation/dimension
capacity, refill, TTL, cost, pseudonymous keys, atomicity, and failure policy.

Application functionality, persistence, contracts, and tests may be built, but
production readiness fails closed for these entry points. No application may
invent quota values, use PostgreSQL for ephemeral limiting, or treat edge
best-effort limits as authoritative semantic limits.

## Rollback Considerations

The gate may be removed only by an accepted quota ADR plus implemented and
verified enforcement; production configuration alone cannot bypass it.
