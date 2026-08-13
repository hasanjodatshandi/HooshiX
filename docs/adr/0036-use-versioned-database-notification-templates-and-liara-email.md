# ADR-0036: Use Versioned Database Templates and Liara SMTP Email

## Status

Accepted

## Date

2026-08-10

## Supersedes

This ADR supersedes ADR-0029 only where it selected immutable Git-bundled
Pebble templates with no database template store. It supersedes ADR-0030's
Amazon SES provider selection for Iranian production. Provider-neutral durable
handoff, exact-content retry, deadline, ambiguity, delivery-evidence, escrow,
terminal-state, and callback rules remain unchanged.

## Decision

Notification PostgreSQL is authoritative for templates. The schema contains
definition, immutable version, activation-pointer, and append-only audit tables.
`semantic_type + channel + locale` is unique. Versions use `DRAFT`, `PUBLISHED`,
or `RETIRED`; a version has no `ACTIVE` status. The activation pointer is the
only active-version authority.

Every edit creates a version, including draft edits. Activation locks the
activation row and validates syntax, placeholder compatibility, channel shape,
and content limits before atomically moving `active_version_id`, retaining
`previous_version_id`, and incrementing `generation`. Rollback is another
pointer move to a `PUBLISHED` version.

The active version is resolved during durable acceptance. Its version ID,
content digest, and exact rendered content are persisted with the Notification.
Dispatch and retry never resolve or render a newer version. Version history is
retained indefinitely and runtime roles cannot update or delete versions.
Template audit is append-only and retained at least ten years.

The renderer is a purpose-built bounded placeholder renderer. Pebble,
expression languages, functions, loops, conditions, includes, arbitrary
variables, and raw HTML parameters are prohibited. Placeholders are allow-listed
per semantic type. Verification templates accept exactly `{code}` and
`{expires_minutes}`. Missing, extra, or unknown variables fail. HTML insertion
is context-safe escaped.

Email requires subject, text body, and HTML body. SMS contains only a text
body. The user-approved Persian and English texts are database seed version 1,
not Java constants.

Iranian production uses Liara Transactional Email through authenticated SMTP
with STARTTLS. Amazon SES is rejected. The dedicated credential is delivered
from OpenBao and never stored in Git, environment variables, telemetry, or
payloads. Sender identity is:

```text
domain       = hooshix.com
from         = no-reply@hooshix.com
display name = Hooshix
reply-to     = omitted
```

SPF, DKIM, and DMARC must pass before production readiness. A successful final
SMTP `2xx/250` is `DEFINITIVE_ACCEPTED` and transitions to
`PROVIDER_ACCEPTED`; it is never `DELIVERED`. Undocumented correlation and
correlation inferred from recipient, subject, or time are prohibited. Without
authenticated provider-correlated evidence, final reconciliation after the
72-hour observation window produces `DELIVERY_STATUS_UNKNOWN`. A sandbox
correlation experiment is informative, not a production gate.

## Verification Requirements

Tests cover version immutability, pointer activation, concurrent activation,
rollback generation, seed digests, placeholder validation, HTML escaping,
content limits, exact-version retry, SMTP STARTTLS/authentication, outcome
classification, ambiguity, credential redaction, and no inferred delivery.

## Rollback Considerations

Rollback may repoint activation to an earlier published version. It may not
delete history, alter accepted content, infer delivery, or restore SES without
a superseding ADR.
