# Local Integrated WSL Runtime Runbook

## Purpose

Use this runbook to run the current executable HooshiX application slices together from the canonical WSL checkout at `/home/coder/workspace/Hooshix`.

This runtime is the fast application-integration lane. It is not staging, production, or the production-fidelity kind/Calico/Istio/Traefik/WAF lane.

## Components

`make local-runtime-up` runs these components together:

```text
PostgreSQL 18.4  -> 127.0.0.1:15432
Redis 8.2.8      -> 127.0.0.1:16379
Compromised Password gRPC / management -> 19090 / 19091
Notification gRPC / management         -> 19100 / 19102
Authorization gRPC / management        -> 19200 / 19202
Identity gRPC / management             -> 19300 / 19302
Web BFF HTTPS / management             -> 18443 / 19402
```

The Web BFF public local origin is `https://localhost:18443`. Its self-signed local certificate is generated under `.local-runtime/tls/`. The private key stays inside the local PKCS12 file and is never versioned.

## Security and data boundaries

- `.local-runtime/` is Git-ignored and uses restrictive local permissions for generated credentials and key material.
- PostgreSQL creates a distinct database, migration role, and non-owner runtime role for Identity, Authorization, and Notification.
- Runtime roles remain `NOSUPERUSER NOBYPASSRLS` and do not own their service database.
- Redis uses bounded local memory, AOF `everysec`, and `noeviction`.
- Identity and Authorization use a local host-time-health fixture for the fast application lane. It is not staging/production host-time synchronization evidence.
- Notification uses only the `local & !staging & !production` simulated Email/SMS adapters.
- Compromised Password uses a repository-built `GENERATED_TEST_FIXTURE` dataset, never the production HIBP corpus.
- OTLP backends are not required for this fast lane. Application observability code remains enabled, but this runtime does not claim Collector/Loki/Tempo/Prometheus/Grafana integration evidence.
- Local plaintext gRPC is permitted only for this loopback developer lane. Production workload identity/mTLS remains governed by Istio Ambient and current network/security ADRs.

## Start

```bash
cd /home/coder/workspace/Hooshix
make local-runtime-up
```

The command verifies the required Java/Docker/OpenSSL tools, builds Boot JARs, starts PostgreSQL and Redis, provisions isolated database roles, runs service-owned Flyway migrations, creates or reuses restart-stable local-only security material, builds the generated compromised-password fixture, and starts all five JVMs in dependency order. A service must become Ready before the next dependent service starts.

## Inspect

```bash
make local-runtime-status
make local-runtime-logs
```

A successful status reports PostgreSQL and Redis `UP` and every service process/readiness endpoint `UP`. The four internal gRPC listener ports must also be reachable.

For the local self-signed HTTPS endpoint:

```bash
curl -k https://localhost:18443/
```

An unknown route returns the bounded `404 / NOT_FOUND` problem response. Browser testing can trust `.local-runtime/tls/web-bff.crt` in a local development trust store if required. Do not reuse this certificate outside local development.

## Stop or reset

```bash
make local-runtime-down
```

This stops the five JVMs and Docker Compose dependencies while retaining local PostgreSQL/Redis volumes and generated `.local-runtime/` state.

To remove the Compose data volumes:

```bash
make local-runtime-reset
```

Generated `.local-runtime/` credentials remain local and Git-ignored. Delete that directory only when intentionally rotating all local generated credentials/material before the next start.

## Failure handling

- If Java is not Eclipse Temurin 25.0.4, fix the WSL toolchain before starting the runtime.
- If a migration fails, inspect only `.local-runtime/logs/<service>-migration.log`; do not bypass Flyway or run the application runtime as the migration owner.
- If one service is not Ready, inspect `.local-runtime/logs/<service>.log` and fix the failing dependency/configuration. Do not increase security deadlines or enable fail-open fallback to make local startup succeed.
- If ports are already used, stop the conflicting local process rather than changing production contracts.
- `local-runtime-reset` is a local destructive operation. It is not a database migration/rollback mechanism for staging or production.

## Evidence boundary

A green local integrated runtime proves that the current executable application slices can run together in the canonical WSL developer environment with local PostgreSQL/Redis and local-only security/provider fixtures. It does not prove kind/mesh/edge/admission behavior, real provider delivery, production HIBP corpus approval, production secret delivery, staging deployment, load/recovery, or production readiness.
