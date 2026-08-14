# Reference Data Service Architecture

## 1. Status and ownership

Reference Data is a decided platform capability, but the **independent service is not yet authorized for implementation** until ADR-0041's deployable trigger is evidenced.

Current state:

```text
Capability architecture: DECIDED
Independent service:     PLANNED / GATED
Implementation:           NOT PRESENT
Runtime evidence:         NOT VERIFIED
Production readiness:     NOT VERIFIED
```

The capability owns canonical reference-source governance for exactly Country, Currency, TimeZone, and SupportedLocale. It is not a generic dictionary/configuration/validation service.

## 2. Before the independent-service trigger

The approved immutable reference bundle may be served inside the current owning deployable, initially Web BFF when the browser reference surface is implemented.

Before the trigger:

- no `reference-data-service` Deployment/Service/ServiceAccount is created;
- no BFF->Reference Data gRPC edge is required;
- no independent service build/container/release exists;
- source/provenance/version/integrity/lifecycle rules still apply;
- no mutable cache/database/provider sync is introduced;
- local module boundaries remain explicit and do not become a generic shared business library.

One user journey, screen, route group, or multiple endpoints of one deployable is not sufficient reason to create the service.

## 3. Independent-service trigger

Create `services/reference-data-service` only after one of these is evidenced:

1. >=2 independently deployable consumers need the same capability;
2. independent update/release lifecycle is required;
3. separate security/trust boundary is required;
4. independent scale/availability profile is required by measurement/SLO;
5. independent team/operational ownership and release accountability exists.

The trigger record identifies the actual consumer/ownership/change/scale/security evidence.

## 4. Data/source model

Sources remain ISO 3166-1, ISO 4217, IANA Language Subtag Registry/BCP 47, IANA tzdb, and stable Unicode CLDR.

Bundle is immutable/read-only with no PostgreSQL/SQLite/Redis/Kafka runtime state. Manifest records format/bundle version, controlled build timestamp, source revisions, supported locales, record counts, and content SHA-256.

Production runtime never downloads/synchronizes standards data. Reference codes use ACTIVE/DEPRECATED/RETIRED lifecycle and are not silently reused.

Supported product locales remain:

```text
fa
en
```

## 5. Public BFF surface

When implemented, BFF may expose read-only `GET`/`HEAD` reference routes under `/api/v1/reference` from either the local immutable adapter or, after trigger, the independent gRPC service.

The public surface:

- still traverses upstream L4 -> Traefik -> WAF -> BFF;
- may be anonymous because data is global/public;
- does not enable credentialed cross-origin CORS;
- uses deterministic representation locale/order/pagination;
- may use deterministic ETag and `Cache-Control: public, max-age=3600`;
- never fabricates unreviewed stale data.

## 6. Independent runtime contract when triggered

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

Typed operations remain Country/Currency/TimeZone/SupportedLocale list/get plus version query. No generic dataset/query surface.

Initial BFF dependency when activated:

```text
class:           AUTHORITATIVE_STATE
deadline:        <=1000 ms and remaining-parent bounded
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
```

The service is ClusterIP-only, Ambient-enrolled, strict mTLS, deny-by-default, and has no standards-source Internet egress.

Single-server: one replica/HPA off/PDB off. HA: reviewed replicated target.

## 7. Day-One observability

Whether local module or independent service, first executable reference path follows ADR-0044.

Allowed telemetry:

- bounded family/operation enum;
- latency/outcome;
- bundle/version/integrity state;
- bounded record counts;
- service in-flight/queue saturation when remote.

No caller free text/page token/raw URL/unbounded source metadata becomes metric label/log/trace attribute.

## 8. Verification

Before any executable reference path:

- source revision/provenance/integrity/license evidence;
- deterministic importer/bundle/manifest/digest;
- canonical identifiers/lifecycle/non-reuse;
- `fa`/`en` display metadata where required;
- bounded memory/pagination/response;
- no runtime standards-source dependency or mutable datastore;
- public BFF edge/CORS/cache semantics where exposed;
- Day-One PII-safe logs/metrics/traces.

Before independent service creation additionally:

- explicit ADR-0041 trigger record;
- no one-journey-only justification;
- typed Protobuf/Buf contract;
- BFF deadline/cancellation/no-retry/no-fallback behavior;
- wrong-workload and egress negatives;
- local adapter cleanly replaced so there are not two authorities;
- profile-correct signed deployment/load/recovery evidence.

Until those artifacts exist, the independent service remains `PLANNED / GATED / NOT VERIFIED`.