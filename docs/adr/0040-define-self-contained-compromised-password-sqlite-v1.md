# ADR-0040: Self-Contained Compromised Password SQLite Runtime v1

## Status

Accepted — current effective decision

## Date

2026-08-14; corpus/hash authority finalized 2026-08-15

## Decision

Compromised Password Service remains an independent internal bounded context. It screens new password credentials against a locally governed immutable compromised-password dataset and has **no external runtime provider/API dependency**.

The v1 corpus authority is **Have I Been Pwned (HIBP) Pwned Passwords, SHA-1 corpus** acquired offline from the official Pwned Passwords range/download process.

SHA-1 is used only as the HIBP corpus lookup identifier. It is **not** the password-storage algorithm. Identity credential storage remains the Technology Baseline Argon2id profile.

## 1. Privacy-preserving lookup contract

Identity owns all password material and exact comparison:

1. NFC-normalize the password under the current Identity password policy;
2. UTF-8 encode it;
3. compute SHA-1 locally only for compromised-password screening;
4. send only the first 20 digest bits as exactly five canonical uppercase hexadecimal characters;
5. receive a bounded set of remaining 35-hex SHA-1 suffix + positive occurrence-count records;
6. reconstruct/compare the full SHA-1 only inside Identity.

Raw password and full SHA-1 digest never leave Identity. The service never receives User, Tenant, Membership, Contact, Session, request, browser, or provider identity.

The service contract is an internal HIBP-derived lookup format. Production request handling never calls HIBP.

## 2. Corpus authority, provenance, and acquisition

HIBP Pwned Passwords is the v1 source authority because its official corpus is available as SHA-1 or NTLM hashes and the range protocol is defined on the first five hash characters. HooshiX uses only the SHA-1 form.

Acquisition is an offline release process:

```text
official HIBP Pwned Passwords SHA-1 range/download source
-> verified bounded importer
-> canonical parse + positive-count validation
-> deterministic deduplicate/aggregate validation
-> SQLite build
-> schema/integrity/cardinality/response validation
-> immutable dataset artifact + manifest + digest/provenance
-> reviewed signed release
```

The official HIBP downloader may be used as an acquisition tool when its exact version/digest/dependency/license review is pinned by the dataset build workflow. A custom downloader must preserve the official range semantics and receive the same security/provenance review.

The dataset manifest records at least:

```text
format_version
corpus_source = HIBP_PWNED_PASSWORDS
hash_mode = SHA1
retrieval_started_at_utc
retrieval_completed_at_utc
importer/downloader identity + version/digest
record_count
max_prefix_cardinality observed
content_sha256
SQLite schema version
build tool/source Git revision
```

Source/import evidence and release metadata are retained so a dataset can be reproduced/audited without retaining plaintext password material.

HIBP does not require a paid API key for Pwned Password range use. Source/tool licensing and current terms still receive release-time review; a future terms/source change that makes the approved offline corpus unusable blocks a new dataset release until reviewed.

## 3. Freshness

The Pwned Passwords corpus changes over time. A dataset artifact therefore has explicit age/freshness state.

Initial v1 policy:

- automated corpus acquisition/build verification runs at least every 30 days;
- a production-approved dataset MUST be no more than 35 days old at deployment/readiness evaluation;
- a material source/security event may trigger an earlier rebuild;
- stale/missing/unverifiable corpus state is availability failure and MUST NOT become `not compromised`;
- release metadata exposes a bounded dataset age/version health signal without password/hash content.

This freshness interval is a HooshiX security policy, not a claim that HIBP publishes on a fixed cadence. It may be tightened through reviewed evidence without weakening fail-closed semantics.

## 4. SQLite dataset decision

The dataset is an embedded SQLite database on service-local read-only disk, queried through the approved Xerial SQLite JDBC driver.

It is an **immutable, read-only, rebuildable reference-data artifact**. It is not mutable business persistence, transactional application state, integration storage, or a replacement for PostgreSQL.

Consequences:

- no PostgreSQL/CloudNativePG/Flyway runtime path for this dataset;
- no Redis or Kafka dataset cache/path;
- no runtime INSERT/UPDATE/DELETE/DDL;
- no external HIBP/provider call on a production request;
- dataset replacement is an atomic versioned artifact release;
- future mutable business/source-of-truth state requires normal persistence review.

Logical schema:

```sql
CREATE TABLE compromised_password (
    prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),
    hash BLOB NOT NULL CHECK (length(hash) = 20),
    occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),
    PRIMARY KEY (prefix, hash)
) WITHOUT ROWID;
```

The compiler verifies that `prefix` equals the first 20 bits of the SHA-1 value, hashes are unique after deterministic aggregation, counts are positive, rows are canonical, and SQLite integrity/schema checks pass.

HIBP padding records with occurrence count `0` are never admitted as compromised-password rows.

Runtime SQL is fixed and parameterized. Caller input is never a database path, URI, SQL identifier, PRAGMA, extension, ATTACH target, or arbitrary query fragment.

## 5. Bounded response and cardinality evidence

A prefix lookup is finite, but HIBP states that range cardinality grows as the corpus grows. HooshiX therefore does not treat an unevidenced fixed historical prefix count as permanent truth.

Before implementation/release promotion, the complete acquired corpus MUST be measured and a versioned maximum accepted prefix cardinality/serialized response limit MUST be selected from observed evidence plus explicit safety margin. The build fails if the corpus exceeds those configured compatibility bounds. Runtime never truncates a valid prefix result because truncation could create a false-negative password decision.

Until that full-corpus evidence exists, exact production prefix/response caps are `NOT VERIFIED` and Compromised Password production readiness remains blocked.

The response contains only canonical 35-hex SHA-1 suffixes and positive occurrence counts. Identity treats an exact full-hash match with count >0 as compromised. Malformed, oversized, truncated, corrupt, incompatible, unavailable, or stale results fail closed as dependency unavailability.

## 6. Runtime security

Production runtime opens only the server-configured approved local dataset path in read-only/query-only mode.

Mandatory:

- extension loading prohibited;
- arbitrary database attachment prohibited;
- no runtime DDL/write;
- no application Internet/provider egress;
- only approved `identity-service` workload may call lookup RPC;
- ClusterIP-only, Ambient-enrolled, strict mTLS, deny-by-default NetworkPolicy/Istio policy;
- approved telemetry egress is separate from lookup semantics;
- writable native-extraction temp path, if required by Xerial, is bounded ephemeral storage and never the dataset path.

## 7. Failure, latency, and concurrency

Identity dependency contract remains:

```text
deadline:        900 ms overall
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure mode:    reject unchecked password
```

SQLite reads use bounded connections/concurrency and fixed prepared queries. No unbounded application queue is permitted. Storage/saturation/corruption/staleness maps to stable availability/overload result, never false clean-password status.

Representative complete-corpus warm/cold disk-backed tests must meet the current Class-B objective before production. If measured corpus size or storage cost does not fit the single-server envelope, increase capacity or review the architecture; do not weaken exact matching or use probabilistic clean-password authority.

## 8. Production profiles

Runtime identity:

```text
base package:   com.sajtech.compromisedpassword
namespace:      platform-apps
Deployment:     compromised-password-service
Service:        compromised-password-service
ServiceAccount: compromised-password-service
application:    gRPC 9090
management:     separate configured port
```

`production-single-server`:

```text
replicas: 1
HPA: disabled
availability PDB: disabled
node failover: none
```

`production-ha` retains the reviewed replicated target. Every replica uses the exact same approved dataset artifact/version.

Same-host deployment is not treated as protection against root compromise. The independent workload boundary still limits ordinary workload identity, network access, runtime dependency exposure, and the password material visible to the compromised-password process.

## 9. Logging and observability

Never log, trace, or use as metric labels:

- password material;
- SHA-1 prefix/suffix/full hash;
- returned dataset rows;
- User/Tenant/Membership/Contact/Session identifiers;
- caller metadata that discloses subject identity;
- sensitive local dataset paths.

Telemetry is limited to bounded outcome classes, latency, saturation, dataset version/age/integrity state, safe aggregate cardinality summaries, and storage/query health. ADR-0044 Day-One Observability applies without weakening this stricter data classification.

## 10. Erasure

Compromised Password Service is not a data-subject erasure participant in v1. The dataset contains compromised-password hash reference data and no User/Contact/Tenant/session linkage. Introducing subject-linked state requires new ownership/erasure review.

## Verification requirements

Implementation/release evidence MUST cover:

- official HIBP SHA-1 corpus source identity and reviewed current source/tool terms;
- acquisition completeness for all `00000`..`FFFFF` prefixes or equivalent official complete-download evidence;
- SHA-1 five-hex prefix validation, 20-byte stored hash, 35-hex suffix reconstruction, exact Identity comparison;
- proof SHA-1 is confined to compromised-password screening and Argon2id remains credential-storage authority;
- raw password/full SHA-1 non-egress and telemetry negatives;
- zero-count/padding-row rejection;
- dataset manifest/provenance/content digest and <=35-day readiness age;
- observed full-corpus prefix-cardinality/serialized-size measurement and configured build/runtime compatibility bounds;
- read-only/query-only server-owned SQLite path; write/DDL/ATTACH/extension negatives;
- fixed parameterized query and malformed input rejection;
- full SQLite schema/integrity validation before publication;
- corrupt/incompatible/missing/stale dataset fails closed;
- no production HIBP/provider request and no arbitrary application Internet egress;
- representative complete-corpus disk-backed load/concurrency tests;
- immutable artifact digest/provenance/SBOM/dependency/advisory checks;
- Xerial/native-engine Java 25/Linux compatibility;
- hardened workload and wrong-workload/network-policy negatives;
- DR redeploy from approved immutable dataset artifact;
- profile-correct replica/HPA/PDB/recovery evidence.

## Rollback considerations

Rollback selects a previously approved application+dataset-format combination only when its corpus remains inside the current freshness bound, integrity/security-supported, and contract-compatible. Rollback MUST NOT restore SHA-256-as-HIBP-corpus assumptions, enable runtime provider fallback, use stale/corrupt data as `not compromised`, expose password/full hash, permit runtime dataset mutation, broaden workload/network access, or convert the SQLite exception into general mutable persistence.