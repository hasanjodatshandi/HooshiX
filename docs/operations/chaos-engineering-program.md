# Reliability and Chaos Engineering Program

## Purpose

The program validates that documented failure semantics actually hold. It is
staging-first and evidence-driven; it is not permission for uncontrolled random
fault injection in production.

## Principles

- test one explicit hypothesis at a time;
- define steady-state signals before injecting failure;
- define blast radius and abort criteria;
- protect customer data and security evidence;
- prefer staging until the same failure has passed there;
- production game days require owner, change window, incident readiness, and
  rollback/containment plan;
- never bypass an accepted security control simply to make a chaos test pass.

## Required cadence

| Exercise | Minimum cadence | Environment |
| --- | --- | --- |
| PostgreSQL-backed service backup verification | every backup cycle | isolated automation |
| PostgreSQL-backed service isolated restore | monthly | isolated recovery environment |
| Full cold-DR exercise | quarterly | isolated recovery environment |
| CloudNativePG primary/replica failure | at least quarterly per critical PostgreSQL-backed service; before material topology changes | staging first |
| Compromised Password immutable dataset rebuild/redeploy + missing/corrupt fail-closed | quarterly and before material dataset-format/storage changes | isolated/staging |
| Compromised Password replica/node loss with identical approved dataset version | quarterly or before material topology/storage changes | staging |
| Authorization outage/overload + breaker recovery | at least quarterly and before breaker-policy changes | staging/load environment |
| Redis failover for security quotas/session state | quarterly | staging |
| Critical dependency outage/fallback semantics | quarterly or before major release | staging |
| Production game day | evidence-driven, not mandatory on a fixed high frequency | production only after staging proof |

PostgreSQL/CloudNativePG backup/restore/failover cadence applies to services that own mutable PostgreSQL relational business persistence. ADR-0040 Compromised Password Service instead uses immutable reference-artifact rebuild/redeploy/integrity/fail-closed evidence; it does not fabricate PostgreSQL restore evidence for its read-only SQLite dataset.

Kafka/OpenBao-specific exercises remain governed by their existing ADRs and are
not changed by this program.

## Evidence record

Each exercise records:

- hypothesis;
- environment and affected components;
- start/end timestamps;
- injected failure;
- steady-state SLIs before/during/after;
- expected vs actual failure mapping;
- RTO/RPO when relevant;
- artifact/dataset version and integrity identity when an immutable reference artifact is under test;
- evidence links/dashboards;
- defects/actions and owners;
- final PASS/FAIL.

## Promotion impact

A failed exercise that disproves a production safety assumption blocks ordinary
promotion for the affected capability until the failure is corrected and the
exercise passes. Emergency security changes may proceed through the approved
emergency path with explicit incident ownership.
