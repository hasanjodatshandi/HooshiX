# Web BFF Observability

Spring MVC observation and the OpenTelemetry starter provide HTTP server request metrics and traces on the private management surface. Internal gRPC calls emit `web_bff.dependency.duration` plus client spans with fixed dependency/operation/outcome tags, Redis session operations emit `web_bff.redis.duration`, Google token exchange emits `web_bff.oidc.provider.duration`, and the OIDC semantic quota emits `web_bff_oidc_quota_total`. Their labels are closed operation/provider/outcome enums only. Telemetry remains low-cardinality. Cookie/session/pre-auth/provider/internal token, state/nonce/verifier/CSRF secret, raw client IP, User/Tenant/Membership identifiers, request IDs, and full request/response bodies are prohibited labels or span attributes.

Prometheus reaches `/actuator/prometheus` only through the management NetworkPolicy/Istio rule. When Google OIDC is enabled, readiness additionally requires fresh quota key material, authoritative Redis quota connectivity, and a synchronized host-time signal. Provider/quota telemetry exporter failure does not change the safe request result.

`web_bff.redis.duration` covers session commands with fixed `operation` values
`create`, `load`, `touch`, `rotate`, `rotate_mfa`, `destroy_load`, `destroy_index`,
`destroy`, `erase_scan`, `erase_session`, and `erase_index`. Its `outcome` values are
`ok`, `timeout`, `unavailable`, and `error`; exception messages, Redis keys, and
session/subject identifiers are never labels. A `timeout` or `unavailable` outcome
is fail-closed dependency failure, not a normal missing/expired session.
