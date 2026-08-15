# Compromised Password offline dataset build

This directory documents the service-owned offline build path for ADR-0040.

## Boundary

The builder does **not** download HIBP data and has no network or URL input. The raw HIBP Pwned Passwords SHA-1 source stays on the approved local/offline build host and outside this repository, container image, and runtime deployment.

The only publishable dataset outputs from this step are:

- the HooshiX immutable SQLite artifact;
- the small release manifest defined by `dataset-release-manifest.schema.json`.

Production source acquisition remains a separate approved offline activity. Record the acquisition tool name, version, digest, source-file SHA-256, and UTC retrieval interval before invoking the builder.

## Input contract

Use the complete SHA-1 download from the approved HIBP source. The builder accepts canonical records only:

```text
40 uppercase hexadecimal SHA-1 characters:positive decimal occurrence count
```

Both CRLF and LF line endings are accepted. Count `0`, lowercase/non-hex hashes, malformed or oversized lines, count overflow, an empty source, and source-digest mismatch fail the build. Duplicate hashes are aggregated in SQLite; integer overflow fails the SQLite constraint instead of producing a release artifact.

The source file is read with bounded buffers. It is never copied into the repository or the final service image and is not cached in JVM memory.

## Build command

From `services/compromised-password-service`:

```bash
./gradlew buildCompromisedPasswordDataset --args="\
--input /approved-local-source/pwnedpasswords.txt \
--output /approved-local-release/compromised-password.sqlite \
--manifest /approved-local-release/compromised-password.manifest.json \
--source-sha256 <64-lowercase-hex> \
--retrieval-started-at <UTC-ISO-8601> \
--retrieval-completed-at <UTC-ISO-8601> \
--acquisition-tool-name <safe-token> \
--acquisition-tool-version <safe-token> \
--acquisition-tool-sha256 <64-lowercase-hex> \
--build-git-revision <40-lowercase-hex>"
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
7. computes a canonical logical `content_sha256` and exact `sqlite_artifact_sha256`;
8. publishes the SQLite file and release manifest only after all checks pass.

`content_sha256` is SHA-256 over each final row in `(prefix, hash)` order using fixed binary encoding:

```text
prefix:           4-byte unsigned big-endian
hash:             20 raw SHA-1 bytes
occurrence_count: 8-byte positive big-endian integer
```

The observed cardinality and response-size values are evidence inputs. They do not invent production compatibility limits. Production limits still require review against a real complete-corpus run plus safety margin.

## Repository verification

Normal PR CI uses generated deterministic fixtures only. It does not download or commit the production HIBP corpus.

The service workflow also verifies that the dataset-builder package is absent from the runtime Spring Boot JAR. Runtime therefore retains only the immutable SQLite reader and has no build/download path.

## Production evidence still required

Repository implementation of the builder does not by itself prove:

- approved real HIBP source acquisition/provenance or licensing review;
- current <=35-day production freshness;
- real complete-corpus cardinality/response measurements;
- disk-backed production p95/p99/saturation;
- signing, SBOM/provenance, admission, deployment, or recovery evidence.

Those gates remain `NOT VERIFIED` until their owning release/environment checks execute.
