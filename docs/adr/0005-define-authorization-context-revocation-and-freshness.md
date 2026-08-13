# ADR 0005: Define Authorization Context, Revocation, and Freshness

## Status

Accepted

## Date

2026-08-05

## Context

ADR-0004 defines tenant-scoped roles, direct membership grants and denies,
default-deny evaluation, and final enforcement inside the resource-owning
service.

The platform must now define how authorization state reaches those services
without requiring a synchronous Authorization Service call for every request.

The runtime design must balance:

- low request latency
- bounded authorization staleness
- rapid revocation
- service autonomy
- failure isolation
- deterministic tenant and membership binding
- resistance to replay and caller-supplied permission claims
- explicit handling of high-risk operations

A full permission list inside every access token would become large and stale.
A mandatory online authorization call for every request would create latency,
availability coupling, and a platform-wide bottleneck.

## Decision

### Runtime model

The platform uses a hybrid authorization runtime:

```text
Short-lived Access Token
        +
Versioned Policy Snapshot
        +
Kafka Invalidation Events
        +
Online gRPC Fetch on Cache Miss
```

The access token identifies the authenticated subject and authorization
revision. The Policy Snapshot contains the effective authorization state.
Resource-owning services cache snapshots and perform final permission
enforcement locally.

Kafka invalidation events accelerate revocation. Short token and snapshot
expiration provide a bounded backstop when an invalidation event is delayed or
missed.

### Access token contents

A tenant-scoped access token contains claims equivalent to:

```json
{
  "sub": "user-id",
  "tenant_id": "tenant-id",
  "membership_id": "membership-id",
  "authorization_version": 42,
  "iat": 0,
  "exp": 0,
  "iss": "trusted-issuer",
  "aud": ["target-service"],
  "jti": "token-id"
}
```

The exact token format may be JWT or another integrity-protected format, but
the receiving service must validate:

- issuer
- audience
- signature or equivalent integrity protection
- issued-at and expiration
- tenant identifier
- membership identifier
- authorization version
- replay controls where required by the operation

The access token must not contain the complete effective permission list.

Authentication assurance claims such as `acr` or `amr` may be included when
required. They do not replace permission evaluation.

A client-provided permission list, role list, deny list, or authorization
version is never trusted.

### Browser and BFF boundary

The browser stores only the secure BFF session cookie defined by the platform
architecture.

The browser must not receive or persist internal service access tokens, Policy
Snapshots, role assignments, or trusted permission sets.

The BFF obtains and manages short-lived internal tokens for calls to backend
services. The BFF may perform early authorization checks for user experience,
but the resource-owning service remains the final enforcement boundary.

### Authorization version

Each TenantMembership has an opaque, monotonically advancing
`authorization_version`.

Every effective authorization change must advance the version, including:

- role assignment
- role removal
- direct permission grant
- direct permission grant revocation
- direct permission deny
- direct permission deny revocation
- permission changes to an assigned role
- SYSTEM role version changes
- membership suspension
- membership removal
- membership restoration or reactivation
- any policy change that alters effective permissions

A role-permission change affects every membership assigned to that role. The
Authorization Service must advance the authorization version for all affected
memberships through a reliable and auditable workflow.

A change is not considered fully published until:

- the durable authorization state is committed
- the new version is recorded
- an outbox record for invalidation is committed
- the change can be reconstructed after retry or recovery

The version is a concurrency and freshness boundary. It must not be accepted
from an untrusted caller.

### Policy Snapshot

A Policy Snapshot contains authorization state equivalent to:

```text
tenant_id
membership_id
authorization_version
effective_permission_keys
issued_at
expires_at
policy_hash
```

The snapshot contains the final effective permission set after applying the
ADR-0004 precedence:

```text
Explicit Membership Deny
    >
Explicit Membership Grant
    >
Role-derived Grant
    >
Default Deny
```

Only exact permission keys are stored. Wildcard assignments remain prohibited.

The Policy Snapshot must be produced by the Authorization Service from durable
authorization state.

A snapshot returned over gRPC must be protected by workload identity and strict
mTLS. A snapshot transported across an untrusted boundary must additionally
have issuer-bound integrity protection.

The `policy_hash` or equivalent digest supports diagnostics and integrity
checks. It does not replace `authorization_version`.

### Cache key and lifetime

A resource-owning service caches Policy Snapshots using:

```text
tenant_id + membership_id + authorization_version
```

A cached snapshot is valid only when:

- the token tenant matches the snapshot tenant
- the token membership matches the snapshot membership
- the token authorization version matches the snapshot version
- the snapshot has not expired
- the token has not expired
- the local minimum accepted version does not exceed the token version
- the membership and policy are not known to be invalidated

A service may evict a snapshot before its expiry.

A service must never extend a snapshot lifetime merely because requests
continue to use it.

### Cache miss and online fetch

On a valid token with no matching local snapshot, the resource-owning service
fetches authorization state from the Authorization Service over authenticated
gRPC.

The fetch request is bound to:

- tenant identifier
- membership identifier
- token authorization version
- requesting workload identity
- correlation and trace context
- a deadline

The Authorization Service returns a snapshot only when the requested version
is current and valid.

If the token version is older than the current authorization version, the
Authorization Service reports stale authorization context. The resource service
denies the request and requires token refresh or reauthentication.

If the token version is ahead of the authoritative version, the request is
treated as inconsistent or forged and is denied.

Retries are allowed only for transient failures and must respect the request
deadline.

### Fail-closed behavior

Authorization fails closed when:

- the access token is invalid or expired
- tenant or membership claims are missing
- the token and snapshot scopes do not match
- the authorization version is stale or inconsistent
- no valid snapshot exists
- snapshot retrieval fails
- the Authorization Service response cannot be authenticated
- the permission key is absent from the effective permission set
- an active invalidation watermark supersedes the token version
- required high-risk freshness cannot be proven

A service must not reuse an unrelated snapshot, fall back to a different
tenant, trust caller-supplied permissions, or allow access because the
Authorization Service is unavailable.

Health, readiness, and observability endpoints may use separately defined
workload policies. They must not bypass user authorization for business data.

### Invalidation events

Every authorization version change publishes a Kafka invalidation event through
a transactional outbox.

The event contains data equivalent to:

```text
event_id
tenant_id
membership_id
previous_authorization_version
new_authorization_version
reason
occurred_at
correlation_id
```

Consumers process invalidation events idempotently.

Events may be duplicated or delivered out of order. Each service therefore
maintains a local minimum accepted version, or invalidation watermark, per
tenant membership.

The watermark is updated using the highest observed version:

```text
minimum_accepted_version =
    max(current_minimum_accepted_version, event.new_authorization_version)
```

When an invalidation event is consumed, the service must:

- advance the local minimum accepted version
- evict snapshots older than the new version
- reject tokens below the new minimum version
- record processing metrics and failures

Eviction alone is insufficient. Without a minimum accepted version, an old
token could cause an old snapshot to be fetched or reconstructed after
eviction.

The invalidation topic is a durable integration contract. Consumers must be
able to rebuild their watermarks after restart through replay, compacted state,
or an authoritative synchronization mechanism.

### Expiration defaults

The default access-token lifetime is:

```text
5 minutes
```

The default Policy Snapshot lifetime is:

```text
5 minutes
```

The values are governed by the Security Baseline and may be shortened for a
specific environment or risk class without superseding this ADR.

A longer value requires explicit security review.

A resource service must enforce the earlier of token expiry and snapshot
expiry.

Expiration is a backstop. It does not replace invalidation events.

### Revocation objective

The normal authorization revocation propagation target is:

```text
60 seconds
```

This is an operational target, not permission to continue access deliberately
for 60 seconds.

The platform must measure:

- outbox publication delay
- Kafka delivery delay
- consumer processing delay
- local watermark age
- stale-token denial counts
- snapshot fetch failures
- end-to-end revocation latency

When the invalidation path is unavailable, the five-minute token and snapshot
expiration bounds ordinary stale authorization, subject to clock-skew policy.

### High-risk operations

High-risk operations require authorization freshness no older than:

```text
60 seconds
```

Examples include:

```text
billing.invoice.refund
platform.legal-hold.manage
platform.support.impersonate
```

For a high-risk operation, the resource-owning service must use one of:

- a Policy Snapshot issued within the maximum freshness window
- an authenticated online authorization check
- a stronger operation-specific approval mechanism defined by another ADR

A five-minute unexpired snapshot is not sufficient when the high-risk
freshness requirement is 60 seconds.

High-risk checks remain subject to domain invariants, tenant ownership,
authentication assurance, audit, and anti-replay controls.

### Membership suspension and removal

Membership suspension or removal must advance the authorization version and
publish an invalidation event.

A suspended, removed, deleted, or otherwise inactive membership has no tenant
permissions, even when an older token has not yet expired.

The Authorization Service must refuse to produce a current Policy Snapshot for
an inactive membership.

High-risk operations involving a membership whose status cannot be verified
within the required freshness window fail closed.

### Platform capabilities

Platform capabilities use a separate global authorization scope, but follow the
same runtime principles:

- short-lived subject context
- versioned policy state
- invalidation events
- bounded freshness
- fail-closed enforcement
- strong audit

A tenant Policy Snapshot must not contain platform capabilities.

Platform support impersonation requires its own accepted security design before
implementation.

### Workload identity

End-user authorization does not replace service-to-service authorization.

Every backend call must also authenticate the calling workload through Istio
mTLS and workload identity.

The resource-owning service evaluates both:

```text
Is this workload allowed to call this endpoint?
AND
Is this end user allowed to perform this tenant operation?
```

Failure of either check denies access.

### Audit and observability

The platform must audit:

- authorization version changes
- snapshot issuance for sensitive operations
- high-risk online checks
- stale-version denials
- invalid or forged version attempts
- membership suspension invalidations
- platform-capability checks
- emergency revocation actions

Operational telemetry must not expose raw tokens or unnecessary permission
sets.

Logs and traces include correlation identifiers, tenant scope, membership
scope, permission key, decision outcome, policy version, freshness age, and
decision source when permitted by data-classification policy.

## Consequences

### Positive

- Normal authorization checks execute locally.
- Permission lists do not inflate access tokens.
- Revocation is accelerated through Kafka invalidation.
- Token and snapshot expiry bound missed invalidations.
- Version binding prevents a token from using a different policy revision.
- Invalidation watermarks prevent old snapshots from returning after eviction.
- High-risk operations receive stricter freshness.
- Authorization failure is deterministic and fail closed.
- Browser clients remain outside the internal token trust boundary.

### Negative

- Services require snapshot caches and invalidation consumers.
- Authorization state is eventually consistent within explicit bounds.
- Role changes may require version fan-out to many memberships.
- Kafka replay and watermark recovery add operational complexity.
- Cache misses depend on Authorization Service availability.
- High-risk operations may have higher latency.
- Clock synchronization becomes security-relevant.

### Required safeguards

Implementation must include:

- issuer and audience validation
- tenant and membership scope validation
- five-minute default token and snapshot expiry
- 60-second normal revocation target
- 60-second maximum high-risk freshness
- transactional outbox publication
- idempotent invalidation consumers
- monotonic local invalidation watermarks
- stale-token denial tests
- cache-miss failure tests
- cross-tenant snapshot rejection tests
- inactive-membership denial tests
- high-risk freshness tests
- metrics for end-to-end revocation latency
- no complete permission set inside browser-visible or service access tokens

## Alternatives considered

### Put all permissions in the access token

Rejected because permission sets become large, changes remain stale until token
expiry, and direct deny changes are difficult to revoke quickly.

### Call Authorization Service for every request

Rejected as the default because it adds latency, availability coupling, and a
platform-wide bottleneck.

Online checks remain valid for high-risk operations and cache misses.

### Cache without authorization version

Rejected because a service cannot prove that the token and cached policy refer
to the same authorization state.

### Evict snapshots without a version watermark

Rejected because an old token could recreate or refetch stale authorization
state after eviction.

### Kafka invalidation without expiration

Rejected because delayed, missed, or incorrectly processed events could leave
authorization stale indefinitely.

### Expiration without invalidation events

Rejected because ordinary revocation could remain ineffective for the entire
token lifetime.

### Fail open during Authorization Service outage

Rejected because service unavailability must not grant access.

## Rollback or migration considerations

This accepted ADR is immutable. A later decision must supersede it.

Initial implementation requires:

- access-token claim contracts
- Authorization Service snapshot gRPC contracts
- authorization-version persistence
- reliable membership and role-change fan-out
- transactional invalidation outbox
- Kafka invalidation event contracts
- service-local snapshot caches
- local invalidation watermarks
- stale-version error contracts
- BFF internal-token handling
- high-risk permission classification
- revocation-latency dashboards and alerts

Changing the default TTL or freshness values through the versioned Security
Baseline does not require rewriting this ADR when the runtime semantics remain
unchanged.

Changing fail-closed behavior, version binding, invalidation watermark
semantics, or the separation between access tokens and Policy Snapshots
requires a superseding ADR.
