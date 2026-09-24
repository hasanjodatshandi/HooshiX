# Conversation model runtime operations

This runbook controls the text-only, tool-free Conversation model runtime. Stage 9 staging evidence is complete; this document does not authorize Production commissioning or a later canary step.

## Activation prerequisites

Keep `CONVERSATION_PROVIDER_RUNTIME_ENABLED=false` and `CONVERSATION_PROVIDER_CANARY_PERCENT=0` until all of the following are retained and reviewed: a passing signed content-free evaluation receipt, provider organization data-control evidence, product/security/privacy/platform approvals, exact model/prompt/price tuple review, and a rollback rehearsal. The provider API key stays in the mounted secret file and never enters Git, values, logs, or command output.

Run the offline suite from the canonical checkout with private mode-0600 files:

```bash
python3 scripts/mlops/run_evaluation.py \
  --api-key-file .platform-runtime/staging/private/openai-api-key \
  --signing-key-file .platform-runtime/staging/private/model-evaluation-signing-key \
  --output .platform-runtime/staging/evidence/model-evaluation-receipt.json
```

The receipt contains only case identifiers, bounded categories, bounded provider outcome codes,
aggregate counts, latency/cost values, version/digest provenance, and an HMAC signature. Provider
outcomes distinguish completed text/refusal, incomplete/failed/cancelled execution, empty output,
HTTP class, timeout/transport failure, and invalid response without retaining provider payload or
error text. It also retains aggregate token totals, total integer-micro-USD cost, p50/p95 cost and
latency, and bounded category counts. An expected provider safety refusal/filter is a safe rejection;
an unexpected filter or any other non-completed outcome remains an evaluation error and blocks
promotion. The receipt
must contain no prompt, input, output, identity, tenant, contact, provider response ID, or raw error
data.

After a canary observation and rollback have been independently verified, issue the content-free
staging receipt. Supply the exact tested source commit/image digest and measured aggregate values;
never place tenant/user/conversation/run IDs or prompt/output content in this command or receipt:

```bash
python3 scripts/mlops/canary_receipt.py issue \
  --runtime-repository-commit "$TESTED_COMMIT" \
  --runtime-image-digest "$TESTED_IMAGE_DIGEST" \
  --observation-started-at "$OBSERVATION_STARTED_AT" \
  --observation-completed-at "$OBSERVATION_COMPLETED_AT" \
  --run-count "$RUN_COUNT" \
  --succeeded-count "$SUCCEEDED_COUNT" \
  --failed-count "$FAILED_COUNT" \
  --outcome-unknown-count "$OUTCOME_UNKNOWN_COUNT" \
  --critical-incident-count "$CRITICAL_INCIDENT_COUNT" \
  --total-actual-cost-micro-usd "$TOTAL_ACTUAL_COST_MICRO_USD" \
  --p95-latency-ms "$P95_LATENCY_MS" \
  --error-rate-basis-points "$ERROR_RATE_BASIS_POINTS" \
  --cost-overrun-basis-points "$COST_OVERRUN_BASIS_POINTS" \
  --assistant-output-verified \
  --privacy-canary-passed \
  --log-leak-scan-passed \
  --rollback-rehearsal-passed \
  --runtime-disabled-after \
  --evaluation-receipt .platform-runtime/staging/evidence/model-evaluation-receipt-v3.1.json \
  --provider-approval-receipt .platform-runtime/staging/evidence/provider-control-approval-v1.json \
  --signing-key-file .platform-runtime/staging/private/model-evaluation-signing-key \
  --receipt .platform-runtime/staging/evidence/staging-canary-receipt-v1.json

python3 scripts/mlops/canary_receipt.py verify \
  --evaluation-receipt .platform-runtime/staging/evidence/model-evaluation-receipt-v3.1.json \
  --provider-approval-receipt .platform-runtime/staging/evidence/provider-control-approval-v1.json \
  --signing-key-file .platform-runtime/staging/private/model-evaluation-signing-key \
  --receipt .platform-runtime/staging/evidence/staging-canary-receipt-v1.json
```

Keep every private receipt and signing key user-owned at mode `0600`. The issuer rejects an observation
shorter than the governance window, a threshold breach, missing explicit output/privacy/log/rollback
checks, a non-disabled final runtime, or evidence that does not bind to the current governance and
provider-approval digests.

## Canary and promotion

The only permitted percentages are `1`, `5`, `25`, and `100`. Tenant assignment is deterministic, so a tenant does not oscillate between cohorts. Observe each step for the governance-defined minimum window before promotion. Re-run evaluation after any model, prompt, price, provider-policy, safety-incident, material quality, or traffic-distribution change.

## Disable and rollback

Immediately set `CONVERSATION_PROVIDER_RUNTIME_ENABLED=false` and `CONVERSATION_PROVIDER_CANARY_PERCENT=0` on any confirmed critical safety/privacy incident, provider failure rate above 1%, cost overrun above 5%, p95 latency above 30 seconds, invalid/expired evidence, or provider-control change. This stops new acceptance; it does not resend ambiguous requests. Reconcile already accepted runs conservatively, retain evidence, and require a fresh reviewed tuple before reactivation.

Provider transport cancellation aborts the local foreground HTTP exchange only. Because the provider request is not a background response, cancellation must never claim that remote compute or cost stopped; final accounting remains conservative.

## Triage

Use only low-cardinality `outcome` and `result` labels on the Conversation dashboard. Never inspect or copy conversation content into alerts, tickets, traces, metrics, receipts, or fixtures. For a real incident-derived regression, manually minimize it into synthetic non-PII data and review it before committing a new suite version.
