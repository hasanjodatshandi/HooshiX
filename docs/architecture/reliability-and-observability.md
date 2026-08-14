# Reliability and Observability Architecture — Current State

## 1. Reliability model

Reliability is built from bounded work, finite deadlines, durable local state, idempotency, explicit dependency semantics, and failure isolation. Blind retry or timeout inflation is not a reliability strategy.

Avoid deep synchronous chains. Normal design allows at most two consecutive synchronous hops after the BFF; deeper chains require explicit architecture justification. N+1 service calls are prohibited.

Independent downstream calls may run concurrently when correctness permits, but Virtual Threads never make PostgreSQL connections, Redis capacity, provider quota, CPU, memory, crypto, SQLite I/O/connections, HTTP/gRPC pools, or queues unbounded.

## 2. Generic synchronous budgets

Generic ceilings:

```text
Client/Gateway:       3000 ms
BFF total budget:     2600 ms
gRPC downstream:      1500 ms
Database statement:    800 ms
Pool acquisition:      200 ms
```

A more-specific current contract/ADR overrides these generic values. Every network/database call has finite budget; child deadlines fit within remaining parent budget; cancellation propagates where supported.

Retry is finite, safe/idempotent, jittered when appropriate, and owned by exactly one layer. Duplicate application + client + mesh/gateway retry for same failure is prohibited.

## 3. Web BFF critical paths

Web BFF is the browser aggregation boundary and therefore owns bounded fan-out rather than hidden retry/reconstruction.

Current browser-session/security dependencies are registered individually:

```text
session Redis lookup:          AUTHORITATIVE_SECURITY, no fallback
semantic-quota Redis:          AUTHORITATIVE_SECURITY, 75 ms, one attempt, no retry
Google OIDC protocol edge:     AUTHORITATIVE_SECURITY, protocol-safe handling only
Identity OIDC evidence:        AUTHORITATIVE_SECURITY, one attempt, no fallback
Identity audience token:       AUTHORITATIVE_SECURITY, <=1500 ms, one attempt, no retry/fallback
Authorization management:      AUTHORITATIVE_SECURITY, <=1500 ms, one attempt, no retry/fallback
registered resource dispatch:  AUTHORITATIVE_STATE, bounded child deadline, no fabricated data
```

The inbound BFF request retains the 2600ms outer budget. Child deadlines are capped by remaining parent budget even when their registered ceiling is larger than remaining time. HTTP client disconnect/deadline cancellation propagates through Redis/provider/gRPC work where supported and releases bounded in-flight permits.

Session Redis failure never reconstructs authenticated state from cookies/browser data. OIDC quota failure never bypasses abuse control. Identity token-broker failure never reuses an expired/stale/fabricated JWT. Authorization-management/resource-service outage never becomes local management allow or fabricated business response.

BFF session `last_seen` persistence is intentionally coalesced to at most once per five-minute activity window to reduce Redis write amplification; absolute expiry is immutable and Identity RefreshFamily validity remains an upper bound. The User->sessions index makes global revocation/erasure bounded rather than requiring a Redis-wide scan.

BFF refresh-key handling is local. The last fully validated key-ring snapshot can bridge source outage for <=1h; after that, operations requiring refresh encrypt/decrypt fail closed. Reload is atomic so partial/corrupt replacement never poisons active keys. Key-source outage does not justify storing plaintext refresh credentials or making per-request OpenBao calls.

Access JWT server-side retention, when used, ends no later than JWT `exp` and is invalidated by relevant session/tenant/assurance change. It is transport reuse only and never a permission-result cache; final resource authorization remains online in the resource-owning service.

BFF runtime/load evidence separately tracks auth/OIDC, session/bootstrap, Identity onboarding, Authorization-management and resource-dispatch route classes. HPA is enabled only after load evidence demonstrates a signal that does not amplify saturated Redis/downstreams.

## 4. Authorization critical paths

Tenant resource authorization contract:

```text
CheckPermission deadline: 300 ms
attempts: 1
wait-for-ready: off
retry/cache/stale fallback: none
availability: >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
engineering target p95/p99 <=75/150 ms
```

Resource services perform one final authoritative online check. Safe local validation may reject malformed/invalid traffic but never grant authority. Successful `CheckPermission` RPC completion means ALLOW; authoritative deny is a denial status, not successful `allowed=false`. Routine duplicate BFF resource permission checks are prohibited.

Identity platform-only tenant/legal-hold operations use separate authoritative edge:

```text
CheckPlatformPermission deadline: 300 ms
attempts: 1
wait-for-ready: off
retry/cache/fallback: none
failure: fail closed
```

Only Identity may call this operation. Platform-check failure blocks platform-authorized operation and never fabricates `platform_admin` authority or falls back to tenant permission. Platform profile never changes tenant/resource authorization meaning.

Authorization tenant-management RPCs are authenticated through BFF facade but authorization is evaluated locally in Authorization from locally verified exact-audience Identity JWT; no self-gRPC authorization dependency is introduced.

Authorization `AUTH_ADMIN_WRITE` quota is evaluated before local PostgreSQL mutation transaction. Its cost equals actual bounded semantic mutation count; consumed quota is not refunded on later DB failure, while DB mutation itself remains all-or-none. This prevents abuse amplification without holding DB locks across Redis I/O.

Paging uses paired multi-window burn:

- 14.4x: 5m + 1h -> page;
- 6x: 30m + 6h -> page;
- 3x: 2h + 24h -> reliability/release-risk action.

Breaker opening follows current ADR-0032 criteria. Repeated OPEN timing and HALF_OPEN behavior follow ADR-0036: de-correlated bounded reopen backoff, at most one real `CheckPermission` probe in flight, three consecutive infrastructure-successful probes to close, immediate reopen on infrastructure failure/overload, no health-endpoint-authorized closure, and no tenant-tier variation.

Owner safety is correctness boundary rather than availability optimization. Identity Membership-removal reservations and local `tenant_owner` assignment/removal/demotion share same tenant-scoped serialization domain. Unresolved reservations remain fail-closed and never auto-expire into owner capacity.

## 5. Semantic quota dependency

ADR-0024 is the single current quota decision:

```text
Redis budget: 75 ms
attempts: 1
retry: none
failure: fail closed
```

Quota time uses trusted application time + Redis `TIME`, <=2s permitted skew, monotonic effective time, and no security-significant reset based only on TTL expiry.

BFF OIDC protocol quotas are separate from Identity Google-login subject/network pressure. Both can apply because they protect different stages. Exact BFF values are `OIDC_START/network 60, refill 1/5s, 1h cleanup` and `OIDC_CALLBACK/network 120, refill 2/1s, 30m cleanup`, plus independent max five live pre-auth transactions/browser.

## 6. Compromised Password critical path

Password create/change/reset uses the registered `identity.password-credential-write` authoritative-security edge:

```text
Identity -> Compromised Password `LookupCompromisedPasswordRange`
deadline:        <=900 ms overall
attempts:        1
wait-for-ready:  off
retry:           none
fallback/cache:  none
failure:         reject unchecked password
```

Identity computes the full SHA-256 locally and sends only the 20-bit/five-uppercase-hex prefix. Compromised Password performs one fixed indexed disk-backed read from the approved immutable SQLite dataset. It returns only the bounded suffix/count range and Identity performs exact full-digest comparison. Raw password/full digest/User identity never enters the service.

The serving process has no HIBP/external compromised-password provider dependency, no Redis/PostgreSQL/Kafka dataset dependency and no full-dataset JVM cache. SQLite database missing/corrupt/incompatible/read failure, storage saturation, bounded queue/concurrency saturation, malformed/oversized result, or deadline expiry fails closed. Runtime never truncates a prefix range into an empty/partial successful answer because that could create a false clean result.

The service uses the current Class-B objective:

```text
availability >=99.95% rolling 30d
p95 <=250 ms
p99 <=750 ms
```

Representative multi-million-row load measures warm/cold storage conditions, prefix-cardinality distribution, SQLite read latency, I/O saturation, bounded in-flight/queue behavior, gRPC result size and replica/node loss. HPA remains evidence-gated.

The SQLite artifact is rebuildable reference data rather than mutable business state. A failed pod/node is replaced with the same approved dataset version. Cold DR restores/redeploys the immutable dataset artifact and keeps the service unready until compatible identity/schema/integrity checks pass. No stale/corrupt artifact becomes a fallback.

## 7. Notification critical contracts

```text
SubmitNotification:        900 ms, one attempt, no transport retry
ReportNotificationResult:  750 ms, one attempt, durable dispatcher retry outside RPC
```

Unknown handoff outcomes recover through stable idempotent replay. Provider ambiguity remains unknown and is reconciled; it never authorizes blind resend.

Notification uses PostgreSQL-authoritative immutable deadlines and short durable `DISPATCHING` transaction before provider I/O. No application clock-health/fence control plane or request-path OpenBao RPC exists in current runtime.

## 8. Dependency failure containment

Canonical synchronous edge registry is `dependency-criticality.yaml`; its Markdown matrix is generated.

Every production synchronous edge has finite deadline, bounded in-flight work/queue, one retry owner, explicit fallback/failure action, stable error mapping, and test evidence. Criticality is operation->dependency, not whole-service label.

- authoritative security/state failure blocks;
- durable commands remain pending only after local durable intent exists;
- external side effects preserve ambiguous outcome semantics;
- optional reads degrade only through explicitly registered bounded fallback;
- missing fallback means no fallback.

Circuit breakers are used when repeated dependency failures would otherwise consume scarce caller resources and OPEN behavior is semantically safe; they are not blanket annotations around every remote call. Database/Redis/SQLite correctness relies primarily on bounded connections/timeouts/storage/transaction or immutable-artifact semantics rather than generic breaker that obscures outcomes.

## 9. PostgreSQL, Redis, and immutable SQLite reference data

Critical service PostgreSQL clusters use current three-instance CloudNativePG synchronous required-durability/failover model. Safe primary recovery target is <=60s for ordinary failover only when acknowledged durability can be preserved; unsafe promotion fails availability instead of acknowledged data.

Authorization uses jOOQ/JDBC and keeps permission queries bounded; critical plans/indexes are measured. No remote dependency is placed inside its DB transaction merely to improve policy composition.

Security Redis uses one primary + two replicas + three Sentinel voters. Failover tests cover quota/session semantics and fail-closed behavior where dependency is authoritative. Web BFF tests additionally prove HMAC session/pre-auth locators, User->sessions index integrity, no session resurrection, last-seen write coalescing, and max-five pre-auth behavior across failover/restart.

If BFF sessions and quota workloads materially interfere, split Sentinel deployments before Redis Cluster. Failover/capacity pressure does not permit eviction, plaintext identifiers, or bypass of authoritative checks.

ADR-0040 SQLite is not a mutable replicated database. Availability comes from >=3 service replicas using the same approved immutable local dataset artifact plus normal pod/node replacement. Dataset artifact integrity/version/readiness and rebuild/redeploy recovery are tested; PostgreSQL WAL/PITR and Redis failover semantics do not apply to it.

## 10. SLO classes and error budgets

ADR-0005 defines current generic classes:

### Class A

Critical interactive security transactions:

```text
availability 99.90% rolling 30d
p95 <=500 ms
p99 <=1500 ms
server timeout ceiling 2s
```

### Class B

Generic critical internal dependency baseline:

```text
availability >=99.95%
p95 <=250 ms
p99 <=750 ms
```

More-specific current contracts such as Authorization, semantic quotas, Notification handoff, BFF operation-specific edges, and Compromised Password's 900ms caller ceiling override generic latency envelope where stricter.

### Class C

99.9% of durably accepted Notification intents begin first provider attempt within five seconds. External delivery is separate SLI.

For a 99.90%/30d objective, approximate error budget is 43m12s:

| Consumption | Response |
| --- | --- |
| <25% | normal delivery |
| >=25% within 24h | reliability review; stop risky releases |
| >=50% | freeze affected feature releases |
| >=100% | security/incident/reliability changes only |

Planned maintenance counts when users cannot obtain service. Real errors/latency are never hidden by ad-hoc grace periods.

## 11. Disaster recovery

Cold DR sequence:

```text
clean Kubernetes/GitOps foundation
-> restore OpenBao control plane
-> restore service CloudNativePG/PostgreSQL + PITR
-> reconstruct Kafka + replay/reconstruct retained service evidence
-> restore/redeploy approved rebuildable reference artifacts such as ADR-0040 SQLite dataset
-> reconcile erasure/legal-hold/data integrity
-> verify secrets/contracts/security
-> smoke/security checks
-> enable traffic
```

Targets:

```text
PostgreSQL RPO <=5m
OpenBao RPO <=1h
platform RTO <=4h
PostgreSQL PITR 35d
```

Every PostgreSQL backup cycle is verified; isolated restore is monthly per applicable service; full cold DR is quarterly. ADR-0037 requires queryable recovery evidence and freezes ordinary affected-service promotion after failed restore until replacement evidence passes.

Authorization restore reconciliation must not resurrect erased subject Membership/direct-override/platform-profile authority, reuse retired permission/Role identifiers, release unresolved owner-safety reservations unsafely, or discard required idempotency/audit evidence.

Web BFF security Redis is ephemeral rather than cold-DR business truth. After loss/rebuild, authenticated browser state is not reconstructed from cookies: users reauthenticate. Restored/restarted BFF must not revive expired/consumed pre-auth state or erased/revoked sessions. Required erasure receipts remain in durable participant/coordinator evidence rather than depending on Redis survival.

Compromised Password DR does not restore mutable subject state. It redeploys/rebuilds the approved immutable SQLite artifact and validates dataset version/schema/integrity before readiness. Service unavailability fails password writes closed until the reference dataset is usable.

## 12. Observability

Every service emits:

- structured JSON logs to stdout;
- OpenTelemetry traces;
- Micrometer/Prometheus-compatible metrics;
- W3C trace propagation across REST/gRPC/Kafka/workers;
- bounded low-cardinality dimensions;
- relevant SLI/SLO/error-budget/burn and saturation signals.

Base safe operational fields may include timestamp, level, service name/version, environment, trace/span/correlation identity, and stable event code. High-cardinality business/security identifiers are not metric labels.

Logging is allow-list based. Raw credentials, passwords/PIN/OTP/recovery codes, tokens/API keys, authorization/cookie headers, session/pre-auth IDs, private keys/secrets, connection strings, payment/high-risk PII, full request/response bodies, SQL binds, complete gRPC metadata, Kafka headers, and unreviewed provider payload/exception text are prohibited.

Input-derived fields are CR/LF-safe. Production debug elevation is time-bounded/audited and cannot enable sensitive body/bind/credential logging.

Ordinary non-audit telemetry export follows `OBSERVABILITY` dependency class: bounded buffering/drop may keep primary business request serving, but sustained loss/backpressure is observable/alertable. **Required security/audit evidence is different**: when current operation contract classifies it as `AUTHORITATIVE_STATE`, it must be durably persisted/outboxed according to that contract and cannot be silently dropped merely because exporter/backend is unavailable. Log-store access is least privilege and audited.

Authorization records bounded SLO/deny/unavailable/overload/queue/SQL/pool/breaker signals without user/tenant/Membership IDs as metric labels. Required management/platform audit stays in durable local evidence and is not replaced by ordinary telemetry export. Routine `CheckPermission` allow/deny does not synchronously append one durable audit row per request.

Web BFF observability includes bounded route-class latency/error/in-flight, downstream dependency latency/outcome, Redis session/quota latency/failover, session write-coalescing effectiveness, pre-auth-limit/quota rejection counts, key-ring age/reload/staleness, token-broker failures, body/header-limit rejections and egress/policy denials. It excludes raw URLs when cardinality/sensitivity is unbounded and never labels metrics with user/session/pre-auth/tenant/membership/provider-subject/token IDs.

Compromised Password observability includes lookup latency/outcome, bounded in-flight/queue, SQLite open/query/I/O failure categories, safe dataset-version/format health, safe aggregate record-count/max-prefix-cardinality build evidence and storage saturation. It never logs or labels metrics with password material, SHA-256 prefix/suffix/full hash, returned rows, User/Tenant/Contact/session identifiers, caller metadata, or unreviewed SQLite/JDBC/native exception text.

## 13. Required failure evidence

Critical components run applicable timeout/cancellation, overload/bulkhead, PostgreSQL primary loss, Redis Sentinel failover, Kafka broker/controller/replay, OpenBao restore/unseal, WAF/Istio/NetworkPolicy negative, Authorization tenant/platform fail-closed/recovery, owner-safety concurrency, admin-quota-before-DB/no-refund, erased-authority restore reconciliation, Notification provider ambiguity/crash/failover, backup/PITR/DR, ordinary telemetry-loss behavior, required-audit persistence failure behavior, and SLO/burn-alert correctness tests.

Web BFF failure evidence additionally covers client cancellation, session/quota Redis outage/failover, Google/evidence/token-broker/Authorization-management/resource-service outage, no retry/fabrication, stale key snapshot <=1h then fail closed, session rotation/revocation/erasure during failures, OIDC replay/quota pressure, and deny-by-default egress/wrong-workload behavior.

Compromised Password failure evidence covers malformed prefix, missing/incompatible/corrupt dataset, fixed-query/read failure, path/URI manipulation negatives, no-write/DDL/ATTACH/extension policy, storage latency/saturation, bounded concurrency/queue overload, deadline/cancellation, no response truncation/false clean result, replica/node loss with identical dataset version, no external provider/Internet egress, Java/native dependency compatibility, immutable-artifact rebuild/redeploy and PII/hash-safe telemetry.
