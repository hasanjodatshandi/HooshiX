# Compromised Password Service Architecture

## 1. Ownership

Compromised Password Service is the internal bounded context for password-compromise reference lookup. Identity is the only v1 caller and remains owner of password policy, password material, exact digest comparison, credential mutation, and user outcome.

The service owns:

- offline HIBP Pwned Passwords SHA-1 corpus acquisition/import validation;
- immutable SQLite dataset format/build/release identity;
- fixed prefix lookup;
- dataset freshness/integrity/readiness;
- bounded lookup concurrency;
- service-specific telemetry and recovery.

It owns no User/Tenant/Contact/session/business state and has no external runtime provider dependency.

Implementation target:

```text
services/compromised-password-service
base package: com.sajtech.compromisedpassword
```

## 2. Lookup protocol

ADR-0040 is authoritative.

Identity:

```text
NFC password
-> UTF-8
-> SHA-1 only for HIBP screening
-> first 5 uppercase hex chars / 20 bits
-> gRPC lookup
-> returned 35-hex suffix + positive count rows
-> exact full SHA-1 comparison inside Identity
```

Raw password and full SHA-1 never leave Identity. SHA-1 is not password storage; Argon2id remains credential-storage authority.

The request accepts exactly five canonical uppercase hexadecimal characters. The response contains only canonical SHA-1 suffix/count records for that prefix and is bounded by the dataset-release compatibility limits selected from complete-corpus evidence.

Malformed/oversized/truncated/incompatible/stale/unavailable response is dependency failure, never `not compromised`.

## 3. Dataset source and build

V1 source authority is official HIBP Pwned Passwords SHA-1 corpus acquired offline through the official range/download process.

Production request handling never calls HIBP.

Repository implementation contains a service-owned offline/local builder that accepts a local canonical SHA-1/count file and emits only the HooshiX immutable SQLite artifact plus a bounded release manifest. The builder has no URL, HTTP, provider, shell-downloader, or runtime acquisition path. Raw source data stays outside Git, the runtime image, and the deployed service.

Build inputs identify their evidence kind explicitly:

```text
GENERATED_TEST_FIXTURE
HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD
```

Normal PR CI uses only `GENERATED_TEST_FIXTURE`. `HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD` is valid only when a separate approved acquisition record proves that the local input is the complete official HIBP SHA-1 download. The builder validates the file it reads and its supplied SHA-256; it does not independently prove upstream completeness, licensing, or provenance approval.

Build pipeline records at least source evidence kind/hash mode, retrieval start/end, acquisition-tool identity/digest, source-file SHA-256, row count, observed maximum prefix cardinality, exact maximum serialized Protobuf response size, reviewed prefix/serialized-size compatibility bounds, canonical content SHA-256, SQLite artifact SHA-256, schema/format version, and build Git revision.

Rules:

- all official prefix ranges or equivalent complete-download evidence are required for production release;
- count must be positive; HIBP padding count `0` is rejected;
- records are canonical/deduplicated/validated;
- source is streamed with bounded buffers and bounded SQLite batches; no full-corpus JVM cache exists;
- release build measures full-corpus prefix cardinality and serialized response size;
- build fails when observed data exceeds reviewed runtime compatibility bounds selected from real complete-corpus evidence plus safety margin;
- no runtime truncation is permitted;
- dataset age <=35 days at production readiness/deployment;
- acquisition/build verification at least every 30 days;
- source/tool licensing/use-right/security review is recorded with the release.

Repository CI additionally rejects tracked raw HIBP/generated SQLite artifacts, verifies the actual builder CLI with a generated fixture, keeps fixture evidence distinct from production HIBP evidence, and verifies that builder classes are absent from the runtime Spring Boot JAR.

## 4. SQLite runtime

The dataset is immutable/read-only/rebuildable SQLite, not mutable service persistence.

Logical storage follows ADR-0040:

```text
prefix: 20-bit integer
hash:   20-byte SHA-1 BLOB
occurrence_count: positive integer
```

Runtime:

- server-owned dataset path only;
- read-only/query-only;
- fixed parameterized prefix SQL;
- no INSERT/UPDATE/DELETE/DDL;
- no ATTACH;
- extension loading disabled;
- no caller-selected PRAGMA/path/URI/query;
- no full-corpus JVM cache;
- bounded connection/concurrency/queue behavior.

The release manifest is part of the runtime dataset identity. Deployment pins the exact approved manifest SHA-256. Runtime verifies that digest before trusting manifest fields, then validates source kind, manifest/format/schema versions, freshness, compatibility measurements/bounds, SQLite artifact SHA-256, SQLite schema/integrity, and runtime reviewed bounds. Any mismatch is unavailable/fail-closed. Runtime never truncates a valid dataset response to fit a compatibility limit.

Xerial/embedded SQLite exact versions are in Technology Baseline. Native extraction temp storage, if required, is separate bounded ephemeral storage and never contains source/password/subject data.

## 5. Dependency and failure contract

Identity -> Compromised Password:

```text
class:           AUTHORITATIVE_SECURITY
deadline:        900 ms overall
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure action:  reject unchecked password
```

Corrupt, missing, stale, incompatible, overloaded, or storage-failed dataset/service is unavailable. No external provider, local Bloom/filter guess, truncated response, or false-clean fallback is permitted.

## 6. Runtime identity and network

```text
namespace:      platform-apps
Deployment:     compromised-password-service
Service:        compromised-password-service
ServiceAccount: compromised-password-service
application:    gRPC 9090
management:     separate configured port
```

Only approved `identity-service` workload may reach application gRPC. Service is ClusterIP-only, Ambient-enrolled, strict mTLS, deny-by-default, and has no application Internet/provider egress. Telemetry egress is only to the ADR-0044 Collector/monitoring path.

Single-server uses one replica/HPA off/availability PDB off. HA retains the reviewed replicated target with one identical dataset version across replicas.

Same-host placement does not protect against root compromise, but the separate workload still limits routine workload identity/network access and keeps password/full digest material out of this process.

## 7. Day-One observability

ADR-0044 applies from first implementation commit.

Allowed bounded signals include:

- lookup latency/outcome;
- in-flight/queue/storage saturation;
- dataset version/age/integrity state;
- aggregate safe row/prefix cardinality summaries established by release/build;
- readiness and failed release-validation category.

Never log/trace/label:

- password;
- SHA-1 prefix/suffix/full hash;
- returned rows;
- User/Tenant/Membership/Contact/Session/request identifiers;
- sensitive local paths or caller metadata.

OTLP/telemetry outage is not a lookup fallback and does not change fail-closed password screening.

## 8. Recovery

Dataset is recovered by redeploying the exact approved immutable artifact or rebuilding a reviewed equivalent from approved HIBP acquisition evidence. No PostgreSQL PITR exists for this reference artifact.

Service remains unready until dataset source identity, manifest digest, SQLite digest, schema, integrity, freshness, and compatibility bounds validate.

## 9. Verification

Implementation/release evidence includes:

- complete official HIBP SHA-1 acquisition/provenance;
- exact SHA-1 prefix/suffix reconstruction and positive-count semantics;
- proof SHA-1 is screening-only while Argon2id remains credential storage;
- raw password/full-hash non-egress;
- zero-count padding rejection;
- <=35-day freshness and release metadata;
- full-corpus cardinality/serialized-size measurement and configured build/runtime bounds;
- manifest-digest -> manifest metadata -> SQLite-digest runtime identity binding;
- read-only/query-only SQLite and path/DDL/ATTACH/extension negatives;
- corrupt/missing/stale/incompatible dataset fail close;
- no HIBP/provider runtime call or arbitrary Internet egress;
- representative complete-corpus disk-backed p95/p99 and saturation evidence;
- Xerial/native security compatibility and SBOM/provenance;
- wrong-workload/mTLS/NetworkPolicy negatives;
- Day-One logs/metrics/traces PII/hash negatives;
- telemetry-backend outage does not alter screening result;
- profile-correct deployment/recovery evidence.

Repository source/build/deployment-package evidence for the implemented slice includes the offline/local dataset builder, version-2 release-manifest schema, deterministic generated-fixture tests, actual CLI fixture execution, source SHA-256/integrity validation, observed cardinality/exact Protobuf-size measurement with explicit compatibility bounds, runtime manifest/dataset digest binding, raw-corpus/generated-database Git guards, no-network/process-exec static gates, architecture/privacy regression tests, runtime-JAR exclusion, and digest verification of the exact Temurin runtime archive used to construct the service image. Commit-specific CI remains the evidence source for whether those gates passed.

The local production-fidelity kind/staging lane deploys the service with the deterministic `GENERATED_TEST_FIXTURE` and verifies exact manifest-digest binding and readiness. Production HIBP complete acquisition/provenance/licensing, current freshness, real full-corpus measurements and reviewed compatibility bounds, production-corpus deployed runtime/staging, signed supply-chain/admission, complete-corpus performance, recovery, and production-readiness evidence remain `NOT VERIFIED` until their owning checks execute.
