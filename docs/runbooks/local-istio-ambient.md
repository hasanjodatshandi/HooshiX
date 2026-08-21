# Local Istio Ambient Mesh

## Purpose

The local `kind-platform-local` cluster uses Istio Ambient for production-fidelity
east-west identity, mTLS, L4 authorization, and failure testing.

Traefik remains the north-south gateway. No Istio ingress gateway is installed.
Unlike a routing-only local demo, the platform edge workloads that must call
Ambient-protected destinations are themselves explicitly Ambient-enrolled so
STRICT mTLS and ServiceAccount identity remain testable end to end.

## Status and evidence

This runbook defines the required repository interface. A command shown here is
not considered implemented until the corresponding repository target/script
exists and its verification passes.

```text
Architecture: DECIDED
Local implementation: IMPLEMENTED
Local execution evidence: PASSED through the repository verifier; evidence remains execution/commit specific
Production deployment evidence: NOT VERIFIED
```

## Pinned components

The local foundation installs only:

- Istio base resources/CRDs;
- `istiod` with the ambient profile;
- Istio CNI with the ambient profile;
- `ztunnel`.

Pinned baseline:

```text
Istio 1.30.3
Gateway API 1.5.1
Kubernetes 1.35.x local kind minor
Calico 3.32.1
```

Repository pins belong in:

```text
infrastructure/istio/pins.env
```

Vendored/verified Helm artifacts belong under:

```text
infrastructure/istio/chart/1.30.3/
```

Image digests and chart checksums are required. An installer must verify them
before mutating the cluster.

## Prerequisites

Required local baseline:

```text
docs/technology/local-development-baseline.md
```

The kind foundation must already exist and pass its own checks:

```bash
make baseline-verify
make local-cluster-verify
```

The cluster must use Calico rather than kind's default CNI, and the Gateway API
CRDs must be installed from the pinned repository artifact/version.

## Namespace and security boundaries

`istio-system` requires the Pod Security profile necessary for Istio CNI and
ztunnel node-level networking. This exception is limited to mesh infrastructure.
Application namespaces remain restricted.

Ambient enrollment is explicit:

```text
istio.io/dataplane-mode=ambient
```

Initial local enrollment:

| Namespace | Local enrollment | Reason |
| --- | --- | --- |
| `istio-system` | control plane | Istio components |
| `traefik-system` | ambient | Traefik needs workload identity/mTLS to the WAF |
| `platform-edge` | ambient | Caddy/Coraza edge WAF |
| `platform-apps` | ambient | BFF and application services |
| `platform-data` | selective | only datastore workloads explicitly under mesh test |
| `kube-system` | none | infrastructure |

Every workload uses an independent Kubernetes ServiceAccount. The Kubernetes
`default` ServiceAccount is prohibited for platform workloads.

## Required edge identity path

The local edge path must preserve current architecture:

```text
Traefik ServiceAccount
  -> mTLS / explicit authorization
edge-waf ServiceAccount
  -> mTLS / explicit authorization
web-bff ServiceAccount
```

Direct application routing from Traefik to Web BFF is prohibited. The verifier
must prove both the allowed and denied paths.

## STRICT mTLS policy

Ambient workloads use STRICT mTLS for the test profile. A plaintext/non-mesh
client must not bypass the protected workload path.

L7 waypoints are **not** installed by default. Add a waypoint only when the test
requires L7 method/path/header/claim authorization, L7 mesh routing, or L7
telemetry. The corresponding service/namespace must own the waypoint decision
and test its latency/failure behavior.

## Host inotify prerequisite

ztunnel watches certificate/configuration material. On kind under WSL2, all
nodes share the host kernel, so the local foundation requires these minimums:

```text
fs.inotify.max_user_watches=524288
fs.inotify.max_user_instances=512
```

Configure/verify before mesh installation:

```bash
make kind-inotify-configure
make kind-inotify-verify
```

The installer must fail before Helm mutations if the minimum is not met.

## Install

```bash
make local-istio-ambient-install
```

Required install order:

1. `istio-base`
2. `istiod`
3. `istio-cni`
4. `ztunnel`

The installation must:

- use repository-pinned charts/artifacts;
- verify checksums/digests first;
- use the ambient profile;
- not install an Istio ingress gateway;
- not auto-enroll arbitrary namespaces;
- not change application authorization policies implicitly.

## Verify

The pre-edge foundation verifier is:

```bash
make verify-local-istio-ambient
```

It must run before edge/application deployment and therefore checks only the
Istio foundation state that exists at this stage:

- all four expected Helm releases are deployed;
- `istiod` is available;
- Istio CNI and ztunnel are ready on every kind node;
- running image digests match repository pins;
- expected namespace Ambient enrollment is correct;
- `istioctl analyze` reports no blocking configuration errors.

Full workload-identity and STRICT-mTLS integration is verified only after the
edge and application workloads exist. The repository-owned gates are:

```bash
make verify-local-traefik-edge
make production-fidelity-verify
```

Those integration gates must check at least:

- enrolled workloads run without sidecars;
- every platform workload uses the expected independent ServiceAccount;
- STRICT mTLS allows an enrolled authorized caller;
- a non-enrolled/plaintext caller cannot bypass STRICT mTLS;
- positive/negative L4 `AuthorizationPolicy` cases behave as expected;
- Traefik identity can reach the WAF only on approved ports;
- WAF identity can reach BFF only on approved ports;
- direct Traefik -> BFF application access is denied;
- temporary smoke resources are removed after the run.

A green workload status alone is insufficient evidence.

## Waypoint verification when applicable

If a service uses a waypoint, verification additionally checks:

- the waypoint has the expected `targetRefs`/ownership;
- ingress/east-west waypoint routing is explicit;
- L7 positive and negative authorization tests pass;
- waypoint failure behavior does not create an unsafe bypass;
- latency/resource overhead remains within the service budget.

## Remove

```bash
make local-istio-ambient-delete
```

Removal order is the reverse of installation:

1. `ztunnel`
2. `istio-cni`
3. `istiod`
4. `istio-base`

Retained cluster-scoped CRDs are removed only through an explicit local-cluster
reset target. Normal uninstall must not silently delete unrelated CRDs/resources.

## Diagnostics

Inspect releases:

```bash
helm list \
  --kube-context kind-platform-local \
  --namespace istio-system
```

Inspect workloads:

```bash
kubectl \
  --context kind-platform-local \
  get deployment,daemonset,pods \
  --namespace istio-system \
  --output=wide
```

Inspect recent events:

```bash
kubectl \
  --context kind-platform-local \
  get events \
  --namespace istio-system \
  --sort-by=.metadata.creationTimestamp
```

Inspect component logs:

```bash
kubectl \
  --context kind-platform-local \
  logs --namespace istio-system deployment/istiod

kubectl \
  --context kind-platform-local \
  logs --namespace istio-system daemonset/istio-cni-node --container install-cni

kubectl \
  --context kind-platform-local \
  logs --namespace istio-system daemonset/ztunnel --container istio-proxy
```

Analyze configuration:

```bash
istioctl analyze --context kind-platform-local --all-namespaces
```

Inspect Ambient enrollment and identities using the repository's pinned
`istioctl` and Kubernetes resources; do not use a different Istio CLI minor.

## Failure handling

If install/verify fails:

1. stop further platform-layer installs;
2. preserve the relevant Helm status, events, logs, and rendered values;
3. classify whether the failure is host prerequisite, CNI, CRD, Istio control plane,
   ztunnel, policy, or application enrollment;
4. do not switch `PeerAuthentication` to `PERMISSIVE` merely to make tests pass;
5. do not disable authorization/NetworkPolicy as a diagnostic endpoint;
6. either repair and rerun verification or remove the local mesh using the controlled target.

## Security prohibitions

- no production CA/private key material in the local cluster;
- no public Istio admin/control-plane surfaces;
- no manual app-managed mesh certificates;
- no permanent `PERMISSIVE` workaround;
- no shared/default ServiceAccount for business workloads;
- no allow-all authorization policy;
- no unpinned remote chart/image substitution;
- no Istio retry layer that duplicates application-owned retries.
