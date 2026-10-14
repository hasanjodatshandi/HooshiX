# ADR-0002: Production Istio Trust and Ambient Enrollment v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Production application east-west traffic uses Istio Ambient with strict workload identity and mTLS.

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
principal   = prod.sajtech.internal/ns/<namespace>/sa/<service-account>
```

### Trust hierarchy

```text
Offline Root CA
-> Production Cluster Intermediate CA
-> Istio workload certificates
```

- Root lifetime: 10 years; private key offline and never stored in Kubernetes/OpenBao;
- two encrypted physical root-key copies in separate locations;
- intermediate lifetime: one year;
- intermediate rotation begins 90 days before expiry with >=30-day overlap;
- intermediate private key exists only in the tightly controlled `istio-system` trust boundary and is encrypted at rest;
- initial supervised, encrypted VPS custody before `cacerts` import is limited to the owner-approved commissioning exception in ADR-0030; only the public CSR goes to the offline Root;
- workload certificate TTL: 24 hours with automatic rotation.

### Enrollment

Enrollment is explicit/opt-in:

| Namespace | Initial production state |
| --- | --- |
| `platform-edge` | enrolled |
| `platform-apps` | enrolled |
| `platform-data` | selective per workload |
| `istio-system` | control plane |
| `argocd` | not initially enrolled |
| `observability` | not initially enrolled |
| `kube-system` | not enrolled |

Production application workloads use dedicated ServiceAccounts. Kubernetes `default` ServiceAccount is prohibited for application workloads.

### Owner-approved ESO admission webhook boundary (2026-10-11)

Only the `hooshix-eso-webhook` Deployment in `platform-apps` may set the Pod label
`istio.io/dataplane-mode=none`. The Kubernetes API admission caller is outside
Ambient; its callback uses verified HTTPS and the chart-managed serving certificate
and CA bundle, not an unauthenticated application path. Calico ingress selects only
that webhook and exact API endpoint addresses on TCP/10250; it grants no Pod-wide,
namespace-wide or public ingress. Kubernetes node-local health traffic remains
subject to the CNI's host-health semantics; NetworkPolicy is not an API identity proof.
The webhook retains `failurePolicy=Fail`, a five-second callback deadline,
namespace-scoped selection and the approved immutable ESO image.

The ESO controller and cert-controller explicitly remain Ambient. Namespace STRICT
and default-deny policies are unchanged; only the dedicated ESO controller identity
may reach OpenBao TCP/8200, with bounded NetworkPolicy HBONE/15008 allowance.
No cert-controller opt-out, sidecar injection, PERMISSIVE/DISABLE policy, root/privilege
exception, public route, or new application egress is authorized. Render validation
rejects an opt-out on either other ESO workload. Native positive/negative callback,
identity, certificate and delivery tests remain required before target promotion.

References: [Istio Ambient selection](https://istio.io/latest/docs/ambient/usage/add-workloads/)
and [Ambient NetworkPolicy](https://istio.io/latest/docs/ambient/usage/networkpolicy/).

### Owner-approved internal cert-manager webhook boundary (2026-10-13)

For the database control-plane TLS dependency, only the `cert-manager-webhook`
Deployment in `cnpg-system` may set `istio.io/dataplane-mode=none`. The API-server
callback remains verified HTTPS, `failurePolicy=Fail`, five-second bounded and
namespace-scoped. Calico ingress selects only that Pod on TCP/10250 from exact
discovered API endpoints. Node-local health traffic follows the existing CNI host
semantics; an IP rule is not cryptographic API identity proof.

The cert-manager controller and cainjector remain explicitly Ambient, with
namespace STRICT, dedicated non-root ServiceAccounts, default-deny networking,
fixed signed images, exact source provenance and signed SBOM admission. This is
not a controller, namespace-wide, application, CNPG/Barman webhook or public-route
opt-out. No PERMISSIVE/DISABLE, sidecar, privilege or host mount is authorized.

The initial certificate controllers operate only in `cnpg-system` for internal
CA/self-signed Issuers. ClusterIssuers, ACME/solver, ingress/Gateway, external
issuers and Kubernetes CSR controllers are disabled. Namespace Roles bound Secret
access; cainjector can read webhook metadata cluster-wide but may update only
`cert-manager-webhook` configurations. Other injector targets require their own
reviewed exact-name extension. Fail-closed admission pins callback service, rules,
namespace selection and `failurePolicy=Fail` while allowing CA-bundle injection;
the injector cannot weaken its own callback boundary. Pods use an exact one-hour
projected API token; executable arguments, environment and mounts are admission-bound.
The upstream approval webhook checks the virtual `signers` resource at cluster
scope. A controller-only ClusterRole grants `approve` solely for
`issuers.cert-manager.io/cnpg-system.hooshix-database-selfsigned` and
`issuers.cert-manager.io/cnpg-system.hooshix-database-ca`; no wildcard, foreign
namespace or Secret access is granted. Validating callbacks cover the actual
`certificaterequests/status` endpoint, including approval transitions.
The serving CA is distinct control-plane TLS
material; it does not replace or modify the offline Root, Istio intermediate,
OpenBao TLS or application secret authority. Root material is never imported.

Render and live admission tests must reject wrong image/signer/source/SBOM,
wrong account, widened controller scope, controller/cainjector opt-out, external
issuer and unapproved webhook Secret writes. The supervised installer creates
only an EMPTY, owned serving-CA destination with direct-injection annotation;
the webhook has exact-name update/read/watch and no Secret create privilege.
Existing private material is neither read nor replaced by the installer. This
uses the pinned upstream DynamicAuthority existing-Secret update path. Required
Kyverno report aggregation adds read-only Issuer/webhook-configuration metadata,
never cluster Secret access or a reporting/admission bypass. Verified callback TLS, internal
issuance/renewal, RBAC/network negatives and existing CA/OpenBao/ESO/storage
preservation are required before a supervised target bundle is usable.

References: [controller namespace scope](https://cert-manager.io/docs/cli/controller/),
[cainjector scope](https://cert-manager.io/docs/installation/best-practice/) and
the immutable source/manifest in `cert-manager-sources.json`. Approval and render
are not executed installation, certificate or Production-readiness evidence.

### Authorization

STRICT mTLS authenticates workload identity; least-privilege `AuthorizationPolicy` controls permitted communication. NetworkPolicy and native datastore authentication remain independent controls.

Waypoints are added only for an explicit L7 method/path/header/claim policy, L7 routing, or L7 telemetry requirement. A waypoint is not installed merely for symmetry.

Retry/fallback semantics remain owned by the application/dependency contract; mesh retry MUST NOT duplicate application/client retry for the same failure.

## Verification requirements

### Owner-approved existing Root continuation (2026-10-04)

The owner authorizes reuse of one existing Root for this owner's
`hooshix-production` / `production-single-server` installation without regeneration.
The exact certificate **file SHA256** is
`f6d49249e221fa49138771c3d86037575f55eee5f581af373f4523008e424b94`;
its RFC2253 subject is
`subject=CN=HooshiX Local Root Candidate 2026 - NOT Production Approved,O=HooshiX`.
The hash-bound public tooling policy is `scripts/production/offline-ca/root-authority.json`.

This is an explicit existing-root provenance exception, not proof of offline generation:
the Root was generated on a connected Windows computer. Moving it offline cannot undo
possible prior exposure, machine backups or snapshots. The owner accepts continuation
with that reduced origin assurance and reports offline custody plus two physical backups.
Independent recovery evidence remains `Not verified`; no old receipt or certificate name
is rewritten to imply otherwise. `ToOnline` is merely a public export convention and is
not required when this exact public certificate and the historical backup receipt are
already available.

Other installations and `production-ha` retain the normal offline-generated Root process.
The exception does not relax key encryption, offline signing, chain/key/expiry checks,
private-key isolation, intermediate rotation, mTLS, or any Production-readiness gate.
The Root private key/password must never be supplied to the agent or online host.

Verify CA/key custody, certificate rotation/overlap, Ambient enrollment, dedicated ServiceAccounts, STRICT mTLS positive/negative paths, least-privilege authorization, NetworkPolicy interaction including HBONE/health traffic, waypoint behavior when present, `istioctl analyze`, and absence of root private key material from Kubernetes/OpenBao.

## Rollback considerations

Rollback MUST preserve authenticated workload identity, strict mTLS, least-privilege authorization, root-key custody, and explicit enrollment. `PERMISSIVE`, allow-all policy, shared/default ServiceAccounts, or manual long-lived service certificates are not rollback mechanisms.
