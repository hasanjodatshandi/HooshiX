# ADR-0031: PII-Safe Logging Detection Pipeline v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

PII-safe logging is enforced by prevention, static analysis, transport redaction, and runtime detection. Documentation alone is not a sufficient control.

### Application logging contract

Production Java logs are structured events with a stable `eventCode` and an allow-listed field set. Security-sensitive code must not serialize arbitrary Domain/DTO/request/response objects or use their `toString()` as log payload.

Dynamic values classified as credentials, authentication secrets, contact PII, payment/health data, raw external payloads, request/notification IDs prohibited by service ADRs, or SQL bind values are not allowed in logs.

### CI static enforcement

Repository CI runs custom Semgrep rules that fail the build for reviewed unsafe patterns, including:

- logging sensitive getters/fields such as password, token, cookie, OTP, recovery code, email, phone, secret, key material, or raw recipient;
- logging whole request/response/domain objects in security-sensitive packages;
- string concatenation/interpolation of untrusted request data into log message bodies;
- enabling SQL bind/body/gRPC metadata debug logging in production config.

Rules are versioned and have positive/negative fixtures. Suppression requires a reviewed code annotation/comment plus security ownership and an expiry when the exception is temporary.

### Telemetry pipeline redaction

The OpenTelemetry/log collection pipeline applies a second allow-list/redaction layer to structured attributes before export. Unknown sensitive attributes are dropped rather than forwarded by default for production security namespaces.

### Runtime detection

Staging/release tests exercise seeded fake PII/secret canaries through critical flows and assert that the downstream log sink does not contain them.

Production runs an out-of-band detector for high-confidence secret/PII patterns and seeded canaries. Findings page the owning service/security team. The detector stores a hash/reference for triage rather than duplicating the sensitive value into an alert or metric label.

Runtime detection is a last line of defense and does not justify logging PII intentionally. No static rule, regex, or runtime detector is treated as proof that all sensitive-data leaks are impossible; layered prevention and negative verification remain mandatory.

## Verification requirements

- Semgrep rule unit fixtures and repository scan;
- service log-injection/redaction tests;
- canary email/phone/token/OTP absence in the downstream sink;
- OTel attribute allow-list/redaction tests;
- production config prevents body/bind/metadata debug logging;
- alert payload itself contains no copied secret/PII;
- incident/runbook test for accidental logging and retention/purge response.

## Rollback considerations

Rollback MUST preserve structured allow-listed logging, static enforcement, telemetry redaction, canary/runtime detection, and PII-safe alerting. It MUST NOT restore arbitrary object logging, production body/bind/metadata debug logging, broad analyzer suppression, or alerts that duplicate the sensitive value being detected.
