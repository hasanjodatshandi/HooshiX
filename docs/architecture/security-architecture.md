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

Authorization owns `tenant_owner` role state. Membership removal never uses a race-prone read-only owner count. Identity first durably records a removal intent, then calls Authorization `PrepareMembershipRemoval` outside the DB transaction with 300ms maximum, one attempt, no retry/cache/fallback. Authorization atomically creates an owner-safety reservation or returns `LAST_TENANT_OWNER`; reservations do not auto-expire into unsafe allow. Identity then commits removal + durable finalize outbox or durably cancels the preparation if removal does not commit. This protocol prevents simultaneous removals from deleting the final owner without duplicating role authority into Identity.

## 2. Logical deletion, retention, erasure, legal hold

Logical deletion records `deleted_at`, `deleted_by`, and stable `deletion_reason`; `deleted_at` is authoritative. Normal queries exclude deleted rows by persistence contract. Restoration, include-deleted access, purge, and legal-hold actions are explicit, authorized, and audited.

Default retention is 360 days; expiry creates eligibility, not automatic destruction. Physical purge is unavailable from ordinary repositories/APIs and must be authorized, idempotent, observable, tenant-safe, and blocked by legal hold. Generated technical/security/event/audit IDs are never reused. Domain `ON DELETE CASCADE` is prohibited by default unless a current aggregate decision proves safety.

ADR-0028 governs irreversible data-subject erasure. Identity coordinates a non-PII UUIDv4 `erasure_request_id`; each service erases/anonymizes its owned copies and returns durable non-PII evidence. The required participant registry is server-owned/versioned and cannot be selected or reduced by the caller. Initial required participants are Identity, Authorization, Notification, and Web BFF.

Self-erasure requires authentication age <=5m and active MFA proof when applicable. It is not accepted while the User retains an ACTIVE/SUSPENDED Membership for a non-DELETED Tenant. The User must first leave Memberships, transfer last ownership, or complete tenant deletion through the owner-safe protocol above; erasure never bypasses last-owner safety. Acceptance atomically makes the User `DELETING`, revokes all RefreshFamilies, revokes pending invitations targeting that User, records audit/request state, and creates the erasure outbox. No remote I/O occurs inside that transaction.

Coordination uses local Transactional Outbox + versioned Kafka/Protobuf events and idempotent participant Inbox/receipt processing rather than availability-coupled synchronous fan-out. Critical publication/Inbox-dedup evidence follows the existing 35-day recovery horizon. Restore procedures replay erasure/legal-hold decisions and reconcile required participant receipts before traffic.

Legal hold is an explicit durable audited ledger with `ACTIVE -> RELEASED`, actor, authority/reference, timestamps, policy version, and integrity evidence. Ordinary erasure callers cannot create, release, omit, or bypass a hold. A hold may block irreversible progress but never reactivates the User or restores sessions. v1 exposes no ordinary self-service erasure undo; irreversible participant work is never cancellable. Crypto-shredding is valid only for separately key-enveloped material with destroyable keys and does not replace erasing ordinary relational PII.

## 3. Browser/BFF/OIDC security

Browser login uses OIDC Authorization Code + PKCE S256 through Web BFF. BFF stores single-use `state`, `nonce`, and PKCE verifier server-side for <=10m, validates exact redirect, issuer/audience/signature/timestamps, and only permits bounded same-origin relative post-login destinations.

BFF owns provider-protocol validation for browser login/link/signup. Identity does not call Google on that path and does not receive provider authorization codes or provider tokens.

After successful provider validation BFF creates trusted evidence:

```text
evidence_id        exactly 256 CSPRNG bits
evidence_issued_at trusted BFF server time after provider validation
request_id         canonical UUIDv4
issuer             canonical validated issuer
subject            validated provider subject
metadata           versioned/bounded optional email + email_verified + name suggestions
```

Identity accepts it only from the authorized BFF workload, for exactly two minutes from `evidence_issued_at` subject to bounded clock tolerance, binds all evidence fields into the idempotency/security fingerprint, and retains spent/replay evidence for >=10m. Equal replay returns the original committed outcome; changed request/payload under the same `evidence_id` returns `OIDC_EVIDENCE_REPLAY`. Browser/provider input cannot extend evidence lifetime.

External identities bind by stable `(issuer, subject)`. Email equality never auto-links. `email_verified=true` may create a verified Contact only if the canonical email is free; collision becomes `ACCOUNT_LINK_REQUIRED`. Missing or `email_verified=false` never creates a Contact automatically. Provider names are suggestions only and never silently complete the Identity profile.

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

Session ID has >=256 bits CSPRNG entropy and rotates after login, MFA completion, tenant switch, recovery, password reset/change where retained, privilege/assurance elevation, and observed MFA-state changes that preserve a current session. Server-side BFF session state uses an ACL-isolated Redis namespace; idle <=7d, absolute <=30d. Any retained Identity refresh credential is AES-256-GCM encrypted with a BFF-specific local key ring and never stored raw in browser/Redis/telemetry.

A successful primary proof that still requires MFA is only pre-auth state. This applies to password **and** trusted Google proof. BFF does not establish either a normal authenticated session or `authenticated_onboarding` until Identity confirms MFA and creates the corresponding Session/RefreshFamily.

After all required factors, a User with no selected active Membership may have only `authenticated_onboarding`: no normal tenant-scoped resource JWT, only the reviewed Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection allow-list. Zero Membership remains onboarding; one valid Membership selects automatically; multiple follow Identity last-valid/explicit selection. Tenant selection rotates BFF session identity.

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

No arbitrary composition rule, password-history blacklist, or periodic forced rotation exists in v1. Local password login has no username: any active verified email or verified E.164 phone Contact may identify the User; primary status is not required. Unverified/removed Contacts do not authenticate, and unknown/no-local-Credential/wrong-password/blocked-account failures are non-enumerating.

Create/change/reset checks the compromised-password service and hashing/verification uses a bounded CPU/memory bulkhead. The compromised-password protocol is an internal k-anonymous SHA-256-prefix contract:

1. NFC normalize and UTF-8 encode inside Identity;
2. compute SHA-256 locally;
3. only the first 20 digest bits / five canonical hex characters leave Identity;
4. parse a bounded suffix + non-negative occurrence-count response;
5. compare the full digest locally.

Raw password/full digest never leave Identity. The remote check uses a 900ms overall deadline, one attempt, no automatic retry, finite cancellation/concurrency bounds, and fail-closed behavior. Malformed/oversized/ambiguous response fails closed. An unchecked password is not committed. The remote check occurs outside an Identity DB transaction.

Forgot/reset Password applies only when an active local Credential already exists and only through the primary verified Contact. Unknown/non-primary/no-local-Credential initiation is caller-visible non-enumerating. Reset cannot create the first local Credential for an external-only User. Recovery uses a purpose-separated eight-digit CSPRNG/HMAC-only challenge with 10m TTL, five failed tries, 60s resend, replacement invalidation and single use. Active TOTP additionally requires TOTP or a recovery code; there is no automated bypass if both password and MFA recovery are lost.

External identity link/unlink requires recent authentication <=5m and current MFA assurance where applicable. Unlinking the last authentication method fails `LAST_AUTHENTICATION_METHOD`. Successful unlink rotates the retained current refresh/session and revokes other families.

TOTP v1:

- HMAC-SHA-256, 6 digits, 30s, ±1 step;
- issuer `SajTech`;
- local versioned AES-256-GCM secret key ring sourced through OpenBao/External Secrets;
- 10 independent 80-bit recovery codes shown once and stored only as domain-separated HMAC-SHA-256;
- enroll/disable/replace/recovery requires authentication age <=5m;
- no trusted-device bypass.

When active TOTP exists, **any** successful primary authentication proof—password or trusted Google evidence—creates only a five-minute, single-use pre-auth MFA challenge with maximum five failed proofs. No completed Session/access/refresh credential is issued until that same challenge is completed with TOTP or one valid recovery code. A new successful primary proof invalidates the previous live challenge. An accepted TOTP timestep cannot be replayed for the same enrollment.

Successful MFA enrollment/disable/replacement/recovery rotates the retained current session/refresh where applicable and revokes other families so sessions established under older assurance state do not silently survive.

Iran SMS MFA uses IPPanel Webservice mode only after ADR-0024 quota evidence, provider contract/credentials, Notification encrypted exact-content lifecycle, MFA/session controls, and delivery/ambiguity tests pass. It is only available when active TOTP is absent; SMS never downgrades an active TOTP requirement.

SMS MFA proof is purpose-separated from all other Identity challenges: exactly eight CSPRNG decimal digits, HMAC-SHA-256 verifier only, no plaintext durable persistence after safe handoff creation, expires no later than the enclosing five-minute pre-auth challenge, maximum five failed proofs across that challenge, 60s resend spacing, replacement invalidation, and single use. Local logging SMS is never a production fallback.

## 5. Authorization ownership and runtime

Authorization Service owns roles, role permissions, membership-role assignments, direct grants/denies, evaluation, management audit, membership-removal owner-safety reservations, Identity-driven tenant/member lifecycle projections, and private persistence. Permission meaning/resource/domain invariants remain owned by the protected bounded context.

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

Identity `PrepareMembershipRemoval` is a separate authoritative-security edge, also 300ms maximum/one attempt/no retry/cache/fallback/fail closed. Its safety result is persisted by Authorization as an idempotent reservation rather than cached/read-only state. Finalize/cancel and owner/member/tenant lifecycle synchronization are durable commands resolved after local Identity intent/Outbox commit.

Production Authorization target:

- >=3 replicas, PDB/topology spread;
- global + per-caller-principal bounded concurrency;
- <=25ms server queue wait;
- p95<=100ms / p99<=200ms; availability >=99.95% rolling 30d;
- Hikari acquisition p99<25ms under validated target load;
- >=2x projected peak with >=30% validated resource/database headroom.

Breaker opening follows ADR-0032; recovery follows ADR-0036: bounded de-correlated reopen backoff, one real half-open probe in flight, three consecutive infrastructure-successful probes to close, immediate reopen on infrastructure failure/overload, no health-endpoint-authorized closure, no commercial-tier variation.

## 6. Token signing, sessions, and local verification

Identity signs access tokens locally using RSA-3072/RS256 private keys sourced from OpenBao/External Secrets and mounted read-only. Key IDs are immutable random values; normal rotation is 90 days with next-key prepublication and >=24h previous public-key overlap.

The v1 access-token claim allow-list is:

```text
standard: iss, aud, sub, jti, iat, exp
private:  tenant_id, membership_id, sid
```

Roles, permissions, `authorization_version`, or equivalent permission snapshots are not authorization authority in the token. `aud` is the exact intended service identifier; wildcard audiences are prohibited. The Identity issuer is typed deployment configuration, with initial production logical value `https://identity.sajtech.internal` unless the reviewed environment configuration supplies the final value before rollout.

Verifiers use a bounded non-secret GitOps public JWK bundle locally. Normal verification makes no Identity/OpenBao/remote-JWKS/introspection call and accepts only approved algorithm/issuer/audience/key IDs. Verifier clock leeway is typed configuration and cannot exceed 30 seconds. Unknown key, algorithm confusion, invalid issuer/audience/time/signature fail closed.

Refresh credentials use exactly 32 CSPRNG bytes, Base64URL without padding when encoded, and only purpose-separated versioned HMAC-SHA-256 digests at rest. Idle lifetime is 7d; absolute 30d; rotation invalidates predecessor; reuse revokes the family. A User has at most 20 active RefreshFamilies; creating the 21st revokes the oldest deterministically.

Current logout revokes current family; logout-all/password reset/User suspension/User `DELETING` revoke all; password change, ExternalIdentity unlink, and material MFA-state change rotate retained current credentials and revoke others as defined by Identity. Normal JWT verification has no blacklist/introspection, so a previously issued valid access JWT can remain cryptographically valid only for its remaining five-minute issuance lifetime plus the configured <=30s clock tolerance; online resource Authorization remains authoritative for permission.

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

Registration exact current values are server-owned: REGISTER Contact 5/refill1 per15m/24h and network 60/refill1 per5s/1h; RESEND Contact 5/refill1 per10m/2h plus 60s challenge spacing and network 60/refill1 per5s/1h; CONFIRM network 120/refill2 per1s/30m plus challenge-local five-proof cap. Contact-management/recovery variants use distinct domain-separated namespaces even when reusing an approved numeric envelope.

## 8. Workload identity, mTLS, network security

Production application workloads use dedicated Kubernetes ServiceAccounts and Istio Ambient STRICT mTLS. Kubernetes `default` ServiceAccount is prohibited. AuthorizationPolicy is default-deny/identity-based; NetworkPolicy is independent defense in depth. New/changed service edges require positive and negative identity/policy tests.

Istio does not replace end-user authorization or native Kafka/PostgreSQL/Redis authentication/ACLs.

Authentication dependency ownership follows the machine-readable registry: Web BFF owns the provider-protocol edge to Google and the trusted evidence/session edge to Identity; Identity does not own a direct Google login/link dependency. Identity owns explicit edges to semantic-quota Redis, compromised-password service, Notification durable handoff, Authorization owner/member provisioning, owner-safe Membership-removal prepare/resolution, and tenant lifecycle synchronization.

## 9. Secrets and cryptographic material

OpenBao 2.6.1 is the authoritative secret source; External Secrets is the normal Kubernetes materialization boundary. Secret values never enter Git, images, Helm/Kustomize values, logs, traces, or metrics.

Rotating key rings are mounted read-only; key purposes are separated and key IDs never rebind to new bytes. Notification/BFF/Identity use purpose-specific local key rings where current contracts require them. Normal application hot paths do not make routine OpenBao RPCs.

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

Security-impacting changes run applicable cross-tenant/RLS negatives including pooled-connection tenant-context reuse; local registration Contact reservation expiry/non-overwrite/login identifier/non-enumeration tests; password recovery/no-first-local-Credential tests; OIDC exact 256-bit/2m/10m evidence replay/provider-token/unverified-email/no-auto-link tests; active-TOTP after both password and Google proof; five-minute/five-proof/TOTP-replay/SMS no-downgrade and exact SMS challenge tests; MFA-state-change/session-family revocation; compromised-password 20-bit SHA-256 prefix/raw/full-digest non-egress/deadline/fail-closed tests; exact JWT claim/audience/<=30s leeway tests; Authorization deny/outage/overload/recovery plus concurrent owner-safety reservation/finalize/cancel; semantic-quota exact registration/time/failure tests; self-erasure Membership/last-owner/pending-invitation/session/legal-hold/Kafka-replay/restore tests; workload identity/mTLS/NetworkPolicy positives and negatives; WAF/bypass/DDoS controls; secret/key rotation/recovery; PII/log-injection canaries; artifact admission/vulnerability gates including policy-authoring RBAC/policy-engine SSRF negatives; privileged-access expiry/direct-access denial; and restore/erasure reconciliation.
