# ADR-0057: Govern Model Evaluation, Safety, and Promotion v1

## Status

Accepted — current effective decision

## Date

2026-09-12

## Context

ADR-0054 defines a deliberately narrow private text Conversation and asynchronous ModelRun slice,
but architecture alone does not make a model/prompt safe to execute. Before Stage 9, the repository
needs a versioned, reviewable answer to four questions: what exact model/prompt/price is proposed;
how quality, safety, latency, and cost are evaluated; what blocks or rolls back promotion; and which
provider data controls must be evidenced. Production conversation content must not become an
unreviewed evaluation or training dataset.

## Decision

### Git-owned governance boundary

The current candidate authority is `mlops/governance/v2/governance.json`, with its referenced prompt,
price snapshot, and synthetic evaluation suite. The original v1 candidate remains an immutable
historical definition; its content-free failed-run receipt is retained only in the bounded private
environment and v1 is not runtime authority. JSON schemas document the consumer shape and
`scripts/mlops/verify_governance.py` enforces semantic and cross-file invariants in
`make baseline-verify`.

Every model, prompt, price, and evaluation suite has an immutable version. A change creates a new
version and a new evaluation receipt; it never rewrites evidence for a promoted version. Runtime
configuration may refer only to a catalog tuple that passed the promotion policy. A provider alias
is not a rollback target; the first candidate uses an exact provider snapshot.

No MLOps control plane, online feature store, model registry service, prompt SaaS, or production
training pipeline is added. Git, CI artifacts, existing observability, and service-owned persistence
are sufficient for the first slice. A new platform requires measured coordination, scale, or
reproducibility evidence plus a separate reviewed decision.

### Evaluation data and receipts

The initial suite is synthetic, adversarial, non-PII, bilingual `fa`/`en`, and covers helpfulness,
uncertainty, high-stakes qualification, safety, privacy, prompt injection, and the no-tool/no-authority
boundary. Direct contact identifiers and production-derived content are rejected from committed
fixtures. Critical safety/privacy/injection/authority cases require 100% pass; any evaluation error
blocks promotion. Aggregate quality, regression, p95/p99 latency, and integer-micro-USD cost must
meet the catalog policy.

Stage 8 supplies the suite and validator, not fabricated model-run results. Stage 9 must add the
deterministic runner and retain a content-free receipt containing only catalog versions, suite
version, aggregate/category results, latency/cost aggregates, evaluator versions, commit, and time.
Raw prompts/outputs remain in the bounded encrypted test workspace and never ordinary CI logs.

### Promotion, canary, and rollback

Promotion requires the exact model + prompt digest + price + suite tuple, all named owner approvals,
provider data-control approval, zero critical failures/errors, all thresholds, privacy canary, and a
rollback rehearsal. Canary exposure progresses only through the declared `1 -> 5 -> 25 -> 100`
steps after each observation window.

One confirmed critical safety/privacy incident immediately triggers disable/rollback. Error,
latency, or cost thresholds trigger the same response. Rollback atomically restores the last approved
model/prompt tuple; the applicable price snapshot remains attached to each accepted ModelRun for
correct reconciliation. With no previously approved tuple, rollback means model execution is
disabled and queued work fails safely under ADR-0054 semantics.

### Safety and acceptable use

Conversation owns product safety decisions. Provider safety mechanisms are defense in depth, never
authorization or the sole product policy. Model output is untrusted data: it cannot grant access,
change tenant state, execute a tool, cause an external action, or become a system/developer prompt.
The first slice refuses material facilitation of credential theft, malware, privacy invasion, sexual
content involving minors, serious harm, and hidden-instruction/secret exfiltration. High-stakes
medical/legal/financial output states material limitations and directs users to qualified help when
appropriate.

Safety rejection occurs before output publication for a critical policy failure. Stable bounded
outcome codes may be observed; input, output, prompt text, provider payload, contact PII, and subject
identifiers never enter logs/traces/metric labels. Safety changes require adversarial regression
cases and the same promotion gates.

### Provider data controls

The provider adapter remains fixed-egress OpenAI Responses API with explicit `store=false`,
`background=false`, `tools=[]`, no provider conversation state, and no response-ID continuation.
Background execution is prohibited because it requires provider-side polling retention and conflicts
with the client-owned durable worker/state model.

Runtime remains disabled while the governance record is `PENDING_ORGANIZATION_VERIFICATION`.
Enablement requires a privacy-owner receipt for the exact organization/project retention mode,
abuse-monitoring implications, region/subprocessor review, and project settings. ZDR or Modified
Abuse Monitoring availability is not assumed from request flags. A provider policy/control change
forces re-review and evaluation before further promotion.

### Feedback and drift

First-slice feedback is an enum only; free text and automatic training use are disabled. Feedback is
tenant-scoped, participates in retention/erasure, and uses only bounded low-cardinality aggregates.

The offline suite runs at least weekly after runtime exists and on every model, prompt, price,
provider-policy, safety-incident, material quality, or material traffic-distribution change.
Production content is never automatically copied into fixtures or used for training. A new fixture
derived from a real incident must be manually minimized, synthetic, reviewed for PII, and committed
as a new suite version.

## Consequences

- Stage 9 has a small, explicit and machine-checked activation contract.
- Catalog presence does not claim a model is promoted or provider data controls are approved.
- Price snapshots can age; a changed provider price blocks new promotion until a new version and
  worst-case reservation are reviewed.
- Offline keyword expectations are only baseline deterministic checks. Model-based or human graders
  may be added later, but never as the sole critical-safety gate and only with versioned evaluator
  provenance and bounded data handling.

## Verification requirements

CI validates JSON readability/schema identity, SemVer and unique identifiers, prompt digest,
cross-catalog references, exact stateless/tool-free provider settings, integer worst-case cost,
critical 100% thresholds, ordered canary, immediate critical rollback, non-PII fixture screening,
feedback restrictions, and drift triggers. Negative tests must prove prompt tampering, premature
runtime enablement, weakened critical thresholds, and direct contact PII are rejected.

Before runtime promotion, Stage 9 additionally verifies the real evaluation runner, provider
fixtures/faults/ambiguity, content-redacted receipts, quotas/reservation/reconciliation, canary and
rollback behavior, and privacy-owner provider-control evidence.

## Rollback considerations

This decision is fail-closed. Removing or invalidating the governance bundle disables model
execution; it must never silently select a provider alias/default prompt/default price. Rollback does
not delete historical catalog/evaluation receipts, weaken erasure, resend an ambiguous request,
expose stored content, or enable any ADR-0054 excluded capability.
