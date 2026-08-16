# Notification Service observability

The first vertical slice exports structured allow-list logs, low-cardinality Micrometer metrics, W3C trace context, health/readiness, Prometheus alerts, and a Grafana dashboard.

Readiness fails closed when PostgreSQL is unavailable, the required registration-verification templates are not active and digest-valid, or either local key-ring snapshot is stale. Ordinary telemetry exporter failure must not change durable notification submission behavior.

The service must not place recipient addresses, verification codes, rendered message content, request IDs, notification IDs, provider response bodies, or raw exceptions into ordinary telemetry.

Provider delivery metrics are added with the production provider adapters. This slice does not report simulated local sends as canonical provider acceptance or delivery outcomes and does not claim production Liara/IPPanel evidence.
