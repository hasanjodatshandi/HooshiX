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

Authorization owns `tenant_owner` role state. Membership removal never uses a race-prone read-only owner count. Identity first durably records a removal intent, then calls Authorization `PrepareMembershipRemoval` outside the DB transaction with 300ms maximum, one attempt, no retry/cache/fallback. Authorization atomically creates an owner-safety reservation or returns `LAST_TENANT_OWNER`; reservations do not auto-expire into unsafe allow. Identity then commits removal + durable finalize outbox or durably cancels the preparation if removal does not commit.

Authorization local `tenant_owner` assignment/removal/demotion uses the same tenant-scoped owner-safety serialization domain as active Membership-removal reservations. A local role mutation therefore cannot race a prepared Identity removal and independently consume the same final-owner capacity. Caller-supplied owner counts, stale snapshots, and force-last-owner flags are never authority.

## 2. Logical deletion, retention, erasure, legal hold

Logical deletion records `deleted_at`, `deleted_by`, and stable `deletion_reason`; `deleted_at` is authoritative. Normal queries exclude deleted rows by persistence contract. Restoration, include-deleted access, purge, and legal-hold actions are explicit, authorized, and audited.

Default retention is 360 days; expiry creates eligibility, not automatic destruction. Physical purge is unavailable from ordinary repositories/APIs and must be authorized, idempotent, observable, tenant-safe, and blocked by legal hold. Generated technical/security/event/audit IDs are never reused. Domain `ON DELETE CASCADE` is prohibited by default unless a current aggregate decision proves safety.

ADR-0028 governs irreversible data-subject erasure. Identity coordinates a non-PII UUIDv4 `erasure_request_id`; each service erases/anonymizes its owned copies and returns durable non-PII evidence. The required participant registry is server-owned/versioned and cannot be selected or reduced by the caller. Initial required participants are Identity, Authorization, Notification, and Web BFF.

Self-erasure requires authentication age <=5m and active MFA proof when applicable. It is not accepted while the User retains an ACTIVE/SUSPENDED Membership for a non-DELETED Tenant. The User must first leave Memberships, transfer last ownership, or complete tenant deletion through the owner-safe protocol above; erasure never bypasses last-owner safety. Acceptance atomically makes the User `DELETING`, revokes all RefreshFamilies, revokes pending invitations targeting that User, records audit/request state, and creates the erasure outbox. No remote I/O occurs inside that transaction.

Authorization erasure removes the erased subject's service-owned tenant/global authority: subject-linked MembershipRole state, direct Membership overrides, subject-linked authorization projections, and any platform capability-profile assignment. Tenant-owned Role/RolePermission definitions remain. Required retained audit facts remove the direct User link or replace it with an irreversible service-scoped erasure pseudonym that cannot restore application authority. Authorization never requires Contact/email/phone data for this workflow.

Web BFF erasure removes or irreversibly unlinks all user-linked browser authentication state including completed sessions, pre-auth/OIDC transactions, encrypted retained refresh credentials, User->sessions index entries, and other subject-linked continuation/token-broker state. Completion evidence is idempotent and non-PII. Successful erasure leaves no usable subject-linked Web BFF authentication state.

Coordination uses local Transactional Outbox + versioned Kafka/Protobuf events and idempotent participant Inbox/receipt processing rather than availability-coupled synchronous fan-out. Critical publication/Inbox-dedup evidence follows the existing 35-day recovery horizon. Restore procedures replay erasure/legal-hold decisions and reconcile required participant receipts before traffic.

Legal hold is an explicit durable audited ledger with `ACTIVE -> RELEASED`, actor, authority/reference, timestamps, policy version, and integrity evidence. Ordinary erasure callers cannot create, release, omit, or bypass a hold. A platform User entry point requires the explicit Authorization platform permission `platform.legal_hold.manage`; any separate legal-authority path must be at least as privileged/audited and cannot silently bypass Authorization. A hold may block irreversible progress but never reactivates the User or restores sessions. v1 exposes no ordinary self-service erasure undo; irreversible participant work is never cancellable. Crypto-shredding is valid only for separately key-enveloped material with destroyable keys and does not replace erasing ordinary relational PII.

## 3. Browser/BFF/OIDC security

Web BFF is the only browser-facing application API boundary. v1 public REST lives under `/api/v1`, with `/api/v1/auth`, `/api/v1/identity`, and `/api/v1/authorization` as reviewed subspaces. Internal RPC names are not mechanically exposed as public routes.

Public input is bounded before expensive work: JSON <=256KiB, auth/OIDC/session body <=64KiB, request headers/metadata <=16KiB, and multipart/file upload is outside v1. Public RFC 9457 errors expose only `type`, `title`, `status`, stable `code`, and an optional safe correlation identifier; tenant/membership/Contact IDs, internal request IDs, provider/exception text, tokens, Role/permission internals, and security details remain private.

Browser login uses OIDC Authorization Code + PKCE S256 through Web BFF. Exact browser-protocol randomness is:

```text
state:          exactly 256 CSPRNG bits
nonce:          exactly 256 CSPRNG bits
PKCE verifier:  exactly 32 CSPRNG bytes, Base64URL without padding
PKCE method:    S256 only
```

State, nonce, verifier, provider/redirect context, and post-login target are bound to one server-side pre-auth transaction. Browser receives only `__Host-sajtech-preauth` with `Secure; HttpOnly; SameSite=Lax; Path=/; no Domain` and >=256-bit opaque identifier entropy. Raw pre-auth ID is not a Redis key or log field; BFF uses a purpose/version HMAC locator. State expires <=10m, is single-use, and at most five live pre-auth transactions exist per browser.

Post-login target is only a same-origin relative path beginning with one `/`, <=1024 characters after canonical validation. `//`, backslash, scheme/userinfo/authority forms, control characters, and raw/percent-encoded canonicalization bypasses are rejected. Provider redirect URI matching is exact; wildcard/open redirects are prohibited.

BFF validates exact redirect, issuer/audience/signature/timestamps, nonce/PKCE/state and bounded provider claims before invoking Identity. BFF owns provider-protocol validation for browser login/link/signup. Identity does not call Google on that path and does not receive provider authorization codes or provider tokens.

After successful provider validation BFF creates trusted evidence:

```text
evidence_id        exactly 256 CSPRNG bits
evidence_issued_at trusted BFF server time after provider validation
request_id         canonical UUIDv4
issuer             canonical validated issuer
subject            validated provider subject
metadata           versioned/bounded optional email + email_verified + name suggestions
```

Identity accepts it only from authorized BFF workload, for exactly two minutes from `evidence_issued_at` subject to bounded clock tolerance, binds all evidence fields into idempotency/security fingerprint, and retains spent/replay evidence >=10m. Equal replay returns original committed outcome; changed request/payload under same `evidence_id` returns `OIDC_EVIDENCE_REPLAY`. Browser/provider input cannot extend evidence lifetime.

External identities bind by stable `(issuer, subject)`. Email equality never auto-links. `email_verified=true` may create verified Contact only if canonical email is free; collision becomes `ACCOUNT_LINK_REQUIRED`. Missing or `email_verified=false` never creates Contact automatically. Provider names are suggestions only and never silently complete Identity profile.

The browser receives no provider token, Identity access/refresh token, internal gRPC credential, trusted Role list, permission list, or downstream JWT. Browser input also never selects a downstream JWT audience.

BFF maintains a server-owned reviewed route->audience mapping and obtains exact-audience five-minute access JWTs through the Identity-owned internal `IssueAudienceAccessToken` contract. The source Identity Session/RefreshFamily must be active; target audience must be allow-listed for BFF workload and current session mode; arbitrary browser audience is rejected. This is not a public generic OAuth token exchange. `authenticated_onboarding` cannot obtain ordinary resource-service or `authorization-service` audiences. Any bounded server-side JWT reuse ends at token `exp` and on session/tenant/assurance rotation and is not an authorization cache.

Primary cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
no Domain
```

Session ID has >=256 CSPRNG bits. Raw session ID is not Redis-key/log/metric material; BFF uses purpose/version HMAC locator. Session state is bounded and contains required User/Identity Session/RefreshFamily references, active tenant/membership when selected, explicit mode, CSRF digest, created/last-seen/idle/absolute times, encrypted retained refresh credential when present, and bounded assurance/security state.

Session idle <=7d, absolute <=30d and never exceeds underlying RefreshFamily validity. Absolute expiry never extends. `last_seen` persistence is coalesced to at most once per five-minute activity window to bound Redis write amplification.

Every completed BFF session links to one current Identity RefreshFamily. A purpose-HMAC/pseudonymous User->sessions index supports logout-all, suspension, `DELETING`, erasure and family-wide revocation without unbounded key scans.

Session rotates after login, MFA completion, tenant switch, recovery, password reset/change where retained, privilege/assurance elevation, and observed MFA-state changes that preserve current session. Rotation is atomic: once replacement is authoritative, predecessor ID is immediately invalid; no dual-valid grace exists.

Any retained Identity refresh credential is encrypted before Redis persistence with AES-256-GCM, random 96-bit nonce, 128-bit tag, and AAD binding session+purpose+key-id/version. BFF key rotation is every 90d; old decrypt keys remain through dependent-session lifetime/rekeying plus 7d. Reload is atomic. During key-source outage the last fully validated snapshot may be used <=1h; after that key-dependent operations fail closed. Key material never enters Git/Redis/browser/telemetry.

A successful primary proof that still requires MFA is only pre-auth state. This applies to password **and** trusted Google proof. BFF establishes neither normal session nor `authenticated_onboarding` until Identity confirms MFA and creates Session/RefreshFamily.

After all required factors, a User with no selected active Membership may have only `authenticated_onboarding`: no normal tenant-scoped resource JWT and only reviewed Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection allow-list. Zero Membership remains onboarding; one valid Membership selects automatically; multiple follow Identity last-valid/explicit selection. Tenant selection rotates BFF session identity.

CSRF token is exactly 256 CSPRNG bits, bound to current BFF session, stored only as purpose/version HMAC and compared constant-time. It rotates with session/assurance rotation. There is no separate CSRF cookie; clear token is delivered only through reviewed same-origin session/bootstrap response for `X-CSRF-Token` and is not persisted in local/session storage or URLs.

Unsafe cookie-authenticated production browser requests require all of trusted exact `Origin`, valid `X-CSRF-Token`, and `Sec-Fetch-Site: same-origin`. Missing/invalid Fetch Metadata on normal browser production routes fails closed. GET/HEAD/OPTIONS remain side-effect free. A future non-browser client that cannot satisfy this model requires a separately reviewed surface.

Cross-origin credentialed CORS is disabled in v1: same-origin only. Future CORS requires architecture review; wildcard/reflected credentialed origins remain prohibited.

Exact v1 CSP is:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'
```

`unsafe-eval` and `unsafe-inline` are prohibited. Additional external sources/directives require explicit review. Authentication/OIDC/session/Authorization-management responses use `Cache-Control: no-store`. HSTS after domain-coverage verification, nosniff, strict-origin-when-cross-origin referrer policy, restrictive Permissions Policy, and CSP frame protection are centrally tested.

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

SMS MFA proof is purpose-separated from all other Identity challenges: exactly eight CSPRNG decimal digits, HMAC-SHA-256 verifier only, no plaintext durable persistence after safe handoff creation, expires no later than enclosing five-minute pre-auth challenge, maximum five failed proofs across that challenge, 60s resend spacing, replacement invalidation, and single use. Local logging SMS is never a production fallback.

## 5. Authorization ownership and runtime

Authorization Service owns the exact permission-definition catalog/projection, tenant SYSTEM/custom roles, role permissions, membership-role assignments, direct Membership grants/denies, online evaluation, management idempotency/audit, owner-safety reservations, Identity-driven tenant/member lifecycle projections, platform capability assignments, and private PostgreSQL persistence. Permission-key meaning/resource/domain invariants remain owned by the protected bounded context.

Permission keys are exact Git-owned contracts with TENANT/PLATFORM scope and `ACTIVE -> DEPRECATED -> RETIRED` lifecycle. Unknown/retired keys fail closed; deprecated keys cannot receive new grants/assignments; identifiers are never reused for new meaning. v1 has no Role inheritance, wildcard permission assignment, resource-condition policy, or caller-defined expression language.

Tenant evaluation:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

SYSTEM Roles are server-owned/immutable. Current semantics are `tenant_owner` = all active tenant permissions, `tenant_admin` = all active tenant permissions except `tenant.delete` and `membership.owner.assign`, and `tenant_member` = `tenant.read`, `membership.read`, `role.read`. Custom Roles are bounded `ACTIVE -> ARCHIVED`, versioned, and tenant-name unique under current normalization rules.

Current online `CheckPermission` request contains only `tenant_id`, `membership_id`, and exact `permission_key`:

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

Successful RPC completion means **ALLOW**. Authoritative deny -> `PERMISSION_DENIED / AUTHORIZATION_DENIED`; dependency/open-breaker failure -> `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; healthy saturation -> `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED`, mapped by callers to fail-closed dependency unavailability. There is no successful `allowed=false` response and no Role/permission snapshot in success.

Safe local JWT/claim/tenant/syntax prechecks may reject invalid traffic but never grant access. Bloom filters, signed permission lists, caches, stale allow, or duplicate routine BFF checks are not authoritative.

Browser tenant-administration traffic reaches Authorization through Web BFF. Authorization locally verifies Identity JWT with exact audience `authorization-service`, derives trusted actor/tenant/Membership claims, and performs management permission check in-process rather than calling its own gRPC API. Caller-provided actor/role/permission snapshots are not authority. Role/grant mutations cannot introduce a permission the actor does not possess; removing a direct DENY is privilege-elevating and requires that permission.

Administration hard limits are bounded by current ADR-0013/service contract; `AUTH_ADMIN_WRITE` quota is evaluated before DB transaction by actual semantic mutation count (maximum 100), is not refunded after later DB failure, and local PostgreSQL mutation remains all-or-nothing.

Identity `PrepareMembershipRemoval` is a separate authoritative-security edge, also 300ms maximum/one attempt/no retry/cache/fallback/fail closed. Its safety result is persisted by Authorization as idempotent reservation rather than cached/read-only state. Finalize/cancel and owner/member/tenant lifecycle synchronization are durable commands resolved after local Identity intent/Outbox commit. Local owner Role mutations share same serialization domain.

`platform_admin` is explicit global SYSTEM capability profile, not tenant Role/wildcard. Current platform permissions are `platform.tenant.create`, `platform.tenant.suspend`, `platform.tenant.resume`, `platform.tenant.restore`, and `platform.legal_hold.manage`. Identity performs authoritative `CheckPlatformPermission(user_id, permission_key)` with 300ms maximum, one attempt, no retry/cache/fallback and fail-closed behavior. Only Identity workload may use this edge. Platform permission never bypasses tenant/resource/domain invariants, and platform assignment/revocation is excluded from ordinary tenant APIs and requires separately privileged JIT-controlled audited workflow.

Authorization management/lifecycle/platform writes use canonical UUIDv4 request identity plus purpose/version HMAC-SHA-256 intent fingerprints. Equal replay returns original committed outcome; changed intent under same ID returns `REQUEST_ID_CONFLICT`. Security-sensitive idempotency evidence remains >=35d. Required management/platform audit is durable and >=365d; owner changes, direct grant/deny changes, Role-permission mutation and platform-authority operations require bounded CR/LF-safe reason. Routine hot-path CheckPermission allow/deny remains bounded telemetry rather than adding synchronous durable audit write on every request.

Authorization uses jOOQ/JDBC without JPA. Tenant-owned tables use forced RLS and transaction-local trusted tenant context. `CheckPermission` query budget remains <=100ms with representative query-plan evidence. No Redis/gRPC/HTTP/Kafka/provider I/O occurs inside Authorization DB transactions.

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

Roles, permissions, `authorization_version`, or equivalent permission snapshots are not authorization authority in token. `aud` is exact intended service identifier; wildcard audiences are prohibited. Identity issuer is typed deployment configuration, with initial production logical value `https://identity.sajtech.internal` unless reviewed environment configuration supplies final value before rollout.

Web BFF audience brokerage is an Identity token-signing operation, not a browser authority. `IssueAudienceAccessToken` accepts only authorized BFF workload, an active Identity Session/RefreshFamily and a server-allow-listed exact audience valid for current tenant/session mode. Browser cannot supply arbitrary audience and `authenticated_onboarding` cannot obtain normal resource/Authorization audiences. Issued access JWT remains five-minute exact-audience token under this same claim/algorithm baseline.

Verifiers use a bounded non-secret GitOps public JWK bundle locally. Normal verification makes no Identity/OpenBao/remote-JWKS/introspection call and accepts only approved algorithm/issuer/audience/key IDs. Verifier clock leeway is typed configuration and cannot exceed 30 seconds. Unknown key, algorithm confusion, invalid issuer/audience/time/signature fail closed.

Refresh credentials use exactly 32 CSPRNG bytes, Base64URL without padding when encoded, and only purpose-separated versioned HMAC-SHA-256 digests at rest. Idle lifetime is 7d; absolute 30d; rotation invalidates predecessor; reuse revokes family. A User has at most 20 active RefreshFamilies; creating 21st revokes oldest deterministically.

Current logout revokes current family; logout-all/password reset/User suspension/User `DELETING` revoke all; password change, ExternalIdentity unlink, and material MFA-state change rotate retained current credentials and revoke others as defined by Identity. Normal JWT verification has no blacklist/introspection, so previously issued valid access JWT can remain cryptographically valid only for remaining five-minute issuance lifetime plus configured <=30s clock tolerance; online resource Authorization remains authoritative for permission.

## 7. Semantic security quotas

ADR-0024 is the single current quota decision. The operation-owning service enforces its own quota in ACL-isolated `security-redis` namespace; no quota microservice exists.

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

Registration exact current values are server-owned: REGISTER Contact 5/refill1 per15m/24h and network 60/refill1 per5s/1h; RESEND Contact 5/refill1 per10m/2h plus 60s challenge spacing and network 60/refill1 per5s/1h; CONFIRM network 120/refill2 per1s/30m plus challenge-local five-proof cap. Contact-management/recovery variants use distinct domain-separated namespaces even when reusing approved numeric envelope.

Web BFF OIDC browser-protocol buckets are `OIDC_START/network` capacity 60/refill1 per5s/cleanup1h and `OIDC_CALLBACK/network` capacity 120/refill2 per1s/cleanup30m. They are BFF-owned and domain-separated from Identity `GOOGLE_LOGIN` pressure. Independent max-five-live-pre-auth/browser remains enforced. Redis/time-source failure does not bypass these OIDC controls.

Authorization `AUTH_ADMIN_WRITE` uses current actor+scope and tenant/platform buckets. Request cost is actual semantic mutation count with minimum 1 and maximum 100; for Role permission replacement this is additions+removals. Both dimensions consume atomically before DB mutation and consumption is not refunded by later DB failure.

## 8. Workload identity, mTLS, network security

Production application workloads use dedicated Kubernetes ServiceAccounts and Istio Ambient STRICT mTLS. Kubernetes `default` ServiceAccount is prohibited. AuthorizationPolicy is default-deny/identity-based; NetworkPolicy is independent defense in depth. New/changed service edges require positive and negative identity/policy tests.

Istio does not replace end-user authorization or native Kafka/PostgreSQL/Redis authentication/ACLs.

Authentication dependency ownership follows machine-readable registry: Web BFF owns provider-protocol edge to Google, trusted evidence/session edge to Identity, authoritative Identity audience-token brokerage, session Redis, semantic-quota Redis, Authorization tenant management, and registered resource dispatch. Identity does not own direct Google login/link dependency. Identity owns explicit edges to semantic-quota Redis, compromised-password service, Notification durable handoff, Authorization owner/member provisioning, owner-safe Membership-removal prepare/resolution, tenant lifecycle synchronization, and authoritative `CheckPlatformPermission` for platform tenant/legal-hold operations.

Authorization workload policy distinguishes operations: approved resource-owner workloads may call only registered permission-check surfaces for their namespaces; Identity may call lifecycle/platform-authority operations; Web BFF may call reviewed management surface. Workload identity never replaces end-user/tenant management authorization.

Web BFF NetworkPolicy/Istio egress is deny-by-default and allows only Identity, Authorization management, explicitly registered resource services, BFF/security Redis, configured Google OIDC endpoints, and approved telemetry backend/collector. Arbitrary URL/Internet egress is prohibited. Every new synchronous downstream requires dependency-registry entry before production.

## 9. Secrets and cryptographic material

OpenBao 2.6.1 is authoritative secret source; External Secrets is normal Kubernetes materialization boundary. Secret values never enter Git, images, Helm/Kustomize values, logs, traces, or metrics.

Rotating key rings are mounted read-only; key purposes are separated and key IDs never rebind to new bytes. Notification/BFF/Identity use purpose-specific local key rings where current contracts require them. Authorization idempotency HMAC material is likewise purpose-separated and locally mounted. Normal application hot paths do not make routine OpenBao RPCs.

BFF retained-refresh key ring is AES-256-GCM only with random 96-bit nonce and 128-bit tag; AAD binds session/purpose/key-id/version. Normal rotation is every 90d; previous decrypt keys remain through dependent-session expiry/rekey plus 7d. Reload is atomic. A last fully validated key snapshot may bridge source outage for <=1h; after that refresh encrypt/decrypt-dependent operations fail closed. Raw refresh credential, key bytes, session-bound AAD, and ciphertext payload are never logged or exported.

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

Privileged containers, host networking, `hostPath`, extra capabilities, or relaxed security context require explicit current security decision plus automated validation.

Authorization production defaults use `platform-apps/authorization-service` workload identity, application gRPC convention 9090, separate management port, 64KiB inbound message cap, 16KiB metadata cap, minimum 3 replicas, PDB `minAvailable=2`, and HPA initial 3..12. Liveness is local process/runtime only; readiness requires DB, compatible permission catalog/projection, approved local JWT verifier bundle for management traffic, and required local security configuration.

Web BFF production defaults use `platform-apps/web-bff` ServiceAccount, HTTP 8080, separate management port, minimum 3 replicas, PDB `minAvailable=2`, and HPA range 3..12 only after load/connection/Redis/crypto/downstream evidence. Liveness is local runtime progress only; readiness requires usable session/security/key configuration and entry-point prerequisites without synchronously probing every downstream per health request.

## 13. Logging/PII and privileged access

Logging is structured and allow-list based. Raw passwords/OTP/recovery codes/tokens/cookies/keys/secrets/payment data/high-risk identity data/full sensitive payloads/SQL binds/complete gRPC metadata/Kafka headers/unreviewed provider payloads are prohibited.

Ordinary PII requires approved purpose and masking/tokenization or managed-key HMAC pseudonymization where correlation is needed. Input-derived fields are CR/LF-safe; exception/provider text is untrusted until sanitized. Metric labels remain low-cardinality and exclude business/security IDs, trace IDs, raw URLs, and free-form errors.

Authorization durable audit stores only bounded trusted actor/workload and technical target identifiers, stable action/result/machine code, bounded before/after summary or digest, policy/catalog version, and UTC-microsecond time. It never stores raw JWT, Contact/email/phone, HMAC material, arbitrary request body, SQL bind, or unrestricted exception text. Required reason fields are trim+NFC, 1..500 code points, and reject control characters including CR/LF.

Static Semgrep rules, pipeline redaction, synthetic canary sink tests, and runtime leak detection provide defense in depth.

Human production access uses Teleport JIT SSO/WebAuthn, approvals, short TTL, least privilege, and recorded/audited sessions. Standing admin/root/database-superuser/shared credentials are prohibited. Authorization platform-profile assignment/revocation is available only through separately privileged JIT-controlled audited workflow, not ordinary BFF/tenant APIs.

## 14. Verification

Security-impacting changes run applicable cross-tenant/RLS negatives including pooled-connection tenant-context reuse; local registration Contact reservation expiry/non-overwrite/login identifier/non-enumeration tests; password recovery/no-first-local-Credential tests; OIDC exact 256-bit state/nonce/evidence, 32-byte verifier, pre-auth HMAC/TTL/single-use/max-five, return-target canonicalization/encoded-open-redirect negatives, evidence 2m/10m replay/provider-token/unverified-email/no-auto-link tests; active-TOTP after both password and Google proof; server-owned route->audience/arbitrary-audience rejection/exact-audience/onboarding-audience denial/no-browser-JWT tests; BFF session HMAC locator, atomic no-grace rotation, five-minute last-seen coalescing, user-session index/logout/revocation/idle/absolute tests; BFF AES-GCM nonce/tag/AAD/90d-rotation/previous-key+7d/atomic-reload/one-hour-stale-key fail-closed tests; exact CSRF entropy/HMAC/constant-time/rotation, Origin and mandatory Fetch-Metadata negatives, same-origin-only CORS, exact CSP/no unsafe-inline/eval, no-store and request/error-bound tests; five-minute/five-proof/TOTP-replay/SMS no-downgrade and exact SMS challenge tests; MFA-state-change/session-family revocation; compromised-password 20-bit SHA-256 prefix/raw/full-digest non-egress/deadline/fail-closed tests; exact JWT claim/audience/<=30s leeway tests; Authorization permission-catalog lifecycle/non-reuse, SYSTEM/custom Role/override limits, exact CheckPermission success/deny semantics, management audience/workload/local-evaluator/privilege-escalation negatives, AUTH_ADMIN_WRITE cost/no-refund/all-or-none behavior, platform permission no-bypass/outage/wrong-workload tests, concurrent owner-role/removal reservation safety, idempotency/audit/reason/PII controls, jOOQ/RLS/query-plan/no-remote-I/O tests, and erased subject tenant/platform authority removal; semantic-quota exact registration and Web-BFF OIDC values/time/failure tests; Web BFF erasure state removal/non-PII receipt tests; self-erasure Membership/last-owner/pending-invitation/session/legal-hold/Kafka-replay/restore tests; workload identity/mTLS/NetworkPolicy/egress positives and negatives; WAF/bypass/DDoS controls; secret/key rotation/recovery; PII/log-injection canaries; artifact admission/vulnerability gates including policy-authoring RBAC/policy-engine SSRF negatives; privileged-access expiry/direct-access denial; and restore/erasure reconciliation.
