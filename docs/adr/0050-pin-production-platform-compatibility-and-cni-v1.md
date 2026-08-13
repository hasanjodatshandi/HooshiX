# ADR-0050: Pin Production Platform Compatibility and CNI v1

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR closes the remaining production compatibility/CNI selection gap. It does not change ADR-0011's intentionally non-HA initial Argo CD topology or ADR-0037's in-repository GitOps model.

## Decision

The exact production compatibility set is maintained in `docs/technology/technology-baseline.md` and immutable deployment metadata.

Current approved set includes:

- Eclipse Temurin 25.0.4 / Java 25;
- Spring Boot 4.1.0;
- Gradle Wrapper 9.6.1;
- gRPC Java 1.81.0;
- Spring Kafka 4.1.0 / Kafka 4.2.1;
- Kubernetes 1.35.6;
- Calico OSS 3.32.1;
- Helm 4.2.0;
- Istio 1.30.3 Ambient;
- Gateway API 1.5.1;
- Traefik 3.7.1 / chart 40.2.0;
- CloudNativePG 1.30.0 / PostgreSQL 18.4;
- Redis OSS 8.2.8;
- OpenBao 2.6.1;
- External Secrets Operator 2.8.0;
- Argo CD 3.4.2;
- Kyverno 1.18.2 while that minor remains upstream-supported and Kubernetes-compatible at rollout;
- Caddy 2.11.4, Coraza 3.7.0, coraza-caddy 2.5.0, OWASP CRS 4.25.1 LTS;
- Prometheus 3.13.1 LTS, Alertmanager 0.33.1, Grafana 13.1.0.

### CNI

Calico is the v1 primary CNI and Kubernetes NetworkPolicy enforcement implementation.

The platform uses upstream Istio Ambient. Kubernetes NetworkPolicy remains defense in depth and must account for Ambient HBONE/health traffic. Calico-specific eBPF acceleration/experimental network optimization is not enabled in v1. The standard dataplane reduces mesh/CNI interaction complexity.

### Argo CD

ADR-0011's initially non-HA Argo CD remains accepted for v1 because Argo CD is not in the application request path and the platform already has Git source-of-truth plus a 4-hour cold-DR objective. Production resources continue running during a controller outage.

Argo CD HA is introduced only when deployment/reconciliation availability or recovery evidence justifies the added control-plane footprint. This is an explicit cost/availability tradeoff, not an unresolved decision.

### Upgrade governance

No unattended automatic platform upgrades.

Compatible patch updates use a reviewed Technology Baseline PR plus CI/staging compatibility validation. A compatible minor may use the same mechanism only when it does not change architecture/security semantics and remains supported with the pinned Kubernetes version.

Major, incompatible-minor, product-substitution, protocol, or security-model changes require an ADR.

An upstream-EOL component cannot be deployed merely because an old baseline names it; update to the nearest supported compatible release through the baseline process before rollout.

### Artifact immutability

Production desired state uses immutable image digests. Chart/rules artifacts are pinned with integrity metadata where available. The digest in `deploy/` is the deployment source of truth.

## Verification Requirements

Compatibility CI/staging validates Kubernetes API/CRD compatibility, Helm/Kustomize render, Gateway/Traefik behavior, Istio Ambient and `istioctl analyze`, Calico NetworkPolicy positive/negative flows including HBONE paths, CloudNativePG failover, Kyverno admission, Argo reconciliation, and exact image digests.

## Consequences

The platform has one explicit compatible production matrix without forcing unnecessary HA for a non-request-path GitOps controller. Calico closes the NetworkPolicy enforcement gap and avoids an additional experimental dataplane mode.
