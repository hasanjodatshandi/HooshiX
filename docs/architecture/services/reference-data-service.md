# Reference Data Service Architecture

## 1. Status, ownership, and implementation gate

Reference Data is the authoritative platform boundary for the current global standard reference families:

- Country;
- Currency;
- TimeZone;
- SupportedLocale.

Status:

```text
Architecture:         DECIDED
Implementation:       PLANNED
Runtime evidence:     NOT VERIFIED
Production readiness: NOT VERIFIED
```

Executable `services/reference-data-service` work starts only after at least **two independent consumers** require the boundary or **one specific production user journey** proves the centralized service is needed. Multiple endpoints owned by one integration layer do not automatically count as independent consumers.

The service owns canonical reference identifiers, imported standard metadata, lifecycle/non-reuse policy, localized display metadata, bundle format/build validation, typed read contracts, and runtime delivery of the approved immutable bundle.

It does **not** own tenant configuration, user preference, business validation/eligibility, pricing, exchange rates, payments, tax/legal policy, feature flags, product catalogs, permissions, roles, workflow rules, or another bounded context's domain state.

Implementation target when the gate is met:

```text
service path: services/reference-data-service
base package: com.sajtech.referencedata
```

## 2. Closed v1 reference families

v1 has exactly four typed families. There is no generic shared dictionary or caller-defined registry.

### Country

Canonical identifier: ISO 3166-1 alpha-2.

Metadata may include:

```text
alpha2
alpha3
numeric_code
lifecycle
localized_display_name.fa
localized_display_name.en
```

Alpha-2/alpha-3 are canonical uppercase ASCII source codes. Numeric source code is represented losslessly so leading zeroes are not discarded by transport/serialization. Private/user-assigned ranges are outside the canonical country list unless a later explicit platform decision defines their meaning.

### Currency

Canonical identifier: ISO 4217 three-letter code.

Metadata may include:

```text
alpha_code
numeric_code
minor_unit metadata when defined by the approved source
lifecycle
localized_display_name.fa
localized_display_name.en
```

Reference Data never interprets this as pricing/payment/provider availability authority.

### TimeZone

Canonical identifier: IANA Time Zone Database identifier.

Persisted/reference selection uses canonical tzdb identifiers, not abbreviations such as `EST` and not raw offsets such as `+03:30`.

Metadata may contain reviewed aliases only when the imported source format needs historical/canonical-resolution support. Alias graphs must be validated as bounded and acyclic before publication. The exact tzdb release is bundle metadata.

### SupportedLocale

Canonical identifiers are BCP 47 tags. v1 exposes exactly:

```text
fa
en
```

These records describe product-supported reference/display locales only. Identity still owns persisted registration locale. BFF/frontend may own language negotiation/presentation selection. Notification owns template localization. Reference Data does not take those responsibilities over.

## 3. Source authority and offline ingestion

Runtime has no Internet/source-provider dependency. Approved inputs are acquired only by the offline importer/release process.

Source authorities:

| Family/metadata | Authority |
| --- | --- |
| Country | ISO 3166-1 |
| Currency | ISO 4217 |
| Locale identifier | IANA Language Subtag Registry / BCP 47 |
| Time zone | IANA Time Zone Database |
| Localized display metadata | stable Unicode CLDR release |

A new bundle records the exact source revision/artifact used for every applicable authority. The platform does not permanently hardcode today's tzdb/CLDR source revision into the runtime Technology Baseline.

Before import, release evidence verifies source provenance, integrity, and license/use rights. Production bundles never ingest draft/development CLDR data.

Importer pipeline:

```text
approved source acquisition
-> provenance/license/integrity checks
-> parse with bounded source-specific adapter
-> canonical normalization
-> duplicate/code/lifecycle/alias validation
-> fa/en display coverage validation
-> deterministic sort/serialization
-> manifest + SHA-256 content digest
-> signed image/release evidence
```

No production request can choose a source URL, source revision, parser, dataset family, or arbitrary schema.

## 4. Immutable application bundle

Reference Data v1 has no database or broker:

```text
PostgreSQL:     none
CloudNativePG:  none
Flyway:         none
SQLite:         none
Redis:          none
Kafka:          none
runtime source/provider HTTP: none
```

The reference bundle is a versioned immutable read-only application resource included in the same signed service image. There is no separately mutable production data volume and no runtime update endpoint.

This is not another ADR-0040 storage exception. ADR-0040 remains the SQLite-specific Compromised Password decision. Reference Data simply has no mutable relational persistence.

At startup the service validates the bundle format/manifest/digest and may load the small v1 families into bounded immutable in-process indexes. Bundle cardinality/memory remains measured and bounded; this decision does not authorize arbitrary large datasets to be added to the service.

Manifest contains at least:

```text
format_version
bundle_version
built_at_utc
source_revisions
supported_locales
record_counts_by_family
content_sha256
```

`built_at_utc` is a controlled build/release input. Rebuilding from identical approved logical inputs and build metadata must produce deterministic normalized content/digest under the same format version.

## 5. Lifecycle and compatibility

Every reference record has:

```text
ACTIVE
DEPRECATED
RETIRED
```

Rules:

- ordinary selection lists default to ACTIVE;
- exact lookup may resolve DEPRECATED/RETIRED values for historical display/migration;
- a code already used by the platform is not silently reused for new meaning;
- removal that prevents historical resolution needs an explicit migration/compatibility plan;
- bundle upgrades preserve contract compatibility or are rolled out with explicit client migration;
- existence/lifecycle never becomes business acceptance authority.

The owning business context decides whether a reference value may be used in a specific command. Reference Data can reject malformed/unknown reference identifiers but does not grant a business operation.

## 6. Internal gRPC contract

Canonical typed v1 operations:

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

No operation accepts an arbitrary dataset name/schema/query/sort expression. No generic `GetDataset`, fuzzy search, full-text search, or dynamic query language exists in v1.

List behavior:

```text
page_size default: 100
page_size maximum: 200
page_token: opaque, bounded, version-bound
ordering: deterministic family-specific canonical order
serialized response maximum: 128 KiB
```

Page tokens are service-generated navigation state only; caller cannot use them to select arbitrary file/object paths or internal data structures. A token created for one family/bundle is rejected for another family/incompatible bundle.

Exact lookup validates canonical identifier shape. A request may select display locale only from current supported locales; unsupported locale is a stable invalid-argument result, not a fallback to arbitrary server locale.

`GetReferenceDataVersion` exposes bounded non-sensitive bundle/format/source-revision identity required for cache/diagnostic compatibility. It does not expose build filesystem paths, source credentials, or unbounded source payload metadata.

## 7. Stable failure families

The exact Protobuf/public mappings are versioned when implemented. Semantic families include at least:

```text
REFERENCE_DATA_INVALID_ARGUMENT
REFERENCE_DATA_NOT_FOUND
REFERENCE_DATA_UNSUPPORTED_LOCALE
REFERENCE_DATA_BUNDLE_UNAVAILABLE
REFERENCE_DATA_BUNDLE_INCOMPATIBLE
REFERENCE_DATA_OVERLOADED
```

Internal parser/build path, stack trace, filesystem path, source payload, or library exception text is never copied into caller-visible errors.

## 8. Web BFF public facade

Web BFF exposes explicit OpenAPI routes:

```text
/api/v1/reference
/api/v1/reference/countries
/api/v1/reference/currencies
/api/v1/reference/time-zones
/api/v1/reference/locales
```

The list/detail route shape is OpenAPI-owned and may use path/query parameters only when typed and bounded. Internal gRPC method names are not mechanically translated into URLs.

v1 Reference Data public methods are GET/HEAD only and side-effect free. They may be anonymous because the payload is global, public, non-user-specific reference metadata.

Anonymous means:

- no authenticated BFF session is required just to read reference data;
- no user/tenant JWT authority is created or inferred;
- no CSRF token is required for side-effect-free GET/HEAD;
- no cross-origin credentialed CORS is introduced;
- mandatory Internet -> upstream volumetric protection -> Traefik -> WAF -> BFF path remains unchanged;
- BFF must not use a cookie/session locale implicitly to vary public-cache representation.

Representation locale is explicit and canonical, for example a bounded `locale=fa|en` query field defined by OpenAPI. Cache keying therefore does not depend on hidden session state.

Successful public reference responses use:

```text
ETag: deterministic from bundle + family + representation identity
Cache-Control: public, max-age=3600
```

Conditional GET/HEAD may return `304 Not Modified`. `no-store` remains mandatory for authentication/OIDC/session/private administration surfaces but does not apply to these intentionally public immutable-reference responses.

BFF does not hold an application-level stale reference cache/fallback in v1. An unavailable/invalid Reference Data response maps to stable public unavailability rather than a fabricated or unreviewed stale list.

## 9. BFF dependency contract

Canonical edge:

```text
operation_id:     web-bff.reference-data-read
caller:           web-bff
dependency:       reference-data.read
class:            AUTHORITATIVE_STATE
deadline:         <=1000 ms and <= remaining BFF parent budget
attempts:         1
wait-for-ready:   off
automatic retry:  none
fallback:         none
failure action:   reference route unavailable; no fabricated data
```

The BFF outer request ceiling remains <=2600ms. Cancellation propagates to gRPC where supported. BFF and Reference Data release bounded in-flight capacity promptly after cancellation/deadline.

Reference Data server uses finite global/per-caller in-flight and queue limits. Exact launch values require load evidence. Virtual Threads never authorize unbounded work.

This initial edge is read-only. No other service is authorized to create a hidden dependency on Reference Data. A new caller requires dependency-registry entry, consumer-specific failure semantics and explicit NetworkPolicy/Istio authorization.

## 10. Runtime and deployment target

When implementation trigger is met:

```text
namespace:         platform-apps
Deployment:        reference-data-service
Service:           reference-data-service
ServiceAccount:    reference-data-service
principal:         prod.sajtech.internal/ns/platform-apps/sa/reference-data-service
application gRPC:  9090
management:        separate configured port
replicas:          >=3
PDB minAvailable:  2
HPA:               disabled until evidence permits it
```

The service is ClusterIP-only and Ambient-enrolled under STRICT mTLS. NetworkPolicy and Istio authorization initially allow application gRPC ingress only from `platform-apps/web-bff` ServiceAccount. Wrong workloads are denied.

Application egress is deny-by-default. Serving has no ISO/IANA/Unicode/CLDR/Internet synchronization. Only narrowly necessary DNS and approved telemetry egress may be configured.

Normal workload hardening applies:

- immutable signed image digest;
- non-root;
- `allowPrivilegeEscalation=false`;
- capabilities dropped by default;
- `RuntimeDefault` seccomp;
- read-only root filesystem where compatible;
- bounded CPU/memory/ephemeral resources;
- startup/readiness/liveness separation;
- graceful shutdown;
- topology spread.

Liveness is local runtime progress only. Readiness requires a valid compatible bundle, successful bounded startup validation, and required security/configuration. It does not probe external source authorities.

## 11. SLO, load, and scaling

Initial service objective when implemented is Class B:

```text
availability >=99.95% rolling 30d
p95 <=250 ms
p99 <=750 ms
```

The BFF child deadline is stricter at <=1000ms and remains inside its 2600ms outer budget.

Before production, representative load proves:

- list/detail latency by family;
- pagination and max-128-KiB response behavior;
- startup bundle validation/memory footprint;
- bounded in-flight/queue behavior;
- one replica/node loss behavior;
- BFF cache/conditional request behavior;
- >=2x projected reference-route peak with >=30% validated resource headroom where applicable.

If load proves a bottleneck, first optimize serialization/indexing/bounds, then replicas/resources. Do not add Redis/database/provider synchronization merely as speculative optimization.

## 12. Security, privacy, and erasure

The service contains no subject- or tenant-linked application state. It is not an ADR-0028 erasure participant in v1.

Reference Data does not accept authentication credentials, provider secrets, user IDs, tenant IDs, membership IDs, session IDs, permission snapshots, or arbitrary URLs as data-selection authority.

The public BFF facade remains protected by the existing edge/WAF/security-header/request-boundary controls. Anonymous reference reads do not weaken OIDC/session/Authorization routes.

Any future tenant-specific, user-specific, mutable business configuration or source-of-truth state is outside this contract and requires a bounded-context/persistence/security/erasure decision before implementation.

## 13. Logging and observability

Use structured allow-list telemetry. Safe bounded dimensions include:

```text
operation/family
stable outcome class
latency
in-flight/queue saturation
bundle format/version from a bounded deployed set
bundle readiness/integrity category
safe aggregate record counts
```

Do not metric-label page tokens, caller free text, arbitrary reference values, raw URLs, trace IDs, source filenames/paths, or unbounded upstream metadata. Do not dump full source artifacts or response bodies into request logs.

Alerts cover bundle readiness failures, incompatible bundle, sustained service/BFF dependency error rate, saturation and Class-B SLO burn when the service is implemented.

## 14. Release, recovery, and rollback

Bundle changes are release changes. Import/build validation runs before service/image release. Production never edits the active bundle.

Recovery:

```text
reviewed Git/source-import identity
-> deterministic immutable bundle
-> signed immutable service image
-> deploy
-> readiness validates bundle
```

There is no database backup/PITR/restore step. A lost pod/node is replaced from the same approved image/bundle. Cold DR rebuilds/redeploys the same approved release and keeps the workload unready until bundle compatibility/integrity passes.

Rollback selects a previously approved signed image/bundle only when contract and source-support policy remain compatible. Rollback never reuses a retired identifier for new meaning or silently downgrades business semantics owned by consumers.

## 15. Required implementation evidence

Before production, prove:

- implementation trigger evidence is documented;
- independent Java 25/Spring Boot service build/Wrapper/dependency verification exists;
- Protobuf/Buf contracts implement only the typed v1 operations and stable errors;
- ISO/IANA/CLDR source adapters are offline-only, bounded, deterministic and license/integrity reviewed;
- Country/Currency/TimeZone/SupportedLocale canonicalization, lifecycle/non-reuse, duplicate/alias-cycle and fa/en coverage tests pass;
- bundle manifest/content digest is deterministic and tamper/incompatibility detection blocks readiness;
- no PostgreSQL/CloudNativePG/Flyway/SQLite/Redis/Kafka or runtime source-provider dependency is introduced;
- bounded startup memory/indexes and list/detail/page/128-KiB contracts pass;
- BFF OpenAPI `/api/v1/reference` GET/HEAD routes, anonymous read semantics, explicit locale, ETag/304 and `public, max-age=3600` behavior pass;
- same-origin CORS and mandatory edge/WAF path remain intact; reference routes do not create session/JWT/CSRF authority;
- BFF->Reference Data <=1000ms/one-attempt/no-retry/no-fallback/cancellation behavior passes;
- only Web BFF workload reaches application gRPC and arbitrary application Internet/source egress is denied;
- >=3/PDB2/topology-spread hardened deployment renders safely and HPA remains evidence-gated;
- Class-B and >=2x expected-peak load evidence passes before production;
- PII-safe low-cardinality telemetry and alerting passes;
- same signed immutable digest promotion and rebuild/redeploy recovery pass.

Documentation alone is not implementation evidence. Until the trigger, source, contracts, build, deployment, and tests exist and execute, implementation remains `PLANNED / NOT VERIFIED`.
