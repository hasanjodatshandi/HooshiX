# Identity Service observability

The implemented Identity slices export structured allow-list JSON logs, low-cardinality Micrometer metrics, W3C trace context, management-only health/Prometheus endpoints, Prometheus alerts, and a Grafana dashboard.

Readiness fails closed when PostgreSQL is unavailable, any required local key-ring snapshot is stale, the host-time synchronization signal is unhealthy, or the Identity quota Redis connection is unavailable. Compromised Password remains a request-path authoritative security dependency and fails each unchecked password operation closed; Notification delivery is decoupled by the durable local outbox and is not an Identity database transaction dependency.

Tenant lifecycle reconciliation is monitored through Authorization outbox age and definitive-failure metrics. Invitation expiry publishes bounded, label-free expired and failure counters; any expiry worker failure alerts because stale pending invitations must not silently outlive their TTL.

Ordinary telemetry exporter failure must not change registration, confirmation, quota, compromised-password, credential, or handoff correctness. Required security/privileged audit remains on its separately approved durable off-host path.

Logs, metrics, traces, alerts, and dashboards must not contain raw or pseudonymous User, Contact, request, challenge, client-address, password, code, HIBP SHA-1 prefix/suffix/full digest, key material, ciphertext, recipient, or arbitrary exception/provider payload data.

The `identity.registration.duration`, `identity.authentication.duration`, `identity.password_lifecycle.duration`, `identity.profile.duration`, `identity.mfa.duration`, `identity.external_identity.duration`, and `identity.tenant.duration` metric families use only bounded operation/outcome labels. Profile operations and outcomes are closed enums owned by `ProfileManagement` and `ProfileError`; no contact or request values become labels. Password lifecycle operations are limited to `CHANGE`, `REQUEST_RECOVERY`, and `CONFIRM_RECOVERY`; outcomes are stable error enums. MFA and external-identity operations are closed sets and export only bounded outcome/in-flight signals; TOTP/recovery proofs, OIDC evidence, issuer subject, provider metadata, challenges, enrollment secrets, User identity, and source addresses never become telemetry. Global gRPC admission exports only in-flight, configured-limit, and rejection signals. Quota and dependency observations use bounded dependency/operation/outcome enums only.
