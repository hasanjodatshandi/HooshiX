# Production Compatibility Matrix

This matrix records the production technology combinations that must remain compatible. Exact image/artifact/package digests live in reviewed deployment/build/provisioning metadata. A row is an approved target relationship, not evidence that runtime validation has already passed.

| Component | Baseline | Required compatibility relationship |
| --- | --- | --- |
| Java | 25 LTS | Spring Boot/application libraries support Java 25 |
| Spring Boot | 4.1.0 | Java 25; Gradle 9.x; Spring MVC/Virtual Threads |
| Gradle | 9.6.1 | supported by the selected Spring Boot build plugin/toolchain |
| Xerial SQLite JDBC / SQLite | 3.53.2.1 / 3.53.2 | ADR-0040 only: Java 25 + production Linux/native extraction compatibility; immutable read-only Compromised Password dataset; no runtime provider or mutable business persistence; Java artifact + bundled native engine remain under SBOM/advisory review; upgrade when a compatible reviewed Xerial release bundles SQLite 3.53.4+ or a required security fix |
| Kubernetes API/minor | 1.35.6 | supported by selected Istio 1.30.x, CloudNativePG 1.30.x, cert-manager 1.20.x, Kyverno 1.18.x, Calico 3.32.x |
| `production-single-server` Kubernetes | K3s `v1.35.6+k3s1` / Kubernetes 1.35.6 | ADR-0042; one server/workload node; embedded SQLite; Calico custom CNI; K3s Flannel/network-policy controller/bundled Traefik/ServiceLB disabled; exact K3s artifact verified before install |
| `production-ha` Kubernetes | kubeadm-compatible Kubernetes 1.35.6 | ADR-0022; 3 stacked control-plane/etcd + >=3 workers; redundant API endpoint and one-node-loss evidence |
| Istio | 1.30.3 | Kubernetes 1.35 support; Ambient/STRICT mTLS/authorization tests required; single-server additionally requires complete-stack CPU/memory/latency benchmark and >=30% validated headroom |
| Calico | 3.32.1 | Kubernetes 1.35 support; standard dataplane; K3s custom-CNI configuration; HBONE/health NetworkPolicy tests |
| CloudNativePG | 1.30.0 | supports Kubernetes 1.35/PostgreSQL 18; shared one-instance cluster in single-server profile; dedicated HA clusters in HA profile; current operator security hardening remains enabled |
| PostgreSQL | 18.4 | CloudNativePG 1.30-supported major/patch; ADR-0027 separate service databases/roles/Flyway/RLS in both profiles; shared physical cluster only in single-server profile; not used for ADR-0040 immutable reference dataset |
| PostgreSQL JDBC | 42.7.13 | minimum fixed line 42.7.12 for CVE-2026-54291; dependency locks must not regress |
| Barman Cloud plugin | 0.13.0 | selected CloudNativePG integration; single-server physical backup is shared-cluster WAL/PITR; HA profile uses independent service-cluster backup identities |
| cert-manager | 1.20.3 | compatible with Kubernetes 1.35 baseline |
| Kafka | 4.2.1 | approved 4.2.x line; Spring Kafka/client compatibility pinned/tested; single-server combined KRaft RF1/minISR1; HA profile RF3/minISR2 with dedicated controllers |
| Redis | 8.2.8 | single-server: one TLS/ACL/noeviction instance with AOF `appendfsync everysec`; HA: Sentinel/replica topology; semantic quota fail-closed contract unchanged |
| Gateway API | 1.5.1 | repository Traefik/Istio route resources rendered and compatibility-tested; K3s bundled Traefik not used |
| Traefik | 3.7.10 | Gateway API 1.5.1 routes validated; chart 40.2.0 in baseline; security-fixed 3.7.x patch |
| Helm | 4.2.3 | approved 4.2.x patch; chart rendering/schema/policy checks required |
| Kyverno | 1.18.2 | stable policy/image-validation APIs; 1 replica allowed only in single-server non-HA profile while enforcement stays fail closed; >=3 replicas in HA profile; policy-authoring RBAC/SSRF controls remain valid on Kubernetes 1.35 |
| `production-single-server` human access | host-supported OpenSSH + hardware FIDO2 + JIT privilege + protected audit | exact host OpenSSH package pinned in provisioning; user-presence/user-verification required; no password/root/shared-key SSH; `sudo` I/O + OS/boundary audit exported off-host |
| `production-ha` human access | Teleport 18.10.0 | JIT/SSO/WebAuthn/session-audit behavior exercised before rollout |
| Cosign | 3.0.6 | signatures/attestations verify through current admission policy |
| OpenBao | 2.6.1 | exact current secret-authority pin; External Secrets/Kubernetes Auth/local-key workflows validated; unchanged by ADR-0042 |
| Caddy/Coraza/CRS | 2.11.4 / 3.7.0 / 4.25.1 LTS | coraza-caddy 2.5.0; WAF image/rules tested together |
| Argo CD | 3.4.2 | current GitOps baseline; upgrades require rendered/reconciliation/rollback validation |
| Prometheus / Alertmanager / Grafana | 3.13.1 / 0.33.1 / 13.1.0 | observability stack deployed by immutable artifacts and reviewed with current security advisories; complete-stack sizing is benchmark-gated in single-server profile |

## Upgrade and profile-validation rule

An upgrade or initial profile approval is complete only after the affected compatibility set proves, as applicable:

- official upstream support/security relationship;
- rendered manifest/schema/policy compatibility;
- service contract/build compatibility;
- workload identity/mTLS positive and negative paths;
- staging smoke and critical security behavior;
- admission-policy authoring least privilege and policy-engine egress/SSRF behavior where Kyverno is affected;
- load/latency comparison for request-path components;
- selected-profile Kafka/Redis/PostgreSQL failure/recovery behavior without false HA claims;
- Compromised Password SQLite Java/native/dataset-format/read-only compatibility when ADR-0040 is affected;
- backup/PITR/restore evidence when relevant;
- vulnerability/advisory correlation for final artifacts, including bundled native components;
- safe rollback or explicit fail-forward strategy;
- for `production-single-server`, complete-stack CPU/memory/IO/latency evidence with >=30% validated headroom, no OOM/memory-pressure eviction, and benchmark-gated Ambient behavior.

Do not independently upgrade tightly coupled components without evaluating the affected set. A newer release is not automatically preferred when it changes semantics or lacks staging evidence. Unsupported/EOL releases are not production-eligible solely because a previous baseline selected them. A failed single-server capacity/security benchmark requires more host capacity or migration to `production-ha`; it MUST NOT be resolved by disabling OpenBao, Kyverno enforcement, required backup/PITR, workload identity, or MFA controls.