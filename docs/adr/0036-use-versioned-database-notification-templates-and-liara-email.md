# ADR-0036: Versioned Database Templates and Liara Email

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Template source of truth

Notification PostgreSQL is authoritative for message templates. Schema includes template definition, immutable version, activation pointer, and append-only audit.

`semantic_type + channel + locale` is unique. Version states are:

```text
DRAFT
PUBLISHED
RETIRED
```

There is no independent `ACTIVE` version status; the activation pointer is the sole active-version authority.

Every edit creates a new immutable version. Activation locks the activation row, validates syntax/placeholders/channel/content limits, then atomically updates `active_version_id`, retains `previous_version_id`, and increments `generation`. Rollback is another reviewed pointer move to an already `PUBLISHED` version.

During durable Notification acceptance, the active version is resolved and its version ID, content digest, and exact rendered content become fixed for that Notification. Dispatch/retry/reconciliation never resolve/render a newer version.

Template version history is retained according to the approved audit/retention policy; runtime roles cannot mutate/delete immutable published versions. Template audit is append-only and retained at least ten years.

### Renderer

Rendering uses a purpose-built bounded placeholder model. General expression languages, arbitrary functions, loops, conditions, includes, arbitrary variables, and unsafe raw-HTML parameters are prohibited.

Placeholders are allow-listed per semantic type. Verification templates accept exactly `{code}` and `{expires_minutes}`. Missing/extra/unknown variables fail. HTML insertion is context-safe escaped.

Email requires subject + text body + HTML body. SMS contains text body only. Approved Persian/English initial templates are versioned database seed data, not Java constants.

### Production Email

Production Email uses Liara Transactional Email via authenticated SMTP + STARTTLS.

Credential material is sourced through OpenBao/External Secrets and never stored in Git, images, ordinary environment variables, logs, traces, metrics, or message payloads.

Sender identity:

```text
domain       = hooshix.com
from         = no-reply@hooshix.com
display name = Hooshix
reply-to     = omitted
```

SPF, DKIM, and DMARC MUST pass before production readiness.

Final SMTP `2xx/250` is `DEFINITIVE_ACCEPTED` and transitions to `PROVIDER_ACCEPTED`; it is never `DELIVERED`. Delivery requires authenticated/correlated provider evidence. Correlation inferred from recipient, subject, or time is prohibited. Without conclusive evidence, final reconciliation after the 72-hour observation window produces `DELIVERY_STATUS_UNKNOWN`.

## Verification requirements

Tests cover version immutability, pointer activation/concurrent activation, pointer rollback generation, seed digests, placeholder validation, HTML escaping, content limits, exact-version retry, SMTP STARTTLS/authentication, bounded timeout/outcome classification, ambiguity, credential/PII redaction, and no inferred delivery.

## Rollback considerations

Rollback may repoint activation to an earlier approved `PUBLISHED` template version. It MUST NOT delete/modify immutable published content, alter already accepted Notification content, infer delivery, or switch Email provider without a new reviewed current decision and migration/contract evidence.
