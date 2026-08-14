# Compromised Password Service Architecture

## 1. Ownership and scope

Compromised Password Service is the internal bounded context for compromised-password reference-data lookup. Its only v1 business capability is to support password create/change/reset screening without receiving the password or user identity.

It owns:

- the versioned compromised-password dataset format;
- offline dataset build/import validation;
- immutable SQLite reference-data artifact compatibility;
- bounded prefix lookup;
- dataset integrity/readiness behavior;
- stable internal gRPC error semantics;
- service runtime, workload identity, security policy, and observability.

Identity owns password normalization, full SHA-256 computation, exact compromised/not-compromised decision, password policy, Argon2id credential hashing/storage, authentication, and all User/Contact/session state.

The service does not own User, Tenant, Membership, Contact, Credential, Session, Authorization, notification, or erasure subject data.

Implementation defaults:

```text
service path:   services/compromised-password-service
base package:   com.sajtech.compromisedpassword
```

## 2. Internal gRPC contract

The v1 provider surface contains one authoritative lookup operation:

```text
LookupCompromisedPasswordRange
```

Request:

```text
sha256_prefix: exactly five uppercase hexadecimal characters
```

The prefix represents the first 20 bits of SHA-256. Lowercase, non-hex, whitespace, alternative lengths, binary caller-selected encodings, and extra authority-bearing identifiers are rejected.

Response contains a bounded repeated record:

```text
sha256_suffix: exactly 59 uppercase hexadecimal characters
occurrence_count: non-negative integer
```

The 59-character suffix represents the remaining 236 SHA-256 bits. Response order is deterministic by full hash. No successful response contains raw password, full hash, User/Tenant/Contact/session identity, source-row metadata, or provider/source provenance.

Stable service error families include:

```text
COMPROMISED_PASSWORD_DATASET_UNAVAILABLE
COMPROMISED_PASSWORD_DATASET_INCOMPATIBLE
COMPROMISED_PASSWORD_DATASET_CORRUPT
COMPROMISED_PASSWORD_LOOKUP_OVERLOADED
COMPROMISED_PASSWORD_INVALID_PREFIX
```

Internal exception, SQLite/JDBC/native-library, file-path, SQL, source-material, and stack details are never contract fields.

## 3. Identity privacy and decision boundary

Identity remains the final decision owner:

1. NFC normalize password;
2. UTF-8 encode;
3. compute SHA-256 locally;
4. send only first 20 bits/five uppercase hex characters;
5. receive suffix/count records;
6. reconstruct candidate full hashes locally and compare to the Identity-local digest;
7. reject the password when an exact match has `occurrence_count > 0`.

Raw password and full SHA-256 digest never cross the Identity boundary. The Compromised Password Service cannot derive User/account context from its request and must not add account identifiers to this contract.

A response with no matching suffix means only **not present in the currently deployed approved dataset**. It is not a claim that the password has never been breached elsewhere.

## 4. SQLite embedded reference dataset

v1 uses embedded SQLite through the approved Xerial SQLite JDBC driver. Exact versions live in the Technology Baseline and service dependency locks/verification metadata.

This SQLite database is a local **immutable read-only rebuildable reference-data artifact**. It is not mutable service business persistence or an authoritative transaction store.

Consequences:

- no PostgreSQL/CloudNativePG cluster for this dataset;
- no Flyway runtime migration history;
- no runtime SQLite writes/DDL;
- no Redis/Kafka storage path;
- no runtime external compromised-password provider/API;
- no cross-service database access;
- no use of the SQLite file as an integration database for another service.

The exception is specific to this bounded immutable reference dataset. Future mutable business/source-of-truth state requires the normal persistence architecture and review.

### Logical SQLite schema

```sql
CREATE TABLE dataset_metadata (
    format_version INTEGER NOT NULL,
    dataset_version TEXT NOT NULL,
    record_count INTEGER NOT NULL CHECK (record_count >= 0),
    source_digest_sha256 TEXT NOT NULL,
    built_at_utc TEXT NOT NULL
);

CREATE TABLE compromised_password (
    prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),
    hash BLOB NOT NULL CHECK (length(hash) = 32),
    occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),
    PRIMARY KEY (prefix, hash)
) WITHOUT ROWID;
```

Exactly one metadata row is permitted for the runtime format. The dataset compiler validates metadata cardinality and canonical representation before publication.

The runtime query is fixed and parameterized, logically equivalent to:

```sql
SELECT hash, occurrence_count
FROM compromised_password
WHERE prefix = ?
ORDER BY hash;
```

The explicit 20-bit integer prefix allows indexed disk-backed range lookup without loading the full dataset into JVM heap. There is no full-table scan on the normal request path.

## 5. Dataset compiler and publication

Dataset construction is offline and separately executable from the production serving process.

Input can be one or more approved local source datasets. Runtime production does not download or synchronize HIBP, Pwned Passwords, or any other Internet source.

Compiler requirements:

- source provenance, license/use rights, and integrity are reviewed before import;
- plaintext input, if an approved source is plaintext, is read only by the offline compiler and never becomes runtime dataset/log/telemetry/application-source content;
- normalization/hashing rules are deterministic and versioned;
- runtime representation contains SHA-256 material only;
- duplicate hashes aggregate occurrence counts with checked overflow behavior;
- `prefix` is recomputed from each full 32-byte hash and verified;
- invalid hashes/counts/rows fail the build;
- one `(prefix,hash)` row exists after deduplication;
- each 20-bit prefix contains at most 2048 rows;
- generated response for every prefix fits the 128 KiB contract cap;
- SQLite schema/metadata/format compatibility and full database integrity are validated;
- artifact record count and source digest are reproducible/verified;
- final artifact is immutable and tied to reviewed build/source identity.

A new dataset is published as a new immutable dataset version. Production never updates the active SQLite file in place.

## 6. Runtime SQLite safety

The production dataset path is server-owned typed configuration. Request/caller data can never choose or modify:

- JDBC URL;
- filesystem/database path;
- SQLite URI parameters;
- PRAGMA values;
- ATTACH targets;
- SQL identifiers or SQL text;
- native-library path.

Runtime opens only the approved dataset in read-only mode and enforces query-only behavior. SQLite extension loading is disabled. `ATTACH`/`DETACH` and runtime schema mutation are prohibited.

All SQL is fixed Infrastructure-adapter SQL with parameterized values. Domain/Application code does not contain SQLite/JDBC types or SQL.

The full dataset is not loaded into JVM collections, heap indexes, Bloom filters, or an application memory cache. SQLite/OS may use bounded ordinary I/O buffers/page cache as part of disk I/O; that is not application authority and does not justify an unbounded process cache.

## 7. Bounds and concurrency

The serving path has finite capacity:

```text
records per prefix: <=2048
response:           <=128 KiB
Identity deadline:  <=900 ms overall
attempts:           1
retry:              none
fallback:           none
```

SQLite read connections, in-flight lookups, and waiting work are bounded. Exact launch values are implementation configuration validated by representative load tests; unbounded Virtual Thread fan-out is prohibited.

If a valid response cannot be produced inside bounds, the operation fails unavailable/overloaded. It never truncates suffixes and never converts a failed/corrupt lookup into an empty clean result.

Class-B objectives apply:

```text
availability >=99.95% rolling 30d
p95 <=250 ms
p99 <=750 ms
```

Representative production-readiness load must include multi-million-row datasets and cold/warm storage-cache conditions. Tune schema/index/storage/concurrency before adding Redis, another database, a remote provider, or probabilistic shortcut.

## 8. Readiness, liveness, and dataset integrity

Liveness reports local process/runtime progress only.

Readiness requires at least:

- approved dataset path present and readable;
- SQLite database opens in enforced read-only/query-only mode;
- exact supported format/schema metadata;
- exactly one valid metadata row;
- compatible dataset version policy;
- required table/index shape;
- validated deployment artifact identity/integrity evidence;
- service security configuration usable.

Full expensive artifact/integrity validation belongs in the offline build/release pipeline. Runtime startup performs only the bounded validation needed to ensure it is not serving an unknown/incompatible/corrupt deployment artifact. A missing or incompatible dataset keeps the pod unready.

## 9. Failure semantics

The Identity dependency is `AUTHORITATIVE_SECURITY`:

```text
deadline:        900 ms overall
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none
failure mode:    reject unchecked password
```

No transport/mesh retry duplicates the caller contract.

Dataset missing/corrupt/incompatible, SQLite open/read failure, bounded I/O timeout, and saturation are infrastructure failures. They never map to `not compromised`.

Because the runtime has no remote lookup dependency, no provider breaker/retry layer exists inside this service.

## 10. Deployment and workload identity

Production defaults:

```text
namespace:         platform-apps
Deployment:        compromised-password-service
Service:           compromised-password-service
ServiceAccount:    compromised-password-service
application gRPC:  9090
management:        separate configured port
replicas:          >=3
PDB minAvailable:  2
```

Replicas use the same approved dataset version. Topology spread keeps the service available across worker loss. HPA may be enabled only after representative storage/query/load evidence identifies a safe scaling signal and proves downstream/caller behavior.

The service is ClusterIP-only and Ambient-enrolled. NetworkPolicy + Istio authorization permit lookup ingress only from the approved `identity-service` ServiceAccount. Wrong workloads are denied.

Application egress is deny-by-default. The lookup runtime has no compromised-password provider/Internet egress. Only platform-required DNS and approved telemetry paths may be allowed as narrowly required.

The workload uses normal hardened container policy: non-root, `allowPrivilegeEscalation=false`, capabilities dropped, `RuntimeDefault` seccomp, immutable image digest, bounded resources, and read-only root filesystem where compatible.

The dataset remains on a read-only path. If Xerial native-library extraction requires a writable temporary directory, it uses a separate bounded ephemeral runtime mount. That mount contains no dataset/source/password state and cannot change the read-only dataset.

## 11. Technology and dependency security

The approved Xerial JDBC artifact and embedded SQLite engine versions are explicit Technology Baseline entries and service-lock/dependency-verification inputs.

The final application image/SBOM includes the JDBC driver and embedded native SQLite components. Current advisory correlation applies to both Java artifact and embedded native engine. A baseline pin is not proof of zero unknown vulnerabilities.

The production release validates Java 25 and target Linux/CPU native-library compatibility, immutable artifact integrity, native extraction/runtime behavior under the hardened container, and upgrade/rollback compatibility with dataset format.

## 12. Data recovery and erasure

The SQLite dataset is rebuildable reference data. It does not use WAL/PITR/CloudNativePG backup policy. Recovery redeploys/reconstructs the approved immutable dataset artifact and validates it before readiness.

The service is not a required data-subject erasure participant because it stores no User/Contact/Tenant/session relationship or subject-owned state. A future subject-linked dataset/state change requires erasure review before implementation.

## 13. Logging and observability

Never log or metric-label:

- raw password;
- SHA-256 prefix, suffix, or full hash;
- returned hash rows;
- Identity User/Tenant/Membership/Contact/Session identifiers;
- caller metadata;
- unreviewed database/JDBC/native exception text;
- dataset filesystem paths when not needed for safe bounded diagnosis.

Bounded telemetry includes:

- request latency and stable outcome class;
- in-flight/queue saturation;
- SQLite query/open failures;
- safe dataset version/format health;
- safe aggregate record count and maximum prefix cardinality as startup/build evidence;
- pod/storage I/O saturation signals;
- readiness state/reason category.

Metrics use low-cardinality labels only.

## 14. Required implementation evidence

Before production, prove:

- independent Java 25/Spring Boot build and dependency verification;
- Protobuf/Buf compatibility and exact request/response bounds;
- Identity five-hex-prefix interoperability and exact local full-hash decision;
- wrong-workload denial and no public ingress;
- no raw password/full hash or User identity leaves Identity;
- SQLite read-only/query-only fixed parameterized SQL;
- no runtime write/DDL/ATTACH/extension loading;
- server-owned path/JDBC configuration and path/URI injection negatives;
- offline compiler deterministic hash/prefix/dedup/count/schema/integrity/bound rules;
- missing/corrupt/incompatible/oversized-prefix dataset fail-closed behavior;
- no external provider call or arbitrary Internet egress;
- multi-million-row warm/cold query load within Class-B SLO and bounded concurrency;
- identical dataset version across replicas and one-replica/one-worker loss behavior;
- Xerial/SQLite Java25/Linux native compatibility and hardened native extraction behavior;
- SBOM/signature/provenance/current advisory correlation;
- PII/password/hash-safe logging and telemetry;
- rebuild/redeploy recovery from approved immutable dataset artifact.

Documentation does not prove these checks. Until service source/build/contracts/dataset compiler/manifests/tests execute and pass, implementation evidence remains **NOT VERIFIED**.