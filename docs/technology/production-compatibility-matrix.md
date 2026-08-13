# Production Compatibility Matrix

This matrix records the production technology combinations that must remain compatible. Exact image/artifact digests live in reviewed deployment/build metadata. A row is an approved target relationship, not evidence that runtime validation has already passed.

| Component | Baseline | Required compatibility relationship |
| --- | --- | --- |
| Java | 25 LTS | Spring Boot/application libraries support Java 25 |
| Spring Boot | 4.1.0 | Java 25; Gradle 9.x; Spring MVC/Virtual Threads |
| Gradle | 9.6.1 | supported by the selected Spring Boot build plugin/toolchain |
| Kubernetes | 1.35.6 | supported by selected Istio 1.30.x, CloudNativePG 1.30.x, cert-manager 1.20.x, Kyverno 1.18.x, Calico 3.32.x |
| Kubernetes topology | 3 stacked control-plane/etcd + >=3 workers | ADR-0051; redundant API endpoint and one-node-loss evidence |
| Istio | 1.30.3 | Kubernetes 1.35 support; Ambient/STRICT mTLS/authorization tests required |
| Calico | 3.32.1 | Kubernetes 1.35 support; standard dataplane; HBONE/health NetworkPolicy tests; native-CRD migration prerequisites validated before use |
| CloudNativePG | 1.30.0 | supports Kubernetes 1.35/PostgreSQL 18; current operator security hardening remains enabled |
| PostgreSQL | 18.4 | CloudNativePG 1.30-supported major/patch; ADR-0057 dedicated database/cluster/roles; ADR-0064 fleet model |
| PostgreSQL JDBC | 42.7.13 | minimum fixed line 42.7.12 for CVE-2026-54291; dependency locks must not regress |
| Barman Cloud plugin | 0.13.0 | selected CloudNativePG plugin integration; cert-manager TLS path |
| cert-manager | 1.20.3 | compatible with Kubernetes 1.35 baseline |
| Kafka | 4.2.1 | approved 4.2.x line; Spring Kafka/client compatibility pinned/tested in service dependency locks |
| Redis | 8.2.8 | Sentinel/ACL/TLS/noeviction security-state topology |
| Gateway API | 1.5.1 | Traefik/Istio route resources rendered and compatibility-tested |
| Traefik | 3.7.1 | Gateway API 1.5.1 routes validated; chart 40.2.0 in baseline |
| Helm | 4.2.3 | approved 4.2.x patch; chart rendering/schema/policy checks required |
| Kyverno | 1.18.2 | stable policy/image-validation APIs; admission-policy authoring RBAC plus bounded CEL HTTP-context egress/SSRF controls from ADR-0046 must remain valid on Kubernetes 1.35 |
| Teleport | 18.10.0 | JIT/SSO/WebAuthn/session-audit behavior exercised before rollout |
| Cosign | 3.0.6 | signatures/attestations verify through current admission policy |
| OpenBao | 2.6.1 | exact current secret-authority pin; External Secrets/Kubernetes Auth/local-key workflows validated |
| Caddy/Coraza/CRS | 2.11.4 / 3.7.0 / 4.25.1 LTS | coraza-caddy 2.5.0; WAF image/rules tested together |
| Argo CD | 3.4.2 | current GitOps baseline; upgrades require rendered/reconciliation/rollback validation |
| Prometheus / Alertmanager / Grafana | 3.13.1 / 0.33.1 / 13.1.0 | observability stack deployed by immutable artifacts and reviewed with current security advisories |

## Upgrade rule

An upgrade is complete only after the affected compatibility set proves, as applicable:

- official upstream support/security relationship;
- rendered manifest/schema/policy compatibility;
- service contract/build compatibility;
- workload identity/mTLS positive and negative paths;
- staging smoke and critical security behavior;
- admission-policy authoring least privilege and policy-engine egress/SSRF behavior where Kyverno is affected;
- load/latency comparison for request-path components;
- database/Kafka/Redis failover/recovery behavior;
- backup/PITR/restore evidence when relevant;
- vulnerability/advisory correlation for final artifacts;
- safe rollback or explicit fail-forward strategy.

Do not independently upgrade tightly coupled components without evaluating the affected set. A newer release is not automatically preferred when it changes semantics or lacks staging evidence. Unsupported/EOL releases are not production-eligible solely because a previous baseline selected them.
