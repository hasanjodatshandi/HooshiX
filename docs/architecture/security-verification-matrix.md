# Security Verification Matrix

- **External reference baseline:** OWASP ASVS 5.0.0, latest stable release as reviewed on 2026-08-13.
- **Repository target:** applicable ASVS Level 2 controls, plus stricter current HooshiX controls where architecture/security decisions require them.
- **Evidence rule:** this matrix maps verification intent; it is not itself evidence that implementation is secure.

Use versioned ASVS identifiers (`v5.0.0-...`) in test/evidence systems so future ASVS revisions do not silently change the referenced control.

| ID | HooshiX control family | Required evidence |
| --- | --- | --- |
| SEC-001 | New trust boundaries, credential flows, file/webhook/source ingestion and sensitive processing receive threat/abuse modeling | reviewed threat model + mitigations + owners |
| SEC-002 | Injection prevention: parameterized SQL, safe command construction, contextual output encoding | SAST/Semgrep + negative integration tests |
| SEC-003 | Boundary validation plus durable domain/database/invariant enforcement | unit/application/integration constraint tests |
| SEC-004 | Browser session security: BFF-only token custody, HMAC-located server session, secure `__Host-` cookie, exact CSRF/Origin/Fetch-Metadata enforcement, atomic no-grace rotation/termination, user-session revocation index and idle/absolute bounds | browser security tests + Redis/BFF integration/concurrency tests |
| SEC-005 | Authorization and tenant isolation: deny-by-default final resource enforcement, exact `CheckPermission`/`CheckPlatformPermission` fail-closed contracts, permission-catalog lifecycle/non-reuse, tenant-management privilege-escalation prevention, atomic owner safety, platform no-bypass semantics, forced RLS, and transaction-local pool-safe tenant DB context | authorization contract/matrix + catalog tests + management/platform negative tests + owner-race tests + cross-tenant/RLS pooled-context negatives + outage/overload tests |
| SEC-006 | OAuth/OIDC and token brokerage: exact redirect/return-target canonicalization, PKCE S256, exact state/nonce/verifier entropy, pre-auth HMAC/TTL/single-use/live bound, issuer/audience/signature verification, trusted evidence, server-owned route->audience mapping and arbitrary-audience rejection | protocol/browser replay/tampering/open-redirect + Identity broker contract tests |
| SEC-007 | Cryptography/key lifecycle: approved algorithms, CSPRNG, purpose-separated keys, BFF AES-256-GCM refresh encryption/AAD, 90d rotation, dependent-session+7d old-key retention, atomic reload, <=1h stale snapshot | config/crypto tests + rotation/staleness/recovery evidence |
| SEC-008 | Service communication: dedicated workload identity, strict mTLS, least-privilege Istio authorization, deny-by-default NetworkPolicy and BFF exact egress allow-list including only registered Reference Data/resource/provider edges | positive/negative deployment policy + wrong-workload/arbitrary-egress tests |
| SEC-009 | Secrets/configuration: OpenBao/ESO, no Git/image/log leakage, safe production startup defaults | secret scan + manifest tests + rotation/recovery evidence |
| SEC-010 | API abuse controls: OpenAPI/schema validation, Web BFF body/header/multipart bounds, semantic quotas including exact OIDC start/callback buckets, max-five pre-auth, bounded bulk mutation cost, idempotency/replay behavior | API/abuse/concurrency/load tests including OIDC quota outage/skew and Authorization delta-cost/no-refund/all-or-none mutation tests |
| SEC-011 | Sensitive data: classification, minimization, encryption, redaction, retention/erasure/legal-hold behavior including removal of erased subject tenant/platform authority and BFF sessions/pre-auth/refresh/session-index state | data inventory + erasure/restore/redaction/authorization/session-revocation tests |
| SEC-012 | Security logging/audit: structured allow-list, CR/LF safety, no raw sensitive data or raw session/pre-auth identifiers, durable required audit, audited debug elevation | Semgrep + canary sink tests + Authorization audit reason/retention/PII tests + BFF token/session leak negatives + access audit evidence |
| SEC-013 | Browser hardening: same-origin-only v1, mandatory Fetch Metadata on unsafe production browser requests, exact CSP without unsafe-inline/eval, HSTS/nosniff/referrer/Permissions-Policy, private no-store for auth/session/admin, ADR-0041 deterministic ETag/public one-hour cache only for anonymous safe Reference Data, safe redirects, no unsafe raw HTML/private service-worker caching | header/browser/source/OpenAPI/cache tests |
| SEC-014 | Supply chain/admission: pinned dependencies/actions, dependency verification, SBOM, vulnerability correlation, provenance/signature, least-privilege policy authoring, and bounded policy-engine egress/SSRF controls | CI/release/admission evidence + policy RBAC + policy-engine SSRF negative tests |
| SEC-015 | Runtime/container hardening: non-root, no privilege escalation, dropped capabilities, RuntimeDefault seccomp, read-only FS where possible, critical-service replica/PDB/HPA gating | rendered manifest/policy tests |
| SEC-016 | Recovery/security continuity: backups, restore/rebuild, immutable-reference release recovery, key/credential rotation, revoked/erased data reconciliation | scheduled restore/rebuild/rotation exercises |
| SEC-017 | Production human access: JIT least privilege, phishing-resistant MFA, approvals, short TTL, session/audit evidence | access-plane policy + session evidence |
| SEC-018 | External providers/webhooks/files/offline reference sources: bounded parsing, signature/auth/integrity/provenance verification as applicable, SSRF destination control, ambiguity/replay handling; Web BFF arbitrary Internet egress prohibited except configured Google OIDC endpoints; Reference Data production runtime has no standards-source Internet egress | provider/webhook/file/source security + egress/SSRF tests |
| SEC-019 | Compromised Password privacy/reference-data boundary: only 20-bit SHA-256 prefix leaves Identity; exact full digest remains local; SQLite dataset path/config is server-owned, immutable/read-only/query-only, no write/DDL/ATTACH/extension loading, no external provider/Internet lookup, no subject identity, no full-dataset JVM cache, bounded prefix/result, fail closed on corruption/unavailability, Xerial+bundled SQLite advisory coverage | Identity/service contract tests + malformed/path/URI/SQL/write/extension negatives + workload/egress policy tests + dataset compiler/integrity/bound tests + log/hash leak negatives + dependency/SBOM/native advisory evidence |
| SEC-020 | Reference Data public/internal boundary: only Country/Currency/TimeZone/SupportedLocale typed families; no generic registry/business authority; approved ISO/IANA/stable-CLDR offline import with provenance/integrity/license review; immutable signed-image bundle; no DB/Redis/Kafka/runtime source synchronization; anonymous GET/HEAD creates no session/JWT/CSRF authority; same-origin CORS and mandatory WAF path remain; only BFF workload may initially call internal gRPC; no subject/tenant state; no stale/fabricated BFF fallback | importer/bundle/canonicalization tests + OpenAPI/ETag/cache/CORS/CSRF-negative tests + wrong-workload/egress policy tests + no-subject-state/log/source leak tests + dependency/load/rebuild evidence when implementation trigger is met |

## Security-gate rule

A production-impacting change is not security-verified merely because this matrix is present. Every applicable row requires concrete automated or reviewed evidence tied to the PR/release artifact. A row marked not applicable requires a written scope reason; Critical/High findings and expired exceptions follow ADR-0035/ADR-0038.

ADR-0040's SQLite decision is a narrow immutable reference-data exception. It does not weaken PostgreSQL/RLS/Flyway controls for mutable relational business state. ADR-0041 Reference Data uses no database and does not create another persistence exception.

ADR-0041 implementation/security evidence is required only when its explicit implementation trigger is met and the Reference Data service/facade is included in release scope; architecture documentation alone is not such evidence.

## Maintenance

- Security co-reviews changes that weaken or reclassify a control.
- ASVS updates are evaluated explicitly; do not silently relabel versioned control identifiers.
- HooshiX may be stricter than ASVS where current architecture mandates stronger behavior.
- Do not claim complete ASVS certification from a partial repository mapping.
