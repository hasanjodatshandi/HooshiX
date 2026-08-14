# Java Coding Standards — Current Canonical Standard

This is the canonical implementation standard for Java backend services. Current architecture/ADRs own product boundaries; SQL/Flyway details are in `sql-and-flyway-coding-standards.md`; executable build/CI gates are in `build-and-ci-quality-enforcement.md`.

Machine-checkable rules SHOULD be enforced by ArchUnit, Spotless, SpotBugs, Semgrep, dependency verification, contract/schema checks, render/policy checks, or CI rather than agent memory.

## 1. Architecture and dependency direction

Backend services use DDD + Hexagonal Architecture. Clean Architecture is used only for inward dependency direction.

```text
infrastructure -> application -> domain
interfaces     -> application -> domain
configuration  -> application/domain + adapters for composition
```

Mandatory:

- Domain depends only on JDK/approved domain primitives.
- Domain MUST NOT depend on Spring, JPA/Hibernate, jOOQ, Kafka, Redis, SQLite, gRPC/Protobuf, PostgreSQL, Kubernetes/Istio, HTTP clients, transport/provider/generated types, or concrete adapters.
- Application depends on Domain + abstract ports only.
- Infrastructure implements persistence, messaging, cache, clients/providers, security, observability, local reference datasets, and technical outbound concerns.
- Interfaces contains inbound REST/gRPC/Kafka adapters, boundary validation/mapping/trusted-context extraction, and use-case invocation only.
- Configuration composes real adapters/use cases and contains no business policy.
- Business logic in controllers/handlers/listeners/repositories/mappers/configuration is prohibited.
- Domain events and integration/transport events are distinct types.
- Cross-service business/domain/persistence model sharing is prohibited.

## 2. Package structure

Packages are feature-first + nature-separated:

```text
com.sajtech.<service>/
├── domain/<feature>/{aggregate,entity,valueobject,event,exception,repository,service}/
├── application/<feature>/{command,query,dto,port/in,port/out,usecase,saga}/
├── infrastructure/<feature>/{persistence,cache,config,di,messaging,observability,security,client,worker,dataset}/
├── interfaces/<feature>/{rest,grpc,kafka}/
└── configuration/<feature>/
```

Rules:

- create packages only for real code;
- package segments match `[a-z][a-z0-9]*`;
- `common`, `util`, `helper`, `manager`, `misc`, `generic` business dumping grounds are prohibited;
- JPA/Spring Data/generated jOOQ/SQLite JDBC/provider types remain Infrastructure-only;
- aggregate repository abstractions stay in Domain;
- do not invent a competing global adapter taxonomy.

## 3. Files/types/responsibility

One meaningful public top-level type per file is the default. Distinct aggregate/entity/value-object/event/command/query/DTO/port/exception/persistence/mapper/adapter/controller/handler/listener/worker/config responsibilities normally use separate types.

Large constructors, giant switches/controllers/mappers, god classes, or mixed unrelated responsibilities trigger design review. Do not hide them behind vague helper/manager classes.

## 4. Dependency injection and configuration

Spring IoC is the sole DI container.

- required dependencies use constructor injection;
- field injection prohibited;
- Domain objects/events are never Spring beans;
- Application use cases remain plain Java and are normally composed in configuration;
- no `ApplicationContext`/`BeanFactory` lookup, service locator, runtime bean lookup, or concrete adapter construction in Domain/Application;
- circular dependencies and `@Lazy` cycle hiding prohibited;
- singleton beans stateless or explicitly thread-safe;
- related config uses typed `@ConfigurationProperties`;
- implementation selection is explicit, never classpath accident.

## 5. Domain/Application behavior

- aggregates enforce invariants at mutation boundaries;
- domain services exist only for genuine domain behavior;
- use cases orchestrate domain behavior, ports, transactions, authorization calls, and Outbox decisions without provider/persistence details;
- commands/queries/DTOs are explicit and bounded;
- trusted user/tenant/workload identity is validated at boundaries and passed explicitly;
- caller-controlled tenant/security context is never trusted by convention.

## 6. REST, gRPC, events, and errors

- public/browser REST is OpenAPI-first through BFF;
- public errors use RFC 9457 Problem Details or a reviewed extension and expose no stack/SQL/provider/secret/unreviewed PII;
- internal gRPC uses stable status + bounded reviewed metadata;
- every synchronous dependency has finite deadlines and cancellation where supported;
- retry is finite/safe/idempotent and owned by one layer only;
- ordinary request/reply MUST NOT use Kafka;
- state change + integration event as one business effect uses Transactional Outbox;
- consumers assume at-least-once and are idempotent.

Every new/changed synchronous production edge is represented in the dependency registry and defines workload identity, failure action, deadline, retry ownership, cancellation, idempotency, concurrency/queue, authorization tests, and observability.

## 7. Persistence and reference datasets

For mutable relational persistence:

- Flyway is sole schema-change mechanism;
- JPA `ddl-auto=validate`; OSIV prohibited;
- Domain and persistence models separate;
- LAZY by default; broad EAGER/N+1 prohibited;
- explicit column lists; `SELECT *` prohibited;
- every multi-row production query has pagination/hard bound;
- critical queries require index + representative plan evidence;
- transactions short/explicit;
- remote gRPC/HTTP/Kafka/Redis/provider I/O inside DB transaction prohibited;
- DB locks never held across remote I/O;
- retry outside failed transaction;
- released migrations immutable; evolve expand -> migrate -> contract.

### ADR-0040 Compromised Password dataset

This is the narrow immutable SQLite reference-data exception:

- v1 source is offline HIBP Pwned Passwords **SHA-1** corpus;
- SHA-1 is screening-only; password storage remains Argon2id;
- runtime SQLite is immutable/read-only/query-only;
- stored digest is 20-byte SHA-1 with 20-bit prefix; returned suffix is 35 hex characters;
- runtime write/DDL/ATTACH/extension loading prohibited;
- path/URI/query are server-owned;
- no full-corpus JVM cache;
- production dataset freshness <=35 days;
- complete-corpus cardinality/response compatibility is measured before release;
- no HIBP/provider runtime request.

### ADR-0041 Reference Data

Before the independent-service trigger, an owning deployable may use the approved immutable reference bundle as a local module/resource. Do not create a network service merely for one screen/journey. The separate service is implemented only after ADR-0041 evidence trigger.

## 8. Concurrency and Virtual Threads

Spring MVC + Virtual Threads is the blocking-I/O model.

- Virtual Threads do not create DB/Redis/provider/SQLite/CPU/memory/IO capacity;
- constrained dependencies use bounded in-flight concurrency and bounded/zero queues;
- CPU-heavy work uses bounded workers;
- `Thread.sleep` is prohibited for coordination/polling/test synchronization;
- no blanket Java 25 `synchronized` ban; use measurement for contention/pinning;
- shared mutable state has explicit ownership/synchronization tests.

## 9. Build/dependency/configuration

Each independent service owns Wrapper/Kotlin DSL build, dependency verification/locks, contracts, source sets, container, and deployment package.

- Java 25 toolchain;
- no dynamic versions/unbounded ranges/unapproved production SNAPSHOTs;
- dependency/tool addition requires purpose/owner/compatibility/integrity/security/license review;
- prefer Spring Boot alignment; overrides need rationale/tests;
- secrets never enter source/Git/build props/values/images/fixtures/logs/traces/CI;
- production security controls have safe defaults;
- generated code has explicit narrow paths;
- release output identifies reviewed Git revision + immutable digest;
- staging/prod use the same signed digest.

## 10. Day-One observability and logging

ADR-0031 and ADR-0044 are mandatory implementation contracts, not later cleanup.

Every new service/critical path implements applicable:

- structured JSON stdout logs with stable event code and allow-listed fields;
- Micrometer Observation/Metrics for request/operation/dependency/saturation behavior;
- OpenTelemetry tracing through internal OTLP Collector;
- W3C trace propagation where supported;
- health/readiness signals with correct dependency semantics;
- dashboard/alert ownership for defined SLO/security/reliability signals;
- telemetry failure/redaction/cardinality tests.

Do not log/trace raw:

- passwords/PIN/OTP/recovery/security answers;
- access/refresh/ID tokens, API keys, auth/cookie/session values;
- private keys/secrets/DB credentials;
- full request/response bodies, SQL binds, complete gRPC metadata/Kafka headers, unreviewed provider payloads;
- HIBP/SHA-1 compromised-password prefix/suffix/full hash or returned rows;
- unapproved contact/tenant/user/client-IP PII.

Rules:

- structured safe fields, not arbitrary object/string payloads;
- protect input-derived text from CR/LF/log injection;
- unreviewed exception/cause/provider text is sensitive;
- production debug/trace logging off by default; temporary elevation time-bound/audited;
- MDC and trace baggage are bounded/allow-listed;
- baggage/correlation values are never authN/authZ/tenant/idempotency/quota/audit authority;
- metric labels low-cardinality and exclude user/tenant/session/request/resource/trace IDs, raw URLs/IPs, and free-form errors;
- ordinary telemetry exporter/backend outage does not fail ordinary business processing;
- sustained telemetry loss/backpressure is observable/alerted;
- authoritative audit/security evidence is durably persisted/off-host and never reclassified as best-effort telemetry.

Every materially changed log/metric/trace is reviewed/tested for PII/secret/cardinality safety. Staging uses synthetic canary leak tests through the real telemetry path.

## 11. Semantic quota implementation

ADR-0024 is authoritative.

- BFF supplies one trusted exact binary client address only under ADR-0043.
- Owning service derives exact `/32` IPv4 or `/128` IPv6 hard identity and separate `/24`/`/64` aggregate pressure identity.
- Aggregate prefix is not the sole v1 hard 429 gate.
- App/Redis <=2s skew check remains.
- Quota-owning JVM implements local wall-vs-monotonic Clock Safety Guard for common-mode host clock steps.
- Boot/recovery requires healthy host synchronization; guard trip uses 60s stable re-arm conditions.
- `noeviction`, no security TTL reset, bounded cleanup, and >=30% memory headroom remain.
- New security-bucket allocation is bounded by low-cardinality capacity controls; unsafe memory/allocation state returns `QUOTA_CAPACITY_UNHEALTHY`, not fabricated quota denial or success.
- Capacity-guard state itself cannot be attacker-cardinality keyed.

## 12. Testability

- Domain/Application tests instantiate without Spring;
- deterministic/isolated/parallel-safe where intended;
- controllable time where practical;
- no fixed sleeps;
- test-only backdoors impossible in staging/prod;
- flaky tests have owner/remediation; retries do not redefine pass;
- Playwright uses semantic locators/web-first waits;
- BDD covers critical behavior, not trivial CRUD;
- ADR-0040 normal PR tests use deterministic generated fixtures, not production corpus material.

## 13. Container/Kubernetes/Helm/release

Production workload baseline:

- immutable image digest;
- non-root;
- `allowPrivilegeEscalation=false`;
- capabilities drop ALL by default;
- `RuntimeDefault` seccomp;
- read-only root filesystem where compatible;
- no privileged/host-network/`hostPath` without explicit current decision;
- finite CPU/memory;
- correct startup/readiness/liveness;
- graceful shutdown;
- dedicated ServiceAccount;
- deny-by-default NetworkPolicy + least-privilege Istio authorization.

ADR-0044 permits only the narrowly defined read-only Collector mount to exact Kubernetes pod/container log paths. It does not permit general host filesystem access.

Helm/GitOps:

- shared standards in reviewed library/application charts;
- values contain secret references only;
- `helm lint` + render/schema/policy/secret checks;
- complex migration hooks require explicit ownership/idempotency/timeout/failure/rollback evidence;
- no direct unreviewed production mutation;
- same signed digest staging -> production.

## 14. Prohibited practices

Unless a current decision explicitly permits them:

- business logic in adapters/configuration;
- framework-dependent Domain/Application;
- field injection/service locator/runtime lookup/cycles;
- cross-service DB/model sharing;
- backend WebFlux/Reactor;
- mutable SQLite business persistence under ADR-0040;
- remote I/O in DB transactions;
- unbounded query/retry/timeout/queue;
- save-then-direct-Kafka-send for atomic state+event;
- non-idempotent at-least-once consumers;
- raw sensitive/full-body logging or synchronous remote logging;
- telemetry header/baggage as business/security authority;
- floating production dependencies;
- broad suppressions/disabled tests/`ignoreFailures`;
- public Traefik dashboard/insecure trust/direct BFF bypass;
- default ServiceAccount/allow-all Istio/permanent PERMISSIVE;
- legacy Kyverno `ClusterPolicy`/`CleanupPolicy` for new production controls;
- secrets in Git/image/values;
- rebuild after staging validation.

## 15. Automated enforcement mapping

| Rule family | Primary enforcement |
| --- | --- |
| dependency/package/cycles | ArchUnit |
| formatting | Spotless |
| bytecode defects | SpotBugs |
| source security/logging/framework misuse | Semgrep/SAST |
| dependency integrity | Gradle verification/locks |
| contracts/migrations/datasets | Gradle + Testcontainers/fixtures + Buf/OpenAPI |
| observability privacy/cardinality/context | unit/integration/canary/static + Collector config tests |
| quota time/cardinality/network safety | Redis/integration/clock/chaos/load tests |
| container/Kubernetes/Helm/Kyverno CEL | schema/render/policy CI |
| digest/SBOM/signature/provenance | release pipeline/admission |
| final evidence | GitHub Actions + release/readiness gates |

## 16. Mandatory code-generation preflight

Before implementation code changes, review:

1. bounded context/use-case/final-enforcement owner;
2. inbound/outbound ports;
3. Domain/Application independence;
4. adapter placement;
5. sync/event semantics;
6. Outbox need;
7. deadlines/retry/cancellation/idempotency/concurrency/breaker/transaction;
8. migration/dataset-build/tests/**metrics/logs/traces/SLO/alerts**/runbook evidence;
9. ArchUnit impact;
10. dependency/tool compatibility/security/license;
11. Dockerfile/Helm/GitOps/probes/resources/HPA/PDB/SA/NetworkPolicy/securityContext/shutdown;
12. public route/WAF/DDoS/headers/CORS/CSRF impact;
13. BDD impact;
14. Playwright impact;
15. logging/error/trace PII/secret/CRLF/cardinality safety;
16. constructor injection/ports/no lookup;
17. Ambient/workload-identity/policy impact;
18. dependency registry and negative failure/auth tests;
19. same immutable digest staging->prod tied to Git revision;
20. migration/reference-dataset/observability/security-context workflow compliance.

Compilation alone is never completion evidence.