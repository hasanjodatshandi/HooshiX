# Runtime Dependency Criticality and Degradation Matrix

This is the human-readable view of the canonical machine-checkable registry:

```text
docs/architecture/dependency-criticality.yaml
```

The Markdown view MUST be regenerated/checked by CI and MUST NOT become an independent source of truth. Criticality applies to an **operation -> dependency edge**, not to a service globally.

| Operation ID | Caller owner | Dependency | Class | Failure behavior | Retry owner | Allowed fallback | Policy owner | Current policy references |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `protected-resource.check-permission` | resource-owning service | Authorization `CheckPermission` | `AUTHORITATIVE_SECURITY` | `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; fail closed | none | none; no permission cache/stale allow | Platform Authorization | ADR-0013, ADR-0026, ADR-0032, ADR-0036 |
| `identity.security-semantic-quota` | Identity | semantic-quota Redis | `AUTHORITATIVE_SECURITY` | fail closed; anti-lockout/current quota semantics | none | none | Identity | ADR-0024 |
| `identity.password-credential-write` | Identity | Compromised Password `LookupCompromisedPasswordRange` | `AUTHORITATIVE_SECURITY` | reject unchecked password; dataset/query failure never becomes clean result | none | none; no external-provider/Redis/PostgreSQL fallback | Identity | ADR-0040; `security-architecture.md` §4; `services/identity-service.md` §7; `services/compromised-password-service.md` §2 |
| `web-bff.authenticated-session-lookup` | Web BFF | BFF session Redis | `AUTHORITATIVE_SECURITY` | session unavailable; never reconstruct from browser state | none | none | Web BFF | ADR-0016; `services/web-bff.md` §5 |
| `web-bff.security-semantic-quota` | Web BFF | semantic-quota Redis | `AUTHORITATIVE_SECURITY` | OIDC security operation unavailable; fail closed | none | none | Web BFF | ADR-0024; `services/web-bff.md` §9 |
| `service.normal-transaction-or-query` | owning service | owning PostgreSQL | `AUTHORITATIVE_STATE` | abort/unavailable; no fabricated state | transaction-safe owner only | none | owning service | ADR-0027; `data-and-messaging.md` §1 |
| `identity.notification-handoff-dispatch` | Identity | Notification `SubmitNotification` | `DURABLE_COMMAND` | local outbox remains pending | caller durable dispatcher | none | Identity | ADR-0006 |
| `identity.authorization-owner-provisioning` | Identity | Authorization owner provisioning | `DURABLE_COMMAND` | keep tenant PROVISIONING/outbox pending | caller durable dispatcher | none | Identity | ADR-0012; `authorization-service.md` §10 |
| `identity.authorization-member-provisioning` | Identity | Authorization default-member provisioning | `DURABLE_COMMAND` | keep provisioning outbox pending; Authorization default deny | caller durable dispatcher | none | Identity | ADR-0012; `authorization-service.md` §10 |
| `identity.authorization-membership-removal-prepare` | Identity | Authorization `PrepareMembershipRemoval` | `AUTHORITATIVE_SECURITY` | fail closed; Membership removal cannot commit | none | none | Identity | ADR-0012; `authorization-service.md` §9 |
| `identity.authorization-membership-removal-resolution` | Identity | Authorization finalize/cancel removal reservation | `DURABLE_COMMAND` | keep owner-safety reservation + resolution outbox pending | caller durable dispatcher | none | Identity | ADR-0012; `authorization-service.md` §9 |
| `identity.authorization-tenant-lifecycle-sync` | Identity | Authorization tenant lifecycle cleanup/reconciliation | `DURABLE_COMMAND` | keep tenant lifecycle pending; never fabricate Authorization state | caller durable dispatcher | none | Identity | ADR-0012; `authorization-service.md` §10 |
| `identity.authorization-platform-permission-check` | Identity | Authorization `CheckPlatformPermission` | `AUTHORITATIVE_SECURITY` | platform-only operation unavailable; fail closed | none | none | Identity | ADR-0013; `authorization-service.md` §11 |
| `notification.result-callback-dispatch` | Notification | Identity `ReportNotificationResult` | `DURABLE_COMMAND` | result outbox remains pending | Notification durable callback dispatcher | none | Notification | ADR-0006; `notification-service.md` §14 |
| `notification.provider-dispatch` | Notification | email/SMS provider | `EXTERNAL_SIDE_EFFECT` | preserve ambiguity and reconcile | Notification worker/lifecycle | never fabricate ACCEPTED/DELIVERED | Notification | ADR-0006, ADR-0007, ADR-0020 |
| `web-bff.google-oidc-login` | Web BFF | Google OIDC endpoints | `AUTHORITATIVE_SECURITY` | login unavailable | protocol-defined safe flow only | no alternate identity auto-link | Web BFF | ADR-0016; `services/web-bff.md` §3 |
| `web-bff.identity-oidc-evidence-submit` | Web BFF | Identity external-identity/session establishment | `AUTHORITATIVE_SECURITY` | login unavailable | none | none | Web BFF | ADR-0012, ADR-0016; `services/web-bff.md` §3 |
| `web-bff.identity-audience-token-broker` | Web BFF | Identity `IssueAudienceAccessToken` | `AUTHORITATIVE_SECURITY` | token brokerage unavailable; fail closed | none | none | Web BFF | ADR-0016; `services/web-bff.md` §4 |
| `web-bff.authorization-tenant-management` | Web BFF | Authorization tenant-management gRPC | `AUTHORITATIVE_SECURITY` | management unavailable; fail closed; no fabricated local authority | none | none | Web BFF | ADR-0013, ADR-0016; `services/web-bff.md` §10; `authorization-service.md` §7 |
| `web-bff.resource-api-dispatch` | Web BFF | registered resource service | `AUTHORITATIVE_STATE` | abort request/resource unavailable; no fabricated business data | none | none | Web BFF | ADR-0016; `services/web-bff.md` §§10-11 |
| `business.optional-enrichment` | owning bounded context | explicitly approved enrichment service | `OPTIONAL_READ` | use only explicit bounded degraded result | none | bounded-context-defined only | owning bounded context | ADR-0033, ADR-0036 |
| `platform.non-audit-telemetry-export` | any service | observability backend | `OBSERVABILITY` | bounded buffer/drop; business request continues | exporter | bounded loss allowed | Platform Observability | ADR-0033; `reliability-and-observability.md` §10 |
| `service.required-security-audit-evidence` | owning service | audit persistence/outbox | `AUTHORITATIVE_STATE` | durable contract; do not silently drop | owning service | none unless explicit current decision | owning service | ADR-0033; `security-architecture.md` §13 |

## Composition rules

- Every edge keeps its own class and failure action.
- `AUTHORITATIVE_SECURITY` or `AUTHORITATIVE_STATE` failure blocks according to the edge's registered action.
- `OPTIONAL_READ` degrades only through its explicit bounded fallback.
- `DURABLE_COMMAND` defers remote execution only after required local intent is durably committed.
- `EXTERNAL_SIDE_EFFECT` preserves ambiguous outcomes and reconciles them.
- Authoritative security checks SHOULD run before optional remote enrichment when practical.
- Compromised-password lookup is authoritative security for password credential writes: SQLite dataset/query/corruption/unavailability fails closed, no response truncation is allowed, and no HIBP/external-provider/Redis/PostgreSQL fallback exists.
- Membership-removal preparation is deliberately a durable Authorization-side safety reservation rather than a race-prone read-only owner-count check.
- Platform permission is a separate authoritative check and never converts `platform_admin` into tenant/resource fallback authority.
- BFF tenant-management transport is an authoritative-security edge because Authorization owns both management authorization and state mutation; BFF/Authorization outage never becomes local allow.
- BFF audience-token brokerage is an authoritative-security edge; browser input never selects arbitrary audience and no stale/fabricated JWT is a fallback.
- BFF business dispatch is authoritative state transport; downstream failure never becomes fabricated business data.
- BFF OIDC quota Redis is separate from session lookup semantics even when infrastructure is shared; failure does not disable abuse control.
- Missing fallback means **no fallback**.

## Maintenance and CI

- Caller/bounded-context owner owns each edge.
- Platform Architecture owns schema/classes.
- Security co-reviews `AUTHORITATIVE_SECURITY` changes.
- New production synchronous remote edges must be registered before implementation becomes production-eligible.
- `policy_refs` may reference retained current ADRs and/or current canonical documents; they MUST NOT point to deleted history or unrelated ADR merely to satisfy schema.
- CI validates schema, duplicate/orphan edges, required fields and policy refs, current target existence, and Markdown regeneration/coverage.
