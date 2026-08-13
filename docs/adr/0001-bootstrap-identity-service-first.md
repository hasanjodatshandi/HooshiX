# ADR 0001: Bootstrap Identity Service First and Define Java Namespace

## Status

Accepted

## Date

2026-08-04

## Context

The backend architecture identifies a Web BFF, Identity Service, Subscription
Service, Billing Service, and additional bounded-context services, but it does
not define the implementation order.

The first executable service establishes repository-wide precedents for the
exact Spring Boot 4.1.x patch, independent Gradle builds, Java 25 enforcement,
DDD and Hexagonal boundaries, gRPC and Protobuf ownership, persistence,
container packaging, Helm deployment, and service verification.

The root Gradle build is limited to repository governance. Independently
deployable services own independent build and release lifecycles.

The organization Java namespace was also undefined. Application code must not
use the reserved `java.*` namespace. Sajtech's reverse-DNS namespace is
`com.sajtech`.

## Decision

1. The first independently executable backend service is `identity-service`.
2. Its repository path is `services/identity-service`.
3. Each independently deployable service owns its own `settings.gradle.kts`,
   `build.gradle.kts`, Gradle Wrapper, dependency verification, contracts,
   source sets, container build, and Helm deployment package.
4. The root Gradle build must not include independently deployable services as
   subprojects.
5. The second executable backend component is `web-bff`.
6. The first complete vertical slice is React to Web BFF over REST/OpenAPI,
   followed by Web BFF to Identity Service over gRPC with mesh mTLS and
   workload identity.
7. The organization Java namespace and Gradle group are `com.sajtech`.
8. The Identity Service base Java package is `com.sajtech.identity`.
9. The first Identity Service build selects and verifies the exact approved
   Spring Boot 4.1.x patch and updates the Technology Baseline.
10. Tenant-aware identity persistence and schema design remain blocked until
    the multi-tenancy ADR is accepted.

## Consequences

### Positive

- The first service represents a real bounded context rather than a generic
  executable template.
- Internal gRPC, workload identity, service isolation, and independent
  deployment ownership are established before the public BFF.
- The Web BFF receives a real downstream provider for its first vertical slice.
- Java package and Gradle coordinate conventions have an explicit
  organization-owned root.
- The Spring Boot patch is selected through an executable service build.

### Negative

- A browser-facing flow is unavailable until the Web BFF is implemented.
- Some identity persistence work remains blocked by the multi-tenancy decision.
- A Wrapper per service creates deliberate duplication.
- Renaming the namespace later requires source and contract migration.

### Risks and mitigations

- **Risk:** Identity scope expands into authorization, tenancy, or profile
  concerns without bounded-context analysis.
  **Mitigation:** Keep the initial contract narrow and record material scope
  changes through ADRs.
- **Risk:** Independent Wrappers drift across services.
  **Mitigation:** Verify each service Wrapper against the Technology Baseline
  and automate controlled upgrades.
- **Risk:** Protobuf packages become coupled to implementation packages.
  **Mitigation:** Version Protobuf API packages independently and never reuse
  Java domain models as transport messages.

## Alternatives considered

### Bootstrap Web BFF first

Rejected because it would initially depend on mocks and could encourage
business behavior to accumulate in the BFF.

### Bootstrap Subscription Service first

Rejected because subscription boundaries and persistence are likely to depend
on unresolved tenancy and product-model decisions.

### Bootstrap Billing Service first

Rejected because billing introduces provider, idempotency, audit, and
compliance decisions too early.

### Create a generic executable service template first

Rejected because each microservice must represent a real business capability or
bounded context.

### Aggregate services in the root Gradle build

Rejected because independently deployable services require independent build
and release ownership.

## Rollback or migration considerations

This accepted ADR is immutable. A later decision must supersede it.

Before external contracts or deployments exist, changing implementation order
requires a superseding ADR. Changing `com.sajtech`,
`com.sajtech.identity`, the repository path, or service identity after
publication requires a migration plan covering Java packages, Gradle
coordinates, Protobuf options, image names, Kubernetes resources,
ServiceAccounts, Helm releases, observability attributes, CI, and delivery
references.
