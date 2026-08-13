# Runtime Dependency Criticality and Degradation Matrix

This is the human-readable view of the machine-checkable registry required by
ADR-0055, ADR-0063, and ADR-0066.

Canonical source:

```text
docs/architecture/dependency-criticality.yaml
```

The Markdown view MUST be regenerated/checked by CI and must not drift from the
YAML registry. Criticality applies to an **operation -> dependency edge**, not
to a service globally.

| Operation ID | Caller owner | Dependency | Class | Failure behavior | Retry owner | Allowed fallback | Policy owner |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `protected-resource.check-permission` | resource-owning service | Authorization `CheckPermission` | `AUTHORITATIVE_SECURITY` | `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; fail closed | none | none; no permission cache/stale allow | Platform Authorization |
| `identity.security-semantic-quota` | Identity | semantic-quota Redis | `AUTHORITATIVE_SECURITY` | fail closed per quota ADR; anti-lockout sequencing remains authoritative | none | none | Identity |
| `identity.password-credential-write` | Identity | compromised-password service | `AUTHORITATIVE_SECURITY` | reject unchecked password | none | none | Identity |
| `web-bff.authenticated-session-lookup` | Web BFF | BFF session Redis | `AUTHORITATIVE_SECURITY` | session unavailable; never manufacture token fallback | none | none | Web BFF |
| `service.normal-transaction-or-query` | owning service | owning PostgreSQL | `AUTHORITATIVE_STATE` | abort/unavailable; no fabricated state | transaction-safe owner only | none | owning service |
| `identity.notification-handoff-dispatch` | Identity | Notification `SubmitNotification` | `DURABLE_COMMAND` | local outbox remains pending | caller durable dispatcher | none | Identity |
| `notification.provider-dispatch` | Notification | email/SMS provider | `EXTERNAL_SIDE_EFFECT` | preserve ambiguity and reconcile | Notification worker/lifecycle | never fabricate ACCEPTED/DELIVERED | Notification |
| `identity.google-oidc-login` | Identity | Google OIDC endpoints | `AUTHORITATIVE_SECURITY` | login unavailable | protocol-defined safe flow only | no alternate identity auto-link | Identity |
| `business.optional-enrichment` | owning bounded context | explicitly approved enrichment service | `OPTIONAL_READ` | use only explicit bounded degraded result | normally none | bounded-context-defined only | owning bounded context |
| `platform.non-audit-telemetry-export` | any service | observability backend | `OBSERVABILITY` | bounded buffer/drop; business request continues | exporter | bounded loss allowed | Platform Observability |
| `service.required-security-audit-evidence` | owning service | audit persistence/outbox | `AUTHORITATIVE_STATE` / durable contract | do not silently drop | owning service | none unless ADR says otherwise | owning service |

## Composition rules for operations with multiple dependencies

- Every edge keeps its own class and failure action.
- Failure of `AUTHORITATIVE_SECURITY` or `AUTHORITATIVE_STATE` blocks the
  operation; an optional edge cannot override it.
- `OPTIONAL_READ` may degrade only through the fallback explicitly registered
  for that edge.
- `DURABLE_COMMAND` may defer remote execution only after required local intent
  is durably committed.
- `EXTERNAL_SIDE_EFFECT` preserves ambiguous outcomes and reconciles them.
- Authoritative security checks SHOULD run before optional remote enrichment
  when practical.

## Maintenance and CI

- Caller/bounded-context owner owns each edge.
- Platform Architecture owns the schema/enums.
- Security co-reviews `AUTHORITATIVE_SECURITY` changes.
- New production synchronous remote edges must be added before implementation
  is production-eligible.
- CI validates schema, duplicate/orphan edges, required owner/failure/fallback
  fields, and Markdown regeneration.
- Service architecture/contract tests must expose the production synchronous
  edges they use so CI can prove registry coverage.
- Missing fallback means **no fallback**.
