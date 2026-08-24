# Application Implementation Roadmap — Current Execution Authority

- **Status:** Active implementation-sequencing authority
- **Last full application review:** 2026-08-22
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

The implemented foundation already includes substantial registration, local authentication/session/JWT, Tenant/Membership/Invitation, Authorization, Notification delivery/reconciliation/result-callback runtime, Compromised Password screening, the canonical BFF public registration OpenAPI slice, local integrated runtime, local production-fidelity staging, observability, and repository/release security controls.

The application is not yet a complete end-user product. Current material gaps are:

- no browser frontend implementation, generated frontend BFF client, or Playwright onboarding journey yet;
- the public registration OpenAPI/register/resend/confirm backend slice exists, but the end-user registration/verification/login UI is not implemented;
- real staging/production Liara/IPPanel provider execution and production delivery evidence remain NOT VERIFIED;
- Identity Profile/Contact management, password change/recovery, MFA/TOTP, Google OIDC/ExternalIdentity, and erasure are incomplete;
- remaining Tenant lifecycle operations are incomplete;
- application Kafka runtime is not yet required by an implemented asynchronous use case;
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

## 4. Ordered implementation milestones

| Order | Milestone | Current state | Completion boundary / next-step rule |
| ---: | --- | --- | --- |
| 0 | Inter-service contract independence | `COMPLETED` | Move canonical inter-service Protobuf schemas outside every service source tree; remove all service-to-service source/build coupling; keep semantic ownership with the provider bounded context; make Buf/build/CI consume the neutral contract registry; enforce the boundary. When complete, mark this `COMPLETED` and move `NEXT` to milestone 1. |
| 1 | Notification Delivery Runtime v1 | `COMPLETED` | Implement bounded dispatch claiming, durable pre-provider `DISPATCHING`, provider adapter execution, ambiguity-safe reconciliation, retry/observation rules, terminal result outbox, and Identity result callback with Day-One observability/tests. |
| 2 | Public Registration Vertical Slice + OpenAPI | `COMPLETED` | Establish canonical BFF OpenAPI authority and implement public register/resend/confirm routes through BFF -> Identity -> Notification with RFC 9457 errors, browser/security/quota controls, contract tests, and an integrated journey. |
| 3 | Frontend Foundation + Account Onboarding | `NEXT` | Add the reviewed TypeScript/React frontend baseline, generated/validated BFF client boundary, registration/verification/login/session/Tenant-selection UI, `fa`/`en`, RTL/LTR, accessibility, and critical Playwright journey. |
| 4 | Identity Profile & Contact Management | `PLANNED` | Implement profile read/update plus Contact add/verify/resend/set-primary/remove semantics across Identity, BFF/OpenAPI, UI, persistence, security, and tests. |
| 5 | Password Policy Decision + Password Lifecycle | `PLANNED` | ADR-0053 defines the concrete password policy. Implement change/forgot/reset, compromised-password screening, recent-auth/MFA rules, and session revocation according to the approved contract. |
| 6 | MFA/TOTP | `PLANNED` | Implement TOTP enrollment/challenge/replacement/disable, recovery codes, anti-replay, assurance rules, no-factor-downgrade behavior, BFF/OpenAPI/UI, and security evidence. |
| 7 | Google OIDC / ExternalIdentity | `PLANNED` | Implement BFF Authorization Code + PKCE/state/nonce flow, Identity issuer+subject binding/link semantics, collision protection, provider-token custody, MFA continuation, UI, and negative tests. |
| 8 | Complete Tenant lifecycle | `PLANNED` | Complete suspend/resume/delete/restore and remaining Invitation lifecycle behavior with Identity/Authorization coordination, owner safety, audit, BFF/OpenAPI/UI, and recovery tests. |
| 9 | Data Subject Erasure + Kafka | `PLANNED` | Use the first justified application Kafka path for ADR-0028: Identity coordination, Transactional Outbox, Kafka Protobuf events, participant Inbox/idempotency, legal-hold rules, non-PII receipts, replay and restore reconciliation. Kafka is not introduced earlier only to match the platform baseline. |
| 10 | Reference Data local capability | `GATED` | Implement the immutable Reference Data bundle in the owning deployable only when a real consumer journey requires it. `reference-data-service` remains prohibited until an ADR-0041 independent-deployable trigger is evidenced. |
| 11 | Core AI Product Architecture | `PLANNED` | Before creating Conversation/Workflow/Agent/model/tool services, define user journeys, aggregates, tenant ownership, LLM/provider/tool authority, credentials, authorization, persistence, async boundaries, quotas/costs, retention/erasure, audit, observability, and deployment-boundary evidence. |
| 12 | Core AI Product implementation | `PLANNED` | Implement the accepted core-product architecture in vertical slices. Service/module boundaries come from milestone 11 evidence, not from speculative names. |
| 13 | Production Commissioning & Readiness | `DEFERRED` | Do not execute this track as the next application milestone. Re-enter only when the owner explicitly reactivates it; use the production readiness authorities and executed environment evidence at that time. |

## 5. Immediate next milestone contract

At the current state, the next coherent engineering task is:

```text
Milestone 3 — Frontend Foundation + Account Onboarding
```

The target invariant is:

```text
reviewed React/TypeScript baseline   -> no speculative framework surface
BFF OpenAPI                         -> generated/validated browser client boundary
registration/verification/login     -> uses implemented BFF public contracts
session/Tenant selection            -> server-side session authority preserved
fa/en + RTL/LTR                     -> first-class UI behavior
accessibility                       -> required in the first onboarding slice
critical Playwright journey         -> required before milestone completion
```

The frontend must consume the BFF public contract. It must not call Identity, Notification, Authorization, or other internal services directly.

## 6. Rules for updating this roadmap

Update this file when a milestone changes state materially.

A milestone implementation PR must:

- reconcile actual code/tests/configuration with the milestone row;
- change the completed milestone to `COMPLETED` only after required repository evidence passes;
- set exactly one first executable application milestone to `NEXT`, except when the next item is explicitly `GATED` or `DEFERRED` and no executable milestone is available;
- keep later prerequisites ordered unless a reviewed ADR/current architecture change modifies the sequence;
- update `implementation-status.md` when repository-level implementation presence changes materially;
- avoid production-readiness claims from repository implementation alone.

Checkpoint records remain append-only historical evidence. They may help resume work but do not replace this current roadmap or current Git.

## 7. Deferred production track

Production Commissioning & Readiness remains intentionally separate from the application-build sequence. Existing production architecture, automation, release controls, and readiness documents remain valid current authorities, but missing real production environment evidence does not block continuing the application milestones above unless a specific application change itself requires that evidence.
