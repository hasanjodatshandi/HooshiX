# Compromised Password Observability

Ownership is service-local. Platform monitoring consumes these artifacts when the approved Prometheus/Grafana stack is implemented.

## Signals

- `compromised_password_dataset_ready`: `1` only when the approved immutable dataset is ready; otherwise `0`.
- `compromised_password_lookup_in_flight`: bounded in-flight lookup count.
- `compromised_password_lookup_rejected_total{reason="capacity"}`: zero-queue capacity rejection count.
- `compromised_password_lookup_duration_seconds_*{outcome="success|failure"}`: bounded lookup timing/outcome metrics.

Metric labels must remain low-cardinality. They must not contain SHA-1 prefix/suffix/full hash, password, User/Tenant/Membership/Contact/Session/request identifiers, or raw client address.

## Owned artifacts

- `alerts/compromised-password-service.yaml` — service failure and saturation alerts.
- `grafana/compromised-password-service.json` — service operations dashboard.

These files define ownership and queries. They do not prove the platform observability backend is deployed. Runtime Collector/Prometheus/Grafana integration remains separate environment evidence under ADR-0044.
