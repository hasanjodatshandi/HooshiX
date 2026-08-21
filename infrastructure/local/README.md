# HooshiX Local Integrated Runtime

This directory owns the repeatable local application-integration dependencies for the canonical WSL checkout.

It is a **developer-only fast application lane**, not staging, production, or the production-fidelity kind/Calico/Istio/Traefik/WAF lane.

The runtime uses:

- pinned PostgreSQL 18.4 on `127.0.0.1:15432`;
- pinned Redis 8.2.8 on `127.0.0.1:16379`, AOF + `noeviction`, bounded local memory;
- separate PostgreSQL databases, migration roles, and non-owner runtime roles for Identity, Authorization, and Notification;
- generated local-only security key material under `.local-runtime/`;
- a deterministic `GENERATED_TEST_FIXTURE` compromised-password dataset;
- five real Spring Boot JARs running simultaneously on unique WSL ports.

Local state and credentials are generated outside Git under `.local-runtime/` and must never be reused for staging or production.

Use repository targets:

```bash
make local-runtime-up
make local-runtime-status
make local-runtime-logs
make local-runtime-down
```

`local-runtime-up` builds the service Boot JARs, starts PostgreSQL/Redis, applies each service-owned Flyway migration with a distinct migration role, grants the distinct runtime role only its own database objects, creates or reuses restart-stable local key material, and starts all five service processes.

The browser-facing local Web BFF endpoint is `https://localhost:18443`. Management endpoints remain loopback-only on service-specific ports.
