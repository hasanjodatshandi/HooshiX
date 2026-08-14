# Platform Architecture — Current State

## 1. Architectural baseline

HooshiX uses DDD + Hexagonal Architecture. Independently deployable boundaries require real capability/ownership/lifecycle/security/scale value; a UI page, table, endpoint, or single journey is not sufficient reason for a microservice.

Backend dependency direction:

```text
Infrastructure -> Application -> Domain
Interfaces     -> Application -> Domain
```

Domain/Application do not depend on framework, persistence, messaging, transport, Kubernetes, or concrete adapters.

`implementation-status.md` is authoritative for whether planned source/deployment/CI/runtime evidence exists. Architecture paths are not implementation evidence.

## 2. Service/data ownership

Every independently deployable service owns its business rules/contracts/release lifecycle and, when it has mutable relational business state, a distinct PostgreSQL database/runtime role/migration role/Flyway history.

Physical PostgreSQL placement is profile-specific:

- single-server: one shared physical CNPG/PostgreSQL instance with distinct logical DB/role/Flyway/RLS boundaries;
- HA: current dedicated cluster model.

Cross-service SQL/credentials/FK/view/FDW/`dblink`/shared persistence models are prohibited.

ADR-0040 is the narrow immutable SQLite exception for HIBP-derived Compromised Password reference data. It contains no subject-owned business state and has no runtime SQLite mutation/Flyway/provider call.

ADR-0041 Reference Data is a capability, not automatically a microservice. Before its independent-service trigger, the approved immutable bundle may live in its owning deployable, initially BFF when needed. One journey/route group does not justify a service.

## 3. Repository/build ownership

Each independent service, when implemented, owns Wrapper/build/dependency verification/contracts/source sets/container/deployment package. Root build/governance does not turn services into one release unit.

Organization namespace: `com.sajtech`.

Coding/package/DI rules live in Engineering standards and are machine-enforced where feasible.

## 4. High-level topology

```text
Browser
  -> upstream L3/L4 mitigation
  -> external L4
  -> Traefik
  -> Caddy/Coraza WAF
  -> Web BFF
       -> Identity
       -> Authorization
       -> Notification-related internal flows as registered
       -> resource services as implemented
       -> Reference Data local immutable adapter initially, or remote service only after ADR-0041 trigger

Identity -> Compromised Password gRPC -> local immutable HIBP SHA-1 SQLite corpus

Mutable services -> distinct logical PostgreSQL DB boundaries
Approved async flows -> Kafka
Security/session state -> Redis
Secrets -> OpenBao/ESO/local mounted material

Ordinary observability:
service JSON logs -> otelcol-contrib -> Loki
Micrometer metrics -> Prometheus -> Alertmanager
OTel traces -> otelcol-contrib -> Tempo
Prometheus/Loki/Tempo -> Grafana
external black-box monitor -> public edge from outside host failure domain

Management:
approved device -> WireGuard -> OpenSSH/FIDO2 -> JIT privilege
```

Only approved public BFF/edge surfaces are Internet reachable. Internal services are ClusterIP-only/deny-by-default.

## 5. Selected production profile

ADR-0042 selects `production-single-server`:

```text
1 physical server
1 K3s server/workload node
1 app replica per implemented independent service
HPA off
availability PDB off
1 shared physical PostgreSQL instance
1 Redis instance
1 combined KRaft broker/controller
local ordinary observability stack, explicitly non-HA
```

HA expansion retains current redundant topology.

Single-server changes infrastructure availability only. It does not weaken authentication/MFA, Authorization, RLS, OpenBao, WAF/client trust, quota fail-closed safety, supply chain/admission, required audit, backup/PITR, idempotency, or data ownership.

## 6. Protocol boundaries

### Browser/public

HTTPS REST/OpenAPI through BFF. Browser never receives provider/internal refresh/access credentials. Caller forwarding/trace/correlation headers are not security authority.

Reference-data GET/HEAD may be anonymous/cacheable when implemented under ADR-0041, but still uses the mandatory edge/WAF path and same-origin browser policy.

### Internal synchronous

gRPC + Protobuf with finite deadlines, cancellation, one retry owner, bounded concurrency, workload identity, explicit operation criticality/failure semantics.

### Async

Kafka + Protobuf only where asynchronous semantics justify it. Transactional Outbox for state+event atomic intent; at-least-once/idempotent consumers; no business authority in broker.

### Telemetry

Micrometer/OpenTelemetry/OTLP/log forwarding is observability transport only. Trace/baggage/log correlation does not define authN/authZ/tenant/quota/idempotency/business/audit identity.

## 7. Current capabilities/deployables

Current independent service targets:

- Identity;
- Authorization;
- Notification;
- Web BFF;
- Compromised Password.

Reference Data capability is current, but the independent `reference-data-service` remains **PLANNED / GATED** until ADR-0041 trigger evidence exists.

Future Workflow or other services require independent bounded-context evidence; no speculative service creation.

### Compromised Password

Identity computes HIBP screening SHA-1 locally and sends only five uppercase prefix hex chars. Compromised Password returns positive-count 35-hex suffix candidates from its approved immutable offline HIBP SHA-1 SQLite corpus. Identity performs exact full SHA-1 comparison.

SHA-1 is screening-only; Argon2id remains password-storage authority. No runtime HIBP provider call exists.

### Reference Data

Country/Currency/TimeZone/SupportedLocale source/lifecycle governance remains centralized as a capability. Before independent deployment, BFF may use the immutable bundle locally. Independent gRPC service is created only when consumer/lifecycle/security/scale/ownership evidence justifies it.

## 8. Semantic quota/client network

ADR-0043 supplies one exact trusted BFF client address. ADR-0024 derives:

```text
exact hard identity: IPv4 /32 | IPv6 /128
aggregate pressure:  IPv4 /24 | IPv6 /64
```

Aggregate prefix is not the sole v1 hard user-deny bucket.

Quota security also requires app/Redis skew validation, local wall-vs-monotonic common-mode clock guard, host-sync readiness/re-arm, no security TTL reset, `noeviction`, bounded high-cardinality allocation, and distinct time/capacity failure results.

## 9. Day-One observability

ADR-0044 is part of the first executable service Definition of Done.

Current single-server target:

- Micrometer Observation/Tracing + OpenTelemetry;
- internal OTLP to `otelcol-contrib` 0.157.0;
- Prometheus 3.13.2 + Alertmanager 0.33.1;
- Loki 3.7.4 single-binary/non-HA;
- Tempo 3.0.2 monolithic/non-HA;
- Grafana 13.1.3;
- independent external host-down monitor before production.

Collector has internal-only ingress, restricted egress/RBAC, finite queues/memory, and only the narrow exact read-only pod/container-log filesystem mount permitted by ADR-0044.

Required security/privileged audit remains a separate durable off-host authority.

Observability components compete for the same host resources and are included in complete-stack capacity evidence.

## 10. Technology families

Architecture families include Java 25/Spring Boot 4.1/Spring MVC/Virtual Threads, Gradle Kotlin DSL, PostgreSQL/CNPG/Flyway, Xerial SQLite only for ADR-0040, Kafka, Redis, Resilience4j, Micrometer/OpenTelemetry, Prometheus/Loki/Tempo/Grafana/Alertmanager/Collector, Kubernetes/K3s/Calico/Helm/Argo CD, Kyverno CEL policies/Cosign, Traefik/Caddy/Coraza, Istio Ambient, ESO/OpenBao, WireGuard/OpenSSH/FIDO2, and current Notification providers/testing stack.

Exact pins live only in Technology Baseline and deployment/dependency locks.

## 11. Security/resilience constraints

- single-server is non-HA and cannot claim node/data/control-plane/telemetry failover;
- trusted client identity uses ADR-0043 only;
- quota failure never becomes fail-open;
- new Kyverno production controls use stable CEL-based v1 APIs and legacy policy types are gate-rejected;
- OpenBao and end-user MFA remain unchanged;
- insufficient host capacity means safe tuning/more capacity/externalizing ordinary telemetry/HA, not weaker controls;
- cold recovery follows current DR runbook and restores observability before declaring recovery complete.

## 12. Change rule

Architecture changes update the applicable current authority, Decision Register/Sources/Task Matrix/evidence/baseline/status maps in one coherent PR. Merged ADR IDs are stable and are never renumbered/reused.

Implementation MUST NOT silently replace a bounded context, data owner, security invariant, communication model, profile, network trust, telemetry authority, or availability claim.