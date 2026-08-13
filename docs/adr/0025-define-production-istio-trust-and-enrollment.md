# ADR-0025: Production Istio Trust and Ambient Enrollment v1

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

### Authorization

STRICT mTLS authenticates workload identity; least-privilege `AuthorizationPolicy` controls permitted communication. NetworkPolicy and native datastore authentication remain independent controls.

Waypoints are added only for an explicit L7 method/path/header/claim policy, L7 routing, or L7 telemetry requirement. A waypoint is not installed merely for symmetry.

Retry/fallback semantics remain owned by the application/dependency contract; mesh retry MUST NOT duplicate application/client retry for the same failure.

## Verification requirements

Verify CA/key custody, certificate rotation/overlap, Ambient enrollment, dedicated ServiceAccounts, STRICT mTLS positive/negative paths, least-privilege authorization, NetworkPolicy interaction including HBONE/health traffic, waypoint behavior when present, `istioctl analyze`, and absence of root private key material from Kubernetes/OpenBao.

## Rollback considerations

Rollback MUST preserve authenticated workload identity, strict mTLS, least-privilege authorization, root-key custody, and explicit enrollment. `PERMISSIVE`, allow-all policy, shared/default ServiceAccounts, or manual long-lived service certificates are not rollback mechanisms.