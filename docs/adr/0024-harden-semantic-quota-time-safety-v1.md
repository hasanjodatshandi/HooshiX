# ADR-0024: Semantic Security Quotas — Current v1

## Status

Accepted — current effective decision

## Date

2026-08-11; current profile/network semantics updated through 2026-08-15; common-mode clock/cardinality/network-pressure hardening finalized 2026-08-15

## Decision

### 1. Ownership and topology

There is no quota microservice. The operation-owning service owns its semantic security quota policy and isolated Redis namespace.

Production uses approved `security-redis` infrastructure with topology selected by the production profile.

Shared controls:

- Redis OSS 8.2.x, exact patch from Technology Baseline;
- TLS + per-service ACL identities/key namespaces;
- `noeviction`;
- quota/session state is ephemeral security state, not business source of truth or cold-DR authority;
- no Redis/time/capacity failure may be converted into allow/bypass.

`production-single-server` uses one Redis instance with AOF `appendfsync everysec`, no failover claim, and shared-host clock/resource failure domain. `production-ha` uses the current primary/replica/Sentinel topology.

Redis Cluster is not the default because one logical shard preserves atomic multi-dimension evaluation. If session/quota workloads materially interfere, split physical deployments or increase profile capacity before adding cluster complexity.

### 2. Atomic limiter

One reviewed/versioned Redis Function or Lua operation evaluates all hard quota dimensions atomically.

Trusted application code supplies canonical dimension inputs and trusted time evidence. The Redis operation:

1. validates time safety and capacity/allocation health;
2. derives/uses purpose-separated pseudonymous keys;
3. computes refill/failure pressure;
4. rejects without partial consumption when any hard gate fails;
5. otherwise commits all applicable hard-dimension changes atomically.

PostgreSQL is not used for ephemeral quota counters.

### 3. Trusted client address and network dimensions

Raw email, phone, user ID, tenant ID, membership ID, provider subject, session ID, pre-auth identifier, or IP address MUST NOT appear in Redis keys or ordinary telemetry.

For public operations, the network source is trusted only through ADR-0043. Browser/public forwarding headers are never quota authority.

Web BFF derives exactly one trusted binary client IP. When a downstream service owns a public-operation quota, BFF sends only the typed server-created exact client-address context over the authenticated internal call. The receiving service accepts it only from the approved BFF workload for the registered operation.

The quota-owning service derives two purpose-separated network forms:

```text
client_ip_exact:
  IPv4 -> /32 exact address
  IPv6 -> /128 exact address

client_network_aggregate:
  IPv4 -> /24 prefix
  IPv6 -> /64 prefix
```

IPv4-mapped IPv6 normalizes to IPv4 before either derivation. Canonical HMAC input uses binary address/prefix bytes plus explicit family, dimension type, operation, and key-version domain separation.

`client_ip_exact` is the initial hard pre-auth network quota identity for the numeric policies below.

`client_network_aggregate` is an abuse/capacity pressure dimension. It MUST NOT be the sole user-visible hard quota-denial reason in v1. This avoids making every legitimate address behind one ISP/campus/company/VPN/NAT prefix share one hard token bucket. It may contribute to coarse upstream throttling, allocation-pressure protection, alerts, or a later evidence-backed security-baseline change.

A later change that makes aggregate prefix a hard deny gate requires NAT/IPv6/collateral evidence and review; it cannot silently reuse the existing exact-IP numeric policy.

Missing/malformed/untrusted/proxy-address network context on an operation requiring exact-IP quota fails closed.

### 4. Trusted time and common-mode clock safety

Each invocation supplies `app_wall_time_ms` generated inside the application immediately before Redis I/O. Caller-controlled input never supplies it. Redis also reads Redis `TIME`.

```text
maximum absolute app/Redis skew = 2 seconds
```

If skew exceeds the bound:

```text
UNAVAILABLE / QUOTA_TIME_SOURCE_UNHEALTHY
```

No partial decision/consumption commits.

For a valid sample:

```text
effective_now = min(redis_time, trusted_app_time)
```

Each bucket stores its last accepted effective time monotonically. Backward movement gives zero refill and never moves stored time backward. A forward jump in only one clock cannot create premature credit.

Because application wall time and Redis `TIME` can share one host clock, app-vs-Redis skew alone is not sufficient in `production-single-server`.

Every quota-owning JVM therefore has a local wall/monotonic **Clock Safety Guard**:

- maintain a trusted wall-clock sample and matching monotonic elapsed-time sample;
- compare wall elapsed with monotonic elapsed between guard observations;
- detect an abrupt forward/backward wall-clock step that exceeds the reviewed safety threshold;
- on detected discontinuity, mark quota time unhealthy before granting refill-based decisions;
- do not use caller time, remote request timestamps, or a request-path NTP/Chrony RPC as authority;
- normal bounded NTP/Chrony slew is not treated as a step merely because wall and monotonic elapsed differ by ordinary clock-correction noise.

Initial discontinuity threshold is the same 2-second security bound. A threshold change requires test evidence that it does not permit premature refill or create unsafe availability behavior.

Host time synchronization health is a production readiness signal. The host MUST complete approved synchronization before quota-protected traffic is enabled after boot/recovery.

After a Clock Safety Guard trip, quota-protected operations remain time-unhealthy until all are true for a continuous 60-second stabilization window:

- host synchronization reports healthy under the approved platform check;
- no further wall/monotonic step is detected;
- app/Redis absolute skew remains <=2s.

The stabilization check is local/platform health work, not caller-controlled state. A common-mode app+Redis forward jump MUST be tested explicitly.

### 5. TTL is not security authority

Security-critical quota state MUST NOT reset merely because Redis TTL expires. Authoritative bucket/failure-pressure state has no security-significant expiry.

Bounded cleanup may delete state only when the bucket has mathematically returned to full capacity/zero failure pressure, last-use exceeds the policy cleanup horizon, current time safety passes, and deletion is bounded.

TTL/PTTL may be used only for non-authoritative auxiliary state/telemetry.

### 6. High-cardinality allocation and memory safety

`noeviction` plus non-expiring security authority can be attacked by creating many unique subjects/addresses. Availability-under-cardinality-attack is therefore explicit security capacity policy.

A bucket that does not yet exist is a **new security-state allocation**. New allocations are guarded independently from the subject's token balance.

Mandatory behavior:

- track total active security-bucket cardinality as aggregate telemetry;
- track new-bucket allocation rate and cleanup rate by bounded service/operation/dimension enums only;
- maintain >=30% validated Redis memory headroom at approved peak/attack envelope;
- alert before memory reserve reaches the `noeviction` danger boundary;
- apply bounded service/operation new-allocation pressure controls so an attacker cannot create state at unbounded rate;
- use approved upstream edge/WAF/L4 coarse abuse controls before expensive/new Redis state where possible;
- cleanup runs in bounded batches and cannot become an unbounded recovery storm.

When new-allocation rate or Redis memory reserve crosses the reviewed safe limit, creation of **new** security buckets fails as dependency/capacity unavailability:

```text
UNAVAILABLE / QUOTA_CAPACITY_UNHEALTHY
```

It is not reported as `SEMANTIC_QUOTA_EXCEEDED` and does not claim the individual subject exhausted a valid budget. Existing safe state is not evicted to make room.

The capacity guard itself uses only bounded global/service/operation state and MUST NOT create another attacker-cardinality keyspace.

Exact allocation-rate/memory thresholds are deployment/load-test configuration. They MUST be measured against adversarial unique-contact/address creation and complete-stack IO/memory pressure before production; they are not guessed architecture constants.

If safe capacity cannot be maintained, increase Redis/host capacity, split security Redis workloads, improve upstream coarse throttling, or move profile. Do not enable eviction, TTL reset, local fail-open fallback, or weaker quota semantics.

### 7. Anti-lockout rule

Authentication/recovery quotas MUST NOT create an attacker-controlled permanent account lockout.

- exact source/client-IP/device-class hard gates may reject before expensive proof work;
- subject/account failure pressure is charged after failed credential/proof;
- subject failure pressure alone cannot reject a later correctly proven credential once source gates permit evaluation;
- successful proof resets or strongly decays subject failure pressure according to versioned policy;
- responses remain non-enumerating.

Aggregate network-prefix pressure alone is not the v1 hard user lockout gate.

### 8. Initial reviewed policy

Numeric tuning may change through a reviewed security-baseline PR only when coverage, anti-lockout, atomicity, pseudonymization, fail-closed, time, capacity, and collateral-safety intent are not weakened.

For every row named `client_ip_exact`, use exact `/32` IPv4 or `/128` IPv6 pseudonymous identity. Aggregate `/24`/`/64` pressure remains separate.

| Operation | Dimension | Capacity | Refill | Cleanup horizon | Cost | Gate |
| --- | --- | ---: | --- | --- | --- | --- |
| `REGISTER` | canonical contact | 5 | 1 / 15m | 24h | 1 | hard gate |
| `REGISTER` | client_ip_exact | 60 | 1 / 5s | 1h | 1 | pre-auth hard gate |
| `RESEND_REGISTRATION_VERIFICATION` | canonical contact | 5 | 1 / 10m | 2h | 1 | hard gate + fixed 60s challenge resend spacing |
| `RESEND_REGISTRATION_VERIFICATION` | client_ip_exact | 60 | 1 / 5s | 1h | 1 | pre-auth hard gate |
| `CONFIRM_REGISTRATION` | client_ip_exact | 120 | 2 / 1s | 30m | 1 | pre-auth hard gate; challenge-local five-proof limit remains authoritative |
| `LOGIN` | failed-credential login subject | 8 | 1 / 60s | 15m | 1 | post-failure anti-lockout pressure |
| `LOGIN` | client_ip_exact | 120 | 2 / 1s | 15m | 1 | pre-auth hard gate |
| `GOOGLE_LOGIN` | provider subject when trusted/known | 20 | 1 / 15s | 10m | 1 | subject abuse pressure |
| `GOOGLE_LOGIN` | client_ip_exact | 240 | 4 / 1s | 10m | 1 | pre-auth hard gate |
| `GOOGLE_LINK` | authenticated user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `GOOGLE_LINK` | client_ip_exact | 60 | 1 / 10s | 1h | 1 | hard gate |
| `OIDC_START` | client_ip_exact | 60 | 1 / 5s | 1h | 1 | BFF pre-auth hard gate; max five live pre-auth/browser remains separate |
| `OIDC_CALLBACK` | client_ip_exact | 120 | 2 / 1s | 30m | 1 | BFF callback hard gate before provider/Identity work |
| `TENANT_CREATE_SELF_SERVICE` | actor | 3 | 1 / 6h | 24h | 1 | hard gate |
| `TENANT_CREATE_PLATFORM_ADMIN` | platform actor | 30 | 1 / 60s | 2h | 1 | hard gate |
| `TENANT_INVITE` | actor + tenant | 30 | 1 / 20s | 1h | 1 | hard gate |
| `TENANT_INVITE` | tenant | 300 | 1 / 5s | 1h | 1 | hard gate |
| `MFA_ENROLL` | user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `MFA_ENROLL` | client_ip_exact | 30 | 1 / 60s | 1h | 1 | hard gate |
| `MFA_DISABLE` | user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `MFA_DISABLE` | client_ip_exact | 30 | 1 / 60s | 1h | 1 | hard gate |
| `MFA_RECOVERY` | failed-proof recovery subject | 6 | 1 / 15m | 2h | 2 | post-failure anti-lockout pressure |
| `MFA_RECOVERY` | client_ip_exact | 30 | 1 / 60s | 2h | 2 | pre-auth hard gate |
| `AUTH_ADMIN_WRITE` | actor + scope | 120 | 2 / 1s | 1h | `max(1, semantic_mutations)`, max 100 | hard gate before DB transaction |
| `AUTH_ADMIN_WRITE` | tenant/platform scope | 600 | 5 / 1s | 1h | same cost | hard gate before DB transaction |

For registration/contact flows, canonical contact is validated email or E.164 phone before HMAC. Contact-management/recovery operations use distinct domain-separated operation names even when they reuse numeric envelopes.

BFF `OIDC_START`/`OIDC_CALLBACK` remain distinct from Identity `GOOGLE_LOGIN`; each layer protects different work.

Authorization administration keeps semantic mutation cost/set-delta rules, 100-mutation request maximum, quota-before-DB ordering, no quota refund after later failed mutation, and atomic actor/scope consumption.

### 9. Failure contract

```text
overall Redis budget: 75 ms
attempts:             1
automatic retry:      none
```

Quota denial:

```text
gRPC: RESOURCE_EXHAUSTED
stable code: SEMANTIC_QUOTA_EXCEEDED
BFF REST: 429
```

Dependency/time/capacity unavailability is distinct:

```text
QUOTA_TIME_SOURCE_UNHEALTHY
QUOTA_CAPACITY_UNHEALTHY
Redis transport/availability failure
```

These map to stable availability behavior, never fabricated quota denial or success. Exact counter/capacity values are not disclosed to callers. No local fallback bypass is permitted.

### 10. SLO/capacity

Shared constraints:

- internal p95 <=10ms;
- internal p99 <=25ms;
- 75ms hard call ceiling;
- >=2x projected normal critical-path peak plus adversarial cardinality validation;
- >=30% memory headroom;
- zero eviction;
- bounded time-skew/common-step/cardinality/memory telemetry without raw/pseudonymous subject labels.

Single-server records actual availability and must pass restart/reboot/common-clock/cardinality/fail-closed/complete-stack evidence. HA additionally proves current Sentinel behavior.

## Verification requirements

Tests cover at least:

- every numeric capacity/refill/cleanup boundary and atomic multi-dimension race/no-partial-consumption;
- exact-IP `/32` IPv4 and `/128` IPv6 hard identity;
- separate `/24`/`/64` aggregate-pressure identity and proof it is not the sole v1 hard 429 gate;
- NAT/campus/VPN-style multiple exact-IP cases without one shared aggregate hard bucket;
- IPv6 address rotation/aggregate pressure trade-off tests;
- trusted ADR-0043 context, forged forwarding-header rejection, missing/proxy-address fail-close, IPv4-mapped canonicalization;
- Redis outage fail-closed behavior;
- exact/beyond 2s app/Redis skew;
- one-clock forward jump;
- **common-mode app+Redis forward/backward wall-clock step** with Clock Safety Guard trip;
- boot synchronization readiness gate and 60-second safe re-arm window;
- no refill/reset during unhealthy time;
- no TTL security reset and bounded cleanup;
- adversarial unique contact/client-address flood;
- active-bucket/new-allocation/cleanup aggregate telemetry;
- capacity guard rejects new allocation with `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM while existing state remains intact;
- no attacker-controlled high-cardinality capacity-guard keyspace;
- upstream coarse throttling composition where configured;
- HMAC rotation without budget reset;
- anti-lockout/non-enumeration;
- ACL isolation, PII-safe telemetry, local/test bypass prevention;
- >=2x critical-path load plus complete-stack Redis AOF/memory/IO pressure.

## Rollback considerations

Rollback MUST NOT restore `/24`/`/64` as the only hard network identity, sole app-vs-Redis skew as common-mode clock protection, unbounded new-key allocation, TTL-based security reset, eviction, partial multi-dimension consumption, raw identifiers, caller-controlled network identity, retry/fallback, or remote-account-lockout behavior.

If atomic/time/capacity/trusted-network contracts cannot be enforced, affected quota-protected entry points remain unavailable rather than fail open.