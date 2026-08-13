# ADR-0053: Enforce a Dedicated PostgreSQL Database per Microservice in v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR makes the database-per-service boundary explicit for the production v1 architecture.

It extends ADR-0001, ADR-0002, and ADR-0048. It does not require one physical
PostgreSQL/CloudNativePG cluster per microservice. ADR-0048's shared physical
3-instance CloudNativePG HA cluster remains the initial production topology.

Where earlier current-state documentation used phrases such as "private logical
database/schema", this ADR clarifies that every independently deployable
microservice with relational persistence owns a **distinct PostgreSQL database**,
not merely a schema inside a database shared with another microservice.

## Decision

### Dedicated database boundary

Every independently deployable microservice that uses PostgreSQL owns a distinct
PostgreSQL database. Examples for the initial services are:

```text
identity-service      -> identity
authorization-service -> authorization
notification-service  -> notification
```

A service may create multiple schemas inside its own database when technically
justified, but no schema may be shared with another microservice.

### Identity, credentials, and privileges

Each service has independent database credentials and privilege boundaries.
Production separates at least:

- an application runtime role with only the DML/object privileges required by
  the running service; and
- a migration role used by the controlled Flyway migration path with the DDL
  privileges required to evolve that service's database.

A service's runtime and migration roles MUST NOT have `CONNECT`, schema, table,
sequence, function, or ownership privileges on another service's database.
Default/public privileges are reviewed so that creating a new database does not
implicitly grant cross-service access.

Secrets are independently materialized and rotated per service. Sharing one
application database credential across services is prohibited.

### Integration boundary

The following are prohibited:

- cross-service SQL and cross-database joins;
- cross-service foreign keys;
- direct reads/writes to another service's database;
- shared ORM/JPA entities or jOOQ-generated database models across services;
- using database views, FDWs, dblink, logical replication, or database grants as
  an undeclared service-integration API;
- distributed business transactions spanning service databases.

Cross-bounded-context integration uses approved gRPC/Protobuf or Kafka/Protobuf
contracts and the established outbox/idempotency rules.

### Physical cluster topology

Multiple dedicated service databases MAY initially reside on the same physical
CloudNativePG HA cluster. This is an infrastructure consolidation choice only
and does not weaken service data ownership.

The shared physical cluster remains a common capacity/failure domain. A service
moves to a dedicated physical PostgreSQL cluster only when measured SLO,
capacity, noisy-neighbor, recovery, compliance, security-isolation, or tuning
requirements justify the additional operational cost.

A physical split must preserve the database name/ownership contract and must not
require Domain/Application code to know where PostgreSQL is hosted.

### Backup and restore

Cluster-level WAL/PITR remains governed by ADR-0027 and ADR-0048.

Before production, operations must demonstrate recovery of a single
service-owned database without restoring another service over live production
state. The supported procedure may restore/clone the physical cluster to an
isolated recovery environment and then extract/restore only the target service
database.

This requirement avoids coupling a service-level recovery operation to a
cross-service destructive restore.

## Verification Requirements

Production verification includes:

- a distinct PostgreSQL database for every persistent microservice;
- distinct runtime credentials per service;
- controlled Flyway migration credentials/path per service;
- negative `CONNECT` and object-access tests against every other service
  database;
- no cross-service FK/FDW/dblink/database-view integration;
- independent Flyway history inside each service-owned database;
- connection-pool budgets attributed per service while remaining inside the
  shared physical-cluster capacity ceiling;
- a tested single-service database recovery procedure from the approved backup
  artifacts; and
- architecture tests/repository checks preventing direct cross-service
  persistence dependencies.

## Consequences

- Database ownership is enforceable at the PostgreSQL privilege boundary, not
  merely by code convention.
- A compromise of one application database credential does not directly grant
  access to another service's database.
- Service migrations and persistence models remain independently evolvable.
- The initial topology avoids multiplying three-instance PostgreSQL clusters for
  every service before evidence justifies that cost.
- The shared physical cluster remains a capacity/failure-domain bottleneck and
  is monitored with explicit split triggers.

## Rollback or Migration Considerations

This is a boundary-hardening decision and must not be rolled back to shared
service schemas/databases.

If existing service data temporarily shares a database, migration is forward:
create the dedicated database and roles, migrate/copy data with validation,
switch one service, remove old privileges only after verification, and preserve
forward-only Flyway history. Do not solve migration by granting permanent
cross-database access.
