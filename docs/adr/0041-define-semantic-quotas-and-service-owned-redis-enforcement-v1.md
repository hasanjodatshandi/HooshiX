# ADR-0041: Define Semantic Quotas and Service-Owned Redis Enforcement v1

## Status

Accepted

## Date

2026-08-10

## Resolves

This ADR satisfies the semantic-quota architecture decision required by ADR-0040. ADR-0040's production gate remains until this decision is implemented and verified.

## Decision

Semantic quotas use atomic Redis-backed token-bucket/GCRA-equivalent enforcement. Redis is ephemeral security state, never the business source of truth.

Production uses the shared physical `security-redis` deployment with strict logical ownership:

- Redis OSS 8.2.x, exact patch from Technology Baseline;
- 1 primary + 2 replicas;
- 3 Sentinels across failure domains;
- TLS + ACLs;
- separate per-service credentials and key-pattern ACLs;
- `noeviction`;
- no DR backup for quota counters.

Redis Cluster is intentionally not used in v1. One logical shard keeps multi-dimension decisions atomic and operationally simple. If BFF session traffic and semantic-quota traffic interfere materially, split them into separate Sentinel deployments before introducing Redis Cluster.

Each owning service controls its quota policy/namespace and cannot read another service's quota keys. A shared physical deployment does not create shared business ownership. There is no quota microservice in v1.

### Atomic execution

One reviewed versioned Redis Lua/function operation:

1. validates/canonicalizes the operation dimensions supplied by trusted application code;
2. derives pseudonymous keys;
3. reads Redis server time;
4. evaluates token-bucket/GCRA refill and operation cost for all dimensions;
5. denies without partial consumption when any hard-gate dimension lacks capacity;
6. otherwise consumes all applicable dimensions atomically;
7. applies bounded TTLs.

Application-node wall clocks do not control refill. PostgreSQL is not used for ephemeral quota state.

### Pseudonymous keys

Raw email, phone, user ID, tenant ID, membership ID, provider subject, session ID, and IP address never appear in Redis keys or telemetry.

Keys use domain-separated HMAC-SHA-256 over operation + dimension type + canonical value. IPv4 network dimensions canonicalize to `/24`; IPv6 to `/64` before HMAC.

Quota-HMAC key rotation keeps current and previous keys for at least the longest quota TTL. During overlap, both pseudonyms participate in the same atomic decision so key rotation cannot reset an abuse budget.

### Anti-lockout rule

Authentication rate limiting must not create a remote account-lockout primitive.

For login/recovery-style flows:

- source/network/device-class dimensions are **pre-auth hard gates** and may reject before expensive credential work;
- account/login-subject failure pressure is charged **after a failed credential/proof attempt**;
- account/login-subject failure pressure may suppress repeated failed attempts and increase friction, but by itself must not reject a later correctly proven credential once the source hard gates permit verification;
- a successful proof resets or strongly decays the corresponding failure-pressure state according to the versioned Security Baseline;
- responses remain non-enumerating and do not reveal whether a subject exists.

The same principle applies to MFA recovery: subject-failure pressure cannot become an attacker-controlled permanent lockout once a valid proof is presented through an allowed source path.

### Initial production policy

These are initial reviewed defaults. Security Baseline PRs may tune numeric values without a new ADR when operation coverage, anti-lockout semantics, atomicity, pseudonymization, fail-closed dependency behavior, and security intent are not weakened.

| Operation | Dimension | Capacity | Refill | TTL | Cost | Gate |
| --- | --- | ---: | --- | --- | ---: | --- |
| `LOGIN` | failed-credential login subject | 8 | 1 / 60s | 15m | 1 | post-failure anti-lockout pressure |
| `LOGIN` | network | 120 | 2 / 1s | 15m | 1 | pre-auth hard gate |
| `GOOGLE_LOGIN` | provider subject when trusted/known | 20 | 1 / 15s | 10m | 1 | subject abuse pressure |
| `GOOGLE_LOGIN` | network | 240 | 4 / 1s | 10m | 1 | pre-auth hard gate |
| `GOOGLE_LINK` | authenticated user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `GOOGLE_LINK` | network | 60 | 1 / 10s | 1h | 1 | hard gate |
| `TENANT_CREATE_SELF_SERVICE` | actor | 3 | 1 / 6h | 24h | 1 | hard gate |
| `TENANT_CREATE_PLATFORM_ADMIN` | platform actor | 30 | 1 / 60s | 2h | 1 | hard gate |
| `TENANT_INVITE` | actor + tenant | 30 | 1 / 20s | 1h | 1 | hard gate |
| `TENANT_INVITE` | tenant | 300 | 1 / 5s | 1h | 1 | hard gate |
| `MFA_ENROLL` | user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `MFA_ENROLL` | network | 30 | 1 / 60s | 1h | 1 | hard gate |
| `MFA_DISABLE` | user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `MFA_DISABLE` | network | 30 | 1 / 60s | 1h | 1 | hard gate |
| `MFA_RECOVERY` | failed-proof recovery subject | 6 | 1 / 15m | 2h | 2 | post-failure anti-lockout pressure |
| `MFA_RECOVERY` | network | 30 | 1 / 60s | 2h | 2 | pre-auth hard gate |
| `AUTH_ADMIN_WRITE` | actor + scope | 120 | 2 / 1s | 1h | 1 | hard gate |
| `AUTH_ADMIN_WRITE` | tenant/platform scope | 600 | 5 / 1s | 1h | 1 | hard gate |

### Failure contract

Each limiter call has:

```text
overall Redis budget: 75 ms
attempts: 1
automatic retry: none
```

Covered operations fail closed when Redis cannot return a valid semantic decision, subject to the anti-lockout sequencing above.

Quota denial:

```text
gRPC: RESOURCE_EXHAUSTED
stable code: SEMANTIC_QUOTA_EXCEEDED
BFF REST: 429
```

Dependency failure is a distinct availability error, not a fabricated quota denial. Exact counters/capacity are not disclosed to callers.

### SLO and capacity

The semantic limiter is a Class-B internal security dependency:

- availability objective >=99.95% rolling 30d;
- internal p95 <=10ms;
- internal p99 <=25ms;
- 75ms call budget remains the hard dependency ceiling;
- prove >=2x projected peak with >=30% memory headroom and zero eviction.

## Verification Requirements

Atomic race tests, no-partial-consumption tests, Redis outage/failover fail-closed tests, login/recovery anti-lockout tests, non-enumeration tests, HMAC rotation without quota reset, IPv4/IPv6 canonicalization, NAT behavior, refill/TTL/cost tests, ACL isolation, PII-safe telemetry, profile/bypass tests, and >=2x peak load tests are mandatory.

## Consequences

ADR-0040 now has a concrete production implementation. The hot path adds one bounded Redis operation rather than PostgreSQL writes or another synchronous microservice. Sentinel keeps v1 operations simpler than Redis Cluster while preserving a clear split path if session/quota interference appears.
