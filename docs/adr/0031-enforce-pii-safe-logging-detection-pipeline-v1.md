# ADR-0031: PII-Safe Logging Detection Pipeline v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized 2026-08-13; bound to ADR-0044 telemetry runtime 2026-08-15

## Decision

PII-safe logging is enforced by prevention, static analysis, telemetry-pipeline redaction, and runtime detection. Documentation alone is not sufficient control.

ADR-0044 owns the Day-One observability transport/backends. This ADR remains authority for what log/telemetry data is permitted.

### Application logging contract

Production Java logs are structured JSON events with stable `eventCode` and allow-listed fields. Security-sensitive code does not serialize arbitrary Domain/DTO/request/response objects or use their `toString()` as log payload.

Never log raw credentials/authentication secrets/contact PII/payment/health data, raw external payloads, prohibited request/notification identifiers, SQL binds, HIBP screening prefix/suffix/full SHA-1, or other values prohibited by owning service contracts.

Input-derived values are protected from CR/LF/log injection. Unreviewed exception/cause/provider/JDBC/native text is treated as sensitive until classified.

### CI static enforcement

Repository Semgrep/static rules fail reviewed unsafe patterns, including:

- logging sensitive getters/fields such as password/token/cookie/OTP/recovery/email/phone/secret/key/recipient/hash material;
- logging whole request/response/domain objects in security-sensitive packages;
- concatenating/interpolating untrusted request data into log message bodies;
- enabling SQL-bind/body/complete-gRPC-metadata debug logging in production;
- adding unbounded or identity-bearing MDC/log attributes outside the approved allow-list.

Rules have positive/negative fixtures. Suppression requires explicit security ownership and expiry when temporary.

### Collector/pipeline redaction

ADR-0044 `otelcol-contrib` applies a second allow-list/redaction/filtering layer before export to Loki/Tempo or other approved ordinary telemetry backends.

Unknown sensitive attributes in production security namespaces are dropped rather than forwarded. Trace/baggage correlation fields are also subject to the same data-classification rules; moving a value from log to trace does not make it safe.

Required privileged/security audit remains separate durable/off-host authority and is not silently transformed into best-effort Collector/Loki logging.

### Runtime detection

Staging/release tests seed fake PII/secret canaries through critical flows and assert absence from downstream observable stores, including applicable Loki logs, Tempo traces, Prometheus-exposed labels/series, and Grafana-visible queries.

Production runs out-of-band detection for high-confidence secret/PII patterns and seeded canaries. Findings page the owning team without copying the sensitive value into the alert/metric. Detector evidence stores a safe hash/reference only.

Runtime detection is last-line defense and never justifies intentional PII logging.

## Verification requirements

- Semgrep/static rule fixtures + repository scan;
- service CRLF/log-injection/redaction tests;
- email/phone/token/OTP/HIBP-hash canary absence in applicable Loki/Tempo/Prometheus/Grafana-visible data;
- Collector attribute allow-list/redaction tests before export;
- production config prevents body/bind/metadata debug logging;
- bounded MDC/baggage/attribute rules;
- alert payload contains no copied secret/PII;
- Collector/backend failure does not switch to unsafe direct/synchronous logging;
- incident/runbook exercise for accidental logging, access containment, retention/purge response, and evidence handling.

## Rollback considerations

Rollback MUST preserve structured allow-listed logging, static enforcement, Collector redaction/filtering, canary/runtime detection, PII-safe alerting, and separation of authoritative audit from ordinary telemetry. It MUST NOT restore arbitrary object logging, production body/bind/metadata debug logging, broad analyzer suppression, synchronous direct remote logging, or alerts that duplicate the sensitive value.