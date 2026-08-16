# Compromised Password offline dataset build

This directory documents the service-owned offline build path for ADR-0040.

## Boundary

The builder does **not** download HIBP data and has no network or URL input. The raw HIBP Pwned Passwords SHA-1 source stays on the approved local/offline build host and outside this repository, container image, and runtime deployment.

The only publishable dataset outputs from this step are:

- the HooshiX immutable SQLite artifact;
- the small release manifest defined by `dataset-release-manifest.schema.json`.

Production source acquisition remains a separate approved offline activity. Record the acquisition tool name, version, digest, source-file SHA-256, and UTC retrieval interval before invoking the builder.

## Source evidence kind

Every build explicitly identifies what the local file represents:

```text
GENERATED_TEST_FIXTURE
HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD
```

Normal PR CI uses only `GENERATED_TEST_FIXTURE`. It must never claim that a generated fixture is a complete HIBP acquisition.

Use `HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD` only for a release build whose separate approved acquisition record proves that the local input is the complete official HIBP Pwned Passwords SHA-1 download. The builder validates the file it receives, but it does not independently prove source completeness or licensing/provenance approval.

## Input contract

The builder accepts canonical records only:

```text
40 uppercase hexadecimal SHA-1 characters:positive decimal occurrence count
```

Both CRLF and LF line endings are accepted. Count `0`, lowercase/non-hex hashes, malformed or oversized lines, count overflow, an empty source, and source-digest mismatch fail the build. Duplicate hashes are aggregated in SQLite; integer overflow fails the SQLite constraint instead of producing a release artifact.

The source file is read with bounded buffers. It is never copied into the repository or the final service image and is not cached in JVM memory.

## Production build command

Select the reviewed compatibility bounds from real complete-corpus release evidence plus the approved safety margin before the production build. From `services/compromised-password-service`:

```bash
./gradlew buildCompromisedPasswordDataset --args="\
--source-kind HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD \
--input /approved-local-source/pwnedpasswords.txt \
--output /approved-local-release/compromised-password.sqlite \
--manifest /approved-local-release/compromised-password.manifest.json \
--source-sha256 <64-lowercase-hex> \
--retrieval-started-at <UTC-ISO-8601> \
--retrieval-completed-at <UTC-ISO-8601> \
--acquisition-tool-name <safe-token> \
--acquisition-tool-version <safe-token> \
--acquisition-tool-sha256 <64-lowercase-hex> \
--build-git-revision <40-lowercase-hex> \
--max-prefix-cardinality <reviewed-positive-integer> \
--max-serialized-response-bytes <reviewed-positive-integer>"
```

The output paths must not already exist. Source, SQLite output, and manifest must be distinct regular local paths.

## Builder validation

Before publication, the builder:

1. validates every source line and positive count;
2. verifies the complete local source-file SHA-256 supplied by the acquisition record;
3. builds the ADR-0040 `WITHOUT ROWID` SQLite table with 20-byte SHA-1 BLOBs and 20-bit prefixes;
4. runs `PRAGMA integrity_check`;
5. streams the final table in `(prefix, hash)` order and re-validates prefix/hash/count consistency;
6. measures unique `record_count`, duplicate-line aggregation, observed maximum prefix cardinality, and exact Protobuf response bytes per prefix;
7. fails before publication if either measured maximum exceeds its reviewed compatibility bound;
8. computes a canonical logical `content_sha256` and exact `sqlite_artifact_sha256`;
9. publishes the version-2 manifest and then the SQLite artifact only after all checks pass, with SQLite as the final publish step.

`content_sha256` is SHA-256 over each final row in `(prefix, hash)` order using fixed binary encoding:

```text
prefix:           4-byte unsigned big-endian
hash:             20 raw SHA-1 bytes
occurrence_count: 8-byte positive big-endian integer
```

The version-2 manifest records both measured maxima and the reviewed compatibility bounds used by the build. Production bounds are not invented by the builder; they require review against a real complete-corpus run plus safety margin.

At runtime, deployment supplies the exact SHA-256 of the approved manifest and the approved source kind/bounds. The service verifies the manifest digest before trusting its fields, then verifies source kind, format/schema versions, freshness, declared bounds, the SQLite artifact SHA-256, SQLite schema/integrity, and measured compatibility. Any mismatch fails closed. Runtime does not truncate a response to fit a bound.

## Repository verification

Normal PR CI generates deterministic source records locally and marks their manifest as `GENERATED_TEST_FIXTURE`. It does not download or commit the production HIBP corpus.

The service workflow also verifies that:

- no raw corpus or generated SQLite database is tracked in Git;
- the real Gradle dataset-builder command works against the generated fixture with explicit compatibility bounds;
- the dataset-builder package is absent from the runtime Spring Boot JAR;
- Semgrep blocks network APIs inside the builder package;
- the runtime release path rejects invalid manifest/dataset identity and compatibility evidence.

Runtime therefore retains only the immutable SQLite reader and has no build/download path.

## Production evidence still required

Repository implementation of the builder does not by itself prove:

- approved real HIBP source acquisition/provenance or licensing review;
- current <=35-day production freshness;
- real complete-corpus cardinality/response measurements and reviewed production bounds;
- disk-backed production p95/p99/saturation;
- signing, SBOM/provenance, admission, deployment, or recovery evidence.

Those gates remain `NOT VERIFIED` until their owning release/environment checks execute.
