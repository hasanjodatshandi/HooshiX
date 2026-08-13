# ADR-0007: Standardize Feature-First, Nature-Separated Java Packages

## Status

Accepted

## Date

2026-08-06

## Context

The backend architecture requires DDD, Hexagonal Architecture, inward
dependency direction, small single-purpose files, and package-by-feature for
large services.

A flat feature package mixes aggregates, entities, value objects, ports, events,
persistence models, mappers, configuration, and workers. A purely global
package-by-layer structure separates related business concepts and makes
ownership harder to identify.

A repository-wide rule is required so services and generated code use the same
organization and ArchUnit can enforce it instead of relying on agent memory.

## Decision

### Primary organization

Java packages use this order:

```text
architectural layer
  -> business feature or domain module
    -> type nature or technical responsibility
```

Examples:

```text
domain/user/valueobject
application/user/usecase
infrastructure/user/persistence/jpa/entity
interfaces/user/grpc
configuration/user
```

Top-level service packages remain:

```text
domain
application
infrastructure
interfaces
configuration
```

Dependencies continue to point inward:

```text
infrastructure -> application -> domain
interfaces     -> application -> domain
configuration  -> application/domain and adapters for composition
```

### Domain feature structure

A domain feature may contain:

```text
domain/<feature>/
├── aggregate/
├── entity/
├── valueobject/
├── event/
├── exception/
├── repository/
└── service/
```

Aggregate roots belong in `aggregate`, even though they are also entities.
`entity` is for non-root entities owned by an aggregate.

Domain repository interfaces remain technology-independent.

A repository that persists and reconstitutes a Domain aggregate belongs in the
feature's `domain/<feature>/repository` package. The same aggregate repository
contract must not be duplicated under `application/<feature>/port/out`.

Application outbound ports represent non-domain technical capabilities or
cross-boundary interactions needed by a use case, including password hashing,
message delivery, external provider calls, and integration-event publication.

Domain packages depend only on the JDK and approved domain primitives.

### Application feature structure

An application feature may contain:

```text
application/<feature>/
├── command/
├── query/
├── dto/
├── port/
│   ├── in/
│   └── out/
├── usecase/
└── saga/
```

A saga package is created only when an actual long-running workflow exists.

Application depends only on Domain and approved application primitives. It does
not depend on Spring, JPA, Kafka, Redis, gRPC, or concrete adapters.

### Infrastructure feature structure

Infrastructure packages reflect technical responsibilities actually
implemented by the feature:

```text
infrastructure/<feature>/
├── persistence/
│   ├── jpa/
│   │   ├── entity/
│   │   ├── repository/
│   │   ├── mapper/
│   │   ├── specification/
│   │   └── adapter/
│   └── query/
├── cache/
├── config/
├── di/
├── messaging/
│   ├── producer/
│   ├── consumer/
│   ├── outbox/
│   └── inbox/
├── observability/
├── security/
└── worker/
```

The hierarchy is not created merely to reproduce a template.

Service-wide Spring composition belongs under `configuration/<feature>`.
Infrastructure-local configuration may remain under
`infrastructure/<feature>/config` when it configures only that adapter.
Composition of ports and adapters belongs under `configuration/<feature>` unless
inseparable from one adapter.

### Interface feature structure

Inbound adapters use:

```text
interfaces/<feature>/
├── grpc/
├── rest/
└── kafka/
```

Only packages required by implemented transports are created.

Controllers, handlers, and listeners perform validation, mapping, security
context extraction, and use-case invocation. Business logic remains in Domain
or Application.

### Naming rules

Package names:

- use lowercase ASCII letters and digits;
- use no hyphens;
- use no underscores;
- use singular or established technical terms consistently.

Required examples:

```text
valueobject
usecase
configuration
observability
persistence
```

Prohibited examples:

```text
value-object
value_object
use_cases
```

Generic dumping-ground packages such as `common`, `util`, `helper`, `manager`,
`misc`, or `generic` are prohibited for business code.

### File rules

Each aggregate, entity, value object, event, command, query, DTO, port,
meaningful exception, persistence entity, repository adapter, mapper,
controller, listener, worker, and configuration class has its own file.

A Java source file normally contains one public top-level type. Closely scoped
private or package-private helpers are allowed only when they cannot be reused
independently and do not hide a separate responsibility.

Ambiguous class names such as `Manager`, `Helper`, `Util`, and `GenericService`
remain prohibited.

### Package creation

The structure is incremental.

A package is created only when it contains or immediately receives a concrete
type. Empty package trees and `.gitkeep` files used only to display a future
taxonomy are prohibited.

Small features may omit unused nature packages but must place implemented types
under the correct responsibility.

### Persistence separation

Domain entities and JPA entities are separate.

JPA entities, Spring Data repositories, query specifications, persistence
mappers, and adapters remain under infrastructure persistence.

A JPA entity must not be imported by Domain, Application, inbound adapters,
external contracts, or another service.

### Enforcement

ArchUnit tests enforce at least:

- Domain does not depend on Spring, JPA, Infrastructure, Interfaces, Kafka,
  Redis, or gRPC;
- Application does not depend on Infrastructure, Interfaces, or concrete
  adapters;
- JPA entities remain in infrastructure persistence;
- inbound adapters invoke Application ports or use cases;
- prohibited dumping-ground packages do not exist;
- package names follow lowercase, no-hyphen, no-underscore rules;
- dependency cycles are absent.

Repository verification and CI run architecture tests.

## Consequences

- Feature ownership remains visible while files stay separated by nature.
- Related business types remain close without flat dumping-ground packages.
- Paths become deeper, but navigation and enforcement become predictable.
- Domain and persistence types require explicit mapping.
- Empty template packages are avoided.
- Existing services adopt the structure incrementally.
- ArchUnit configuration becomes part of service governance.

## Alternatives considered

### Flat package per feature

Rejected because aggregates, values, events, repositories, commands, DTOs,
mappers, and adapters become mixed as a feature grows.

### Global package-by-layer only

Rejected because service-wide command, DTO, repository, and model folders become
dumping grounds and obscure business ownership.

### Put nature before feature

Example:

```text
domain/valueobject/user
application/usecase/user
```

Rejected because the business feature is less visible and related feature types
are spread across the service.

### Create the entire package tree at bootstrap

Rejected because empty packages communicate no executable architecture and
become stale templates.

### Use hyphens or underscores

Rejected because hyphens are invalid Java package identifiers and underscores
conflict with the selected convention.

### Store aggregate roots under entity only

Rejected because explicit `aggregate` placement makes aggregate boundaries and
transaction ownership visible.

## Rollback or migration considerations

No runtime data migration is required.

Existing package roots from the Identity Service foundation remain valid.
Feature and nature packages are introduced incrementally.

Once public contracts or serialized class names depend on package paths,
renaming requires compatibility review. Domain classes must not be serialized
as REST, gRPC, or Kafka contracts, limiting this risk.

A later ADR may supersede this structure if evidence requires a change.
Accepted ADR history remains unchanged.
