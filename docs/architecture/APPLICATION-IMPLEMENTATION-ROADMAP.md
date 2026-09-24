# Application Implementation Roadmap — Current Execution Authority

- **Status:** Active implementation-sequencing authority
- **Last full application review:** 2026-09-24
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

The current repository has six executable backend deployables:

```text
services/compromised-password-service
services/notification-service
services/identity-service
services/authorization-service
services/web-bff
services/conversation-service
```

The implemented foundation already includes substantial registration, local authentication/session/JWT, Tenant/Membership/Invitation, Authorization, Notification delivery/reconciliation/result-callback runtime, Compromised Password screening, the canonical BFF public REST OpenAPI slice, local integrated runtime, local production-fidelity staging, observability, and repository/release security controls.

The application is not yet a complete end-user product. Current material gaps are:

- the browser frontend foundation, onboarding, profile/contact, password lifecycle, localization,
  repository accessibility, and repository release-alignment slices exist, while deployed journey
  and Production promotion evidence remain incomplete;
- the mandatory repository-wide audit remediation track in `ENGINEERING-HARDENING-ROADMAP.md`
  completed Stages 1-9; Production commissioning remains explicitly deferred;
- complete-corpus HIBP staging and the five-participant staging erasure restart/restore/reconciliation exercise pass in the repository-owned local production-fidelity lane; real Gmail SMTP acceptance plus Production SMS.ir credential/sender/bulk acceptance/authenticated report execution pass in local staging, but SMS delivery remains NOT VERIFIED after the allow-listed recipient returned permanent blacklist state `7`; Production-selected Email-provider execution also remains NOT VERIFIED, the SMS.ir Sandbox contract probe passes but is simulated, and Google OIDC execution is owner-deferred and not a Notification-provider gate;
- Google OIDC/ExternalIdentity is implemented in the repository; deployed real-provider execution remains NOT VERIFIED;
- data-subject erasure and its first justified application Kafka workflow are repository-complete
  and verified in the developer-only integrated runtime; production deployment remains unverified;
- Reference Data independent service remains intentionally gated by ADR-0041;
- ADR-0054's first private Conversation/model-execution bounded context is implemented and its
  Stage 9 staging canary/rollback gate is complete; speculative Workflow/Agent/tool deployables
  remain excluded.

## 3. Milestone status vocabulary

Use only these roadmap states:

```text
COMPLETED  repository implementation and required milestone evidence are complete
IN PROGRESS implementation has started but its completion boundary is not verified
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
| 3 | Frontend Foundation + Account Onboarding | `COMPLETED` | Add the reviewed TypeScript/React foundation, generated/validated BFF client boundary, registration/verification/login/session/Tenant-selection UI, browser-to-BFF-only boundary, baseline semantic markup, and critical Playwright journey. Complete localization, RTL/LTR, component testing, and broader accessibility remain in the hardening track. |
| 4 | Identity Profile & Contact Management | `COMPLETED` | Implement profile read/update plus Contact add/verify/resend/set-primary/remove semantics across Identity, BFF/OpenAPI, UI, persistence, security, and tests. |
| 5 | Password Policy Decision + Password Lifecycle | `COMPLETED` | ADR-0053 defines the concrete password policy. Implement change/forgot/reset, compromised-password screening, recent-auth/MFA rules, and session revocation according to the approved contract. |

## 5. Canonical remaining completion sequence

The following is the one ordered application-completion sequence. Do not start a later numbered step while an earlier executable step is incomplete unless a reviewed architecture change changes the dependency order.

| Order | Completion step | Current state | Completion boundary / next-step rule |
| ---: | --- | --- | --- |
| 1 | Public REST Contract Coverage / BFF parity | `COMPLETED` | BFF-owned OpenAPI 2.0.0 covers all 58 implemented public controller method/path mappings. The breaking pre-Production 2.0 boundary separates UUIDv4 `Idempotency-Key` business authority from mutable `X-Request-Id` telemetry. SemVer-compatible evolution, request/response validation, consumer examples, controller/OpenAPI parity, generated frontend transport types, and generated-type drift are enforced. |
| 2 | MFA/TOTP | `COMPLETED` | TOTP enrollment/challenge/replacement/disable, recovery codes, anti-replay, assurance rules, no-factor-downgrade behavior, BFF/OpenAPI/UI, CSRF reload recovery, and repository security evidence are implemented. Deployed production evidence remains a separate readiness concern. |
| 3 | Google OIDC / ExternalIdentity | `COMPLETED` | BFF Authorization Code + PKCE/state/nonce, bounded encrypted pre-auth custody, Google issuer/audience/authorized-party/time validation, fail-closed semantic quota, Identity issuer+subject binding/link semantics, email collision protection, provider-token isolation, MFA continuation, OpenAPI/UI, observability, and negative/replay tests are implemented. Real deployed Google-provider execution remains a separate readiness concern. |
| 4 | Complete Tenant lifecycle | `COMPLETED` | Suspend/resume/delete/restore and Invitation decline/revoke/expire/reissue are implemented with Identity/Authorization coordination, owner safety, durable replay, BFF OpenAPI/UI, migration, observability, and negative/recovery tests. |
| 5 | Data Subject Erasure + Kafka | `COMPLETED` | Identity coordination, atomic self-erasure acceptance, Transactional Outbox, versioned/validated/exampled Kafka Protobuf events, participant Inbox/idempotency, service-owned effects, legal-hold rules, non-PII receipts, finite observable retry/DLT, 35-day evidence, restore/replay procedure, Helm/network policy, frontend flow, and a real four-participant local Kafka smoke are implemented and verified. Production deployment/readiness remains separate and unverified. |
| 6 | Core AI Product Architecture | `COMPLETED` | ADR-0054 and `services/conversation-service.md` define the private text Conversation journey, aggregates, ownership, OpenAI `store=false` no-tool adapter boundary, authorization, encrypted persistence, durable worker, quotas/cost, retention/erasure, audit, reliability, observability, deployment evidence, and explicit non-goals. |
| 6A | Engineering hardening audit remediation | `COMPLETED` | Stages 1-9 in `ENGINEERING-HARDENING-ROADMAP.md` are complete with recorded diff review and protected repository/staging evidence. Production-only gates remain separate in deferred step 8. |
| 7 | Core AI Product vertical slices | `COMPLETED` | The accepted first private Conversation + asynchronous ModelRun slice, provider-account approval, signed evaluation/canary evidence, one-percent synthetic staging canary, observation, rollback proof, and safe-disabled post-canary state satisfy the ADR-0054/0057 Stage 9 boundary. Workflow/Agent/tool/RAG/streaming/BYOK/shared-conversation boundaries remain excluded. |
| 8 | Production Commissioning & Readiness | `DEFERRED` | Do not execute this track as the next application step. Re-enter only when the owner explicitly reactivates it; use the production-readiness authorities and executed environment evidence at that time. |

Reference Data is a separate conditional track, not part of the numbered completion sequence:

| Conditional track | Current state | Completion boundary |
| --- | --- | --- |
| Reference Data local capability | `GATED` | Implement the immutable Reference Data bundle in the owning deployable only when a real consumer journey requires it. `reference-data-service` remains prohibited until an ADR-0041 independent-deployable trigger is evidenced. |

## 6. Immediate next completion-step contract

At the current state there is no active non-deferred application milestone. Stage 9 is complete;
the next track is intentionally not executable until the owner explicitly reactivates it:

```text
Production Commissioning & Readiness (Engineering Hardening Stage 10, DEFERRED)
```

Stage 9's completion boundary and interruption-safe evidence are owned by ADR-0054, ADR-0057,
`services/conversation-service.md`, and `ENGINEERING-HARDENING-ROADMAP.md`. Stage 9 completed from
base `9f0a811f78006f76c25d95b4499d4dfc868ba5aa`; the exact exercised runtime commit was
`afb738d6623316e876ed938e0869bba39b8a21a7` and its private content-free canary receipt is retained
outside Git. The completed boundary is:

```text
service foundation                  -> COMPLETED: one conversation-service build/image/Helm boundary, private DB/Flyway/RLS, AES-256-GCM key ring, Day-One telemetry, and exact-digest staging execution
contracts                           -> COMPLETED: versioned validated example-backed gRPC plus BFF OpenAPI 2.2.0/generated frontend types
authority                           -> COMPLETED for the service boundary and Conversation CRUD: exact JWT audience, four fixed permission keys, one online 300ms CheckPermission, fail-closed key rotation/readiness, bounded concurrency, and private Membership ownership enforcement
conversation/run state              -> COMPLETED for the private service boundary: Conversation CRUD/lifecycle, encrypted Message history, idempotent budgeted run acceptance/get/cancel, durable bounded worker claim, ASSISTANT append, and terminal reconciliation
provider                            -> COMPLETED for Stage 9: fixed one-attempt stateless OpenAI Responses adapter, safety/refusal/content-filter mapping, local transport cancellation, low-cardinality telemetry, signed content-free evaluation runner, fixed egress, tenant-stable 1/5/25/100 mechanics, approved staging-only provider account, and exercised one-percent canary/rollback
quota and cost                      -> COMPLETED for the private service boundary: atomic worst-case reservation, immutable integer price snapshot, cancellation release, actual-usage reconciliation, and conservative unknown-outcome charging
lifecycle                           -> COMPLETED for the service boundary: ordered fail-closed tenant projection plus ADR-0028 Inbox, service-owned encrypted-content purge, non-PII durable receipt, retry/exhaustion, and rollout-gated fifth-participant activation
BFF and browser journey             -> COMPLETED: fixed-audience/deadline/concurrency facade, exact workload policy, accessible bilingual CRUD/history/run/cancel/enum-feedback UI, bounded abortable polling, and text-only output rendering
evidence                            -> repository gates cover migration/RLS, provider/failure/race/security/privacy/load, browser, Helm, canary and content-free receipts; signed v3.1 provider evaluation and provider-control approval passed; exactly one synthetic staging canary succeeded for $0.001210 with 430 input/9 output tokens and 3717ms latency, remained incident/error/leak-free for 206 minutes, then rollback to false/0 and a no-provider-call FailedPrecondition proof passed
```

ADR-0054 §9 and `services/conversation-service.md` §12 remain the acceptance authority. The browser
continues to consume only BFF-owned public contracts. Completion of step 7 does not authorize
Workflow, Agent, Tool, RAG, streaming, provider-side state, BYOK, shared-conversation, or Production
activation. Reference Data also remains separately gated.

## 7. Rules for updating this roadmap

Update this file when a foundation milestone or completion step changes state materially.

A completion-step implementation PR must:

- reconcile actual code/tests/configuration with the completion-step row;
- change a completed step to `COMPLETED` only after required repository evidence passes;
- set at most one active application step to `IN PROGRESS`; when none is active, set exactly one first executable step to `NEXT`, except when all remaining items are explicitly `GATED` or `DEFERRED`;
- keep later prerequisites ordered unless a reviewed ADR/current architecture change modifies the sequence;
- update `implementation-status.md` when repository-level implementation presence changes materially;
- avoid production-readiness claims from repository implementation alone.

Checkpoint records remain append-only historical evidence. They may help resume work but do not replace this current roadmap or current Git.

## 8. Deferred production track

Production Commissioning & Readiness remains intentionally separate from the application-build sequence. Existing production architecture, automation, release controls, and readiness documents remain valid current authorities, but missing real production environment evidence does not block continuing the application completion steps above unless a specific application change itself requires that evidence.
