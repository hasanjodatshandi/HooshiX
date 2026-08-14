# Threat Model — Current State

This document is the formal design-time threat model for HooshiX. It does not prove that implementation or runtime controls exist. `PRODUCTION-READINESS-CHECKLIST.md` and `security-verification-matrix.md` remain the evidence gates.

## 1. Scope

This model covers the current platform architecture, with emphasis on:

- public edge and browser/BFF trust;
- Identity, MFA, sessions, and token issuance;
- Authorization and tenant isolation;
- service-to-service workload identity;
- PostgreSQL, Redis, Kafka, and immutable reference data;
- OpenBao and secret delivery;
- Git/CI/supply chain;
- human privileged access;
- backup, restore, erasure, and disaster recovery;
- `production-single-server` host concentration risk.

## 2. Security objectives

The platform protects these primary properties:

1. only authenticated and authorized actors gain authority;
2. tenant data and authority do not cross tenant boundaries;
3. secrets, credentials, MFA material, and private keys remain confidential;
4. mutable business state remains correct and recoverable;
5. security dependencies fail closed instead of fabricating success;
6. audit and recovery evidence remains attributable and durable;
7. software artifacts are reviewed, immutable, signed, and traceable;
8. public traffic cannot bypass the approved edge/WAF/BFF path;
9. restored historical data does not revive erased or legally blocked authority;
10. non-HA availability trade-offs never become security downgrades.

## 3. High-value assets

| Asset | Main security property |
| --- | --- |
| User credentials, MFA seeds, recovery codes | confidentiality + integrity |
| Identity sessions, refresh credentials, signing keys | confidentiality + integrity + revocation correctness |
| Tenant Membership and Authorization state | integrity + tenant isolation |
| PostgreSQL mutable business data | confidentiality + integrity + recoverability |
| OpenBao secret material and recovery shares | confidentiality + integrity + recoverability |
| BFF session/pre-auth Redis state | integrity + fail-closed availability |
| Semantic quota state | integrity + anti-bypass |
| Kafka publication/replay evidence | integrity + reconstructability |
| Signed images, provenance, SBOM, GitOps state | integrity + provenance |
| Security/audit records | integrity + availability + attribution |
| Backups/WAL/OpenBao snapshots | confidentiality + integrity + recoverability |
| Erasure/legal-hold evidence | integrity + ordering correctness |
| Client network identity used by abuse controls | integrity + privacy |
| Human production identities and JIT grants | integrity + attribution |

## 4. Trust boundaries

### TB-01 Browser -> public edge

The browser is untrusted input. Cookies, headers, URLs, bodies, OIDC callbacks, and forwarded headers are attacker-controlled until validated.

Controls include BFF-only public API, request bounds, WAF, CSRF, Origin/Fetch Metadata, exact redirect validation, OIDC state/nonce/PKCE, and ADR-0043 client-address sanitization.

### TB-02 External L4 -> Traefik -> WAF -> BFF

Only the reviewed edge chain is trusted to derive client network identity. Caller forwarding headers are not authority. Direct bypass paths are prohibited.

### TB-03 BFF -> internal services

BFF is an authenticated workload, not business authority for backend domains. It may carry trusted server-derived identity context only under typed contracts. Resource-owning services still enforce final authorization/domain rules.

### TB-04 Service -> Authorization

Authorization is authoritative for permission decisions. Callers may reject locally but cannot fabricate ALLOW. Failure is not denial and is never success.

### TB-05 Service -> service-owned persistence

Database identity and tenant context are trusted only after service authentication/authorization. Cross-service SQL is prohibited. Tenant tables use forced RLS and transaction-local context.

### TB-06 Workloads -> Redis/Kafka/OpenBao/providers

Each dependency has explicit identity, egress, credentials, deadlines, failure behavior, and data classification. A dependency cannot be used as a hidden authority outside its owning contract.

### TB-07 Git/CI -> production artifacts/GitOps

Pull-request input and build artifacts are untrusted until reviewed and verified. Privileged workflows cannot execute unreviewed PR-controlled code with production-capable credentials. Production uses immutable signed/provenanced artifacts.

### TB-08 Human operator -> management plane

Network admission, human authentication, and privilege are separate gates. For single-server: WireGuard network admission -> FIDO2 OpenSSH identity -> approved time-bounded JIT elevation.

### TB-09 Backup/recovery environment -> restored production

Restored data is untrusted for current authority until integrity, schema, RLS, erasure/legal-hold, secrets, workload identity, edge, and security gates pass.

## 5. Threat actors

| Actor | Capability assumption |
| --- | --- |
| Internet attacker | arbitrary public requests, spoofed headers, credential stuffing, protocol abuse, DDoS participation |
| Compromised browser/client | valid user session plus attacker-controlled browser inputs/scripts within the compromised client |
| Malicious tenant user | valid tenant membership with limited permissions, attempts horizontal/vertical escalation |
| Malicious tenant admin | broad tenant authority but no platform/cross-tenant authority |
| Compromised workload | one service identity/runtime compromised, attempts lateral movement/data access |
| Compromised service credential | valid DB/Redis/Kafka/provider credential for one owner |
| Compromised CI/GitHub identity | attempts artifact or GitOps manipulation |
| Compromised operator device | may possess a management-network peer key but not necessarily FIDO2/JIT authority |
| Malicious/compromised privileged operator | time-bounded legitimate privileged capability, potential insider misuse |
| Compromised host/root | full single-host process/storage visibility; major single-server blast radius |
| Compromised OpenBao token/recovery material | attempts secret extraction or secret-authority takeover |
| Compromised external provider | email/SMS/OIDC/upstream-network provider sends malformed, replayed, delayed, or false information within its protocol scope |
| Storage/backup attacker | tampers with, deletes, or exfiltrates backup/recovery artifacts |

## 6. STRIDE review

| Category | Representative threat | Primary controls | Required evidence |
| --- | --- | --- | --- |
| Spoofing | forged `X-Forwarded-For` changes network quota identity | ADR-0043 trusted PROXY chain; Caddy strict proxy parsing; BFF internal derived address only | header-spoof/PROXY negative tests |
| Spoofing | forged workload identity calls internal service | Istio strict mTLS + ServiceAccount identity + NetworkPolicy | wrong-workload connectivity/authz tests |
| Spoofing | email equality links external account | issuer + subject binding; no email-only auto-link | collision/link tests |
| Tampering | tenant context changed on pooled DB connection | parameterized transaction-local context + FORCE RLS + NOBYPASSRLS | cross-tenant pool reuse tests |
| Tampering | build/deploy artifact changed after review | digest pin + signature + provenance + SBOM + admission | wrong digest/signer/attestation tests |
| Tampering | restored backup contains obsolete/deleted authority | erasure/legal-hold replay before traffic | restore reconciliation evidence |
| Repudiation | privileged operator denies action | attributable FIDO2 identity + JIT approval + OS/sudo/Kubernetes/DB audit + off-host copy | audit integrity and session exercise |
| Information disclosure | secrets/tokens/PII appear in logs | allow-list logging, redaction, canary/runtime detection | static/runtime leak tests |
| Information disclosure | one service reads another DB | distinct databases/roles/privileges; no cross-service SQL | privilege-negative tests |
| Denial of service | Authorization/Redis failure blocks protected work | bounded deadlines/bulkheads; explicit fail-closed semantics; capacity/SLO controls | overload/failure tests; no fabricated ALLOW |
| Denial of service | single host fails | accepted non-HA profile + cold DR + off-host backups | quarterly cold-DR/RTO evidence |
| Denial of service | WAF/mesh/admission consumes host capacity | complete-stack benchmark; >=30% headroom; no security bypass | simultaneous load/IO/security benchmark |
| Elevation of privilege | tenant admin assigns stronger rights than allowed | Authorization privilege-escalation rules and owner safety | admin negative/concurrency tests |
| Elevation of privilege | network access becomes root access | WireGuard != human identity != JIT privilege | peer/FIDO/JIT separation tests |
| Elevation of privilege | browser/BFF role claim grants backend access | no role/permission authority in browser; final resource-owner CheckPermission | forged-claim and final-authz tests |

## 7. Critical abuse cases

### TM-01 Client-IP spoofing to evade abuse controls

Attacker sends forwarding headers or malformed address forms to reset/shift network quotas.

Required result:

- caller headers do not become authority;
- one canonical server-derived IP is used;
- IPv4-mapped IPv6/textual aliases cannot create separate budgets;
- missing trusted identity fails closed for quota-required operations.

### TM-02 WAF bypass

Attacker or operator attempts direct Traefik -> BFF or Internet -> BFF traffic.

Required result: route, NetworkPolicy, and Istio controls deny the bypass. WAF outage does not create a direct route.

### TM-03 Cross-tenant data access

Authenticated user changes tenant/resource identifiers to access another tenant.

Required result: authenticated tenant context, application checks, Authorization, and forced RLS deny the request. Missing tenant context fails closed.

### TM-04 Authorization dependency manipulation

Attacker causes timeout/breaker/dependency failure and expects fail-open access.

Required result: no ALLOW is fabricated. Permission results are not cached as stale authority and no retry layer changes semantics.

### TM-05 Credential stuffing / MFA downgrade

Attacker uses stolen password or external identity and tries weaker Email/SMS proof instead of active TOTP.

Required result: semantic quotas apply, TOTP/recovery rules remain required, and provider login is only primary proof when MFA is active.

### TM-06 Session theft/replay

Attacker obtains browser session material or replays refresh/pre-auth/OIDC state.

Required result: secure HttpOnly cookies, server-side state, HMAC locators, rotation/revocation, single-use OIDC/pre-auth evidence, and bounded lifetime prevent authority extension.

### TM-07 Compromised service lateral movement

One workload is compromised and attempts to reach another service database, Redis namespace, Kafka capability, OpenBao path, or arbitrary Internet destination.

Required result: workload identity, NetworkPolicy, Istio policy, DB roles, ACLs, Kafka ACLs, OpenBao policy, and egress allow-lists limit blast radius.

### TM-08 Supply-chain substitution

Attacker modifies dependency, build output, image, deployment, or CI workflow.

Required result: PR review, dependency verification/locks, static checks, signed provenance/SBOM, immutable digest promotion, and Kyverno admission block unapproved artifacts.

### TM-09 Privileged access compromise

Attacker steals a management peer key, SSH credential, or JIT grant.

Required result: independent WireGuard peer, FIDO2 user presence/verification, time-bounded JIT, least privilege, revocation, and durable audit prevent one factor from being sufficient.

### TM-10 Backup/restore rollback of security state

Attacker or operator restores old data and unintentionally revives erased user authority, old tenant state, or incompatible schema.

Required result: isolated restore, integrity/Flyway/RLS checks, erasure/legal-hold replay, current secret/policy restoration, and traffic gate.

### TM-11 Compromised host in single-server profile

Root compromise can expose multiple platform components because all run on one physical server.

Required result: contain host, revoke/rotate affected credentials, preserve off-host audit, rebuild from trusted artifacts, restore business data/secrets from protected recovery sources, and do not claim same-host isolation as a security boundary against root.

Residual risk: `production-single-server` has a large host-level blast radius. This is accepted only with explicit owner acceptance, hardening, recoverability, and a migration trigger to `production-ha` when risk is no longer acceptable.

### TM-12 Provider ambiguity or compromise

Email/SMS/OIDC/upstream providers may timeout, replay, return malformed data, or be compromised.

Required result: provider-specific validation, stable idempotency, explicit ambiguous outcomes, bounded retry/reconciliation, exact OIDC issuer/audience validation, and no provider response becomes broader authority than its contract permits.

## 8. Single-server residual risks

The selected initial profile deliberately retains these residual risks:

- one host failure can stop the complete platform;
- host/root compromise has a broad local blast radius;
- one PostgreSQL process/storage failure domain affects multiple service databases;
- Redis and Kafka have no same-profile failover;
- Kyverno and other control-plane components have lower availability;
- maintenance can consume service error budget.

These risks do not permit weaker MFA, Authorization, RLS, OpenBao, WAF, admission, audit, backup, or workload-identity controls.

Migration to `production-ha` is required when business availability, repeated incidents, recovery RTO, physical-isolation need, or capacity evidence makes the residual risk unacceptable.

## 9. Security verification mapping

Every material threat maps to at least one executable control/evidence location:

- authentication/MFA/session: Identity/BFF service tests + security verification matrix;
- Authorization/elevation: Authorization tests + dependency failure tests;
- tenant isolation: RLS/role/pool negative tests;
- client-address/WAF/public edge: ADR-0043 + network architecture + edge negative tests;
- workload lateral movement: NetworkPolicy/Istio positive/negative tests;
- supply chain: CI + signature/provenance/SBOM/admission tests;
- secrets: OpenBao/ESO/key-rotation/recovery tests;
- privileged access: FIDO2/WireGuard/JIT/audit/break-glass tests;
- recovery: backup/PITR/cold-DR/erasure replay exercises;
- logging/privacy: static/runtime leak and cardinality tests;
- capacity/DoS: full-stack benchmark and overload/chaos evidence.

A documented mitigation without executed evidence remains `NOT VERIFIED`.

## 10. Threat-model change triggers

Review this model when any of these changes:

- public proxy/L4/CDN/WAF topology;
- client-address derivation;
- authentication/MFA/session/token behavior;
- service or bounded-context boundary;
- authorization authority/fallback/cache behavior;
- tenant isolation or persistence model;
- new datastore/provider/Internet egress;
- OpenBao/secret model;
- CI/build/signing/admission model;
- human production access;
- production topology/profile;
- backup/restore/erasure/legal-hold behavior;
- a security incident reveals a new abuse path.
