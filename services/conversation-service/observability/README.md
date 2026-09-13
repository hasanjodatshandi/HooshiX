# Conversation service observability

This first executable foundation exposes private Actuator health and Prometheus endpoints, emits
structured JSON logs, and exports bounded OpenTelemetry traces to the internal Collector. The
dashboard and alerts intentionally cover only readiness and PostgreSQL pool saturation because no
Conversation API or provider worker is enabled in this slice. Content and customer identifiers are
prohibited from all telemetry.
