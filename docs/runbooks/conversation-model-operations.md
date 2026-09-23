# Conversation model runtime operations

This runbook controls the Stage 9 text-only, tool-free Conversation model runtime. It does not authorize production commissioning.

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

## Canary and promotion

The only permitted percentages are `1`, `5`, `25`, and `100`. Tenant assignment is deterministic, so a tenant does not oscillate between cohorts. Observe each step for the governance-defined minimum window before promotion. Re-run evaluation after any model, prompt, price, provider-policy, safety-incident, material quality, or traffic-distribution change.

## Disable and rollback

Immediately set `CONVERSATION_PROVIDER_RUNTIME_ENABLED=false` and `CONVERSATION_PROVIDER_CANARY_PERCENT=0` on any confirmed critical safety/privacy incident, provider failure rate above 1%, cost overrun above 5%, p95 latency above 30 seconds, invalid/expired evidence, or provider-control change. This stops new acceptance; it does not resend ambiguous requests. Reconcile already accepted runs conservatively, retain evidence, and require a fresh reviewed tuple before reactivation.

Provider transport cancellation aborts the local foreground HTTP exchange only. Because the provider request is not a background response, cancellation must never claim that remote compute or cost stopped; final accounting remains conservative.

## Triage

Use only low-cardinality `outcome` and `result` labels on the Conversation dashboard. Never inspect or copy conversation content into alerts, tickets, traces, metrics, receipts, or fixtures. For a real incident-derived regression, manually minimize it into synthetic non-PII data and review it before committing a new suite version.
