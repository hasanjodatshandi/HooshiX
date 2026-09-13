# Conversation service observability

The service exposes private Actuator health and Prometheus endpoints, structured JSON logs, and
bounded OpenTelemetry traces to the internal Collector. The active private Conversation gRPC slice
records only fixed operation names and gRPC outcome codes in `conversation.grpc.duration`; contract
validation rejection count is also exported. Readiness, PostgreSQL pool saturation, RPC latency,
and repeated internal/unavailable outcomes are covered by the dashboard and alerts. Content,
request/resource/User/Tenant/Membership identifiers, tokens, and cursor values are prohibited from
all telemetry. ModelRun/provider signals remain pending their implementation slice.
