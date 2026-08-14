# ADR-0040: Self-Contained Compromised Password SQLite Runtime v1

## Status

Accepted — current effective decision

## Date

2026-08-14

## Decision

Compromised Password Service remains an independent internal bounded context. It screens new password credentials against a locally governed compromised-password dataset and has **no external runtime provider/API dependency**.

Identity keeps the current privacy-preserving protocol:

1. NFC-normalize the password;
2. UTF-8 encode and compute SHA-256 inside Identity;
3. send only the first 20 digest bits as exactly five canonical uppercase hexadecimal characters;
4. receive a bounded set of remaining SHA-256 suffix + occurrence-count records;
5. compare the full digest only inside Identity.

Raw password and full SHA-256 digest never leave Identity. The service never receives User, Tenant, Membership, Contact, Session, request, or provider identity.

### SQLite dataset decision

The compromised-password dataset is stored as an embedded SQLite database on local service disk and queried through the approved Xerial SQLite JDBC driver. Exact approved driver/embedded-engine versions are pinned in the Technology Baseline and service dependency verification.

The SQLite database is an **immutable, read-only, rebuildable reference-data artifact**. It is not mutable business persistence, transactional application state, an integration database, or a replacement for the platform PostgreSQL standard. This is a narrow reviewed exception for this service's local reference dataset only.

Consequences:

- no PostgreSQL/CloudNativePG cluster for this dataset;
- no Flyway runtime migration history for this dataset;
- no Redis or Kafka storage path;
- no external HIBP/Pwned Passwords or other network lookup in production request paths;
- no runtime dataset INSERT/UPDATE/DELETE/DDL;
- dataset schema/version changes are produced offline as a new immutable artifact and deployed atomically with compatibility validation;
- if future Compromised Password functionality needs mutable business/source-of-truth state, that state does not inherit this exception and requires the normal persistence architecture review.

### Dataset schema and lookup

The offline dataset compiler stores one exact SHA-256 hash as a 32-byte BLOB plus its derived 20-bit prefix and a positive occurrence count. The primary access path begins with the prefix so SQLite can seek directly to the bounded range without loading the entire corpus into the JVM heap.

Logical schema:

```sql
CREATE TABLE compromised_password (
    prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),
    hash BLOB NOT NULL CHECK (length(hash) = 32),
    occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),
    PRIMARY KEY (prefix, hash)
) WITHOUT ROWID;
```

The dataset compiler MUST verify that `prefix` equals the first 20 bits of `hash`, hashes are unique after deterministic occurrence aggregation, counts are positive, rows are canonical, and the database passes integrity/schema/version checks before publication.

Runtime SQL is fixed and parameterized. Caller input is never a SQL identifier, database path, URI, PRAGMA, extension, ATTACH target, or arbitrary query fragment.

### Response bounds and false-negative safety

A valid dataset version contains at most **2048 records for one 20-bit prefix**. The internal response is capped at **128 KiB**. Dataset build/validation fails if a prefix exceeds the record bound. Runtime never truncates a valid prefix result because truncation could create a false-negative password decision.

The service returns only canonical remaining SHA-256 suffixes and non-negative occurrence counts. Identity treats an exact full-hash match with `occurrence_count > 0` as compromised. Malformed, oversized, ambiguous, corrupt, incompatible, or unavailable dataset results fail closed as dependency unavailability; they never become `not compromised`.

### Dataset publication and recovery

Dataset construction is an offline/release process, not a production request-path downloader.

```text
approved local source material
-> bounded importer/compiler
-> normalize/hash when needed
-> deduplicate/aggregate
-> build SQLite database
-> schema/integrity/bound validation
-> immutable artifact + digest/provenance
-> reviewed release
```

Plaintext password source material, when an approved import requires it, is input to the offline compiler only. It is never committed to application source, logged, emitted to telemetry, or copied into the runtime dataset. Dataset-source provenance/licensing/use rights must be reviewed before ingestion.

The production runtime opens only the server-configured approved local dataset path in read-only mode. Extension loading and arbitrary database attachment are prohibited. Production dataset replacement is a new versioned immutable release, not in-place mutation.

The dataset is reconstructable from approved versioned source/import evidence and immutable release artifacts. PostgreSQL-style WAL/PITR/backup is therefore not required. Disaster recovery restores/redeploys the approved dataset artifact and keeps the service not ready until the correct version is usable.

### Failure, latency, and concurrency

Identity's existing dependency contract remains:

```text
deadline:        900 ms overall
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure mode:    reject unchecked password
```

The service has no remote dependency on its lookup path. SQLite reads use bounded connections/concurrency and fixed prepared queries. No unbounded application queue is permitted. Saturation/corruption/storage I/O failure maps to a stable availability/overload result, not a false clean-password result.

The service remains within the current Class-B internal dependency objective: availability >=99.95%, p95 <=250 ms, p99 <=750 ms. Representative multi-million-row datasets must satisfy the objective before production; scaling/tuning is evidence-driven and must not replace exact-match correctness with probabilistic authority.

### Runtime boundary and production profiles

Production identity:

```text
base package:   com.sajtech.compromisedpassword
namespace:      platform-apps
Deployment:     compromised-password-service
Service:        compromised-password-service
ServiceAccount: compromised-password-service
application:    gRPC 9090
management:     separate configured port
```

Only the approved `identity-service` workload may call the lookup RPC. The service is ClusterIP-only, Ambient-enrolled, strict-mTLS protected, deny-by-default, and has **no application Internet/provider egress**. Approved telemetry egress remains separate from application lookup semantics.

`production-single-server` under ADR-0042 uses exactly one service replica, HPA disabled, and no availability PDB. The immutable dataset is local to the workload image/runtime artifact and the one-host profile explicitly accepts service outage on host/node loss. This does not weaken fail-closed password screening: an unavailable/corrupt/missing service or dataset never becomes `not compromised`.

`production-ha` uses the replicated target:

```text
replicas:        >=3
PDB:             minAvailable=2
topology spread: required
HPA:             evidence-gated only
```

All HA replicas use the exact same approved immutable dataset version. Autoscaling is enabled only after representative SQLite storage/query/load evidence proves a safe signal and capacity envelope.

The dataset path is server-owned configuration and read-only. The workload keeps the normal hardened security context. Any narrowly required writable temporary path for the JDBC native library is a bounded `emptyDir`-style runtime mount, never the dataset path, and requires render/security verification.

### Logging and privacy

Never log or use as metric labels:

- password material;
- SHA-256 prefix/suffix/full hash;
- returned dataset rows;
- User/Tenant/Membership/Contact/Session identifiers;
- caller metadata or database path details that disclose sensitive deployment state.

Telemetry is limited to bounded outcome classes, latency, saturation, dataset-version health, safe row-count/cardinality summaries, and storage/query health with low-cardinality labels.

### Erasure

Compromised Password Service is **not** a data-subject erasure participant in v1. Its dataset contains compromised-password hash reference data and no User/Contact/Tenant/session linkage or subject-owned application state. Introducing subject-linked state requires a new data-ownership/erasure review.

## Verification requirements

Implementation/release evidence MUST cover:

- exact Protobuf/Buf lookup contract and wrong-workload denial;
- SHA-256 five-hex prefix validation and exact suffix reconstruction/matching;
- raw password/full-hash non-egress and telemetry negatives;
- SQLite file opens read-only from only server-controlled path;
- runtime write/DDL/ATTACH/extension-loading prohibition;
- parameterized fixed query and malformed input rejection;
- dataset compiler prefix/hash consistency, deduplication/count rules, <=2048 prefix cardinality and <=128 KiB response compatibility;
- full dataset schema/integrity validation before publication;
- corrupt/incompatible/missing dataset keeps readiness false or returns fail-closed availability result;
- no HIBP/external provider call or arbitrary application Internet egress;
- multi-million-row representative query/load tests within Class-B objectives and bounded concurrency/queue behavior;
- immutable artifact digest/provenance/SBOM/dependency/advisory checks;
- SQLite/Xerial version/security compatibility tests on Java 25 and production Linux image;
- hardened container/render/ServiceAccount/NetworkPolicy/Istio positive and negative tests;
- DR redeploy/reconstruction from approved immutable dataset artifact;
- `production-single-server`: one-replica/HPA-off/PDB-off render, whole-host outage/recovery, exact dataset recovery and fail-closed result with no false clean password;
- `production-ha`: replica/node loss with identical approved dataset version and no false clean result.

## Rollback considerations

Rollback selects a previously approved application + dataset format combination only when the older dataset remains current, integrity-verified, security-supported, and contract compatible. Rollback MUST NOT enable runtime dataset mutation, use stale/corrupt data as `not compromised`, add external provider fallback, expose password/full digest, broaden workload/network access, or silently convert the SQLite exception into general mutable relational persistence. Moving to the single-server profile MUST NOT be represented as retaining replica/node-failure availability.
