# ADR-0025: Define Production Istio Trust and Namespace Enrollment

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR resolves the canonical architecture's pending production Istio trust
domain, CA hierarchy, certificate lifetime, and namespace-enrollment decision.

It retains Istio Ambient Mode, strict mTLS for enrolled workloads,
ServiceAccount-derived workload identity, deny-by-default authorization, and
the rule that L7 waypoints are introduced only when required.

## Context

Production workload identity requires stable trust-domain semantics and a CA
hierarchy whose root key is not routinely accessible from the cluster. At the
same time, namespace enrollment must be explicit so that creating a namespace
does not silently place arbitrary workloads inside the production mesh.

## Decision

### Mesh identity

Production uses:

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
```

Workload principals are derived from this trust domain and their Kubernetes
namespace and ServiceAccount identity.

### CA hierarchy and custody

The approved hierarchy is:

```text
Offline Root CA
  -> Production Cluster Intermediate CA
       -> Istio workload certificates
```

The Root CA lifetime is 10 years. Its private key remains offline and must
never be stored in Kubernetes or OpenBao. Two encrypted offline copies are
kept in two separate physical locations. Routine production operations have no
access to the Root CA private key.

The Production Cluster Intermediate CA lifetime is 1 year. Rotation begins 90
days before expiry, with at least 30 days of CA overlap. The intermediate key
exists only in `istio-system` and is protected with Kubernetes encryption at
rest and highly restricted RBAC.

Istio workload certificates have a 24-hour TTL and rotate automatically through
Istio. Application workloads do not manage or rotate these certificates.

### Namespace enrollment

Production namespace policy is:

| Namespace | Initial Ambient enrollment |
| --- | --- |
| `platform-edge` | Enrolled |
| `platform-apps` | Enrolled |
| `platform-data` | Selective by workload decision |
| `istio-system` | Control plane |
| `argocd` | Not enrolled initially |
| `observability` | Not enrolled initially |
| `kube-system` | Not enrolled |

Enrollment is explicit and opt-in. A newly created namespace is not
automatically enrolled. Every enrolled workload uses the existing
`PeerAuthentication STRICT` baseline and explicit authorization policy.

Selective enrollment in `platform-data` does not authorize every stateful
component to join the mesh. Each datastore requires its existing compatibility,
native TLS/authentication, latency, and failure-mode review.

### Implementation gate

The Root CA generation ceremony, offline encryption mechanism, recovery
procedure, intermediate-key injection mechanism, exact rotation runbook, and
Kubernetes at-rest KMS provider require separate approved operational details.

## Consequences

- Production workload identities use one explicit trust domain and mesh ID.
- Root compromise risk is reduced by keeping the root key outside Kubernetes
  and OpenBao and unavailable to routine operations.
- Intermediate compromise is bounded to the production cluster rather than the
  offline root.
- Explicit enrollment prevents accidental mesh membership.
- `platform-data` needs per-workload enrollment review and cannot use mesh
  membership to remove native datastore security.

## Alternatives Considered

### Store the Root CA key in Kubernetes or OpenBao

Rejected because the approved root-custody model is offline and physically
separated from routine production systems.

### Automatically enroll every namespace

Rejected because namespace creation alone must not grant mesh identity and
reachability semantics.

### Enroll Argo CD, observability, and kube-system initially

Not selected for the initial production model.

## Rollback or Migration Considerations

This ADR creates no certificate or manifest by itself.

Trust-domain or CA migration requires overlap and validation of both old and
new trust anchors before old anchors are removed. Rollback must preserve valid
workload identity and strict mTLS. Namespace enrollment changes are GitOps
changes and must retain deny-by-default policy and positive/negative tests.
