# Production Compatibility Matrix

This matrix records production technology combinations that must remain compatible. Exact image/artifact/package digests live in reviewed deployment/build/provisioning metadata. A row is an approved target relationship, not runtime proof.

| Component | Baseline | Required compatibility relationship |
| --- | --- | --- |
| Java | 25 LTS | Spring Boot/application libraries support Java 25 |
| Spring Boot | 4.1.0 | Java 25; Gradle 9.x; Spring MVC/Virtual Threads; Micrometer Observation/Tracing |
| Gradle | 9.6.1 | selected Spring Boot build plugin/toolchain |
| gRPC Java | 1.83.1 | Java 25; Protobuf/runtime/stubs/codegen/testing aligned; Netty transport stream-limit enforcement retained and bounded server concurrency configured where applicable |
| Gitleaks CLI | 8.30.1 | ADR-0045 native CLI current-tree + Git-history secret scan; redacted output; exact release artifact integrity pinned in CI |
| Semgrep | repository-pinned CLI/image + rules | ADR-0039/0045 first-party SAST/source policy; separate Semgrep Secrets/Supply Chain products are not implied |
| OSV-Scanner | 2.4.0 | ADR-0045 early declared/locked dependency advisory scan; exact Linux/x64 artifact checksum pinned; complements but does not replace final-image Grype authority |
| Syft | 1.51.0 | ADR-0035/0045 final releasable image -> CycloneDX JSON SBOM bound to exact image digest |
| Grype | 0.117.0 | ADR-0035/0038/0045 scans exact final image/Syft SBOM; approved advisory DB/feed freshness and exception policy; release/deployed-artifact vulnerability authority |
| Cosign | 3.0.6 | exact image digest signature + provenance + signed CycloneDX SBOM attestation; compatible with Kyverno verification policy |
| HIBP Pwned Passwords | SHA-1 offline corpus | ADR-0040: official Pwned Password source/range semantics; SHA-1 only for screening; complete acquisition/provenance/freshness/full-corpus cardinality evidence |
| Xerial SQLite JDBC / SQLite | 3.53.2.1 / 3.53.2 | Java 25/Linux native compatibility; 20-byte SHA-1 immutable read-only dataset; no runtime provider/mutable persistence; SBOM/advisory review |
| Kubernetes API/minor | 1.35.6 | selected Istio 1.30.x, CloudNativePG 1.30.x, cert-manager 1.20.x, Kyverno 1.18.x, Calico 3.32.x |
| `production-single-server` Kubernetes | K3s `v1.35.6+k3s1` | one server/workload node; embedded SQLite; custom Calico; bundled Flannel/policy-controller/Traefik/ServiceLB disabled |
| `production-ha` Kubernetes | kubeadm-compatible 1.35.6 | current HA control-plane/worker topology |
| Istio | 1.30.3 | Kubernetes 1.35 support; Ambient/STRICT mTLS; single-server capacity evidence |
| Calico | 3.32.1 | Kubernetes 1.35; K3s custom CNI; HBONE/health policy tests |
| CloudNativePG | 1.30.0 | Kubernetes 1.35/PostgreSQL 18; one shared instance in single-server, dedicated clusters in HA |
| PostgreSQL | 18.4 | CNPG support; distinct service DB/roles/Flyway/RLS; shared physical only in single-server |
| PostgreSQL JDBC | 42.7.13 | fixed security line; locks must not regress below 42.7.12 |
| Barman Cloud plugin | 0.13.0 | selected CNPG WAL/PITR integration |
| cert-manager | 1.20.3 | compatible with Kubernetes 1.35 |
| Kafka | 4.2.1 | Spring Kafka/client compatibility; single combined RF1 in single-server; RF3/minISR2 HA |
| Redis | 8.2.8 | single-server TLS/ACL/noeviction/AOF; HA Sentinel; ADR-0024 common-clock/cardinality/exact-IP semantics |
| Gateway API | 1.5.1 | Traefik 3.7-supported Standard version |
| Traefik | 3.7.10 / chart 41.2.0 | Gateway API 1.5.1; ADR-0043 trusted PROXY-v2/external-L4-only origin; chart-41 render migration |
| Caddy client-address handling | 2.11.4 | trusted-proxy strict parsing; internal exact client address overwrite; caller headers untrusted |
| Helm | 4.2.4 | render/schema/policy compatibility |
| Kyverno | 1.18.2 | Kubernetes 1.35; `policies.kyverno.io/v1` CEL types; verifies current Cosign digest/signature/provenance/SBOM evidence; CI/render rejects legacy `kyverno.io/v1` ClusterPolicy/Policy and `kyverno.io/v2` CleanupPolicy for new production controls |
| OpenTelemetry Collector Contrib | 0.157.0 | Kubernetes 1.35; internal OTLP; approved processors/exporters; exact read-only pod-log mount; finite memory/queues; no public receiver |
| Spring Boot tracing | 4.1.0 starter/OpenTelemetry path | OTLP to Collector; W3C propagation; trace/baggage not authority; no prohibited PII/secret attributes |
| Prometheus | 3.13.2 LTS | scrape Spring Boot actuator/platform targets; low-cardinality labels; management endpoints private |
| Grafana Loki | 3.7.4 | Collector log export; single-binary/non-HA in single-server; bounded storage/retention; ADR-0031 redaction/canaries |
| Grafana Tempo | 3.0.2 | OTLP-compatible trace backend; monolithic/non-HA in single-server; no extra Tempo Kafka in this profile |
| Grafana | 13.1.3 | compatible Prometheus/Loki/Tempo data sources; private admin/access controls |
| Alertmanager | 0.33.1 | Prometheus alert routing; local-host loss supplemented by independent external monitor |
| External black-box monitoring | provider TBD | must be outside production-host failure domain and exercise approved public edge without creating secret/public-bypass risk |
| `production-single-server` management overlay | host-supported WireGuard | selected host OS/kernel/firewall; minimal per-device peers; public SSH denied |
| `production-single-server` human access | host OpenSSH + FIDO2 + JIT + audit | exact package pin; WireGuard separate; no root/password/shared key |
| `production-ha` human access | Teleport 18.10.0 | JIT/SSO/WebAuthn/session-audit evidence |
| OpenBao | 2.6.1 | exact secret authority; unchanged by current single-server/network/observability/DevSecOps decisions |
| Caddy/Coraza/CRS | 2.11.4 / 3.7.0 / 4.25.1 LTS | coraza-caddy 2.5.0; combined image/rules tests |
| Argo CD | 3.4.2 | security-patched line; reconciliation/rollback validation |

Trivy and OWASP Dependency-Check are not current baseline components. Under ADR-0045 they are reconsidered only if a distinct coverage gap is evidenced and the compatibility/ownership/exception model is reviewed.

## Upgrade and profile-validation rule

An upgrade or initial profile approval is complete only after the affected set proves, as applicable:

- official upstream support/security relationship;
- rendered manifest/schema/policy compatibility;
- service contract/build compatibility;
- Gitleaks current-tree/Git-history behavior remains blocking and redacted;
- Semgrep rule compatibility and positive/negative fixtures remain high signal;
- OSV-Scanner declared/locked dependency parsing/advisory behavior remains compatible with the owning service's dependency evidence and remains separate from final-artifact authority;
- Syft final-image CycloneDX output remains consumable by the selected Grype/Cosign/Kyverno chain;
- Grype scanner/feed freshness and ADR-0035/0038 severity/exception behavior remain correct;
- Cosign signature/provenance/signed-SBOM evidence remains verifiable by Kyverno;
- workload identity/mTLS positive/negative paths;
- public client-address trust and exact/aggregate quota behavior when edge/quota components are affected;
- management-only reachability/public-SSH denial when host/network components change;
- Kyverno CEL policy API compatibility and explicit legacy-type rejection;
- telemetry OTLP/scrape/log/trace compatibility, PII redaction, bounded cardinality/queues/storage, backend-outage behavior, and Collector mount/RBAC security when observability changes;
- independent external host-down alert path still works if local stack is lost;
- load/latency/resource comparison for request-path and telemetry components;
- selected-profile Kafka/Redis/PostgreSQL failure/recovery behavior without false HA claims;
- HIBP corpus acquisition/hash/freshness and Xerial/native/dataset-format compatibility when Compromised Password changes;
- backup/PITR/restore evidence where relevant;
- early OSV dependency advisory + final-artifact Grype vulnerability/advisory correlation remain distinct and operational;
- safe rollback or explicit fail-forward;
- for single-server, simultaneous CPU/memory/IO/network/cardinality/storage evidence with >=30% validated headroom and no security/observability bypass.

A newer release is not automatically preferred. Unsupported/EOL releases are not eligible merely because an old baseline named them.
