# ADR-0054: Define Core Conversation and Model Execution v1

## Status

Accepted

## Context

HooshiX has completed its Identity, Authorization, Notification, BFF, frontend, and erasure
foundation, but it does not yet define the business capability that makes the application an AI
product. Creating Conversation, Workflow, Agent, model, or tool deployables before their authority,
data, cost, and failure boundaries are explicit would create speculative services and unsafe model
authority.

The first product slice must be useful end to end while keeping model output untrusted, provider
cost bounded, tenant data isolated, and future Agent/tool behavior outside the initial trust boundary.

## Decision

### 1. First product journey

The first core-product journey is a private tenant conversation:

1. an authenticated active Tenant Member creates a private text conversation;
2. the member submits one user message and receives an accepted `ModelRun` identity;
3. HooshiX executes the run asynchronously against the platform-approved model;
4. the member polls the run and reads the persisted assistant message when it succeeds;
5. the member may cancel a queued/in-progress run, archive the conversation, or delete it.

The first slice deliberately excludes conversation sharing, tenant-admin content inspection,
streaming/SSE, multimodal input, attachments, embeddings/vector search, RAG, memory across
conversations, user prompts as reusable templates, user-supplied provider keys, user-selectable
models, agents, workflows, hosted tools, custom function execution, remote MCP, autonomous side
effects, fine-tuning, and provider-side conversation state.

### 2. Bounded context and deployable

One `Conversation` bounded context owns:

- `Conversation`, `Message`, and `ModelRun` aggregates/state;
- private resource ownership and conversation lifecycle invariants;
- model-context composition and prompt-version evidence;
- tenant/user run budgets, reservations, actual usage, and cost ledger;
- the provider port and the first OpenAI Responses API adapter;
- provider ambiguity/cancellation handling;
- Conversation-owned audit, retention, erasure, and recovery evidence.

It is independently deployable as `conversation-service` because it owns sensitive tenant business
state, a distinct provider credential/egress boundary, cost authority, long-running bounded work,
and a failure/scale profile different from Identity and the Web BFF.

The API listener and bounded run worker remain one service, release, image, database, credential
boundary, and deployment in v1. A separate worker deployment, Model service, Agent service,
Workflow service, Tool service, Prompt service, or Billing service is prohibited until measured
ownership/lifecycle/security/scale evidence justifies another boundary.

### 3. Authority

- Identity owns User, Tenant, Membership, Session, and access-token identity.
- Authorization owns tenant permission decisions.
- Conversation owns final Conversation/Message/ModelRun resource invariants and cost accounting.
- Platform configuration owns the approved provider, exact model allow-list, default model, prompt
  versions, maximum context/output limits, and integer-micro-unit price catalog.
- OpenBao owns provider credentials; browser, BFF, tenant, and database state never contain them.
- The browser consumes only BFF-owned versioned REST/OpenAPI contracts.
- Model output and model-requested actions are untrusted data. They never establish identity,
  tenant, permission, quota, audit, or side-effect authority.

The planned tenant permission keys are `conversation.create`, `conversation.read`,
`conversation.generate`, and `conversation.delete`. Every protected operation performs the one
authoritative online `CheckPermission` call under its existing one-attempt/300ms/no-cache/no-retry/
fail-closed contract. Conversation then enforces that the Membership owns the private resource.
Platform authority and tenant Roles do not grant an ordinary API for reading another member's
conversation content.

### 4. Contracts and flow

BFF-to-Conversation uses versioned Protobuf/gRPC with Protovalidate rules, stable errors, consumer
examples, Buf compatibility, workload identity, exact `aud=conversation-service`, finite deadlines,
and cancellation. The BFF publishes versioned OpenAPI schemas/examples and generated frontend
types. Browser input cannot select a provider, provider endpoint, API key, arbitrary model, system
prompt, tool, or internal audience.

Run acceptance is asynchronous:

```text
authorize + validate + reserve budget
-> short Conversation transaction: append user Message + create QUEUED ModelRun + reservation
-> 202 Accepted with stable run identity
-> bounded worker claim transaction
-> provider I/O with no DB transaction/lock held
-> short completion transaction: assistant Message + usage/cost reconciliation + terminal Run
```

Kafka is not request/reply transport for this journey. The v1 local run queue is durable
PostgreSQL state claimed with deterministic bounded `FOR UPDATE SKIP LOCKED` work. Kafka remains
available only for justified integration events such as Identity-owned tenant lifecycle and
ADR-0028 erasure coordination, with Outbox/Inbox/idempotency.

Identity publishes a versioned, validated, example-backed, non-PII
`hooshix.identity.tenant.lifecycle.v1` event from its Transactional Outbox. It contains only stable
event/Tenant identities, monotonic lifecycle version, lifecycle state, and occurred time.
Conversation consumes it through an atomic Inbox/projection. Missing, out-of-order, suspended,
deleting, deleted, or purge-started state fails closed for new runs. Restore can reactivate only
before irreversible purge and only after the ordered ACTIVE projection is durable. Irreversible
deletion cancels/blocks work and purges Tenant content in bounded batches. Kafka acknowledgement is
never lifecycle or purge authority.

### 5. Model/provider boundary

The first provider adapter targets the OpenAI Responses API through a Conversation-owned abstract
port. Every request uses the platform-approved model, `store=false`, a fixed service-controlled
system prompt version, bounded input/output, and no tools. HooshiX sends the required stateless
conversation context from its own database; provider-side Conversation/Thread/Vector Store state,
background mode, hosted tools, remote MCP, and provider file storage are disabled in v1.

The provider call has one attempt, a finite 60-second deadline, cancellation propagation where the
client supports it, a zero/bounded queue, per-tenant and global bulkheads, and a circuit breaker that
only suppresses new calls. There is no blind retry or alternate-model/provider fallback. A result
that may have been accepted by the provider but lacks a trustworthy response remains
`OUTCOME_UNKNOWN`; a user retry creates a new `ModelRun` and reservation.

Queued cancellation is authoritative locally. In-flight cancellation records intent and attempts
transport cancellation, but never claims that provider computation or cost stopped without
evidence. A response arriving after accepted cancellation is not exposed as an assistant message;
its reported usage is still reconciled.

### 6. Persistence, quota, and cost

Conversation owns a distinct PostgreSQL database, Flyway history, runtime/migration roles, forced
RLS on all tenant-owned tables, and transaction-local tenant context. Prompt, user-message,
assistant-output, and title content is application-layer encrypted with an OpenBao-delivered,
versioned service key ring; searchable plaintext copies are prohibited.

Before a run is accepted, Conversation atomically reserves the maximum configured cost for the
approved model/input-size class/output cap in integer micro currency units. A versioned price
snapshot is stored with the run. Provider-reported usage reconciles the reservation atomically. If
an ambiguous result cannot be reconciled, the conservative maximum reservation becomes charged at
the bounded reconciliation deadline. Budget/configuration uncertainty fails closed. Redis may add
ephemeral burst protection later under ADR-0024, but it never becomes spend or usage authority.

### 7. Privacy, security, and lifecycle

Conversation titles, prompts, outputs, and composed context are restricted customer content. They
never enter ordinary logs, traces, metrics, Kafka lifecycle events, error messages, or durable
security audit. Model/provider/run/resource/User/Tenant identifiers are not metric labels.

Provider egress is fixed to the reviewed adapter destination and cannot follow caller/model URLs.
Rendered model text is escaped/sanitized as untrusted Markdown; raw model HTML/script is never
executed. The v1 service exposes no tool execution surface, so prompt injection cannot create an
external action.

Conversation is a mandatory ADR-0028 erasure participant before product enablement. It must stop
new/in-flight subject work, remove subject-owned content and authority, retain only the minimum
non-PII cost/security evidence permitted by policy, honor legal hold without restoring application
access, and emit its non-PII receipt only after service-owned effects are durable. Identity-owned
tenant lifecycle integration must fail closed for non-active tenants and durably cancel/purge work
when deletion becomes irreversible.

Default content retention is 360 days from last Conversation activity unless deletion, erasure,
legal hold, or a newer approved data-class policy requires an earlier/later action. Required
security/cost audit uses its separately approved retention and contains no content.

### 8. Reliability and observability

Interactive create/read/archive/delete and run-acceptance APIs follow Class A availability and
request latency objectives; model completion is asynchronous and measured separately. While the
provider and required dependencies are healthy, 99.9% of accepted runs should begin within five
seconds. The first release must establish measured completion latency by model/output class before
declaring a completion SLO; the 60-second provider deadline is a safety ceiling, not an SLO claim.

Day-One signals include bounded operation/outcome latency, queue age/depth, worker concurrency,
Authorization/provider/budget outcome, breaker state, cancellation, unknown outcome, token/cost
totals by low-cardinality model alias and price version, erasure lag, database pool saturation, and
audit health. Prompt/output/title/tool arguments and subject/resource/request/provider response IDs
are prohibited telemetry.

### 9. Step 7 acceptance gate

The first executable vertical slice is not complete until it contains, in one coherent boundary:

- service build/container/Helm/NetworkPolicy/Istio/ServiceAccount/probes/resources;
- independent PostgreSQL/Flyway/RLS/runtime-role lifecycle and encrypted content key handling;
- versioned validated example-backed gRPC contracts and BFF OpenAPI/generated frontend types;
- the four permission catalog entries, SYSTEM-role mapping, authoritative checks, and negatives;
- provider-neutral port plus OpenAI `store=false` no-tool adapter with fixed egress;
- atomic run acceptance, durable bounded worker, cost reservation/reconciliation, cancellation, and
  ambiguous-outcome tests;
- tenant lifecycle and ADR-0028 participant behavior before user content is enabled;
- versioned/validated/example-backed Identity tenant-lifecycle event with Outbox/Inbox ordering and
  fail-closed suspend/delete/restore/purge behavior;
- Day-One logs/metrics/traces/alerts/dashboard and PII/cardinality canaries;
- unit, architecture, contract, migration/RLS, provider-fixture, failure/chaos, browser, Helm,
  Semgrep, Gitleaks, OSV, and integrated local journey evidence.

## Consequences

Positive:

- HooshiX gains one explicit, testable AI product journey without speculative Agent/Tool services.
- provider credentials, cost authority, sensitive content, and model execution have one owner.
- privacy and prompt-injection exposure are reduced by stateless `store=false` no-tool execution.

Negative:

- v1 uses polling rather than streaming and supports only private text conversations.
- conservative charging of unreconciled ambiguous outcomes may require support review.
- a new mutable service adds database, deployment, erasure, observability, and recovery cost.

## Enforcement

Step 7 implementations MUST conform to this ADR and
`docs/architecture/services/conversation-service.md`. Any tool execution, provider-side persistent
state, BYOK, user-selectable model, shared conversation, streaming, separate worker/deployable, or
additional AI bounded context requires a new reviewed decision and threat/dependency update.
