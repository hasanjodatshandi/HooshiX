# ADR-0026: Git + Buf Contract Governance Without Runtime Schema Registry v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Protobuf API/event schemas are owned in Git. v1 does not deploy a runtime Schema Registry.

### Protobuf compatibility

CI enforces the repository-approved equivalent of:

```text
buf lint       -> STANDARD
buf breaking   -> FILE policy against main/current compatibility base
```

Field numbers are never reused. Removed field names/numbers are reserved where required to prevent accidental reuse. Generated source is derived from the canonical contract; copied/manual duplicate schemas are prohibited.

Service/version/package ownership remains explicit. Transport messages do not become shared Domain models.

### Runtime behavior

Consumers/producers use build/release-pinned generated contracts. Runtime schema discovery/registration is not a request-path dependency in v1.

Introducing a runtime Schema Registry, dynamic schema-discovery authority, or incompatible compatibility policy requires a new or revised current ADR with availability, security, migration, ownership, and rollback evidence.

## Verification requirements

Run Buf lint/breaking checks, generated-source consistency checks, field-number/reservation validation, service contract tests, and compatibility checks for every changed public/internal/event contract. CI MUST fail incompatible contract changes that violate the current policy.

## Rollback considerations

Rollback uses a contract/application combination that remains wire compatible with deployed peers/events. It MUST NOT reuse removed field numbers, publish incompatible schemas, or introduce an unreviewed runtime schema dependency.