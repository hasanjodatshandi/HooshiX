# Architecture Sources — Current State

- **Mode:** current-only
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`
- **Selected production profile:** `production-single-server` under ADR-0042

This file is an index. It does not restate normative architecture rules. The owning ADR/current-state document remains authoritative.

## Global entry points

Read in this order for non-trivial architecture work:

1. `../../AGENTS.md`
2. `../engineering/current-only-documentation-policy.md`
3. `../engineering/repository-change-workflow.md`
4. `../engineering/documentation-standards.md`
5. `README.md`
6. `implementation-status.md` when implementation/evidence presence matters
7. `TASK-REVIEW-MATRIX.md`
8. `../adr/decision-register.md`
9. applicable current-state documents and service documents
10. applicable retained current ADRs
11. applicable technology baselines, engineering standards, operations documents, and runbooks

## Core architecture

- Platform topology and service boundaries: `platform-architecture.md`
- Network zones, edge trust, management plane, egress: `network-architecture.md`
- Security model: `security-architecture.md`
- Formal threat model: `threat-model.md`
- Data and messaging: `data-and-messaging.md`
- Runtime/deployment: `runtime-and-deployment.md`
- Reliability/observability: `reliability-and-observability.md`
- Performance/capacity: `performance-and-bottlenecks.md`
- Testing/quality gates: `testing-and-quality-gates.md`
- Architecture fitness functions: `architecture-fitness-functions.md`
- Repository implementation/evidence state: `implementation-status.md`
- Production evidence gate: `PRODUCTION-READINESS-CHECKLIST.md`
- Security evidence mapping: `security-verification-matrix.md`
- Dependency criticality authority: `dependency-criticality.yaml`
- Dependency schema: `dependency-criticality.schema.json`
- Generated dependency view: `dependency-criticality-matrix.md`

## Identity, browser, MFA, and erasure

- `services/identity-service.md`
- `services/web-bff.md`
- `security-architecture.md`
- `threat-model.md`
- ADR-0008, ADR-0009, ADR-0012, ADR-0016, ADR-0023, ADR-0024, ADR-0028
- ADR-0043 when public client network identity is involved

## Authorization

- `services/authorization-service.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `dependency-criticality.yaml`
- ADR-0013, ADR-0024, ADR-0025, ADR-0026, ADR-0032, ADR-0033, ADR-0036

## Notification

- `services/notification-service.md`
- `services/identity-service.md`
- `data-and-messaging.md`
- ADR-0006, ADR-0007, ADR-0010, ADR-0014, ADR-0018, ADR-0020

## Compromised Password

- `services/compromised-password-service.md`
- `services/identity-service.md`
- `security-architecture.md`
- `data-and-messaging.md`
- `performance-and-bottlenecks.md`
- `dependency-criticality.yaml`
- ADR-0040

## Reference Data

- `services/reference-data-service.md`
- `services/web-bff.md`
- `platform-architecture.md`
- `data-and-messaging.md`
- `dependency-criticality.yaml`
- ADR-0041

## Public edge, network identity, and DDoS

- `network-architecture.md`
- `services/web-bff.md`
- `runtime-and-deployment.md`
- `security-architecture.md`
- `threat-model.md`
- `../runbooks/local-traefik-edge.md`
- ADR-0001, ADR-0016, ADR-0029, ADR-0043

## Privileged human production access

- `network-architecture.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `../operations/incident-response-runbook.md`
- `../runbooks/production-cold-dr.md`
- ADR-0030, ADR-0042, ADR-0043

## PostgreSQL, persistence, and recovery

- `data-and-messaging.md`
- `runtime-and-deployment.md`
- `reliability-and-observability.md`
- applicable service documents
- `../engineering/sql-and-flyway-coding-standards.md`
- `../runbooks/production-cold-dr.md`
- ADR-0004, ADR-0019, ADR-0027, ADR-0034, ADR-0037, ADR-0042

## Kafka and events

- `data-and-messaging.md`
- `reliability-and-observability.md`
- `../runbooks/production-cold-dr.md`
- ADR-0003, ADR-0015, ADR-0042

## Redis and semantic quotas

- `security-architecture.md`
- applicable service document
- `dependency-criticality.yaml`
- `network-architecture.md` when network identity is used
- ADR-0024, ADR-0042, ADR-0043

## Kubernetes, GitOps, mesh, admission, and secrets

- `runtime-and-deployment.md`
- `network-architecture.md`
- `security-architecture.md`
- `performance-and-bottlenecks.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/production-cold-dr.md`
- ADR-0002, ADR-0011, ADR-0017, ADR-0021, ADR-0022, ADR-0042, ADR-0043

## SLOs, capacity, incidents, and DR

- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `threat-model.md`
- `../operations/chaos-engineering-program.md`
- `../operations/incident-response-runbook.md`
- `../runbooks/production-cold-dr.md`
- ADR-0004, ADR-0005, ADR-0025, ADR-0033, ADR-0037, ADR-0042

## Supply chain, vulnerability response, logging, and audit

- `security-architecture.md`
- `security-verification-matrix.md`
- `testing-and-quality-gates.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../operations/incident-response-runbook.md`
- ADR-0017, ADR-0030, ADR-0031, ADR-0035, ADR-0038

## Engineering and frontend

- `backend-engineering.md`
- `../engineering/coding-standards.md`
- `../engineering/sql-and-flyway-coding-standards.md`
- `../engineering/frontend-coding-standards.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../engineering/developer-workflow.md`
- `services/web-bff.md`
- ADR-0039

## Technology authority

- exact approved production/application pins: `../technology/technology-baseline.md`
- local pins: `../technology/local-development-baseline.md`
- production support relationships: `../technology/production-compatibility-matrix.md`

Version changes use official upstream compatibility/security evidence and the repository baseline process. This source map does not duplicate exact patch numbers.
