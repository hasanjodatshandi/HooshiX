# ADR-0010: Versioned Database Notification Templates

## Status

Accepted — current template decision; former Liara provider selection superseded by ADR-0055

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

### Email provider ownership

ADR-0055 supersedes this ADR's former Liara provider selection and owns the current
provider-neutral SMTP/Google Gmail staging decision. Credential, transport, sender, outcome,
reconciliation, and production-selection gates follow ADR-0055 without changing the template and
exact-content rules above.

## Verification requirements

Tests cover version immutability, pointer activation/concurrent activation, pointer rollback generation, seed digests, placeholder validation, HTML escaping, content limits, and exact-version retry. ADR-0055 owns SMTP/provider verification.

## Rollback considerations

Rollback may repoint activation to an earlier approved `PUBLISHED` template version. It MUST NOT delete/modify immutable published content, alter already accepted Notification content, infer delivery, or restore a superseded provider decision.
