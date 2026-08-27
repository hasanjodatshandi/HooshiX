# Conversation Service Architecture

## 1. Responsibility and implementation state

`conversation-service` is the accepted but not yet implemented ADR-0054 bounded context for private
tenant conversations and model execution. Its planned repository boundary is:

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

The BFF-owned REST surface will expose create/list/get/archive/delete Conversation, create/get/cancel
ModelRun, and bounded Message history. It returns RFC 9457 errors and never proxies provider JSON.

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

Before implementation becomes production-eligible, canonical dependency-registry entries must be
added for `web-bff.conversation-api-dispatch` (`AUTHORITATIVE_STATE`),
`conversation.authorization-permission-check` (`AUTHORITATIVE_SECURITY`), and
`conversation.model-provider-execution` (`EXTERNAL_SIDE_EFFECT`). The registry is not changed by
this architecture-only milestone because none of those runtime edges exists yet.

## 6. Persistence and transaction boundaries

All tenant business tables use forced RLS. The runtime role is non-owner `NOSUPERUSER NOBYPASSRLS`;
tenant context is validated and transaction-local. Global worker/audit/key metadata exceptions must
be explicitly classified and cannot contain plaintext tenant content.

Critical transactions are:

1. accept run: lock Conversation/version, verify local state, reserve cost, append USER Message,
   insert QUEUED ModelRun and dedup evidence atomically;
2. claim: bounded `SKIP LOCKED` transition to RUNNING and release locks before provider I/O;
3. complete: re-lock run, reconcile usage/cost and append one ASSISTANT Message atomically;
4. cancel: serialize with claim/completion and apply deterministic terminal-state precedence;
5. delete/erasure: block new claims first, then remove owned content/evidence atomically in bounded
   batches.

No Authorization, provider, Kafka, Redis, OpenBao, telemetry, or other remote I/O runs in a database
transaction or while a database lock is held. Failed transactions are not retried inside the same
transaction context.

## 7. Provider adapter

The provider port accepts a service-owned request and returns only the normalized output, usage,
finish category, and bounded provider evidence needed for reconciliation. Provider transport models
remain Infrastructure-only.

The first adapter uses OpenAI Responses with:

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

## 8. Cost and abuse safety

Conversation is authoritative for run budget and cost. The platform catalog records a stable model
alias, provider identifier, model identifier, input/output limits, integer micro-unit prices, and
effective version. The browser sees the safe alias and bounded estimated/actual cost only when the
product contract explicitly exposes it.

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
deployable. Provider execution stays disabled until credentials, model/price catalog, egress, privacy,
quota/cost, load, erasure, and provider-fixture evidence pass.

## 12. First vertical-slice Definition of Done

ADR-0054 §9 is the acceptance authority. In addition, tests must prove cross-tenant/other-owner deny,
RLS pool reuse, dedup conflict, cancel/complete races, no DB lock across remote I/O, no blind retry,
unknown-outcome charging, integer overflow rejection, provider option/URL/tool injection rejection,
`store=false`, encrypted-content tamper/key rotation, prompt/output telemetry canaries, erasure/legal
hold/restore ordering, fixed egress, workload identity, and browser-to-BFF-only routing.
