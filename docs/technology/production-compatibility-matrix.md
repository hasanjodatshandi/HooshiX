# Production Compatibility Matrix

This matrix records the production technology combinations that must be kept
compatible. Exact image digests are stored in GitOps/deployment metadata.

| Component | Baseline | Required compatibility relationship |
| --- | --- | --- |
| Java | 25 LTS | Spring Boot/application libraries must support Java 25 |
| Spring Boot | 4.1.0 | Java 25; Gradle 9.x; Spring MVC/Virtual Threads |
| Gradle | 9.6.1 | supported by Spring Boot 4.1 build plugin |
| Kubernetes | 1.35.6 | supported by Istio 1.30.x, CloudNativePG 1.30.x, cert-manager 1.20.x, Kyverno 1.18.x, Calico 3.32.x |
| Kubernetes topology | 3 stacked control-plane/etcd + >=3 workers | ADR-0051 active-cluster HA; redundant API endpoint and one-node-loss validation |
| Istio | 1.30.3 | supports Kubernetes 1.35; Ambient/STRICT mTLS tests required |
| Calico | 3.32.1 | Kubernetes 1.35 NetworkPolicy enforcement; standard dataplane; Ambient-aware HBONE/health tests |
| CloudNativePG | 1.30.0 | supports Kubernetes 1.35 and PostgreSQL 18 |
| PostgreSQL | 18.4 | CloudNativePG 1.30 supported major; ADR-0053 requires distinct database/credentials per persistent microservice, and ADR-0057/ADR-0064 require a dedicated production CloudNativePG cluster per persistent microservice |
| Barman Cloud plugin | 0.13.0 | CloudNativePG >=1.26; cert-manager TLS integration |
| cert-manager | 1.20.3 | supported with Kubernetes 1.35 baseline |
| Kafka | 4.2.1 | Spring Kafka/client compatibility pinned by service dependency locks |
| Redis | 8.2.8 | Sentinel/ACL/TLS/noeviction semantic-quota topology |
| Gateway API | 1.5.1 | Traefik/Istio route resources rendered and compatibility-tested |
| Traefik | 3.7.1 | Gateway API 1.5.1 resources rendered/validated in CI |
| Helm | 4.2.0 | charts rendered and schema/policy checked in CI |
| Kyverno | 1.18.2 | stable `policies.kyverno.io/v1`; ImageValidatingPolicy admission |
| Teleport | 18.10.0 | ADR-0060 privileged human access; JIT/SSO/session audit exercised before rollout |
| Cosign | 3.0.6 | CI signatures/attestations must verify through Kyverno policy |
| OpenBao | 2.6.1 | External Secrets/Kubernetes Auth + local key material workflows |
| Caddy/Coraza/CRS | 2.11.4 / 3.7.0 / 4.25.1 LTS | dedicated WAF image + coraza-caddy 2.5.0 compatibility tested together |

## Upgrade rule

An upgrade is not complete because components individually start. The upgrade
must prove the relevant combination through:

- official support-matrix check;
- rendered manifest validation;
- architecture/security policy tests;
- staging smoke;
- applicable failover/backup/restore tests;
- workload identity/mTLS positive and negative tests;
- load/latency comparison for request-path components;
- documented rollback to the prior known-good set.

Do not independently upgrade tightly coupled components in production without
checking this matrix first.
