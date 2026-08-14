# ADR-0041: Define Reference Data Service v1

## Status

Accepted — current effective architecture decision

Executable service implementation: **PLANNED / NOT VERIFIED** until the implementation trigger in this ADR is met.

## Date

2026-08-14

## Decision

Reference Data is a distinct platform capability boundary for small, global, non-tenant, low-change standard reference data. It is deliberately **not** a generic shared CRUD store, business-configuration service, validation oracle, or dumping ground for data that belongs to another bounded context.

The v1 reference families are exactly:

- `Country`;
- `Currency`;
- `TimeZone`;
- `SupportedLocale`.

Country subdivisions, cities, postal/address datasets, measurement units, industry classifications, and other code sets are outside v1 and require a real consumer plus reviewed contract change before they enter this boundary.

No generic caller-defined `key/value`, `category/item`, `dictionary`, `lookup_table`, dataset-name, schema-name, or arbitrary registry API is permitted. A new reference family is an explicit architecture/contract change.

### Canonical source authorities

Reference Data imports reviewed source material offline. Runtime never calls ISO, IANA, Unicode, or another Internet/provider endpoint.

Current source authorities are:

- ISO 3166-1 for country codes;
- ISO 4217 for currency codes;
- IANA Language Subtag Registry / BCP 47 for locale identifiers;
- IANA Time Zone Database for time-zone identifiers;
- stable Unicode CLDR releases for localized display metadata.

Exact upstream source revisions are **dataset-release metadata**, not permanently pinned platform-runtime versions. Every bundle records the exact source revision/artifact actually imported, its integrity evidence, and the reviewed license/use-right basis. Draft/development CLDR data is not admitted into a production bundle.

### Country

The canonical public identifier is ISO 3166-1 alpha-2. Alpha-3 and numeric codes are bounded metadata. Private/user-assigned country-code ranges are not canonical platform countries unless a later explicit platform decision defines their semantics.

Reference Data does not decide whether a country is available for a particular product, tenant, workflow, provider, legal policy, or business operation.

### Currency

The canonical public identifier is the ISO 4217 three-letter currency code. The ISO numeric code and minor-unit metadata are retained where the approved source defines them.

Reference Data does not own prices, exchange rates, settlement logic, payment-provider support, product pricing, tax policy, or whether a business operation accepts a currency.

### Supported locale

External locale identifiers are canonical BCP 47 language tags. Product-selectable v1 locales are exactly:

```text
fa
en
```

Reference Data exposes these supported locale records and localized display metadata. It does not take ownership of Identity registration-locale persistence, browser language negotiation, user locale preference, Notification template selection, or another bounded context's locale rules.

### Time zone

Time-zone identifiers are canonical IANA tzdb identifiers. Abbreviations such as `EST` and raw UTC offsets such as `+03:30` are not stable reference identifiers for persisted business selection.

Every bundle manifest records the exact tzdb release used. Time-zone rule changes are delivered through a new immutable bundle/image release, not runtime Internet synchronization.

### Localized display metadata

Reference Data contains bounded `fa` and `en` display metadata from an approved stable CLDR release. The exact CLDR revision is recorded in the bundle manifest. Display names are presentation metadata; stable code identity remains the canonical identifier.

## Immutable bundled data model

Reference Data v1 has **no PostgreSQL, CloudNativePG, Flyway, SQLite, Redis, or Kafka datastore/runtime path**.

The approved dataset is a versioned immutable read-only resource packaged into the same signed application image. Startup may deserialize it into bounded immutable in-process indexes because the v1 reference families are intentionally small. There is no separately mutable production dataset and no in-place update operation.

This decision does not extend ADR-0040's SQLite exception. ADR-0040 remains specific to Compromised Password. Reference Data needs no persistence exception because it owns no mutable relational business state and uses no database.

The bundle manifest records at least:

```text
format_version
bundle_version
built_at_utc
source_revisions
supported_locales
record_counts_by_family
content_sha256
```

The build timestamp is a controlled build/release input so deterministic rebuild evidence is not defeated by arbitrary wall-clock variation. The canonical content digest covers the normalized logical bundle content and source-revision identity according to the versioned compiler format.

Offline ingestion is:

```text
approved source acquisition
-> license/use-right + integrity review
-> normalization
-> schema/code validation
-> deterministic ordering/deduplication
-> localized metadata validation
-> immutable bundle build
-> digest/provenance verification
-> signed application release
```

Production runtime does not download, synchronize, mutate, or repair reference data from the network.

## Reference lifecycle

Reference codes have lifecycle:

```text
ACTIVE
DEPRECATED
RETIRED
```

Rules:

- ordinary selection lists return `ACTIVE` records by default;
- exact lookup can resolve a retained `DEPRECATED` or `RETIRED` code so historical records remain displayable during migration/history use;
- a code already used by the platform is not silently deleted or reused for a different meaning;
- retirement/removal requiring loss of exact historical resolution needs an explicit migration/compatibility plan;
- lifecycle is reference metadata, not domain acceptance authority.

A domain may reject an otherwise `ACTIVE` country/currency/time-zone/locale for its own operation. Reference Data never grants business validity simply because a code exists.

## Internal gRPC contract

The Reference Data-owned v1 Protobuf surface is typed and closed:

```text
ListCountries
GetCountry
ListCurrencies
GetCurrency
ListTimeZones
GetTimeZone
ListSupportedLocales
GetSupportedLocale
GetReferenceDataVersion
```

There is no generic `GetDataset`, arbitrary dataset selector, dynamic schema, query language, fuzzy/full-text search, or caller-provided sort expression in v1.

List contracts use deterministic stable ordering with:

```text
page_size default: 100
page_size maximum: 200
page_token: opaque and bounded
response maximum: 128 KiB
```

Exact lookups use canonical identifiers and bounded optional display-locale selection. Inputs are validated before lookup. Invalid/unknown identifiers remain distinct from infrastructure unavailability through stable contract error codes.

## Browser/Web BFF facade

Web BFF owns the public REST facade under:

```text
/api/v1/reference
/api/v1/reference/countries
/api/v1/reference/currencies
/api/v1/reference/time-zones
/api/v1/reference/locales
```

These v1 reference routes are read-only `GET`/`HEAD` surfaces. They may be anonymous because the payload is global public reference metadata and carries no User/Tenant/session authority.

Consequences:

- reference GET/HEAD does not require a BFF authenticated session merely to read public reference data;
- CSRF proof is not required for these side-effect-free safe methods;
- this does **not** enable cross-origin credentialed CORS: the existing same-origin browser API policy remains;
- the request still traverses the mandatory upstream mitigation -> Traefik -> WAF -> Web BFF path;
- locale/representation selection is explicit and canonical so cache keys are deterministic; no hidden session/cookie locale authority changes the representation;
- internal gRPC method names are not mechanically exposed as public URLs.

Successful public reference responses use deterministic cache validators derived from the immutable bundle/representation identity:

```text
ETag: deterministic representation validator
Cache-Control: public, max-age=3600
```

Conditional requests may return `304 Not Modified`. Reference endpoints do not inherit the `no-store` rule used for authentication/session/private administration responses.

BFF v1 has no server-side stale-reference fallback. If current Reference Data cannot produce a valid response, BFF returns stable unavailability rather than fabricating, reconstructing, or serving an unreviewed stale dataset.

## Synchronous dependency semantics

The initial runtime edge is only:

```text
Web BFF -> Reference Data
```

Canonical operation class:

```text
operation:       web-bff.reference-data-read
class:           AUTHORITATIVE_STATE
deadline:        <=1000 ms child deadline and remaining-parent bounded
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure action:  reference response unavailable; never fabricate data
```

Inbound HTTP cancellation propagates through the gRPC lookup where supported and releases bounded in-flight capacity. Service concurrency/queue limits are finite and load-tested before implementation promotion; exact launch values are implementation configuration rather than invented architecture constants.

The existing BFF 2600ms outer request ceiling remains. No other service may add a synchronous per-write Reference Data dependency merely because this service exists. Every new caller/operation requires its own dependency-registry entry, ownership review, deadline/failure semantics, and workload policy.

## Runtime and workload identity

Target runtime identity when implementation trigger is met:

```text
service path:    services/reference-data-service
base package:    com.sajtech.referencedata
namespace:       platform-apps
Deployment:      reference-data-service
Service:         reference-data-service
ServiceAccount:  reference-data-service
application:     gRPC 9090
management:      separate configured port
replicas:        >=3
PDB:             minAvailable=2
HPA:             evidence-gated only
```

The service is ClusterIP-only and Ambient-enrolled. Initial application ingress is allowed only from the approved `web-bff` workload. NetworkPolicy and Istio authorization deny unregistered application callers.

Application egress is deny-by-default. The serving process has no ISO/IANA/Unicode/CLDR/provider/Internet synchronization path. Only narrowly required platform DNS and approved telemetry paths may be allowed by deployment policy.

The normal hardened workload baseline applies: immutable signed image digest, non-root, `allowPrivilegeEscalation=false`, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem where compatible, bounded resources/probes, graceful shutdown, dedicated ServiceAccount, and topology spread.

Reference Data uses the current Class-B objective when implemented:

```text
availability >=99.95% rolling 30d
p95 <=250 ms
p99 <=750 ms
```

HPA is enabled only after representative route/load evidence proves an appropriate signal and confirms scaling does not hide an invalid bundle or multiply a downstream bottleneck.

## Security, privacy, and authority

Reference Data contains global public reference metadata only. It does not contain User, Contact, Tenant, Membership, Session, Credential, Role, permission, provider credential, tenant configuration, product catalog, pricing, feature flag, or workflow state.

It is therefore not an ADR-0028 data-subject erasure participant in v1. Adding subject-linked or tenant-specific state requires a new data-ownership/security/erasure review before implementation.

Reference Data is never an authorization service or a universal validation service. A successful lookup does not authorize a user, select a tenant, permit a payment, satisfy a legal rule, or prove a business workflow can use the code.

## Observability and logging

Bounded low-cardinality telemetry may include:

- operation/family enum;
- latency/outcome;
- in-flight/queue saturation;
- active bundle/format version from a bounded release set;
- bundle integrity/readiness category;
- safe aggregate record counts established at build/startup.

Do not use caller-supplied free text, page tokens, arbitrary identifiers, raw URLs, or unbounded source metadata as metric labels. Public reference payloads still follow structured allow-list logging; full responses and unreviewed upstream-source files are not dumped into request logs.

## Release, recovery, and implementation trigger

Reference data is recovered by redeploying the same approved signed application image/bundle or by rebuilding a logically equivalent reviewed release from approved importer inputs. There is no database restore, WAL/PITR, runtime repair, or provider fallback.

Before publishing a bundle, compiler/importer verification covers at least:

- malformed/non-canonical identifiers;
- duplicate canonical codes;
- source revision/integrity metadata;
- reviewed license/use rights;
- deterministic ordering/output;
- lifecycle/code non-reuse rules;
- unsupported/draft source artifacts;
- localized `fa`/`en` coverage where required;
- alias/canonicalization cycles where an imported source defines aliases;
- list/page/128-KiB response bounds;
- manifest/digest consistency.

The architecture is decided now, but executable service implementation remains deferred until at least one of these evidence triggers exists:

1. **two independent consumers** require this boundary; or
2. **one specific production user journey** requires the centralized boundary.

Multiple endpoints of one integration layer are not automatically multiple independent consumers. The trigger must identify actual ownership/change/reuse need rather than using this ADR as justification for premature microservice creation.

Until that trigger and the required Java/contract/container/policy/load evidence exist, status remains:

```text
Architecture: DECIDED
Implementation: PLANNED
Runtime evidence: NOT VERIFIED
Production readiness: NOT VERIFIED
```

## Verification requirements

When implementation starts, evidence MUST cover:

- typed Protobuf/Buf contract and no generic registry/query surface;
- canonical Country/Currency/TimeZone/SupportedLocale identifiers and lifecycle/non-reuse behavior;
- exact `fa`/`en` supported-locale and display-metadata coverage;
- deterministic importer/bundle/manifest/digest output from approved source revisions;
- source provenance, integrity and license/use-right review;
- no production runtime Internet/source synchronization;
- no PostgreSQL/CloudNativePG/Flyway/SQLite/Redis/Kafka datastore path;
- bounded startup data/index memory and response size/pagination;
- public OpenAPI `/api/v1/reference` GET/HEAD surface, explicit representation locale, deterministic ETag and one-hour public cache policy;
- proof anonymous reference reads do not create/require authenticated session authority, while cross-origin credentialed CORS remains disabled and edge/WAF path remains mandatory;
- exact BFF->Reference Data <=1000ms/one-attempt/no-retry/no-fallback semantics and cancellation propagation;
- wrong-workload ingress denial and no arbitrary application Internet egress;
- hardened >=3 replica/PDB2/topology-spread deployment and evidence-gated HPA;
- Class-B load/SLO evidence before production;
- PII-safe low-cardinality telemetry;
- immutable same-digest staging->production promotion and rebuild/redeploy recovery.

## Rollback considerations

Rollback uses a previously approved signed application image whose Reference Data bundle format/source revisions remain contract-compatible and supported. Rollback MUST NOT reintroduce a generic dictionary registry, mutate a deployed bundle, silently reuse retired codes, add a database/provider runtime dependency, relax workload/egress policy, fabricate stale reference data, or change a bounded context's business validation authority.
