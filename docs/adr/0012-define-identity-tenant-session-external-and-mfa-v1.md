# ADR-0012: Identity Tenant, Session, External Identity, and MFA v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; Identity implementation contracts finalized on 2026-08-13; remaining v1 Identity lifecycle/security contracts finalized on 2026-08-14

## Decision

### Identity-owned internal contract surface

Identity owns versioned, feature-scoped gRPC + Protobuf contracts for:

- registration and registration verification;
- profile/contact read/update/add/verification/primary/removal;
- password authentication, password change/reset/recovery, and session/refresh-family lifecycle;
- active-tenant selection and authenticated onboarding;
- tenant and tenant-membership lifecycle;
- tenant invitation lifecycle;
- external-identity establish/signup/link/unlink flows;
- MFA enrollment, challenge, recovery, disable, and replacement;
- data-subject erasure coordination entry points.

Contracts are governed by ADR-0003. Identity generates security-sensitive identifiers, challenge/session/token TTLs, and policy values. Callers provide only stable `request_id` values and explicitly allowed business inputs; callers cannot supply token lifetime, challenge lifetime, quota policy, hashing parameters, key identifiers, MFA policy, or other server-owned security policy.

Internal errors use stable typed machine codes mapped to bounded gRPC statuses. Raw exception/cause text, provider payloads, SQL details, credentials, or PII are never copied into transport errors.

Provider-owned contracts remain provider-owned. Identity consumes Notification and Authorization canonical Protobuf sources rather than copying their schemas or implementing those services' runtimes inside Identity.

### Identity identifier, secret, and timestamp baseline

Unless a narrower current contract says otherwise:

- Identity-generated entity/aggregate technical identifiers are UUIDv4 and are never reused;
- caller-supplied stable `request_id` is canonical lowercase UUIDv4 text and identifies one logical command intent;
- durable idempotency fingerprints are purpose-separated, versioned HMAC-SHA-256, never unsalted hashes of guessable inputs;
- refresh credentials contain exactly 32 CSPRNG bytes and are encoded Base64URL without padding when a string representation is required;
- refresh persistence stores only a purpose-separated versioned HMAC-SHA-256 digest plus required key/version metadata;
- BFF/Identity session secret identifiers contain at least 256 bits of CSPRNG entropy;
- OIDC `evidence_id` contains exactly 256 bits of CSPRNG entropy;
- persisted instants use UTC microsecond precision unless a provider contract requires a narrower representation;
- secret/token/challenge identifiers and random values are never intentionally reused.

Cryptographic values are not UUIDs merely for visual consistency; opaque credentials retain the entropy/encoding defined by their security contract.

### User lifecycle and profile/contact contract

User lifecycle is:

```text
PENDING
ACTIVE
SUSPENDED
DELETING
DELETED
```

A User starts `PENDING` and becomes `ACTIVE` only after the ADR-0009 required profile is complete, at least one Contact is verified, an applicable local Credential is valid for local-password registration, and no blocking security/deletion condition applies. `SUSPENDED` rejects new login and refresh operations. `DELETING` and `DELETED` are non-authenticatable states. Suspension/deletion revocation semantics are defined under session lifecycle below.

ADR-0009 is authoritative for local EMAIL/PHONE registration, verified-Contact login identifiers, registration contact/profile canonicalization, pending contact reservation/expiry, challenge semantics, first-primary behavior, and production PHONE-registration gate.

v1 profile/contact methods include:

```text
GetProfile
UpdateProfile
AddContact
ResendContactVerification
ConfirmContactVerification
SetPrimaryContact
RemoveContact
```

Rules:

- `UpdateProfile` applies the same name canonicalization/length/control-character rules as registration;
- `AddContact` uses the same provider-neutral email/E.164 canonicalization and verified-contact global uniqueness rules as registration;
- contact-verification challenges use the ADR-0009 eight-digit/10-minute/five-failed-try/60-second/single-use baseline under a distinct purpose/key namespace;
- the first verified Contact becomes primary automatically when no primary exists;
- `SetPrimaryContact` may select only an active verified Contact owned by the User;
- changing primary Contact or removing a Contact requires authentication age <=5 minutes;
- an `ACTIVE` User must retain at least one verified Contact;
- `RemoveContact` cannot remove the last verified Contact except as part of the approved account deletion/erasure workflow;
- removing the current primary Contact requires another verified Contact to be made primary first; the API does not silently choose one;
- contact conflicts disclose no owning User identity.

### Tenant creation and lifecycle

Identity supports self-service and platform-admin tenant creation. Creator becomes initial `tenant_owner`.

Tenant stores immutable UUID `tenant_id`, mutable Unicode name (1..120 code points), immutable canonical slug, status, creator, UTC-microsecond timestamps, logical-deletion metadata, and optimistic-lock version.

Slug is lowercase ASCII, 3..63 chars, matches:

```text
^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$
```

It is canonicalized before lookup, unique including deleted tenants, and never reused. Initial reserved names include `admin`, `api`, `system`, `platform`, `www`, `support`.

Lifecycle:

```text
PROVISIONING
ACTIVE
SUSPENDED
DELETING
DELETED
```

Tenant creation commits Tenant + creator membership + audit + stable owner-provisioning outbox in one local transaction without network I/O. Tenant remains `PROVISIONING` until Authorization idempotently acknowledges owner provisioning; Identity then activates it transactionally.

Owner-provisioning transport baseline:

```text
deadline: 900 ms
attempts per claim: 1
wait-for-ready: off
immediate transport retry: none
durable delays: 1s, 5s, 30s, 2m, 10m, then <=10m ±20%
```

Transient `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`, `INTERNAL` remain durable-retry candidates. Definitive errors alert. 15 minutes pending warns; one hour pages unless the workflow has been explicitly cancelled/resolved.

A tenant owner may initiate deletion only after the resource-owner authorization flow proves `tenant.delete`. Platform-admin may suspend/resume under the current explicit audited platform capability. While Identity owns a Tenant in `DELETING`, Identity rejects new invitation, tenant-selection, and tenant/membership lifecycle mutations for that tenant and revokes pending invitations. Authorization cleanup is a durable post-commit command; the Tenant does not become `DELETED` until Authorization acknowledges the lifecycle cleanup/deny state.

A `DELETED` tenant may be restored only by platform-admin, only before irreversible tenant data erasure/purge has begun, and only through an audited restore flow. Restore re-enters `PROVISIONING` while Authorization-owned state is reconciled, then returns to `ACTIVE` only after acknowledgement. Slug/technical identifiers are never released/reused.

### Tenant invitation and membership lifecycle

Invitation lifecycle is:

```text
PENDING
ACCEPTED
DECLINED
EXPIRED
REVOKED
```

Membership lifecycle is:

```text
ACTIVE
SUSPENDED
REMOVED
```

v1 invitation targets an already existing non-erased User through an Identity-owned verified contact reference. Raw email/phone is not placed in the provisioning outbox or Authorization contract.

- target contact must be verified and belong to the target User;
- invitation acceptance requires the authenticated User to be the same target User;
- invitation TTL is seven days;
- at most one pending invitation exists for `(tenant_id, target_user_id)`;
- invitation is not itself a Membership;
- successful acceptance creates one `ACTIVE` Membership and transitions the invitation to `ACCEPTED` transactionally;
- the same acceptance transaction writes audit + a durable Authorization provisioning outbox for the default `tenant_member` SYSTEM role;
- until Authorization provisioning is acknowledged, no permission is fabricated; normal Authorization default-deny applies;
- v1 invitations do not carry arbitrary role/permission assignment; elevation occurs later through Authorization-owned role-management flows;
- resend/reissue creates a new reviewed invitation intent rather than extending an expired credential implicitly;
- inviting an unregistered email/phone is outside v1 and requires a future reviewed registration-linking workflow.

Suspended/removed Membership cannot be selected as active tenant context. Removing a Membership uses the owner-safety protocol below before the Identity removal commit.

### Last-owner-safe membership removal

Authorization owns `tenant_owner` role state, so Identity MUST NOT duplicate owner-role authority or infer last-owner safety from a stale local copy.

A read-only “check then remove” is insufficient because concurrent removals can both observe more than one owner and violate the last-owner invariant. v1 therefore uses an Authorization-owned durable owner-safety preparation/reservation protocol.

Identity first commits a local stable `MembershipRemovalIntent`/equivalent durable intent in `PREPARING` state, then—outside every Identity DB transaction—calls Authorization `PrepareMembershipRemoval` using the same stable `request_id`:

```text
deadline:       300 ms maximum
attempts:       1
wait-for-ready: off
retry:          none
cache/fallback: none
failure mode:   fail closed
```

Authorization atomically evaluates owner state while excluding already reserved removals, and persists an idempotent reservation bound to tenant, membership, and request identity. A membership under an active removal reservation is ineligible for conflicting owner-role mutation. If removing the target could leave no effective owner, Authorization returns `FAILED_PRECONDITION / LAST_TENANT_OWNER`. Equal replay returns the same prepared result; conflicting request reuse returns `ALREADY_EXISTS`.

Authorization-side removal reservations do not silently expire into an unsafe allow state. They remain conservative until explicitly finalized/cancelled/reconciled through the stable request identity.

After a successful preparation, Identity performs one local transaction that marks the Membership `REMOVED`, writes its lifecycle audit, changes the local removal intent to finalization-pending, and commits a durable Authorization finalization outbox. Remote I/O is not inside that transaction. `FinalizeMembershipRemoval` retires Authorization role state and closes the reservation idempotently.

If the Identity removal transaction definitively does not commit, Identity records cancellation-pending and durably resolves the same preparation through Authorization `CancelMembershipRemovalPreparation`. A crash between preparation and the second local transaction is recovered from the durable `PREPARING` intent by replaying the same idempotent prepare request and completing or cancelling it. Failure to resolve a reservation remains fail-closed and alertable rather than auto-expiring into an unsafe owner-removal race.

Finalize/cancel dispatch follows the same 900ms one-attempt/no-immediate-transport-retry durable-command baseline and bounded durable retry family used by Identity->Authorization provisioning unless a stricter current provider contract applies.

### Persistence and consistency boundaries

Identity uses separate Domain and persistence models. JPA/Hibernate is the default for aggregate CRUD and ordinary bounded queries; JDBC/jOOQ may be used in Infrastructure where SQL-level control is required, including `FOR UPDATE SKIP LOCKED`, durable outbox/inbox work claims, or measured query paths.

The v1 consistency boundaries are:

- `User` + `Profile` + active Contact set as one consistency boundary for user/profile/contact invariants;
- `Credential` as a separate aggregate;
- registration/recovery/contact-verification `Challenge` as a separate aggregate;
- `Session` / `RefreshFamily` as a separate aggregate boundary;
- `MfaEnrollment` / recovery material as a separate aggregate boundary;
- `ExternalIdentity` as a separate aggregate;
- `Tenant`, `TenantMembership`, and `Invitation` as tenant lifecycle aggregates coordinated by Application use cases and local transactions/outboxes as required;
- durable cross-service lifecycle intents/outboxes remain explicit application/persistence coordination records rather than hidden remote calls from aggregates.

One giant JPA entity graph spanning these capabilities is prohibited. Remote gRPC/HTTP/Kafka/Redis/provider I/O never occurs inside an Identity database transaction.

Global Identity tables are explicitly separated from tenant-owned tables. Tenant-owned production tables use forced RLS; runtime roles remain non-owner `NOSUPERUSER NOBYPASSRLS`, and tenant context is parameterized/transaction-local under ADR-0027.

A nullable last-selected Membership preference may be persisted as non-authoritative Identity preference/query state. It never grants membership: every use validates current Tenant + Membership lifecycle before selection, and stale values are ignored.

### Primary authentication, MFA continuation, tenantless onboarding, and active-tenant selection

A successful primary authentication proof is either:

- local password proof through an active verified email/phone Contact under ADR-0009; or
- trusted Web BFF Google OIDC evidence that resolves to an existing or newly created User under the external-identity contract below.

If active TOTP exists for the User, **neither primary proof completes authentication by itself**. It creates only the same MFA pre-auth continuation defined below. Google login therefore cannot bypass TOTP/recovery-code requirements merely because the provider proof was strong.

After all required authentication factors succeed, Identity establishes an authenticated Session/RefreshFamily even when the User belongs to no active Tenant. A normal access JWT is tenant-scoped and is **not** issued until one `ACTIVE` Membership/Tenant has been selected.

Selection rules after successful authentication:

1. exactly one valid active Membership -> select it automatically;
2. more than one -> reuse the last-selected Membership only if it is still active and its Tenant is selectable; otherwise require explicit user selection;
3. zero -> remain authenticated for onboarding and permit only tenant create / invitation acceptance / required Identity onboarding flows.

Web BFF represents the zero/unselected state as `authenticated_onboarding`. That browser session is authenticated but has no normal Identity access JWT and cannot be used to call ordinary resource-service APIs. Only an explicit allow-list of Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection endpoints is available until active tenant selection completes.

Tenant switch validates the target active Membership, updates the non-authoritative last-selected preference, rotates the Identity refresh credential within the current family, issues a new tenant-scoped access token, and requires BFF session-ID rotation. A suspended/deleting/deleted Tenant or suspended/removed Membership cannot be selected.

### Access-token contract

Access tokens are RS256 JWTs with five-minute lifetime. The v1 claim contract is intentionally authorization-minimal.

Standard claims:

```text
iss
aud
sub
jti
iat
exp
```

Private claims:

```text
tenant_id
membership_id
sid
```

`roles`, `permissions`, `authorization_version`, or equivalent permission snapshots are prohibited from becoming authorization authority in the access token. Protected resource services use the current online Authorization contract.

`iss` is typed deployment configuration; the initial production logical value is `https://identity.sajtech.internal` unless the reviewed environment configuration replaces it before rollout. `aud` is the exact intended service/audience identifier and wildcard audiences are prohibited.

Verifiers validate algorithm/signature/key, configured issuer, exact audience, `sub`, `jti`, `iat`, `exp`, required active tenant/membership/session claim shape, and canonical size bounds. Signing/private-key lifecycle and maximum clock leeway follow ADR-0023.

There is no normal access-token blacklist/introspection/network callback. Therefore a correctly signed already-issued access token may remain cryptographically valid for at most its five-minute lifetime (plus only the bounded verifier clock leeway in ADR-0023) after logout/session-family revocation/password change/suspension. This is an explicit v1 trade-off; authoritative online resource Authorization still applies and no stale permission snapshot is trusted from the JWT.

### Refresh credentials, session families, logout, and revocation

Refresh credentials are opaque/rotating, stored only as keyed secure digests, with seven-day idle and 30-day absolute lifetime. Rotation invalidates predecessor; reuse detection revokes the credential family. Browsers retain only the secure BFF session cookie.

A User may have at most 20 active RefreshFamilies. Creating a 21st active family transactionally revokes the oldest active family by `created_at` (with stable identifier tie-break), preserving audit evidence.

Revocation rules:

- current logout -> revoke the current RefreshFamily;
- logout-all -> revoke every active RefreshFamily for the User;
- successful password reset -> revoke every active RefreshFamily;
- User suspension or transition to `DELETING` -> revoke every active RefreshFamily and reject new login/refresh;
- password change from an authenticated session -> rotate the current session/refresh credential and revoke all other RefreshFamilies;
- successful ExternalIdentity unlink -> rotate the current session/refresh credential and revoke all other RefreshFamilies;
- successful MFA enrollment, disable, replacement, or recovery that changes active MFA material -> rotate the current authenticated session/refresh credential when one remains valid and revoke all other RefreshFamilies.

The last rule prevents sessions established under an older assurance/MFA configuration from silently surviving a material MFA-state change. A recovery flow that intentionally terminates the current session may revoke all families instead of retaining one.

`DELETED` has no usable Session/RefreshFamily. Revoked families cannot be resurrected by rollback/replay. Refresh reuse detection remains family-wide revocation.

Normal resource verification is local from the approved public verifier bundle and does not call Identity/OpenBao/JWKS per request.

### Password change and recovery

Password policy/hashing follows the current Identity/Technology Baseline and compromised-password contract.

Change Password requires:

- current password proof;
- authentication age <=5 minutes;
- when MFA is active, MFA assurance age <=5 minutes;
- compromised-password check before committing the new credential.

Forgot/reset Password applies only to a User that already has an active local Credential and uses only that User's primary verified Contact. Initiation is non-enumerating: an unknown Contact, a non-primary Contact, or a User with no local Credential produces the same bounded caller-visible initiation result and does not create a password-recovery challenge capable of creating a first local Credential.

The recovery challenge is purpose-separated from registration/contact/MFA challenges but uses the same fixed eight-decimal-digit, 10-minute TTL, five-failed-try, 60-second resend-spacing, HMAC-only, single-use baseline.

If TOTP MFA is active, successful contact recovery proof is not sufficient to reset the password: the same recovery flow must also complete valid TOTP or one valid single-use recovery code. SMS does not bypass active TOTP.

Successful password reset revokes all RefreshFamilies. There is no password-history/reuse blacklist in v1; the approved compromised-password check remains the breached-password control.

If the User has lost password access and also cannot complete active MFA or use a recovery code, v1 provides no automated recovery bypass. Manual/support account recovery is outside v1 and requires a separate reviewed security decision before implementation.

Creating the **first** local Credential for an external-identity-only User is outside v1; forgot-password/reset is never repurposed as that enrollment path.

### Compromised-password protocol

The compromised-password dependency is an internal k-anonymous/prefix service, not a claim that Identity directly uses a third-party HIBP wire protocol.

For create/change/reset checks:

1. Identity applies the password's required NFC normalization;
2. Identity encodes the normalized value as UTF-8 and computes SHA-256 locally;
3. only the first 20 digest bits (five hexadecimal characters in the canonical contract representation) leave Identity;
4. the service returns a bounded set of remaining SHA-256 suffixes with non-negative occurrence counts;
5. Identity compares the full local digest against returned suffixes locally and makes the compromised/not-compromised decision.

Raw password and full SHA-256 digest never leave Identity. Response parsing is bounded and malformed/oversized/ambiguous data fails closed as dependency unavailability. The existing 900ms overall deadline, one attempt, no automatic retry, cancellation, bounded concurrency, and fail-closed semantics remain authoritative. The remote call occurs outside any Identity DB transaction.

### Google OIDC/external identity handoff and signup

Google Authorization Code + PKCE S256 protocol mechanics belong to Web BFF under ADR-0016. BFF validates `state`, `nonce`, PKCE, exact redirect, signature, issuer, audience, and timestamps before invoking Identity.

Identity does **not** call Google OIDC endpoints during the login/link request path and never receives Google authorization codes, access tokens, refresh tokens, or ID tokens.

After successful provider validation, BFF invokes the typed Identity gRPC contract using its authenticated workload identity and supplies:

- exactly 256-bit CSPRNG `evidence_id`;
- BFF-generated trusted `evidence_issued_at` immediately after successful provider validation;
- canonical validated `issuer`;
- validated provider `subject`;
- canonical UUIDv4 BFF-owned `request_id`;
- versioned bounded non-secret metadata only: optional validated provider email, corresponding `email_verified`, and optional given/family-name suggestions.

The policy lifetime is not caller-selected. Identity accepts evidence only while `evidence_issued_at + 2 minutes` remains valid, with only the current bounded clock-skew rules. The BFF workload identity, `evidence_id`, issuer, subject, request ID, issued time, and canonical versioned metadata are bound into the evidence/idempotency fingerprint.

Identity keeps spent/replay evidence for at least 10 minutes after consumption. Equal replay of the same `evidence_id` + request identity + fingerprint returns the original committed outcome. Reuse of the same `evidence_id` with a different request/payload/fingerprint returns `ALREADY_EXISTS / OIDC_EVIDENCE_REPLAY` and never creates a second login/link/signup effect.

Stable external identity is only `(issuer, subject)`:

- known `(issuer, subject)` -> establish primary proof for the already-bound User, then apply active User/MFA/session policy; active TOTP still requires the MFA pre-auth continuation before Session/RefreshFamily completion;
- unknown identity may create a new `PENDING` User;
- provider email may become a verified Contact only when `email_verified=true` and the canonical email is not owned/reserved by another non-erased User;
- `email_verified=false` or absent email never creates a Contact automatically; the User remains in onboarding and must use the normal Identity `AddContact` + verification flow when a Contact is required;
- if verified provider email is already owned by another User, Identity does not auto-link and returns `FAILED_PRECONDITION / ACCOUNT_LINK_REQUIRED` without disclosing additional owner data;
- provider email changes on later logins do not silently rebind/update account ownership;
- provider given/family names are suggestions only and do not satisfy required profile completion until the User confirms/updates the Identity-owned profile;
- if a newly created User lacks required profile/verified Contact, it remains `PENDING` and enters onboarding.

Provider credentials and Google client secret are delivered only through the approved OpenBao/External Secrets boundary and never through Git, chat, browser storage, Identity payloads, or telemetry.

Linking an ExternalIdentity requires authenticated account settings plus authentication age <=5 minutes (and current MFA assurance where applicable) or another future reviewed flow with equivalent assurance. Email equality never authorizes linking.

Unlink requires authentication age <=5 minutes. Identity rejects unlink when the target ExternalIdentity is the User's last remaining authentication method with `FAILED_PRECONDITION / LAST_AUTHENTICATION_METHOD`. Successful unlink rotates the current session/refresh credential and revokes all other RefreshFamilies.

### MFA login gate

TOTP uses HMAC-SHA-256, six digits, 30-second step, ±1 step, issuer `SajTech`.

When an account has active TOTP MFA, any successful primary authentication proof—local password or trusted Google OIDC evidence—does **not** issue an access token, refresh credential, or completed Session. It creates a pre-auth challenge with fixed v1 policy:

```text
TTL:                   5 minutes
maximum failed proofs: 5
single use:            yes
```

The challenge is bound to the intended User, primary-authentication transaction/method, and continuation context. A new successful primary proof invalidates any previous live pre-auth challenge for that login continuation. Successful completion invalidates the challenge. The same TOTP timestep accepted for a given enrollment cannot be accepted again as a replay.

Access/refresh/session completion occurs only after that same challenge is completed by a valid TOTP code or one valid single-use recovery code. MFA verification remains subject to current semantic quota/non-enumeration behavior; the challenge-local five-proof cap is not increased by Redis refill policy.

Secrets use a local versioned AES-256-GCM key ring sourced through OpenBao/External Secrets. Recovery set contains ten independent 80-bit codes shown once and stored only as domain-separated HMAC-SHA-256. Consumption is atomic/single-use.

Enrollment, disable, replacement, and recovery require authentication age <=5 minutes. Their successful MFA-state change applies the session rotation/revocation rule above. Trusted devices do not exist in v1.

### SMS MFA

Production Iran SMS MFA is enabled only when all current controls are healthy/verified:

- semantic quota enforcement/time safety;
- Notification durable encrypted exact-content handoff;
- IPPanel Webservice production credentials/contract fixtures;
- provider ambiguity/reconciliation/delivery-evidence behavior;
- Identity MFA/session/recent-auth controls;
- workload/network authorization and PII-safe telemetry.

If TOTP is active, SMS cannot satisfy or downgrade the TOTP login requirement; only TOTP or a valid recovery code may complete that MFA gate. SMS MFA enrollment/use in v1 is available only for accounts without active TOTP and only after the production SMS gates above pass.

The Identity-owned SMS MFA proof uses a purpose-separated challenge namespace and fixed v1 proof semantics:

```text
code format:           exactly 8 decimal digits
randomness:            SecureRandom / CSPRNG
stored verifier:       purpose-separated HMAC-SHA-256 only
plaintext persistence: none
proof lifetime:        expires no later than the enclosing 5-minute pre-auth challenge
maximum failed proofs: 5 across the enclosing pre-auth challenge
minimum resend gap:    60 seconds
replacement:           new SMS proof invalidates the prior SMS proof
single use:            yes
```

Caller/provider input cannot extend the enclosing pre-auth lifetime, attempt budget, resend spacing, or choose code/key policy. SMS proof/registration/contact/password-recovery HMAC namespaces are distinct. Notification handoff follows the existing encrypted exact-content contract; the plaintext code is never stored in ordinary durable state after safe handoff representation creation.

Provider unavailability fails the SMS-dependent operation. The local logging SMS adapter is not a staging/production fallback.

### Data-subject deletion entry point

User-initiated account erasure follows ADR-0028. Identity requires authentication age <=5 minutes and, when MFA is active, current MFA proof before accepting the self-erasure command.

Self-erasure is accepted only after the ADR-0028 Membership precondition is satisfied: no remaining ACTIVE/SUSPENDED Membership for a non-DELETED Tenant. The User must first leave Memberships/transfer last ownership/complete tenant deletion through the normal owner-safe workflow. Erasure never bypasses `PrepareMembershipRemoval`. Pending invitations targeted to the User are revoked at acceptance.

Acceptance transitions the User to non-authenticatable `DELETING`, revokes all RefreshFamilies, and creates the durable erasure coordination state/outbox. A legal hold may block irreversible erasure progress but never restores login/session usability. Legal-hold create/release remains a platform/legal-authorized audited operation, not a self-service caller parameter.

### Idempotency and security evidence

Security-sensitive idempotency/intent fingerprints use purpose-separated, versioned HMAC-SHA-256 with locally available key material delivered through the approved secret boundary. Plain unsalted SHA-256 is not used for guessable security/business intent fingerprints; the local SHA-256 digest used by compromised-password k-anonymity is not an idempotency fingerprint.

For an idempotent command:

- identical `request_id` + equal fingerprint returns the original committed result;
- conflicting reuse of the same `request_id` returns `ALREADY_EXISTS` with a stable machine code;
- comparison does not expose canonical sensitive input;
- retained critical publication/idempotency evidence follows the 35-day recovery horizon where ADR-0015 applies.

Security audit evidence is append-only/durable under the current logging/audit policy and retained at least 365 days unless a stricter approved data-class policy applies.

## Verification requirements

Tests cover:

- feature-scoped Protobuf/Buf compatibility and typed error mapping;
- UUIDv4/request-id/entropy/refresh-credential/timestamp invariants and secret identifier non-reuse;
- server-owned identifiers/TTLs/policy fields and rejection of caller-controlled security policy;
- User lifecycle, local Credential activation requirement, verified email/phone password-login semantics, non-enumeration, profile/contact API invariants, recent-auth primary/remove rules, and last-verified-contact protection;
- pending Contact reservation expiry/non-overwrite/reacquisition behavior;
- tenant isolation, slug tombstones, invitation lifecycle/target/TTL/single-pending/acceptance ownership, default-member provisioning and no arbitrary invitation role;
- Membership lifecycle and owner-safety preparation under concurrent owner removals, crash between preparation/local commit, idempotent finalize/cancel, no auto-expiry unsafe allow, and no remote I/O in DB transactions;
- owner/member/tenant-lifecycle Authorization outbox replay/conflict;
- aggregate boundaries and absence of remote I/O inside DB transactions;
- forced RLS and pooled transaction-local tenant-context negatives;
- tenantless authenticated onboarding, zero/one/many membership selection, stale last-selected preference rejection, tenant switch refresh/BFF rotation, and no ordinary resource access token before selection;
- exact JWT claim allow-list, wildcard-audience rejection, bounded <=30s clock leeway, prohibited permission/role snapshot claims, signing-key compatibility;
- 20-family cap, deterministic oldest-family revocation, logout-current/logout-all, password-change/reset/suspension/deleting/ExternalIdentity-unlink/MFA-change revocation, refresh rotation/reuse, and explicit <=5m plus bounded clock-leeway already-issued access-token residual lifetime;
- password change/recovery recent-auth/MFA requirements, primary-contact-only/non-local-Credential non-enumerating recovery, challenge boundaries, no first-local-Credential creation through reset, no automated password+MFA-loss bypass, and no password-history policy;
- compromised-password SHA-256 prefix-only egress, full digest/raw password non-egress, suffix matching, malformed/oversized response, deadline/cancellation/fail-closed behavior;
- OIDC BFF-only provider validation, provider-token absence from Identity, exact 256-bit evidence, two-minute expiry, >=10-minute spent/replay evidence, replay/conflict/workload-identity negatives, Google signup/profile suggestion behavior, unverified-email no-Contact behavior, verified-email collision `ACCOUNT_LINK_REQUIRED`, and no-auto-link;
- active TOTP after both password and Google primary proof proving no Session/access/refresh issuance before MFA completion;
- ExternalIdentity unlink recent-auth/last-authentication-method/session-revocation rules;
- five-minute/five-failure pre-auth boundaries, new-primary-proof invalidation, TOTP timestep replay rejection, encryption rotation, recovery-code atomic single use, and session revocation after MFA-state change;
- SMS exact eight-digit proof/HMAC-only/no-plaintext/<=5m/five-proof/60s/replacement/single-use semantics, production gate, and no-downgrade when TOTP is active;
- self-erasure recent-auth/MFA/Membership-precondition/pending-invitation/session-revocation/legal-hold behavior;
- recent authentication, idempotency fingerprint replay/conflict, audit, and PII-safe telemetry.

## Rollback considerations

Rollback preserves stable tenant/user/membership/session/external-identity identifiers, slug non-reuse, invitation target/lifecycle binding, membership owner-safety reservations, refresh-family revocation, JWT claim/verifier compatibility, MFA/key lifecycle, BFF-only provider validation, one-time evidence semantics, and durable provisioning/idempotency state.

It MUST NOT reintroduce provider tokens into Identity, direct Google verification from Identity, email auto-link, Google/TOTP bypass, wildcard JWT audiences, token permission snapshots, ordinary resource JWT issuance without an active Membership, access/refresh issuance before required MFA, TOTP replay/SMS downgrade, password-reset creation of a first local Credential, erasure bypass of Membership/last-owner preconditions, read-only race-prone last-owner removal, or SMS through an unverified provider/local logging fallback.
