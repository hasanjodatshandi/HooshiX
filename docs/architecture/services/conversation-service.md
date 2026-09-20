# Conversation Service Architecture

## 1. Responsibility and implementation state

`conversation-service` is the implemented foundation plus private Conversation CRUD/lifecycle,
encrypted Message history, budgeted ModelRun acceptance/cancellation, and the bounded provider
worker/completion slice of the ADR-0054 bounded context. Ordered tenant lifecycle, erasure, and the
BFF/UI journey are implemented. Provider activation, safety evaluation, provider telemetry, and
transport-level cancellation remain incomplete. Its current
repository boundary is:

```text
services/conversation-service
base package: com.sajtech.conversation
persistence: PostgreSQL + Flyway + jOOQ/JDBC
```

Architecture presence is not implementation evidence. `implementation-status.md` remains canonical
for source/runtime state.

## 2. Owned model

Conversation owns:

```text
Conversation
  conversation_id
  tenant_id
  owner_membership_id
  encrypted title
  lifecycle: ACTIVE | ARCHIVED | DELETED
  version + created/last-activity timestamps

Message
  message_id + conversation_id
  role: USER | ASSISTANT
  encrypted content
  immutable ordinal
  author reference where applicable
  created timestamp

ModelRun
  run_id + conversation_id + request_id
  state: QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED | OUTCOME_UNKNOWN
  model alias + prompt version + included-message evidence
  price snapshot + reservation + reconciled usage/cost
  cancellation/failure category + timestamps
```

Messages are append-only until deletion/erasure. Exactly one assistant Message may be committed for
a successful run. `request_id` is UUIDv4 and equal replay returns the same accepted run; conflicting
reuse fails with a stable conflict error.

## 3. First public journey and validation

The service now exposes validated private gRPC create/list/get/archive/delete Conversation,
list-Message, and create/get/cancel ModelRun methods. They enforce Membership ownership,
encrypted-at-rest titles and Message content, bounded opaque pagination, optimistic versions,
durable UUIDv4 request replay/conflict behavior, and fail-closed exact model/prompt/price governance.
Run acceptance atomically reserves both tenant and Membership worst-case budgets, appends the USER
Message, and queues the run. Queued cancellation releases both reservations; running cancellation
records durable intent without prematurely releasing cost. The bounded worker, fixed provider
adapter, completion/reconciliation path, and ASSISTANT Message creation are implemented behind the
disabled execution gate. The BFF-owned REST surface and accessible bilingual UI journey are
implemented with bounded, abortable polling; public errors use RFC 9457 and never proxy provider
JSON or execution internals.

Initial validation authority is:

| Field | Rule |
| --- | --- |
| IDs/request identity | canonical UUIDv4 |
| title | NFC, trim, 1..120 Unicode code points, no control characters |
| user message | NFC, 1..16,000 Unicode code points, no NUL/invalid Unicode |
| output cap | server-owned, maximum 4,096 model tokens |
| pagination | opaque server cursor; default 20, maximum 100 |
| model/provider/system prompt/tools | absent from browser-controlled input |

HTTP and gRPC request-size limits must reject before large materialization. The context composer uses
only this Conversation's messages, newest complete turns that fit the platform model's bounded
input policy, and records the exact included Message identities plus prompt/model configuration
version. It never performs cross-Conversation memory or silent retrieval.

## 4. Authentication and authorization

Only the Web BFF workload may call browser journey operations. Conversation validates the exact
Identity JWT issuer/time/signature and `aud=conversation-service`, derives User/Tenant/Membership,
and rejects tenantless or inactive context.

Operation mapping:

| Operation | Authorization permission | Local invariant |
| --- | --- | --- |
| create Conversation | `conversation.create` | active Membership |
| list/get/history | `conversation.read` | owning Membership |
| create/get/cancel run | `conversation.generate` | owning Membership and active Conversation |
| archive/delete | `conversation.delete` | owning Membership |

Every operation uses one online `CheckPermission` with a maximum 300ms caller deadline, one attempt,
no wait-for-ready, retry, permission cache, stale fallback, or fabricated ALLOW. The service is final
resource authority after Authorization allows. A platform administrator or tenant owner has no
ordinary content-inspection bypass.

## 5. Contracts and deadlines

The neutral Protobuf package owns the versioned `hooshix.conversation.v1` transport with
Protovalidate rules and valid protobuf-JSON examples for every request. The BFF owns OpenAPI SemVer,
examples, schema validation, controller parity, generated frontend types, and drift enforcement.

Planned BFF dependency budgets:

```text
CRUD/history/run acceptance: 900 ms parent maximum
Conversation -> Authorization: 300 ms maximum, one attempt
provider execution:           60 s maximum, one attempt
queues:                       zero for RPC; finite durable DB worker queue
```

Client/BFF/mesh must not add a retry layer. Cancellation propagates where safe but never fabricates
provider cancellation.

The canonical registry includes `conversation.authorization-permission-check`
(`AUTHORITATIVE_SECURITY`), `conversation.model-provider-execution` (`EXTERNAL_SIDE_EFFECT`), and
`web-bff.conversation-api-dispatch` (`AUTHORITATIVE_STATE`) for the implemented runtime edges.

## 6. Persistence and transaction boundaries

All tenant business tables use forced RLS. The runtime role is non-owner `NOSUPERUSER NOBYPASSRLS`;
tenant context is validated and transaction-local. Global worker/audit/key metadata exceptions must
be explicitly classified and cannot contain plaintext tenant content.

Critical transactions are:

1. accept run (implemented): lock Conversation/version, verify local state, reserve cost, append USER
   Message, insert QUEUED ModelRun and dedup evidence atomically;
2. claim (implemented): select bounded global control-plane metadata with deterministic
   `SKIP LOCKED`, set transaction-local tenant context, transition to RUNNING, decrypt bounded
   same-Conversation context, and release the transaction/locks before provider I/O;
3. complete (implemented): re-lock the tenant run, reconcile integer usage/cost and append at most
   one encrypted ASSISTANT Message atomically; ambiguous/malformed usage publishes no output and
   conservatively charges the reservation;
4. cancel (implemented for QUEUED/RUNNING): serialize with the run, durably deduplicate the request,
   release queued reservations, and retain running reservations until reconciliation;
5. Conversation delete (implemented for current owned state): cancel queued work, release its
   reservations, request cancellation for running work, detach and erase Message ciphertext, and
   erase title ciphertext atomically. Tenant lifecycle/ADR-0028 erasure remains pending.

No Authorization, provider, Kafka, Redis, OpenBao, telemetry, or other remote I/O runs in a database
transaction or while a database lock is held. Failed transactions are not retried inside the same
transaction context.

The worker queue is the explicitly classified global metadata exception: it stores only Run,
Tenant, and Conversation UUIDs plus availability/lease timestamps. It contains no title, prompt,
message, output, User/contact, credential, or provider payload data. An expired in-flight lease is
never re-sent; it becomes `OUTCOME_UNKNOWN` and charges the existing reservation once.

## 7. Provider adapter

The provider port accepts a service-owned request and returns only the normalized output, usage,
finish category, and bounded provider evidence needed for reconciliation. Provider transport models
remain Infrastructure-only.

The first adapter uses OpenAI Responses under ADR-0057 and
`../mlops-evaluation-and-safety.md` with:

```text
store=false
background=false
tools=[]
platform-approved model only
service-owned stateless context
fixed api.openai.com:443 egress
OpenBao-delivered secret-file credential
one attempt / 60s / bounded global and tenant concurrency
```

No caller/model-selected URL or provider option is accepted. Responses, errors, headers, and model
text are allow-listed before persistence; raw provider payloads are not logged or returned to the
browser. Circuit-open/overload/provider failure maps to stable availability/failure states and does
not choose another model.

The adapter, worker, file-backed credential boundary, exact governance tuple loader, 60-second
one-attempt deadline, lease expiry, global/per-tenant concurrency, and circuit suppression are now
implemented. Kubernetes still blocks external provider egress and the committed governance tuple
remains execution-disabled pending provider-account controls and real evaluation. Transport-level
interruption after a durable running-cancellation request, provider safety mapping/evaluation,
provider-specific telemetry and the reviewed activation egress profile remain pending Stage 9
gates; no live provider execution is claimed.

## 8. Cost and abuse safety

Conversation is authoritative for run budget and cost. The Git-owned ADR-0057 catalog records a
stable model alias, provider identifier, model identifier, input/output limits, integer micro-unit
prices, and effective version. The browser sees the safe alias and bounded estimated/actual cost only
when the product contract explicitly exposes it.

Acceptance reserves worst-case cost before queueing. Completion charges provider-reported usage;
unused reservation is released. Missing trustworthy usage after an ambiguous outcome conservatively
charges the reservation at reconciliation expiry. All arithmetic is overflow-checked integer
arithmetic; floating-point money is prohibited.

Limits include tenant/user concurrent runs, global provider concurrency, bounded claim batch,
bounded conversation context/output, and a hard budget. Saturation rejects promptly. Virtual Threads
do not expand provider/database capacity.

## 9. Privacy, erasure, and audit

Title/message/output/system-composed context is encrypted with AES-256-GCM under a versioned
Conversation key ring, unique nonce, authenticated tenant/conversation/message purpose binding, and
rotation/re-encryption evidence. Keys are mounted read-only from OpenBao and never stored in Git,
values, images, database, browser, telemetry, or provider request metadata.

The service is an ADR-0028 Kafka participant from its first enabled release. Atomic Inbox processing
blocks new runs, cancels owned work, deletes/anonymizes subject state, preserves only permitted
non-PII cost/security facts, and commits a receipt Outbox. Legal-hold state cannot restore login,
permission, content access, or model execution. Restore replays erasure and tenant lifecycle evidence
before traffic.

The separate Identity-owned `hooshix.identity.tenant.lifecycle.v1` event carries event/Tenant IDs,
monotonic lifecycle version, state, and occurred time only. Conversation atomically projects it and
fails closed unless the current ordered state is ACTIVE. Suspend/deletion blocks claims and requests;
restore is accepted only before purge-started evidence; irreversible deletion drives bounded content
purge. Out-of-order/conflicting lifecycle events are retained for reconciliation and never applied as
new authority.

Durable audit records bounded action/outcome, trusted actor/workload, technical identifiers or safe
digests, model/prompt/price policy version, and timestamps. It excludes prompts, titles, outputs,
provider payloads/credentials, User contact data, raw IP, JWT/session data, and free-form errors.

## 10. Observability and reliability

Required low-cardinality signals include API latency/outcome, queue age/depth, claim/start delay,
run state transition, provider latency/outcome, Authorization outcome, budget rejection/reservation/
reconciliation, breaker/bulkhead/cancellation/unknown outcome, token/cost totals by model alias/price
version, PostgreSQL pool/query saturation, erasure lag, and audit health.

Prompt/output/title, User/Tenant/Membership/Conversation/Message/Run/request/provider-response IDs,
provider error bodies, and raw/pseudonymous customer identifiers never enter logs/metrics/traces.
Telemetry outage does not fail an otherwise safe business transition; required audit/cost state is
durable service state.

Provider outage leaves queued work bounded or terminally unavailable according to the run policy;
it does not create an unbounded retry storm. `OUTCOME_UNKNOWN`, cost-reservation age, oldest queued
run, provider breaker open, budget reconciliation failure, erasure lag, and audit failure require
owned alerts and runbook actions.

## 11. Deployment boundary

The first implementation uses one replica in `production-single-server`, HPA/PDB off, one dedicated
ServiceAccount, immutable digest, restricted non-root security context, finite resources, strict
Ambient mTLS, deny-by-default NetworkPolicy, exact BFF/Authorization/Kafka/PostgreSQL/OpenBao/
Collector/DNS/OpenAI egress, and no public Service.

Worker/API toggles may provide rollback inside the same deployment, but they do not create a second
deployable. Provider execution stays disabled until credentials, the exact model/prompt/price/eval
tuple passes ADR-0057 promotion and provider-data-control approval, and egress, privacy, quota/cost,
load, erasure, provider-fixture, canary, and rollback evidence pass.

## 12. First vertical-slice Definition of Done

ADR-0054 §9 and ADR-0057 are the acceptance authorities. In addition, tests must prove
cross-tenant/other-owner deny, RLS pool reuse, dedup conflict, cancel/complete races, no DB lock
across remote I/O, no blind retry, unknown-outcome charging, integer overflow rejection, provider
option/URL/tool injection rejection, `store=false`, encrypted-content tamper/key rotation,
prompt/output telemetry canaries, erasure/legal-hold/restore ordering, fixed egress, workload
identity, and browser-to-BFF-only routing.
