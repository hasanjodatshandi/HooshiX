# Security Architecture — Current State

Security is layered and fails closed when identity assurance, authorization, or security-significant dependency state cannot be proven. No control substitutes for another.

## 1. Tenant and identity trust

Current tenant model:

```text
Global User
  -> TenantMembership
      -> Tenant
```

A user may belong to multiple tenants. Trusted active tenant/membership comes from validated authenticated context; caller-controlled headers never establish tenant trust.

Every tenant-owned row has non-null `tenant_id` unless explicitly global. Production tenant-owned PostgreSQL tables additionally use `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`; runtime roles are non-owner `NOSUPERUSER NOBYPASSRLS` and cannot access another service database. Application/repository tenant checks remain mandatory. Tenant database context is parameterized and transaction-local under the canonical SQL/Flyway standard; session-scoped tenant state on pooled connections is prohibited, missing/malformed context fails closed, and cross-tenant pooled-connection reuse after commit/rollback must prove no context leakage.

Tenant lifecycle is `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DELETING`, `DELETED`. Creator becomes initial owner. Tenant + creator membership + audit + owner-provisioning Outbox commit locally; activation waits for idempotent Authorization acknowledgement.

## 2. Logical deletion, retention, erasure, legal hold

Logical deletion records `deleted_at`, `deleted_by`, and stable `deletion_reason`; `deleted_at` is authoritative. Normal queries exclude deleted rows by persistence contract. Restoration, include-deleted access, purge, and legal-hold actions are explicit, authorized, and audited.

Default retention is 360 days; expiry creates eligibility, not automatic destruction. Physical purge is unavailable from ordinary repositories/APIs and must be authorized, idempotent, observable, tenant-safe, and blocked by legal hold. Generated technical/security/event/audit IDs are never reused. Domain `ON DELETE CASCADE` is prohibited by default unless a current aggregate decision proves safety.

ADR-0028 governs irreversible data-subject erasure. Identity coordinates a non-PII `erasure_request_id`; each service erases/anonymizes its owned copies and returns durable non-PII evidence. The required participant registry is server-owned/versioned and cannot be selected or reduced by the caller. Initial required participants are Identity, Authorization, Notification, and Web BFF.

Coordination uses local Transactional Outbox + versioned Kafka/Protobuf events and idempotent participant Inbox/receipt processing rather than availability-coupled synchronous fan-out. Critical publication/Inbox-dedup evidence follows the existing 35-day recovery horizon. Restore procedures replay erasure/legal-hold decisions and reconcile required participant receipts before traffic.

Legal hold is an explicit durable audited ledger with `ACTIVE -> RELEASED`, actor, authority/reference, timestamps, policy version, and integrity evidence. Ordinary erasure callers cannot create, release, omit, or bypass a hold. Crypto-shredding is valid only for separately key-enveloped material with destroyable keys and does not replace erasing ordinary relational PII.

## 3. Browser/BFF/OIDC security

Browser login uses OIDC Authorization Code + PKCE S256 through Web BFF. BFF stores single-use `state`, `nonce`, and PKCE verifier server-side for <=10m, validates exact redirect, issuer/audience/signature/timestamps, and only permits bounded same-origin relative post-login destinations.

BFF owns provider-protocol validation for browser login/link. Identity does not call Google on that path and does not receive provider authorization codes or provider tokens. After successful validation, BFF invokes the typed Identity gRPC contract over its authorized workload identity with a cryptographically random, short-lived, single-use `evidence_id`, validated canonical `(issuer, subject)`, stable request identity, and only explicitly versioned non-secret evidence metadata. Identity consumes the evidence atomically; email equality never auto-links.

The browser receives no provider token, Identity access/refresh token, internal gRPC credential, trusted role list, or permission list.

Primary cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
no Domain
```

Session ID rotates after login, MFA completion, tenant switch, recovery, and elevation. Server-side BFF session state uses an ACL-isolated Redis namespace; idle <=7d, absolute <=30d. Any retained Identity refresh credential is AES-256-GCM encrypted with a BFF-specific local key ring and never stored raw in browser/Redis/telemetry.

A password-success response that requires MFA is only pre-auth state. BFF does not establish the completed authenticated browser session/cookie until Identity confirms the MFA challenge and corresponding session/token issuance.

Unsafe cookie-authenticated requests require trusted Origin + session-bound synchronizer CSRF token. Fetch Metadata is defense in depth. Credentialed wildcard/reflected CORS is prohibited; same-origin is preferred. CSP/HSTS/nosniff/referrer/Permissions-Policy and frame protection are centrally tested.

## 4. Passwords, external identity, and MFA

Passwords cross one explicit Application security port, are never reversibly encrypted/persisted/logged/emitted, and use the current Technology Baseline Argon2id profile:

```text
m=19 MiB, t=2, p=1
16-byte random salt
>=32-byte derived hash
15..128 Unicode code points
NFC before hashing
```

No arbitrary composition rule or periodic forced rotation. Create/change/reset checks the compromised-password service and hashing/verification uses a bounded CPU/memory bulkhead.

The compromised-password contract is k-anonymous/digest-prefix style: raw password never leaves Identity, including over mTLS. The remote check uses a 900ms overall deadline, one attempt, no automatic retry, finite cancellation/concurrency bounds, and fail-closed behavior. An unchecked password is not committed. The remote check occurs outside an Identity DB transaction and dependency failure remains a distinct availability/security error rather than fabricated “compromised” evidence.

External identities bind by stable `(issuer, subject)`. Email equality alone never auto-links an identity. Provider validation/handoff follows the BFF-only evidence contract in §3 and ADR-0012.

TOTP v1:

- HMAC-SHA-256, 6 digits, 30s, ±1 step;
- issuer `SajTech`;
- local versioned AES-256-GCM secret key ring sourced through OpenBao/External Secrets;
- 10 independent 80-bit recovery codes shown once and stored only as domain-separated HMAC-SHA-256;
- enroll/disable/replace/recovery requires authentication age <=5m;
- no trusted-device bypass.

When active TOTP exists, successful password verification issues only a short-lived pre-auth MFA challenge. No access or refresh credential is issued until the same challenge is successfully completed with TOTP or one valid single-use recovery code. MFA challenge completion is server-owned, single-use, semantic-quota protected, and non-enumerating.

Iran SMS MFA uses IPPanel Webservice mode only after ADR-0024 quota evidence, provider contract/credentials, Notification encrypted exact-content lifecycle, MFA/session controls, and delivery/ambiguity tests pass. Local logging SMS is never a production fallback.

## 5. Authorization ownership and runtime

Authorization Service owns roles, role permissions, membership-role assignments, direct grants/denies, evaluation, management audit, and private persistence. Permission meaning/resource/domain invariants remain owned by the protected bounded context.

Evaluation:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

No role inheritance or wildcard permission assignments in v1. Final enforcement is always in the resource-owning service and never overrides tenant ownership/domain invariants.

Current online `CheckPermission` contract:

```text
deadline: 300 ms
attempts: 1
wait-for-ready: off
retry: none
permission-result cache: none
Kafka invalidation: none
stale fallback: none
fail closed
```

Authoritative deny -> `PERMISSION_DENIED`; dependency/open-breaker failure -> `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; healthy saturation -> `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED`, mapped by callers to fail-closed dependency unavailability.

Safe local JWT/claim/tenant/syntax prechecks may reject invalid traffic but never grant access. Bloom filters, signed permission lists, caches, stale allow, or duplicate routine BFF checks are not authoritative.

Production target:

- >=3 replicas, PDB/topology spread;
- global + per-caller-principal bounded concurrency;
- <=25ms server queue wait;
- p95<=100ms / p99<=200ms; availability >=99.95% rolling 30d;
- Hikari acquisition p99<25ms under validated target load;
- >=2x projected peak with >=30% validated resource/database headroom.

Breaker opening follows ADR-0032; recovery follows ADR-0036: bounded de-correlated reopen backoff, one real half-open probe in flight, three consecutive infrastructure-successful probes to close, immediate reopen on infrastructure failure/overload, no health-endpoint-authorized closure, no commercial-tier variation.

## 6. Token signing and local verification

Identity signs access tokens locally using RSA-3072/RS256 private keys sourced from OpenBao/External Secrets and mounted read-only. Key IDs are immutable random values; normal rotation is 90 days with next-key prepublication and >=24h previous public-key overlap.

The v1 access-token claim allow-list is:

```text
standard: iss, aud, sub, jti, iat, exp
private:  tenant_id, membership_id, sid
```

Roles, permissions, `authorization_version`, or equivalent permission snapshots are not authorization authority in the token. `aud` is the exact intended service identifier; wildcard audiences are prohibited. The Identity issuer is typed deployment configuration, with initial production logical value `https://identity.sajtech.internal` unless the reviewed environment configuration supplies the final value before rollout.

Verifiers use a bounded non-secret GitOps public JWK bundle locally. Normal verification makes no Identity/OpenBao/remote-JWKS call and accepts only approved algorithm/issuer/audience/key IDs. Unknown key, algorithm confusion, invalid issuer/audience/time/signature fail closed.

## 7. Semantic security quotas

ADR-0024 is the single current quota decision. The operation-owning service enforces its own quota in an ACL-isolated `security-redis` namespace; no quota microservice exists.

```text
1 primary + 2 replicas + 3 Sentinel voters
TLS + ACL isolation
noeviction
75ms evaluation budget
1 attempt / no retry
fail closed on security-significant dependency failure
```

Atomic multi-dimension enforcement uses reviewed versioned Redis Function/Lua logic. Keys use purpose-separated HMAC pseudonyms. Time uses trusted application wall time + Redis `TIME`, <=2s skew, `effective_now=min(...)`, monotonic stored time, and `QUOTA_TIME_SOURCE_UNHEALTHY` fail-close. TTL expiry alone never resets a security budget.

Authentication anti-lockout: source dimensions may block before credential work; subject/account failure pressure is charged on failed credentials but alone cannot reject a subsequently proven correct credential after source controls allow evaluation.

Registration register/resend/confirm, login, external-identity login/link, tenant create/invite, and MFA lifecycle/recovery are all subject to versioned service-owned semantic policy where defined. Caller requests cannot choose quota capacity/refill/TTL/security policy.

## 8. Workload identity, mTLS, network security

Production application workloads use dedicated Kubernetes ServiceAccounts and Istio Ambient STRICT mTLS. Kubernetes `default` ServiceAccount is prohibited. AuthorizationPolicy is default-deny/identity-based; NetworkPolicy is independent defense in depth. New/changed service edges require positive and negative identity/policy tests.

Istio does not replace end-user authorization or native Kafka/PostgreSQL/Redis authentication/ACLs.

Authentication dependency ownership follows the machine-readable registry: Web BFF owns the provider-protocol edge to Google and the trusted evidence/session edge to Identity; Identity does not own a direct Google login/link dependency.

## 9. Secrets and cryptographic material

OpenBao 2.6.1 is the authoritative secret source; External Secrets is the normal Kubernetes materialization boundary. Secret values never enter Git, images, Helm/Kustomize values, logs, traces, or metrics.

Rotating key rings are mounted read-only; key purposes are separated and key IDs never rebind to new bytes. Notification/BFF use purpose-specific local AES-256-GCM key rings. Normal application hot paths do not make routine OpenBao RPCs.

## 10. Public edge and DDoS

Canonical path:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> redundant external L4 load balancing
-> Traefik
-> Caddy + Coraza WAF
-> Web BFF
```

Direct Internet/Traefik application access to BFF is denied through route + NetworkPolicy + Istio policy. WAF uses pinned CRS, PL1, DetectionOnly tuning before blocking, narrow exceptions, bounded body inspection, and PII-safe telemetry. WAF never substitutes for authentication, authorization, quotas, validation, output encoding, or upstream volumetric protection.

## 11. Supply chain and vulnerability response

Final release images use immutable digests, signed CycloneDX SBOMs, signed provenance, Cosign signatures/attestations, and production admission verification through HA Kyverno. Deployed SBOM/digest inventory is continuously correlated with approved vulnerability/advisory/threat-intelligence inputs.

Admission policy authoring is restricted to tightly controlled GitOps/CI identities; ordinary application/service identities cannot create or modify cluster-scoped admission policy. Kyverno CEL HTTP context is disabled when unnecessary. Any approved external context lookup uses an exact versioned destination/purpose allow-list, blocks loopback/link-local/cloud-metadata/unreviewed private/arbitrary caller-controlled targets, forwards no credentials to arbitrary destinations, bounds response/time/failure semantics, and is covered by positive/negative SSRF and NetworkPolicy tests. External-context failure never silently becomes admission allow.

Critical/known-exploited production findings target immediate incident handling and <=24h mitigation; High target <=48h. Exceptions are exact, owned, reviewed, expiring; expiry stops new promotion and escalates running exposure. Scanner/feed success is never proof of zero unknown vulnerabilities and never authorizes an unsigned artifact.

## 12. Runtime/container security

Production application workloads use immutable digest, non-root, `allowPrivilegeEscalation=false`, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem where compatible, bounded resources, safe probes/shutdown, dedicated ServiceAccount, deny-by-default NetworkPolicy, and least-privilege Istio authorization.

Privileged containers, host networking, `hostPath`, extra capabilities, or relaxed security context require an explicit current security decision plus automated validation.

## 13. Logging/PII and privileged access

Logging is structured and allow-list based. Raw passwords/OTP/recovery codes/tokens/cookies/keys/secrets/payment data/high-risk identity data/full sensitive payloads/SQL binds/complete gRPC metadata/Kafka headers/unreviewed provider payloads are prohibited.

Ordinary PII requires an approved purpose and masking/tokenization or managed-key HMAC pseudonymization where correlation is needed. Input-derived fields are CR/LF-safe; exception/provider text is untrusted until sanitized. Metric labels remain low-cardinality and exclude business/security IDs, trace IDs, raw URLs, and free-form errors.

Static Semgrep rules, pipeline redaction, synthetic canary sink tests, and runtime leak detection provide defense in depth.

Human production access uses Teleport JIT SSO/WebAuthn, approvals, short TTL, least privilege, and recorded/audited sessions. Standing admin/root/database-superuser/shared credentials are prohibited.

## 14. Verification

Security-impacting changes run applicable cross-tenant/RLS negatives including pooled-connection tenant-context reuse, registration challenge/canonical-contact tests, authentication/OIDC evidence replay/provider-token isolation/MFA pre-auth/session tests, compromised-password raw-value non-egress/deadline/fail-closed tests, exact JWT claim/audience tests, Authorization deny/outage/overload/recovery, semantic-quota failure/time tests, erasure required-participant/legal-hold/Kafka-replay/restore tests, workload identity/mTLS/NetworkPolicy positives and negatives, WAF/bypass/DDoS controls, secret/key rotation/recovery, PII/log-injection canaries, artifact admission/vulnerability gates including policy-authoring RBAC and policy-engine SSRF negatives, privileged-access expiry/direct-access denial, and restore/erasure reconciliation.