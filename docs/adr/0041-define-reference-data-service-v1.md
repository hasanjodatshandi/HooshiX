# ADR-0041: Define Reference Data Capability v1

## Status

Accepted — current effective architecture decision

Executable independent service implementation: **PLANNED / GATED / NOT VERIFIED** until the deployable trigger in this ADR is met.

## Date

2026-08-14; deployable trigger tightened 2026-08-15

## Decision

Reference Data is a distinct platform capability for small, global, non-tenant, low-change standard reference data. The capability boundary is decided now; a separate network-deployed microservice is **not** created merely because the capability exists or one user journey needs the data.

The v1 families are exactly:

- `Country`;
- `Currency`;
- `TimeZone`;
- `SupportedLocale`.

It is not a generic shared CRUD store, business-configuration service, validation oracle, or arbitrary dictionary registry. New families require a real consumer and reviewed contract/source change.

## 1. Canonical source authorities

Reference Data imports reviewed source material offline. Runtime never calls standards bodies/providers.

Current authorities:

- ISO 3166-1 — country codes;
- ISO 4217 — currency codes;
- IANA Language Subtag Registry / BCP 47 — locale identifiers;
- IANA Time Zone Database — time-zone identifiers;
- stable Unicode CLDR — localized display metadata.

Every produced bundle records exact source revisions/artifacts, integrity evidence, license/use-right basis, deterministic content digest, and format version. Draft/development source data is not admitted to production bundles.

Product-selectable v1 locales remain exactly:

```text
fa
en
```

Reference existence/lifecycle never grants domain/business/legal validity.

## 2. Immutable bundle model

Reference Data v1 has no PostgreSQL, CloudNativePG, Flyway, SQLite, Redis, or Kafka state path.

The dataset is a versioned immutable read-only bundle. Runtime may deserialize the intentionally small v1 families into bounded immutable indexes.

Bundle manifest records at least:

```text
format_version
bundle_version
built_at_utc
source_revisions
supported_locales
record_counts_by_family
content_sha256
```

Offline build:

```text
approved source acquisition
-> license/use-right + integrity review
-> normalization/schema/code validation
-> deterministic ordering/deduplication
-> localized metadata validation
-> immutable bundle build
-> digest/provenance verification
-> signed release
```

No production runtime download, synchronization, repair, or mutation exists.

## 3. Lifecycle and typed contract

Reference codes use:

```text
ACTIVE
DEPRECATED
RETIRED
```

Used codes are not silently deleted/reused for another meaning. Historical exact lookup may resolve retained deprecated/retired codes while normal selection defaults to active records.

The capability surface remains typed and closed:

```text
ListCountries / GetCountry
ListCurrencies / GetCurrency
ListTimeZones / GetTimeZone
ListSupportedLocales / GetSupportedLocale
GetReferenceDataVersion
```

There is no generic dataset selector, dynamic schema/query language, fuzzy search, caller-defined sort, or arbitrary key/value registry.

List behavior remains deterministic with default page 100, maximum page 200, bounded opaque page token, and <=128 KiB serialized response.

## 4. Deployment modes before and after the trigger

### Before independent-service trigger

The immutable bundle MAY be used inside the owning deployable that needs it, through a module/adapter boundary, provided the same source/provenance/version/integrity/lifecycle rules are preserved.

Before the trigger:

- no `reference-data-service` Deployment/Service/ServiceAccount is created;
- no BFF->Reference Data gRPC/network dependency is required;
- no second mutable copy/cache is introduced;
- the bundle/module does not become a generic shared validation package or cross-service business model;
- another independently deployable consumer does not read another service's local file/process memory.

For browser-facing global reference reads, Web BFF may initially own the thin serving adapter around the approved immutable bundle until independent deployment is justified. This does not transfer canonical source/lifecycle governance to arbitrary BFF feature code.

### Independent Reference Data Service trigger

Create the separate independently deployable `reference-data-service` only when at least one of these evidence-backed conditions is true:

1. **at least two independently deployable consumers** require the same reference capability; or
2. the reference bundle requires an **independent update/release lifecycle** that cannot be safely coupled to the current owning consumer; or
3. an **independent security/trust boundary** is required; or
4. an **independent scale/availability profile** is required by measured load/SLO evidence; or
5. **independent team/operational ownership and release accountability** is established.

One user journey, one BFF route group, one screen, or multiple endpoints of the same deployable is **not** by itself sufficient evidence for a network service.

The trigger record identifies which condition applies and includes consumer/ownership/change/scale/security evidence. Architecture prose cannot use this ADR as permission for premature service creation.

Until then:

```text
Capability architecture: DECIDED
Independent service:     PLANNED / GATED
Runtime evidence:         NOT VERIFIED
Production readiness:     NOT VERIFIED for service deployment
```

## 5. Service contract when triggered

When the trigger is met, the planned runtime identity remains:

```text
service path:    services/reference-data-service
base package:    com.sajtech.referencedata
namespace:       platform-apps
Deployment:      reference-data-service
Service:         reference-data-service
ServiceAccount:  reference-data-service
application:     gRPC 9090
management:      separate configured port
```

Initial intended network caller is Web BFF, but the trigger must already have justified the independent deployable through the rules above. Every caller/operation requires dependency-registry, workload-policy, deadline/cancellation/failure ownership.

Initial BFF service-call contract when activated:

```text
class:           AUTHORITATIVE_STATE
deadline:        <=1000 ms and remaining-parent bounded
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure action:  unavailable; never fabricate reference data
```

The service is ClusterIP-only, Ambient-enrolled, deny-by-default, has no standards-source Internet synchronization, and uses the normal hardened workload baseline.

Profile deployment remains one replica/HPA off/PDB off in `production-single-server` and the reviewed replicated target in `production-ha`.

## 6. Browser facade

Web BFF owns the public REST representation under `/api/v1/reference` when that product surface is implemented, whether its source adapter is initially in-process or later remote.

Routes are read-only `GET`/`HEAD`, may be anonymous because data is global public metadata, still traverse the mandatory edge/WAF path, do not create cross-origin credentialed CORS, and use deterministic representation locale/cache validators.

Successful immutable public responses may use:

```text
ETag: deterministic representation validator
Cache-Control: public, max-age=3600
```

No server-side stale/fabricated fallback is permitted when the current approved bundle/adapter cannot produce a valid result.

## 7. Security, privacy, observability

Reference Data contains no User, Contact, Tenant, Membership, Session, Credential, Role, permission, tenant configuration, pricing, product catalog, feature flag, or workflow state.

It is not an erasure participant in v1 and is never authorization/domain acceptance authority.

Telemetry is low-cardinality: family/operation enum, latency/outcome, bundle/version health, bounded record counts, and saturation when a remote service exists. Caller free text/page tokens/raw URLs/unbounded source metadata are not labels/log payloads. ADR-0044 applies from the first executable adapter/service implementation.

## Verification requirements

Before using an in-process bundle or independent service, prove applicable:

- exact source revisions/provenance/integrity/license review;
- deterministic importer/bundle/manifest/digest;
- canonical Country/Currency/TimeZone/SupportedLocale identifiers and lifecycle/non-reuse;
- exact `fa`/`en` metadata requirements;
- no runtime standards-source Internet dependency;
- no database/Redis/Kafka state;
- bounded startup/index/response/pagination behavior;
- typed contract/no generic registry surface;
- public `/api/v1/reference` method/cache/CORS/edge semantics when exposed;
- PII-safe Day-One logs/metrics/traces.

Before creating `reference-data-service`, additionally prove:

- one explicit deployable trigger above with evidence;
- no service creation based only on one journey/route group;
- BFF/consumer local adapter removed or cleanly replaced without two competing authorities;
- exact gRPC deadline/cancellation/no-retry/no-fallback behavior;
- wrong-workload/egress negatives;
- profile-correct deployment/load/SLO and signed-image recovery evidence.

## Rollback considerations

Rollback MUST NOT reintroduce a generic dictionary service, mutate a deployed bundle, silently reuse retired codes, add an unreviewed datastore/provider runtime dependency, fabricate stale data, or create/retain an independent Reference Data microservice after its trigger no longer exists without an explicit ownership review.