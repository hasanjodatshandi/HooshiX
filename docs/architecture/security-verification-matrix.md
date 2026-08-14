# Security Verification Matrix

- **External reference baseline:** OWASP ASVS 5.0.0, latest stable release as reviewed on 2026-08-13.
- **Repository target:** applicable ASVS Level 2 controls, plus stricter current HooshiX controls where architecture/security decisions require them.
- **Evidence rule:** this matrix maps verification intent; it is not itself evidence that implementation is secure.

Use versioned ASVS identifiers (`v5.0.0-...`) in test/evidence systems so future ASVS revisions do not silently change the referenced control.

| ID | HooshiX control family | Required evidence |
| --- | --- | --- |
| SEC-001 | New trust boundaries, credential flows, file/webhook ingestion and sensitive processing receive threat/abuse modeling | reviewed threat model + mitigations + owners |
| SEC-002 | Injection prevention: parameterized SQL, safe command construction, contextual output encoding | SAST/Semgrep + negative integration tests |
| SEC-003 | Boundary validation plus durable domain/database invariants | unit/application/integration constraint tests |
| SEC-004 | Browser session security: BFF token custody, secure `__Host-` cookie, CSRF/origin validation, rotation/termination | browser security tests + BFF integration tests |
| SEC-005 | Authorization and tenant isolation: deny-by-default final resource enforcement, exact `CheckPermission`/`CheckPlatformPermission` fail-closed contracts, permission-catalog lifecycle/non-reuse, tenant-management privilege-escalation prevention, atomic owner safety, platform no-bypass semantics, forced RLS, and transaction-local pool-safe tenant DB context | authorization contract/matrix + catalog tests + management/platform negative tests + owner-race tests + cross-tenant/RLS pooled-context negatives + outage/overload tests |
| SEC-006 | OAuth/OIDC: exact redirect allow-list, PKCE, state, nonce, issuer/audience/signature verification | protocol/browser replay and tampering tests |
| SEC-007 | Cryptography/key lifecycle: approved algorithms, CSPRNG, purpose-separated keys, rotation/recovery | config/crypto tests + rotation evidence |
| SEC-008 | Service communication: dedicated workload identity, strict mTLS, least-privilege Istio authorization and NetworkPolicy | positive/negative deployment policy tests |
| SEC-009 | Secrets/configuration: OpenBao/ESO, no Git/image/log leakage, safe production startup defaults | secret scan + manifest tests + rotation/recovery evidence |
| SEC-010 | API abuse controls: schema validation, payload bounds, method allow-list, semantic quotas, bounded bulk mutation cost, idempotency/replay behavior | API/abuse/concurrency tests including Authorization delta-cost/no-refund/all-or-none mutation tests |
| SEC-011 | Sensitive data: classification, minimization, encryption, redaction, retention/erasure/legal-hold behavior including removal of erased subject tenant/platform authority | data inventory + erasure/restore/redaction/authorization-revocation tests |
| SEC-012 | Security logging/audit: structured allow-list, CR/LF safety, no raw sensitive data, durable required audit, audited debug elevation | Semgrep + canary sink tests + Authorization audit reason/retention/PII tests + access audit evidence |
| SEC-013 | Browser hardening: CSP/security headers, safe redirects, no unsafe raw HTML, no private service-worker caching | header/browser/source tests |
| SEC-014 | Supply chain/admission: pinned dependencies/actions, dependency verification, SBOM, vulnerability correlation, provenance/signature, least-privilege policy authoring, and bounded policy-engine egress/SSRF controls | CI/release/admission evidence + policy RBAC + policy-engine SSRF negative tests |
| SEC-015 | Runtime/container hardening: non-root, no privilege escalation, dropped capabilities, RuntimeDefault seccomp, read-only FS where possible | rendered manifest/policy tests |
| SEC-016 | Recovery/security continuity: backups, restore, key/credential rotation, revoked/erased data reconciliation | scheduled restore/rotation exercises |
| SEC-017 | Production human access: JIT least privilege, phishing-resistant MFA, approvals, short TTL, session/audit evidence | access-plane policy + session evidence |
| SEC-018 | External providers/webhooks/files: bounded parsing, signature/auth verification, SSRF destination control, ambiguity/replay handling | provider/webhook/file security tests |

## Security-gate rule

A production-impacting change is not security-verified merely because this matrix is present. Every applicable row requires concrete automated or reviewed evidence tied to the PR/release artifact. A row marked not applicable requires a written scope reason; Critical/High findings and expired exceptions follow ADR-0035/ADR-0038.

## Maintenance

- Security co-reviews changes that weaken or reclassify a control.
- ASVS updates are evaluated explicitly; do not silently relabel versioned control identifiers.
- HooshiX may be stricter than ASVS where current architecture mandates stronger behavior.
- Do not claim complete ASVS certification from a partial repository mapping.
