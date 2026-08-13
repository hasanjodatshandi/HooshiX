# ADR-0042: Define Authorization Runtime SLO, Capacity, and Deployment v1

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR operationalizes ADR-0039 without changing its online, one-attempt, no-cache, no-retry, no-stale-fallback semantics.

## Decision

`CheckPermission` production objectives:

- availability >=99.95% rolling 30d;
- p95 server latency <=75ms;
- p99 server latency <=150ms;
- caller deadline remains 300ms, exactly one attempt;
- wait-for-ready off; automatic retry off.

These objectives are intentionally stricter than the caller ceiling but realistic for an indexed database-backed online decision. A future tighter objective is earned by load evidence, not assumed by documentation.

### Production deployment

- minimum 3 replicas;
- PDB `minAvailable=2`;
- topology spread/anti-affinity across worker failure domains;
- HPA initial range: min 3, max 12;
- scaling uses bounded CPU plus request/concurrency signals where validated;
- independent ServiceAccount, Istio Ambient STRICT mTLS, explicit AuthorizationPolicy;
- bounded in-flight concurrency; no unbounded internal request queue.

### Hot path

`CheckPermission` synchronously depends only on Authorization's service-owned PostgreSQL. No Identity, Redis, Kafka, OpenBao, HTTP provider, or other microservice call is allowed in the permission hot path.

Authorization owns the durable membership-eligibility projection needed by evaluation. Identity remains membership lifecycle owner and propagates changes through its transactional outbox to Authorization's idempotent provisioning/update commands. This is owned durable state, not a permission-result cache.

Membership deactivation propagation objective is <=5s under healthy dependencies and alerts when exceeded.

Hot permission evaluation uses bounded SQL/jOOQ/JDBC with reviewed indexes and plans; broad ORM graphs and N+1 are prohibited.

Internal database budgets for the hot path are:

```text
pool acquisition ceiling: 50 ms
permission SQL ceiling:   100 ms
```

The 300ms caller deadline remains authoritative and child budgets never exceed the remaining request budget.

The resource-owning service performs the one final authorization check. BFF must not routinely duplicate the same `CheckPermission` merely for UX when the downstream resource operation will make the authoritative check.

### Capacity gate

Before production, prove >=2x projected peak QPS while meeting p95/p99 objectives with:

- Hikari pool-acquisition p99 <20ms under steady target load;
- no sustained queue growth;
- >=30% CPU/memory/database headroom;
- no N+1 or unbounded SQL result set;
- one application replica/node loss without violating the availability objective;
- CloudNativePG primary failover under sustained authorization traffic without stale allow or retry amplification.

If measured demand later makes the online query path the limiting platform bottleneck, optimize query/index/projection/capacity first. Reintroducing a local decision cache/read model requires a new ADR because it changes ADR-0039 freshness semantics.

## Verification Requirements

Precedence, inactive membership, cross-tenant denial, no retry/cache/fallback, exact deadline/status mapping, no downstream hot-path network calls, query plans, pool/SQL timeout tests, >=2x peak load, replica loss, PostgreSQL failover, Istio positive/negative policy, and PII-safe logs are mandatory.

## Consequences

Online authorization stays fresh and fail closed without creating a nested service chain. Its latency and database capacity are explicit production SLO concerns rather than hidden assumptions.
