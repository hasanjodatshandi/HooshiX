# Security Verification Matrix

- **External reference baseline:** OWASP ASVS 5.0.0 as reviewed in current sources.
- **Repository target:** applicable ASVS Level 2 plus stricter HooshiX controls.

This matrix maps material security properties to executable evidence. A documented control without executed evidence is `NOT VERIFIED`.

| ID | Security property | Required evidence |
| --- | --- | --- |
| SEC-001 | Browser receives no provider/internal access/refresh tokens | BFF cookie/token-custody tests + browser storage/network negatives |
| SEC-002 | OIDC state/nonce/PKCE/replay/redirect integrity | protocol positive/negative tests |
| SEC-003 | External identity binds issuer+subject, not email-only | collision/link tests |
| SEC-004 | Active TOTP cannot be downgraded to weaker factor | MFA regression/negative tests |
| SEC-005 | Tenant authority/data does not cross tenant boundary | JWT/context + Authorization + forced-RLS/cross-tenant pool negatives |
| SEC-006 | Authorization failure never fabricates ALLOW | deny/error/timeout/breaker/overload tests; no cache/retry/stale fallback |
| SEC-007 | Service database/credential isolation | cross-service CONNECT/object/role negatives |
| SEC-008 | Secrets stay out of Git/image/values/log/trace/metric/CI | Gitleaks current-tree/history scan + secret/render/static/runtime canaries; real exposed credentials revoked/rotated |
| SEC-009 | Workload identity/mTLS/NetworkPolicy least privilege | wrong-SA/plaintext/unapproved-edge negatives |
| SEC-010 | Edge/WAF path cannot be bypassed | direct origin/BFF/Traefik->BFF negatives |
| SEC-011 | Trusted client address cannot be forged | external-L4 PROXY v2 + exact trusted CIDRs + forged header/untrusted PROXY/proxy-address negatives |
| SEC-012 | Human privileged access is attributable/JIT/phishing resistant | WireGuard/FIDO2/JIT/audit/break-glass tests |
| SEC-013 | Supply chain admits only reviewed signed/provenanced/SBOM artifacts | Syft final-image CycloneDX + Grype final-artifact decision + Cosign wrong digest/signer/provenance/SBOM negatives + Kyverno admission negatives |
| SEC-014 | OpenBao remains secret authority | snapshot/restore/unseal/ESO/local-key tests; no plaintext/Git fallback |
| SEC-015 | Notification ambiguity does not create blind duplicate send | provider ambiguity/reconciliation/idempotency tests |
| SEC-016 | Restored data does not revive erased/illegal authority | PITR + erasure/legal-hold reconciliation before traffic |
| SEC-017 | Compromised-password screening uses approved offline source and fails closed | HIBP SHA-1 source/provenance/freshness/SQLite integrity/no-runtime-provider/false-clean negatives |
| SEC-018 | Semantic quota exact client identity resists collateral aggregate lockout | `/32`/`/128` hard-gate + separate `/24`/`/64` pressure; NAT/campus/VPN/IPv6 tests |
| SEC-019 | Semantic quota resists common-mode host clock step | wall-vs-monotonic guard + app/Redis skew + common-forward/backward-step + boot-sync + 60s re-arm tests |
| SEC-020 | Semantic quota resists high-cardinality Redis exhaustion | unique-subject/address flood + bounded allocation guard + >=30% memory reserve + no eviction/OOM + `QUOTA_CAPACITY_UNHEALTHY` evidence |
| SEC-021 | Quota failure remains distinct from user denial | time/capacity/transport unavailability mapped separately from 429; no fail-open/local fallback |
| SEC-022 | Kyverno new production controls use stable CEL APIs | CI/render rejects legacy ClusterPolicy/CleanupPolicy; CEL positive fixtures; wrong signer/provenance/SBOM/security-context negatives |
| SEC-023 | Day-One telemetry contains no prohibited secrets/PII/high-cardinality identity | source/static + Collector redaction + canary + Loki/Tempo/Prometheus/Grafana query negatives |
| SEC-024 | Trace/baggage/correlation cannot become security authority | forged trace/baggage tests prove no authN/authZ/tenant/quota/idempotency/audit effect |
| SEC-025 | Telemetry ingress/storage cannot create public/host compromise path | private OTLP/scrape endpoints + wrong-workload denial + exact read-only Collector log mount + no broad hostPath/host-network/privilege |
| SEC-026 | Observability failure does not weaken correctness/security | Collector/Loki/Tempo/Prometheus outage/pressure tests; ordinary requests continue where safe; required audit remains durable/off-host |
| SEC-027 | Total single-host loss is externally detectable | independent external black-box monitor remains alert-capable while local stack is unavailable |
| SEC-028 | Single-server capacity does not force security downgrade | simultaneous app+DB+Redis+Kafka+mesh+WAF+Kyverno+OpenBao+observability load with >=30% headroom and no bypass |
| SEC-029 | Email identity comparison/delivery representation remains current product rule | case-only uniqueness/login/reservation + delivery-preservation tests |
| SEC-030 | A committed secret remains detectable after deletion from the latest tree | Gitleaks synthetic commit-then-delete history fixture + fully redacted CI output |
| SEC-031 | Dependency integrity, early dependency advisory, and final-artifact vulnerability authority cannot be conflated | separate Gradle verification failure, OSV locked-dependency advisory finding, and Syft/Grype vulnerable final-artifact fixture |
| SEC-032 | Required DevSecOps evidence cannot be bypassed by scanner/feed outage or stale data | Gitleaks/Semgrep/OSV/Grype/Cosign/Kyverno failure/freshness negatives under ADR-0035/0038/0045 |
| SEC-033 | Agent context cannot silently outrank current Git, expose mutation authority, accept stale/ambiguous narrow scope, forge post-merge continuity, or turn ChatGPT Web access into public/general host authority | clean/dirty bootstrap provenance + route escalation + tracked-file bounds/sensitive-name exclusion + work/post-merge provenance negatives + read-only stdio tool-list/unknown-write negatives + MCP entrypoint launched from non-repository CWD + ADR-0047 tunnel review proving stdio child/no HooshiX listener/restricted runtime credential/no shell or arbitrary filesystem tool |

## Security-gate rule

`production-single-server` lowers infrastructure availability only. It does not weaken MFA, Authorization, RLS, OpenBao, WAF, trusted client identity, semantic quota safety, source/secret/dependency-advisory scanning, final-artifact vulnerability policy, signing/provenance/SBOM admission, audit, or telemetry privacy.

ADR-0044 ordinary telemetry is best-effort/bounded. Required security/privileged audit is separate authoritative evidence and cannot be silently reclassified as Loki/Collector telemetry.

ADR-0045 owns the selected DevSecOps tool responsibility map. OSV-Scanner is early declared/locked dependency advisory feedback; Syft+Grype own final-image release/deployed-artifact vulnerability evidence. Trivy and OWASP Dependency-Check are not missing required controls in the current baseline; introducing them requires a reviewed distinct-coverage decision.

ADR-0046 keeps current Git authority above derived context/checkpoints/model memory. Context retrieval being tracked-file-only does not prove the repository is secret-free; Gitleaks remains the required committed-secret control. Post-merge context evidence must be path-confined, same-PR linked, reachable from current main, and reproducible from the exact Git diff. The Context MCP adapter has no write/mutation/network-listener authority in v1.

ADR-0047 permits an external OpenAI tunnel-client to bridge ChatGPT Web to the unchanged stdio MCP adapter. The bridge does not authorize a HooshiX public MCP listener, general shell/filesystem access, Git mutation, or long-lived admin credential. Real tunnel-client/runtime-key/ChatGPT discovery evidence remains environment-specific and is not proven by repository documentation.

A failed applicable security gate blocks the dependent merge/promotion boundary until remediation and revalidation.
