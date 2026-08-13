# Runtime Dependency Criticality and Degradation Matrix

This is the human-readable view of the canonical machine-checkable registry:

```text
docs/architecture/dependency-criticality.yaml
```

The Markdown view MUST be regenerated/checked by CI and MUST NOT become an independent source of truth. Criticality applies to an **operation -> dependency edge**, not to a service globally.

| Operation ID | Caller owner | Dependency | Class | Failure behavior | Retry owner | Allowed fallback | Policy owner | Current policy references |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `protected-resource.check-permission` | resource-owning service | Authorization `CheckPermission` | `AUTHORITATIVE_SECURITY` | `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; fail closed | none | none; no permission cache/stale allow | Platform Authorization | ADR-0039, ADR-0056, ADR-0062, ADR-0066 |
| `identity.security-semantic-quota` | Identity | semantic-quota Redis | `AUTHORITATIVE_SECURITY` | fail closed; anti-lockout/current quota semantics | none | none | Identity | ADR-0054 |
| `identity.password-credential-write` | Identity | compromised-password service | `AUTHORITATIVE_SECURITY` | reject unchecked password | none | none | Identity | `security-architecture.md` §4; `services/identity-service.md` §7 |
| `web-bff.authenticated-session-lookup` | Web BFF | BFF session Redis | `AUTHORITATIVE_SECURITY` | session unavailable; never manufacture token fallback | none | none | Web BFF | ADR-0038, ADR-0045 |
| `service.normal-transaction-or-query` | owning service | owning PostgreSQL | `AUTHORITATIVE_STATE` | abort/unavailable; no fabricated state | transaction-safe owner only | none | owning service | ADR-0057; `data-and-messaging.md` §1 |
| `identity.notification-handoff-dispatch` | Identity | Notification `SubmitNotification` | `DURABLE_COMMAND` | local outbox remains pending | caller durable dispatcher | none | Identity | ADR-0029 |
| `notification.provider-dispatch` | Notification | email/SMS provider | `EXTERNAL_SIDE_EFFECT` | preserve ambiguity and reconcile | Notification worker/lifecycle | never fabricate ACCEPTED/DELIVERED | Notification | ADR-0029, ADR-0030, ADR-0049 |
| `identity.google-oidc-login` | Identity | Google OIDC endpoints | `AUTHORITATIVE_SECURITY` | login unavailable | protocol-defined safe flow only | no alternate identity auto-link | Identity | ADR-0038, ADR-0045 |
| `business.optional-enrichment` | owning bounded context | explicitly approved enrichment service | `OPTIONAL_READ` | use only explicit bounded degraded result | none | bounded-context-defined only | owning bounded context | ADR-0063, ADR-0066 |
| `platform.non-audit-telemetry-export` | any service | observability backend | `OBSERVABILITY` | bounded buffer/drop; business request continues | exporter | bounded loss allowed | Platform Observability | ADR-0063; `reliability-and-observability.md` §10 |
| `service.required-security-audit-evidence` | owning service | audit persistence/outbox | `AUTHORITATIVE_STATE` / durable contract | do not silently drop | owning service | none unless explicit current decision | owning service | ADR-0063; `security-architecture.md` §13 |

## Composition rules

- Every edge keeps its own class and failure action.
- `AUTHORITATIVE_SECURITY` or `AUTHORITATIVE_STATE` failure blocks according to the edge's registered action.
- `OPTIONAL_READ` degrades only through its explicit bounded fallback.
- `DURABLE_COMMAND` defers remote execution only after required local intent is durably committed.
- `EXTERNAL_SIDE_EFFECT` preserves ambiguous outcomes and reconciles them.
- Authoritative security checks SHOULD run before optional remote enrichment when practical.
- Missing fallback means **no fallback**.

## Maintenance and CI

- Caller/bounded-context owner owns each edge.
- Platform Architecture owns schema/classes.
- Security co-reviews `AUTHORITATIVE_SECURITY` changes.
- New production synchronous remote edges must be registered before implementation becomes production-eligible.
- `policy_refs` may reference retained current ADRs and/or current canonical documents; they MUST NOT point to deleted history or an unrelated ADR merely to satisfy the schema.
- CI validates schema, duplicate/orphan edges, required fields and policy refs, current target existence, and Markdown regeneration/coverage.
