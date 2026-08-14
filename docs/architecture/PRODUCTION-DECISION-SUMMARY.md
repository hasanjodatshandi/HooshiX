# Production Decision Summary — Current State

- **Reviewed:** 2026-08-15
- **Selected profile:** `production-single-server`
- **Expansion profile:** `production-ha`
- **Implementation evidence:** NOT VERIFIED; see `implementation-status.md`

## 1. Selected single-server topology

- one K3s `v1.35.6+k3s1` server/workload node;
- Kubernetes 1.35.6;
- Calico CNI/NetworkPolicy; K3s Flannel/policy controller disabled;
- bundled K3s Traefik/ServiceLB disabled; repository Traefik + Caddy/Coraza edge used;
- one application replica; HPA off; availability PDB off;
- one physical CloudNativePG/PostgreSQL instance with distinct DB/runtime/migration roles/Flyway per mutable service and forced RLS where applicable;
- continuous WAL/PITR/encrypted off-site backup; `pg_dump + cron` not primary recovery;
- one TLS/ACL/`noeviction`/AOF security Redis;
- one combined KRaft Kafka broker/controller, RF1/minISR1/acks-all/idempotence, explicitly non-HA;
- Istio Ambient and blocking Kyverno retained subject to complete-stack capacity evidence;
- OpenBao 2.6.1 remains secret authority; end-user MFA semantics unchanged;
- WireGuard-only normal management reachability + FIDO2 + JIT + off-host audit.

## 2. Public network/client identity

ADR-0043 path is mandatory:

```text
Internet -> upstream mitigation -> external L4 -> Traefik -> Caddy/Coraza -> BFF
```

- external L4 preserves validated client address through trusted PROXY v2;
- direct non-approved Internet access to Traefik origin denied before application routing;
- insecure proxy/forwarded-header trust prohibited;
- BFF receives one server-derived exact canonical IP only through WAF path;
- raw client IP is not ordinary telemetry/business state.

ADR-0024 derives:

```text
exact hard quota identity: IPv4 /32, IPv6 /128
aggregate pressure only:   IPv4 /24, IPv6 /64
```

Aggregate prefix is not the sole v1 hard 429 bucket.

## 3. Semantic quota safety

Redis security quotas preserve:

- atomic hard-dimension decision;
- <=2s app/Redis skew;
- JVM wall-vs-monotonic Clock Safety Guard for common-mode host clock steps;
- host time synchronization before quota-protected traffic and 60s stable re-arm after guard trip;
- `noeviction` and no security TTL reset;
- bounded cleanup;
- low-cardinality new-bucket allocation guard before memory exhaustion;
- >=30% validated Redis memory reserve;
- `QUOTA_TIME_SOURCE_UNHEALTHY` / `QUOTA_CAPACITY_UNHEALTHY` distinct from ordinary quota denial;
- adversarial unique-subject/address load evidence.

## 4. Compromised Password

The independent Compromised Password service remains.

V1 corpus authority is official offline **HIBP Pwned Passwords SHA-1** data:

- SHA-1 is only compromised-password lookup identity;
- password storage remains Argon2id;
- Identity computes full SHA-1 locally, sends five-hex prefix only, and exact-compares returned 35-hex suffixes locally;
- runtime service has no HIBP/provider dependency;
- immutable SQLite stores 20-byte SHA-1 + prefix + positive count;
- dataset acquisition/provenance/full-corpus cardinality/serialized bounds are release evidence;
- production dataset age <=35 days and build/acquisition verification runs at least every 30 days;
- stale/corrupt/missing/incompatible data fails closed.

## 5. Reference Data

Reference Data capability is decided but its **independent microservice is gated**.

Before the trigger, the approved immutable bundle may be used in the owning deployable, initially BFF when required.

Create `reference-data-service` only after evidence of at least one:

- >=2 independently deployable consumers;
- independent release/update lifecycle;
- independent security boundary;
- independent scale/availability profile;
- independent team/operational ownership.

One user journey/route group alone is insufficient.

## 6. Day-One observability

ADR-0044 applies from the first executable service commit.

```text
structured JSON logs -> otelcol-contrib -> Loki
Micrometer metrics    -> Prometheus -> Alertmanager
OpenTelemetry traces  -> otelcol-contrib -> Tempo
Prometheus/Loki/Tempo -> Grafana
```

Pinned current additions:

- `otelcol-contrib` 0.157.0;
- Loki 3.7.4;
- Tempo 3.0.2;
- existing Prometheus 3.13.2 / Alertmanager 0.33.1 / Grafana 13.1.3 retained.

Single-server Loki is single-binary/non-HA; Tempo monolithic/non-HA. Collector is internal-only and may read only exact Kubernetes pod/container log paths through the narrow read-only ADR-0044 mount exception.

Trace/baggage/correlation is telemetry only, never authN/authZ/tenant/quota/idempotency/audit authority. Metric labels remain low-cardinality/PII-safe.

Because local monitoring shares the host failure domain, production requires an independent external black-box availability signal outside that host. Provider remains TBD until environment selection.

Required security/privileged audit remains separate durable/off-host authority.

## 7. Kyverno

Kyverno 1.18.2 remains blocking/fail-closed. Greenfield production policies use stable CEL-based `policies.kyverno.io/v1` types. CI/render gates reject new legacy `ClusterPolicy`/`CleanupPolicy` manifests.

## 8. Governance

- merged ADR IDs are permanent and never renumbered/reused;
- current-state documents remain current-only;
- fully superseded ADRs retain compact stable-ID provenance/pointer and are not current authority;
- one PR represents one coherent engineering change, not one conversation prompt;
- material post-merge defects may use focused follow-up PRs rather than being delayed by prompt boundaries.

## 9. Production approval

No documentation above is runtime proof.

Production approval still requires complete-stack simultaneous benchmark, >=30% CPU/RAM headroom, safe WAL+AOF+Kafka+telemetry IO, security/admission/network/quota negatives, real observability/alerting, external host-loss detection, restore/PITR/cold-DR evidence, and all mandatory readiness gates `PASS`.