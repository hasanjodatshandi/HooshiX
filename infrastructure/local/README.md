# HooshiX Local Integrated Runtime

This directory owns the repeatable local application-integration dependencies for the canonical WSL checkout.

It is a **developer-only fast application lane**, not staging, production, or the production-fidelity kind/Calico/Istio/Traefik/WAF lane.

The runtime uses:

- pinned PostgreSQL 18.4 on `127.0.0.1:15432`;
- pinned Redis 8.2.8 on `127.0.0.1:16379`, AOF + `noeviction`, bounded local memory;
- pinned Apache Kafka 4.2.1 on `127.0.0.1:19092` as a single combined KRaft broker/controller, with auto-topic creation and unclean leader election disabled;
- explicit 35-day erasure command/receipt topics and 14-day DLT topics;
- separate PostgreSQL databases, migration roles, and non-owner runtime roles for Identity, Authorization, Notification, and Web BFF;
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

Run the complete synthetic erasure/Kafka journey after the runtime is ready:

```bash
python3 scripts/local/runtime.py smoke-erasure
python3 scripts/local/runtime.py smoke-erasure-recovery
```

`local-runtime-up` builds the service Boot JARs, starts PostgreSQL/Redis/Kafka, creates the versioned erasure topics, applies each service-owned Flyway migration with a distinct migration role, grants the distinct runtime role only its own database objects, creates or reuses restart-stable local key material, and starts all five service processes with the erasure participants enabled.

The browser-facing local Web BFF endpoint is `https://localhost:18443`. Management endpoints remain loopback-only on service-specific ports.

`smoke-erasure` inserts only random UUID test identities, publishes one durable command through the
Identity outbox, and waits until Identity, Authorization, Notification, and Web BFF have all emitted
durable receipts and the coordinator reaches `COMPLETED`. It fails rather than treating a partial or
timed-out workflow as success.

`smoke-erasure-recovery` is the destructive-to-test-state local recovery rehearsal.
It stops the five local service processes, snapshots only the four service-owned local
databases with PostgreSQL 18 `pg_dump --clean --if-exists`, completes the same synthetic
four-participant request, restarts all services and verifies terminal evidence, restores
the pre-completion snapshots while traffic is stopped, and requires normal Outbox/Kafka/
Inbox reconciliation to complete the original request again. Snapshot files are mode
`0600`, temporary, and removed after the run. It never targets databases outside the
generated `.local-runtime` lane and is not staging or Production restore evidence.
On success it atomically writes a mode-`0600`, identifier-free aggregate receipt to
`.local-runtime/erasure-recovery-evidence.json`, bound to the exact Git revision.
