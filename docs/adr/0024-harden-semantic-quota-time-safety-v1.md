# ADR-0024: Semantic Security Quotas — Current v1

## Status

Accepted — current effective decision

## Date

2026-08-11; consolidated to current-only documentation on 2026-08-14; Identity registration quota values, Authorization administration cost semantics, Web BFF OIDC abuse quotas, and profile-aware Redis topology finalized on 2026-08-14

## Decision

### Ownership and topology

There is no quota microservice. The operation-owning service owns its semantic security quota policy and isolated Redis namespace.

Production uses approved `security-redis` infrastructure with topology selected by the production profile.

Shared controls in both profiles:

- Redis OSS 8.2.x, exact patch from Technology Baseline;
- TLS + per-service ACL identities/key namespaces;
- `noeviction`;
- quota/session state is ephemeral security state, not business source of truth and not part of cold-DR RPO;
- no availability failure may be converted into an allow/bypass result.

`production-single-server` under ADR-0042 uses one Redis instance with AOF persistence and `appendfsync everysec`. It has no Redis failover and explicitly accepts restart/host outage. AOF reduces restart loss; it does not make the instance HA. Covered security operations remain fail closed while Redis cannot produce a valid decision, and lost BFF session state results in re-authentication rather than authority reconstruction.

`production-ha` uses one primary + two replicas with three Sentinel voters across failure domains.

Redis Cluster is not a default because one logical shard preserves atomic multi-dimension evaluation. If BFF-session and quota workloads materially interfere, split physical Redis deployments or move to the HA profile before adding cluster complexity.

### Atomic limiter

One reviewed/versioned Redis Function or Lua operation evaluates all dimensions atomically. Trusted application code supplies normalized operation/dimension inputs; the Redis operation derives/uses pseudonymous keys, validates time safety, computes refill/pressure, rejects without partial consumption when any hard gate fails, and otherwise commits all applicable dimension changes atomically.

PostgreSQL is not used for ephemeral quota counters.

### Pseudonymous keys

Raw email, phone, user ID, tenant ID, membership ID, provider subject, session ID, pre-auth identifier, or IP address MUST NOT appear in Redis keys or telemetry.

Keys use domain-separated HMAC-SHA-256 over operation + dimension type + canonical value. IPv4 network dimensions normalize to `/24`; IPv6 to `/64` before HMAC.

Quota-HMAC rotation overlaps current/previous key identities for at least the longest active policy horizon; both pseudonyms participate in one decision during overlap so rotation cannot reset abuse pressure.

### Trusted dual-clock safety

Each limiter invocation supplies `app_wall_time_ms` generated inside the application process immediately before Redis I/O. Caller-controlled HTTP/gRPC/browser/Kafka data never supplies this value.

The Redis function also reads Redis `TIME`.

```text
maximum absolute app/Redis skew = 2 seconds
```

If skew exceeds the bound:

```text
UNAVAILABLE / QUOTA_TIME_SOURCE_UNHEALTHY
```

No partial decision/consumption is committed.

For a valid sample:

```text
effective_now = min(redis_time, trusted_app_time)
```

Each bucket stores its last accepted effective time monotonically. Backward movement yields zero refill and never moves stored time backward. A forward jump in only one clock cannot create premature credit.

### TTL is not security authority

Security-critical quota state MUST NOT reset merely because Redis TTL expires. Authoritative bucket/failure-pressure state has no security-significant expiry.

Bounded cleanup may remove state only when the bucket has mathematically returned to full capacity/zero failure pressure, last-use exceeds the policy cleanup horizon, current app+Redis time passes the same skew validation, and deletion runs in bounded batches.

If cleanup or clock health is uncertain, retain state and alert before memory threatens `noeviction` capacity. TTL/PTTL may be used only for non-authoritative auxiliary state/telemetry.

### Anti-lockout rule

Authentication/recovery quotas MUST NOT create an attacker-controlled permanent account lockout.

- source/network/device-class dimensions are pre-auth hard gates and may reject before expensive proof work;
- subject/account failure pressure is charged after failed credential/proof;
- subject failure pressure may suppress repeated failures/increase friction but alone cannot reject a later correctly proven credential after source gates permit evaluation;
- successful proof resets or strongly decays subject failure pressure according to versioned policy;
- responses remain non-enumerating.

The same principle applies to MFA recovery.

### Initial reviewed policy

Numeric tuning may change through a reviewed security-baseline PR without a new ADR only when coverage, anti-lockout semantics, atomicity, pseudonymization, fail-closed behavior, time safety, and security intent are not weakened.

| Operation | Dimension | Capacity | Refill | Cleanup horizon / prior TTL intent | Cost | Gate |
| --- | --- | ---: | --- | --- | --- | --- |
| `REGISTER` | canonical contact | 5 | 1 / 15m | 24h | 1 | hard gate |
| `REGISTER` | network | 60 | 1 / 5s | 1h | 1 | pre-auth hard gate |
| `RESEND_REGISTRATION_VERIFICATION` | canonical contact | 5 | 1 / 10m | 2h | 1 | hard gate + ADR-0009 60s minimum resend spacing |
| `RESEND_REGISTRATION_VERIFICATION` | network | 60 | 1 / 5s | 1h | 1 | pre-auth hard gate |
| `CONFIRM_REGISTRATION` | network | 120 | 2 / 1s | 30m | 1 | pre-auth hard gate; challenge-local five-failed-proof limit remains authoritative |
| `LOGIN` | failed-credential login subject | 8 | 1 / 60s | 15m policy horizon | 1 | post-failure anti-lockout pressure |
| `LOGIN` | network | 120 | 2 / 1s | 15m | 1 | pre-auth hard gate |
| `GOOGLE_LOGIN` | provider subject when trusted/known | 20 | 1 / 15s | 10m | 1 | subject abuse pressure |
| `GOOGLE_LOGIN` | network | 240 | 4 / 1s | 10m | 1 | pre-auth hard gate |
| `GOOGLE_LINK` | authenticated user | 5 | 1 / 10m | 1h | 1 | hard gate |
| `GOOGLE_LINK` | network | 60 | 1 / 10s | 1h | 1 | hard gate |
| `OIDC_START` | network | 60 | 1 / 5s | 1h | 1 | Web BFF pre-auth hard gate; independent max five live pre-auth transactions/browser |
| `OIDC_CALLBACK` | network | 120 | 2 / 1s | 30m | 1 | Web BFF callback hard gate before expensive provider/Identity work |
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
| `AUTH_ADMIN_WRITE` | actor + scope | 120 | 2 / 1s | 1h | `max(1, semantic_mutations)`, max 100 | hard gate before DB transaction |
| `AUTH_ADMIN_WRITE` | tenant/platform scope | 600 | 5 / 1s | 1h | same request cost | hard gate before DB transaction |

For `REGISTER`/resend, the contact dimension is canonical email or E.164 phone after Identity validation and before HMAC pseudonymization. `CONFIRM_REGISTRATION` deliberately has no Redis subject hard-lock bucket; the single challenge's five-failed-proof limit is subject proof authority so Redis pressure cannot create a separate permanent account/contact lockout.

Authenticated `AddContact`/contact-verification add/resend/confirm reuses corresponding registration numeric envelope under distinct domain-separated operation names and authenticated-user context; it never shares Redis keys with account registration.

Password-recovery proof and MFA pre-auth proof use the `MFA_RECOVERY` numeric envelope under distinct domain-separated operation names where Redis pressure is required, while challenge-local proof-attempt limits remain independently authoritative. Reusing numeric envelope does not reuse keys or challenge state.

Web BFF `OIDC_START/network` and `OIDC_CALLBACK/network` are BFF-owned domain-separated keys. They do not reuse Identity `GOOGLE_LOGIN` keys: BFF quota protects browser protocol initiation/callback and provider/Identity fan-out, while Identity `GOOGLE_LOGIN` protects trusted identity semantics after provider validation. Both layers may therefore apply without one substituting for the other. The independent BFF maximum of five live pre-auth transactions per browser is not a Redis-token-bucket refill rule and remains enforced separately.

For Authorization administration, `semantic_mutations` is number of actual authority-affecting changes in request. For `ReplaceRolePermissions` it is additions + removals (set symmetric difference), not final Role size. A request with >100 semantic mutations is rejected before quota/DB mutation. Both AUTH_ADMIN_WRITE dimensions consume same computed request cost atomically. Quota is evaluated before Authorization DB transaction and consumed quota is **not refunded** when later domain validation/optimistic-concurrency/DB commit fails. Database mutation remains all-or-nothing with no partial-success response.

### Failure contract

```text
overall Redis budget: 75 ms
attempts:             1
automatic retry:      none
```

Covered operations fail closed when Redis cannot produce a valid semantic decision, subject to anti-lockout sequencing above.

Quota denial:

```text
gRPC: RESOURCE_EXHAUSTED
stable code: SEMANTIC_QUOTA_EXCEEDED
BFF REST: 429
```

Dependency/time-source failure is distinct availability error, never fabricated quota denial. Exact counters/capacity are not disclosed to callers. No application may bypass a failed quota/time decision with local fallback.

### SLO/capacity

Shared security-dependency constraints in both profiles:

- internal p95 <=10ms;
- internal p99 <=25ms;
- 75ms hard call ceiling;
- >=2x projected peak validation;
- >=30% memory headroom;
- zero eviction;
- bounded time-skew and memory-growth telemetry without raw/pseudonymous subject labels.

`production-ha` retains availability >=99.95% rolling 30d and Redis failover evidence.

`production-single-server` does not claim Sentinel/node failover availability. It records actual user-visible/dependency availability and must pass restart/reboot/fail-closed evidence plus complete-stack capacity tests before production approval. Failure to fit the security state safely on the host requires more capacity or migration to `production-ha`, not weaker quota semantics.

## Verification requirements

Tests cover exact registration capacities/refill/cleanup boundaries, atomic contact+network races/no partial consumption, resend 60-second challenge spacing independent of Redis refill, confirmation network quota + five-challenge-attempt composition, distinct contact-registration/contact-management/password-recovery/MFA namespaces, exact Web BFF OIDC_START/OIDC_CALLBACK capacity/refill/horizon behavior, separation from Identity GOOGLE_LOGIN keys, max-five-live-pre-auth composition, exact AUTH_ADMIN_WRITE semantic-mutation counting and 100-mutation bound, Role-permission set-delta charging, quota-before-DB ordering, no quota refund after later failed mutation, atomic actor+scope and tenant/platform cost consumption, Redis outage fail-closed behavior, forward/backward jumps in both clocks, exact/beyond 2s skew, no refill from one-clock forward jump, no security reset from expiry, cleanup under time mismatch, long-idle refill capped at capacity, anti-lockout/non-enumeration, HMAC rotation without budget reset, IPv4/IPv6 canonicalization, NAT behavior, refill/cost dimensions, ACL isolation, memory-growth alerts, PII-safe telemetry, local/test profile bypass prevention, and >=2x peak load.

`production-single-server` additionally verifies TLS, `noeviction`, AOF enabled with `appendfsync everysec`, restart recovery, session invalidation semantics when state is lost, and explicit absence of failover claims.

`production-ha` additionally verifies Sentinel failover and replica topology.

## Rollback considerations

Rollback MUST NOT remove approved registration/Authorization/Web-BFF-OIDC quota coverage, restore sole Redis-wall-clock authority, security-significant TTL reset, partial multi-dimension consumption, one-unit arbitrarily large admin mutations, quota refund on failed DB mutation, raw identifiers in keys, retry/fallback, or remote-account-lockout behavior. If dual-clock/atomic fail-closed contract cannot be enforced, affected production semantic-quota entry points remain disabled. Moving to the single-server profile MUST preserve TLS/ACL/noeviction/AOF/fail-closed behavior and MUST NOT be presented as Redis HA.
