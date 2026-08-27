# Application Implementation Roadmap — Current Execution Authority

- **Status:** Active implementation-sequencing authority
- **Last full application review:** 2026-08-26
- **Scope:** HooshiX application implementation order and continuation across chats/agents
- **Production Commissioning & Readiness:** DEFERRED until explicitly reactivated by the owner

## 1. Purpose

This document records the current application implementation sequence so a new chat or agent can determine what has already been completed, where implementation currently is, and which coherent engineering milestone comes next.

This file cannot override current Git, current ADRs, service architecture, `implementation-status.md`, executable tests, or runtime evidence. It is a current roadmap and continuation authority only after source-of-truth reconciliation.

When the owner asks for the next step or asks to continue implementation:

1. bootstrap the canonical repository and verify the current Git branch, HEAD, dirty state, and `origin/main` relationship;
2. read `AGENTS.md`, this roadmap, `implementation-status.md`, the Decision Register, and the current sources required by task routing;
3. inspect actual source, configuration, and tests for the milestone instead of trusting this document as implementation evidence;
4. compare the milestone status below with current Git and checkpoints;
5. if Git proves a milestone is already complete, do not replay its side effects; advance to the first real incomplete milestone;
6. if Git and this roadmap disagree, current Git wins and the roadmap must be reconciled in the same coherent PR that establishes the new current state;
7. do not start a later milestone while an earlier prerequisite is incomplete unless a reviewed architecture change explicitly changes the dependency order.

A conversation interruption, stale checkpoint, or old chat summary is never evidence that a repository action still needs to be repeated.

## 2. Current application position

The current repository has five executable backend deployables:

```text
services/compromised-password-service
services/notification-service
services/identity-service
services/authorization-service
services/web-bff
```

The implemented foundation already includes substantial registration, local authentication/session/JWT, Tenant/Membership/Invitation, Authorization, Notification delivery/reconciliation/result-callback runtime, Compromised Password screening, the canonical BFF public REST OpenAPI slice, local integrated runtime, local production-fidelity staging, observability, and repository/release security controls.

The application is not yet a complete end-user product. Current material gaps are:

- the browser frontend foundation, onboarding, profile/contact, and password lifecycle slices exist, but broader accessibility/localization and deployed journey evidence remain incomplete;
- real staging/production Liara/IPPanel provider execution and production delivery evidence remain NOT VERIFIED;
- Google OIDC/ExternalIdentity is implemented in the repository; deployed real-provider execution remains NOT VERIFIED;
- data-subject erasure and its first justified application Kafka workflow are incomplete;
- Reference Data independent service remains intentionally gated by ADR-0041;
- Conversation/Workflow/Agent/LLM/tool-execution and other core AI-product bounded contexts are not yet defined and must not be created speculatively.

## 3. Milestone status vocabulary

Use only these roadmap states:

```text
COMPLETED  repository implementation and required milestone evidence are complete
NEXT       first coherent implementation milestone to execute
PLANNED    ordered future milestone whose prerequisites are not yet complete
GATED      implementation is prohibited until its explicit architecture/evidence trigger is satisfied
DEFERRED   intentionally excluded from the current application implementation sequence
```

`COMPLETED` in this roadmap is repository/application milestone status. It is not a production-readiness claim.

## 4. Completed foundation milestones

| Order | Milestone | Current state | Completion boundary / next-step rule |
| ---: | --- | --- | --- |
| 0 | Inter-service contract independence | `COMPLETED` | Move canonical inter-service Protobuf schemas outside every service source tree; remove all service-to-service source/build coupling; keep semantic ownership with the provider bounded context; make Buf/build/CI consume the neutral contract registry; enforce the boundary. |
| 1 | Notification Delivery Runtime v1 | `COMPLETED` | Implement bounded dispatch claiming, durable pre-provider `DISPATCHING`, provider adapter execution, ambiguity-safe reconciliation, retry/observation rules, terminal result outbox, and Identity result callback with Day-One observability/tests. |
| 2 | Public Registration Vertical Slice + OpenAPI | `COMPLETED` | Establish canonical BFF OpenAPI authority and implement public register/resend/confirm routes through BFF -> Identity -> Notification with RFC 9457 errors, browser/security/quota controls, contract tests, and an integrated journey. |
| 3 | Frontend Foundation + Account Onboarding | `COMPLETED` | Add the reviewed TypeScript/React frontend baseline, generated/validated BFF client boundary, registration/verification/login/session/Tenant-selection UI, `fa`/`en`, RTL/LTR, accessibility, and critical Playwright journey. |
| 4 | Identity Profile & Contact Management | `COMPLETED` | Implement profile read/update plus Contact add/verify/resend/set-primary/remove semantics across Identity, BFF/OpenAPI, UI, persistence, security, and tests. |
| 5 | Password Policy Decision + Password Lifecycle | `COMPLETED` | ADR-0053 defines the concrete password policy. Implement change/forgot/reset, compromised-password screening, recent-auth/MFA rules, and session revocation according to the approved contract. |

## 5. Canonical remaining completion sequence

The following is the one ordered application-completion sequence. Do not start a later numbered step while an earlier executable step is incomplete unless a reviewed architecture change changes the dependency order.

| Order | Completion step | Current state | Completion boundary / next-step rule |
| ---: | --- | --- | --- |
| 1 | Public REST Contract Coverage / BFF parity | `COMPLETED` | BFF-owned OpenAPI 1.5.0 covers all 57 implemented public controller method/path mappings. Its SemVer-compatible contract evolution, request/response validation, consumer examples, controller/OpenAPI parity, generated frontend transport types, and generated-type drift gate are part of the completion boundary. |
| 2 | MFA/TOTP | `COMPLETED` | TOTP enrollment/challenge/replacement/disable, recovery codes, anti-replay, assurance rules, no-factor-downgrade behavior, BFF/OpenAPI/UI, CSRF reload recovery, and repository security evidence are implemented. Deployed production evidence remains a separate readiness concern. |
| 3 | Google OIDC / ExternalIdentity | `COMPLETED` | BFF Authorization Code + PKCE/state/nonce, bounded encrypted pre-auth custody, Google issuer/audience/authorized-party/time validation, fail-closed semantic quota, Identity issuer+subject binding/link semantics, email collision protection, provider-token isolation, MFA continuation, OpenAPI/UI, observability, and negative/replay tests are implemented. Real deployed Google-provider execution remains a separate readiness concern. |
| 4 | Complete Tenant lifecycle | `COMPLETED` | Suspend/resume/delete/restore and Invitation decline/revoke/expire/reissue are implemented with Identity/Authorization coordination, owner safety, durable replay, BFF OpenAPI/UI, migration, observability, and negative/recovery tests. |
| 5 | Data Subject Erasure + Kafka | `NEXT` | Use the first justified application Kafka path for ADR-0028: Identity coordination, Transactional Outbox, Kafka Protobuf events, participant Inbox/idempotency, legal-hold rules, non-PII receipts, replay and restore reconciliation. Kafka is not introduced earlier only to match the platform baseline. |
| 6 | Core AI Product Architecture | `PLANNED` | Before creating Conversation/Workflow/Agent/model/tool services, define user journeys, aggregates, tenant ownership, LLM/provider/tool authority, credentials, authorization, persistence, async boundaries, quotas/costs, retention/erasure, audit, observability, and deployment-boundary evidence. |
| 7 | Core AI Product vertical slices | `PLANNED` | Implement the accepted core-product architecture in vertical slices. Service/module boundaries come from step 6 evidence, not from speculative names. |
| 8 | Production Commissioning & Readiness | `DEFERRED` | Do not execute this track as the next application step. Re-enter only when the owner explicitly reactivates it; use the production-readiness authorities and executed environment evidence at that time. |

Reference Data is a separate conditional track, not part of the numbered completion sequence:

| Conditional track | Current state | Completion boundary |
| --- | --- | --- |
| Reference Data local capability | `GATED` | Implement the immutable Reference Data bundle in the owning deployable only when a real consumer journey requires it. `reference-data-service` remains prohibited until an ADR-0041 independent-deployable trigger is evidenced. |

## 6. Immediate next completion-step contract

At the current state, the next coherent engineering task is:

```text
Step 5 — Data Subject Erasure + Kafka
```

The target invariant is:

```text
coordination authority              -> Identity owns the server-defined participant policy and global request state
self-erasure acceptance             -> recent auth/MFA, Membership precondition, DELETING, session and invitation revocation atomically
transport                           -> versioned Protobuf events over Kafka after a local Transactional Outbox commit
participant effects                 -> service-owned erase/anonymize/retention actions with atomic Inbox/idempotency
legal hold                          -> platform/legal-authorized durable ledger; blocks purge but never restores authentication
receipts                            -> durable non-PII participant progress through participant Outbox events
completion                          -> impossible until every snapshotted required participant has a current successful receipt
retry and recovery                  -> finite observable retry/DLQ plus 35-day publication and dedup evidence
restore reconciliation              -> replay erasure/legal-hold evidence before restored traffic can open
security and observability          -> no PII/secrets in events, DLQs, receipts, logs, traces, or metric labels
```

The browser continues to consume only the BFF public contract. Identity remains global erasure coordinator while each bounded context exclusively owns its local erasure effect. Kafka is recoverable transport rather than business authority; no broker acknowledgement replaces a local database commit or participant receipt. Remote calls and Kafka publication never run inside a business transaction, and stable event/request identities preserve at-least-once replay safety.

## 7. Rules for updating this roadmap

Update this file when a foundation milestone or completion step changes state materially.

A completion-step implementation PR must:

- reconcile actual code/tests/configuration with the completion-step row;
- change a completed step to `COMPLETED` only after required repository evidence passes;
- set exactly one first executable application step to `NEXT`, except when the next item is explicitly `GATED` or `DEFERRED` and no executable step is available;
- keep later prerequisites ordered unless a reviewed ADR/current architecture change modifies the sequence;
- update `implementation-status.md` when repository-level implementation presence changes materially;
- avoid production-readiness claims from repository implementation alone.

Checkpoint records remain append-only historical evidence. They may help resume work but do not replace this current roadmap or current Git.

## 8. Deferred production track

Production Commissioning & Readiness remains intentionally separate from the application-build sequence. Existing production architecture, automation, release controls, and readiness documents remain valid current authorities, but missing real production environment evidence does not block continuing the application completion steps above unless a specific application change itself requires that evidence.
