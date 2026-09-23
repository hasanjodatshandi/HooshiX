# MLOps Evaluation and Safety — Current Architecture

## 1. Purpose and boundary

This document is the implementation-facing authority for ADR-0057. It governs the model/prompt
change path for ADR-0054 without creating a new deployable or production training platform.

```text
reviewed Git catalogs + prompt + synthetic suite
        -> deterministic CI validation
        -> Stage 9 offline provider evaluation receipt
        -> owner approvals and provider-data-control receipt
        -> bounded canary
        -> approved runtime tuple or fail-closed rollback
```

Stage 8 established the first two boxes. Stage 9 now owns the real-provider runner and Conversation
runtime boundary, while runtime activation remains fail-closed until every later box is evidenced.

## 2. Canonical assets

| Asset | Authority |
| --- | --- |
| Governance/catalog bundle | `mlops/governance/v2/governance.json` |
| System prompt | `mlops/prompts/conversation-system-v2.txt` |
| Offline suite | `mlops/evaluations/conversation-v2.json` |
| Consumer schemas | `mlops/schemas/*.schema.json`; receipts currently emit v2 while v1 remains immutable |
| Semantic validator | `scripts/mlops/verify_governance.py` |
| Executable gate | `make mlops-verify`, included in `make baseline-verify` |

Catalog versions are immutable evidence identities. The prompt SHA-256 binds the reviewed file to
the catalog. Price values use integer micro-USD per million tokens; floating-point money is
prohibited. The maximum request reservation is recomputed from configured input/output maxima and
must exactly match the catalog.

## 3. Initial candidate and runtime state

The current v2 catalog candidate retains the exact OpenAI snapshot `gpt-5.4-2026-03-05`, foreground
Responses API, text-only, no tools, `store=false`, `background=false`, and bounds of 16,000 input plus
2,000 output tokens. It replaces the unpromoted v1 candidate with an immutable v2 prompt/suite after
the first real evaluation exposed brittle refusal phrasing and unclassified incomplete outcomes.
The captured standard price snapshot is review input, not a guarantee that provider price remains
unchanged.

The candidate is deliberately:

```text
lifecycle:         CANDIDATE
execution_enabled: false
data control:      PENDING_ORGANIZATION_VERIFICATION
```

Any further change must create another versioned candidate if real evaluation, account availability,
price, or provider policy makes another exact snapshot preferable. It cannot mutate an existing
record or promote an alias implicitly.

## 4. Evaluation protocol

Each case declares an expected behavior, at least one acceptable concept, forbidden fragments, a
locale, category, severity, and output-token bound. The runner normalizes output only for reviewed
deterministic comparison; it never logs case input/output. A provider refusal or content-filter
outcome passes only an explicit refusal expectation; an unexpected filter remains an error. Critical
cases are additionally reviewed by a versioned deterministic policy evaluator. Human/model graders
may supplement quality assessment, but cannot override a critical deterministic failure.

Required result dimensions:

- aggregate and category pass rate, with critical pass rate exactly 100%;
- zero runner/evaluator errors;
- `fa` and `en` quality separately, not only a combined average;
- p50/p95/p99 end-to-end and provider latency under the fixed 60-second deadline;
- input/cached/output/reasoning token counts and integer cost distribution;
- refusal/over-refusal and no-authority/no-side-effect outcomes;
- commit, suite/model/prompt/price/evaluator versions and immutable artifact digest.

An evaluation receipt contains no user/tenant/request/conversation/message/provider-response IDs and
no prompt/input/output text. Evaluation fixtures are synthetic and committed only after PII/secret
review. Production content is not an evaluation source by default.

## 5. Promotion and rollback state machine

```text
CANDIDATE
  -> OFFLINE_PASSED
  -> APPROVED_DISABLED
  -> CANARY_1
  -> CANARY_5
  -> CANARY_25
  -> APPROVED_100

any failed gate or rollback trigger -> PREVIOUS_APPROVED tuple
no previous tuple                  -> DISABLED
```

Moving forward requires all gates for the exact tuple and the minimum observation time. A safety or
privacy critical incident count of one rolls back immediately. Error, latency, or cost thresholds
also roll back. A canary is tenant-stable and server-assigned; callers cannot select a model/prompt or
canary cohort. Canary assignment and catalog identifiers contain no PII and are not authorization.

## 6. Safety ownership and failure behavior

Conversation owns input/output policy enforcement. The provider may reject or annotate content, but
provider behavior is treated as an untrusted dependency outcome and mapped to a stable local result.
No provider error text or content is copied to a client or telemetry.

Model output is rendered only as untrusted text. It cannot call tools, access another tenant,
authorize an operation, update persistence outside the Conversation aggregate, construct a trusted
prompt, or claim a real external action. HTML/Markdown rendering in Stage 9 must preserve frontend
injection protections; plain-text rendering is the safe default.

Safety-policy dependency failure, malformed provider output, missing required safety evidence, or an
unknown provider status fails before publication. Ambiguous provider execution follows ADR-0054
`OUTCOME_UNKNOWN`; it is not automatically retried.

## 7. Provider privacy gate

Before any real content leaves the workload, an environment-specific receipt must prove:

1. exact provider organization and project are approved without putting their identifiers in public
   telemetry;
2. retention mode and abuse-monitoring consequences are reviewed;
3. `store=false`, `background=false`, `tools=[]`, no response continuation, and fixed endpoint are
   enforced by adapter tests;
4. region/subprocessor and legal/privacy review is recorded;
5. API key comes only from OpenBao/External Secrets and never catalogs, values, environment dumps,
   logs, evidence, or CI output;
6. provider egress, deadlines, concurrency, cancellation, ambiguity, and one-attempt policy pass.

Request flags alone do not prove account-level retention configuration. Until the receipt is
approved, readiness fails and execution remains disabled.

## 8. Feedback and drift

The first slice accepts only the four cataloged feedback enums. No feedback free text is collected,
no conversation is copied to analytics, and no feedback automatically trains or tunes a model.
Feedback persistence is tenant-owned Conversation state and is removed/anonymized under ADR-0028.

After runtime exists, weekly offline evaluation is the maximum normal interval. Any declared trigger
forces re-evaluation before further promotion. Drift signals use bounded labels only; a threshold
breach creates an investigation/disable decision, not automatic training.

## 9. Stage 9 acceptance handoff

Stage 9 must consume these assets rather than re-encode them in configuration. It adds:

- catalog loader/readiness checks and immutable approved-tuple selection;
- provider adapter fixtures and an offline runner that generates redacted signed/retained receipts;
- service-owned safety result mapping and feedback persistence/erasure;
- worst-case reservation and actual-usage reconciliation tied to the price version;
- canary cohort, promotion, disable and rollback mechanics;
- metrics/alerts/dashboard/runbook evidence without content or high-cardinality labels.

Stage 9 cannot mark a candidate approved merely because `make mlops-verify` passes. That gate proves
governance consistency only. The current v2 real-provider receipt is separate evaluation evidence;
provider-account approval plus deployed canary/rollback evidence remain required.
