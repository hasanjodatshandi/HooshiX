# Security Architecture

## 1. Security model

Security is layered and fail closed where authorization, identity assurance, or
security-sensitive quotas cannot be proven.

Primary controls are:

- authenticated end-user/session context;
- tenant isolation;
- resource-service authorization and domain invariants;
- Istio workload identity + strict east-west mTLS;
- NetworkPolicy/native datastore security;
- OpenBao/External Secrets secret lifecycle;
- semantic security quotas;
- dedicated edge WAF;
- signed/provenanced artifact admission;
- PII/secret-safe telemetry.

No single layer substitutes for another.

## 2. Multi-tenancy

ADR-0002 remains authoritative:

```text
Global User
  -> TenantMembership
      -> Tenant
```

A user may belong to multiple tenants. Every tenant-owned row contains non-null
`tenant_id` unless explicitly global. Tenant-owned uniqueness normally includes
`tenant_id`.

Trusted active tenant comes from validated authenticated context. A caller-
controlled `X-Tenant-Id` never establishes tenant trust. Application and
persistence boundaries both enforce isolation and require negative cross-tenant
tests. ADR-0057 additionally requires production tenant-owned PostgreSQL tables
to use `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` with runtime
roles that are `NOSUPERUSER NOBYPASSRLS`; RLS is defense in depth and is not
claimed to defeat a PostgreSQL superuser.

## 3. Tenant lifecycle

Current v1 follows ADR-0038:

- immutable UUID tenant ID;
- immutable canonical slug, never reused after deletion;
- lifecycle `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DELETING`, `DELETED`;
- creator becomes initial owner;
- Tenant + creator membership + audit + owner-provisioning outbox commit in one
  local transaction;
- activation occurs only after idempotent Authorization owner-provisioning ACK.

## 4. Deletion, retention, erasure, legal hold

ADR-0003 platform default:

```text
deleted_at
deleted_by
deletion_reason
```

`deleted_at` is authoritative. Normal queries exclude deleted records by
repository/persistence contract. Deleted-record access, restoration, purge, and
hold operations are explicit, authorized, and audited.

Default retention is 360 days, but expiry creates purge/anonymization
**eligibility**, not automatic destruction. Physical purge is unavailable from
normal APIs/repositories and is idempotent, tenant-safe, observable, and blocked
by legal hold.

Generated technical/security/event/audit IDs are never reused. Database
`ON DELETE CASCADE` is prohibited by default for domain data.

ADR-0058 makes irreversible data-subject erasure executable across bounded
contexts. Identity coordinates a non-PII `erasure_request_id`; each service
erases/anonymizes its owned copies and returns a durable non-PII receipt. Legal
holds block incompatible actions. Restores replay the erasure ledger before
traffic so an old backup cannot silently resurrect erased data. Crypto-shredding
is used only where independently envelope-encrypted material has a destroyable
key; it is not a substitute for erasing ordinary relational PII.

## 5. Browser/BFF/OIDC security

ADR-0045 is current.

Google/future browser OIDC uses Authorization Code Flow + PKCE S256. BFF keeps
single-use `state`, `nonce`, and PKCE verifier server-side with <=10-minute
authorization-transaction lifetime. Redirect URIs match exact registered
values; post-login return targets are bounded same-origin relative paths.

The browser receives no provider token, Identity access/refresh token, internal
gRPC token, trusted role list, or permission list.

Primary browser cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
no Domain attribute
```

Session ID rotates after login, MFA completion, tenant switch, recovery, and
security elevation. BFF session state is server-side in its ACL-isolated
`security-redis` namespace. Idle lifetime <=7d; absolute <=30d.

If the BFF retains an Identity refresh credential, it is AES-256-GCM encrypted
with a BFF-specific local key ring sourced from OpenBao through External
Secrets. Raw refresh credentials never enter browser storage or Redis.

State-changing browser requests require trusted Origin + session-bound
synchronizer CSRF token (`X-CSRF-Token`). Fetch Metadata is defense in depth.
Credentialed wildcard CORS is prohibited; same-origin is preferred.

## 6. Local credentials and MFA

Passwords cross one explicit hashing boundary, are never persisted/logged/
emitted/reversibly encrypted, and are handled through a provider-neutral
Application security port.

Production password baseline:

- Argon2id, memory 19 MiB, iterations 2, parallelism 1;
- unique random 16-byte salt and at least 32-byte derived hash;
- versioned/self-describing encoded format with rehash-on-success when the
  approved baseline increases;
- minimum 15 Unicode code points because password-only login is supported;
- maximum 128 Unicode code points; NFC normalization before hashing;
- spaces/Unicode/password-manager paste allowed; no arbitrary composition rule;
- no periodic forced rotation without compromise evidence;
- password create/change/reset checks the compromised-password service/blocklist;
- password verification runs behind a bounded CPU/memory bulkhead;
- raw password is never trimmed, lowercased, logged, emitted, or persisted.

TOTP v1:

- HMAC-SHA-256;
- 6 digits;
- 30-second step;
- ±1 step;
- issuer `SajTech`;
- secret encrypted with local versioned AES-256-GCM key ring sourced from
  OpenBao;
- recovery set: 10 independent 80-bit codes, shown once and stored only as
  domain-separated HMAC-SHA-256;
- enroll/disable/replace/recovery requires authentication age <=5 minutes;
- no trusted devices in v1.

ADR-0049 selects IPPanel Edge Webservice mode for Iran production SMS. SMS MFA
is production-eligible only after ADR-0041 semantic quotas, the pinned IPPanel
provider contract/credentials, Notification readiness, and ADR-0038 MFA controls
are verified. Provider unavailability fails the SMS-dependent operation; the
local logging adapter is never a production fallback.

## 7. Authorization ownership

ADR-0004 remains authoritative for semantic ownership.
`authorization-service` owns tenant roles, role permissions, membership-role
assignments, direct grants/denies, evaluation, and audit.

Permission keys are exact stable contracts owned semantically by the resource
bounded context. Role inheritance and wildcard assignments are prohibited in
v1.

Precedence:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Final enforcement occurs in the resource-owning service and never overrides
resource tenant ownership or domain invariants.

## 8. Runtime authorization

ADR-0039 + ADR-0056 + ADR-0062 + ADR-0066 are current; ADR-0056 supersedes ADR-0042 latency/overload details only, ADR-0062 defines burn alerting/real-contract recovery, and ADR-0066 de-correlates repeated recovery and serializes half-open probes.

Each protected resource operation performs one final online `CheckPermission`:

```text
deadline: 300 ms
attempts: 1
wait-for-ready: off
retry: none
local cache: none
stale fallback: none
fail closed
```

Authoritative deny -> `PERMISSION_DENIED`. Dependency failure ->
`UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`.

Routine duplicate BFF permission checks are prohibited when the resource service
will make the authoritative check. Safe local JWT/claim/tenant-shape prechecks
reject invalid traffic before Authorization but never grant access. No Bloom
filter or probabilistic/local permission-result cache may make an authoritative
decision. Authorization runs with >=3 replicas, PDB, topology spread, global
and per-workload-principal bounded concurrency, <=25ms server queue wait, and a
fail-closed caller circuit breaker. Production SLO is p95<=100ms/p99<=200ms
inside the unchanged 300ms hard caller deadline; 75/150ms remains an engineering
target. Paging uses paired multi-window burn. Half-open recovery uses real
`CheckPermission` probes rather than a health endpoint; repeated reopen intervals use bounded exponential de-correlation, only one probe is in flight per caller breaker, and tenant tier never changes authorization recovery semantics.

There is no authorization Kafka invalidation topic in v1.

## 9. Token signing and local verification

ADR-0052 completes the RS256 access-token trust model. Identity signs locally
with RSA-3072 private keys sourced from OpenBao through External Secrets. Private
keys exist only in Identity's read-only local key ring.

Token-verifying workloads use a non-secret GitOps public JWK bundle locally;
normal verification never calls Identity/OpenBao/JWKS over the network. Keys use
immutable random `kid` values, rotate every 90 days, pre-publish the next public
key before activation, and retain the previous public key for at least 24 hours.
Emergency compromise rotation may invalidate remaining five-minute tokens.
Algorithm confusion, arbitrary JWKS URLs, unknown keys, issuer/audience mismatch,
and fail-open key lookup are prohibited.

## 10. Platform capabilities

`platform_admin` is a separate global capability profile, not a synthetic tenant
role. Every use is explicit and audited. Platform capability does not silently
grant tenant business permissions.

Tenant SYSTEM roles are immutable. Every active tenant has an owner; the last
owner cannot be removed/demoted.

## 11. Semantic quotas

ADR-0041 resolves ADR-0040's architecture decision gate.

The operation-owning service enforces its quota; there is no quota microservice.
Production uses ACL-isolated namespaces on shared physical `security-redis`:
1 primary, 2 replicas, 3 Sentinel voters, TLS, `noeviction`.

A quota evaluation has a 75ms Redis budget, one attempt, no automatic retry, and
fails protected security operations closed on dependency failure.

Atomic multi-dimension token-bucket/GCRA-equivalent enforcement uses a reviewed
versioned Redis Function/Lua operation. ADR-0054 replaces sole Redis-wall-clock
refill with trusted application wall time + Redis `TIME`, a 2-second maximum
skew, `effective_now=min(redis_time, app_time)`, monotonic stored bucket time,
and fail-closed `QUOTA_TIME_SOURCE_UNHEALTHY` behavior. Security-critical quota
state does not reset merely because a Redis TTL expires; cleanup is bounded and
non-authoritative. Raw PII/IDs are never Redis keys; HMAC-SHA-256 pseudonyms are
purpose/domain separated.

Authentication specifically avoids remote account-lockout: source dimensions
block before credential work, but identifier/account failed-attempt counters are
charged on failed credentials and cannot alone reject a subsequently proven
correct credential after source controls allow the request.

Traefik/WAF coarse rate limits remain defense in depth and never substitute for
semantic quota enforcement.

## 12. Workload identity and mTLS

End-user identity and workload identity are separate mandatory controls where
applicable.

Production application workloads use independent Kubernetes ServiceAccounts and
Istio Ambient strict mTLS. Kubernetes `default` ServiceAccount is prohibited for
production application workloads.

AuthorizationPolicy is deny-by-default and identity-based. Network location/IP
does not replace workload identity. NetworkPolicy remains defense in depth.

## 13. Secrets and cryptographic key delivery

OpenBao 2.6.1 is the authoritative external secret source. External Secrets
Operator is the normal Kubernetes synchronization boundary.

Secret values never enter Git, images, Helm/Kustomize values, logs, traces, or
metrics. Rotating key rings are mounted read-only, not injected as environment
variables for the rotating-key path.

Key purposes are separated; key IDs are immutable and never rebound to different
bytes.

ADR-0043 removes Notification's OpenBao Transit hot path. Notification and BFF
use purpose-specific local AES-256-GCM key rings sourced from OpenBao via
External Secrets. Application hot paths do not make routine OpenBao network
calls.

OpenBao remains a security-sensitive control-plane dependency with encrypted
hourly snapshots and tested recovery.

## 14. Edge security

Canonical public application path:

```text
Internet / External LB
-> Traefik
-> Caddy + Coraza WAF
-> web-bff
```

Direct Internet/Traefik application access to BFF is denied by routing plus
NetworkPolicy/Istio policy. WAF uses CRS PL1, DetectionOnly tuning before
blocking, pinned rules, and bounded payload inspection.

WAF never replaces authentication, authorization, semantic quotas, validation,
output encoding, or secure coding. ADR-0059 additionally requires upstream L3/L4
volumetric DDoS mitigation/scrubbing before the origin link; the in-cluster WAF
is explicitly not treated as protection against bandwidth saturation.

## 15. Supply-chain security

ADR-0046 is current.

CI generates a signed CycloneDX SBOM, provenance, vulnerability results, and
Cosign signature/attestations for immutable image digests. SBOMs are indexed by
service + image digest so a newly disclosed transitive CVE can be correlated to
affected deployable artifacts without rebuilding them. CI uses pinned Syft/Grype
(or an explicitly approved equivalent) tooling and records vulnerability/VEX
exceptions with owner and expiry.

ADR-0065/ADR-0068 turn that inventory into continuous vulnerability response. Final-image
Critical findings block merge/promotion without an approved short-lived exception;
High findings with an available fix block production promotion. Deployed digest
inventory is rescanned at least every six hours so a CVE disclosed after build is
correlated to every affected service/environment. Critical/known-exploited
production findings page Security + owner with <=24h mitigation target; High
findings target <=48h. CISA KEV, approved CVE/ecosystem feeds, and deployed vendor advisories are ingested at least every two hours; a new matching advisory triggers targeted correlation/rescan before the normal six-hour full inventory cycle. KEV membership increases priority but is not described as guaranteed zero-day detection. Expired Critical/High exceptions stop authorizing promotion and escalate running-production exposure. Every service team owns direct and transitive components in its deployed SBOM; Platform owns shared base/runtime artifacts and Security owns scanner/feed/escalation policy. Scanner results never authorize unsigned artifacts or automatically evict running production pods. Production admission uses Kyverno
stable `ImageValidatingPolicy` to require approved registry, digest, signature,
provenance/source/builder, and required attestations.

Fail-closed admission is enabled only after staging/production audit rollout and
Kyverno HA (>=3 replicas, PDB, topology spread). Exceptions are narrow,
Git-reviewed, owned, and time limited.

## 16. Security telemetry

Logging is allow-list based. Raw passwords/OTP/recovery codes/tokens/cookies/
keys/secrets/PII payloads/SQL binds/gRPC metadata/provider payloads are never
logged.

Metric labels are bounded and do not contain raw/pseudonymous user, tenant,
session, request, or resource identifiers.

ADR-0061 enforces PII-safe telemetry with custom Semgrep rules, structured-field
allow-lists, telemetry-pipeline redaction, seeded canary tests against the actual
downstream sink, and out-of-band production leak detection. Raw sensitive values
are never copied into detector alerts.

ADR-0069 and `/docs/engineering/coding-standards.md` add source-level logging rules for CR/LF injection safety, untrusted exception/cause handling, audited time-bounded production debug elevation, non-fatal-but-alertable log-export failure, and least-privilege audited log-store access.

ADR-0060 governs human production access: no standing production admin/root/DB
superuser credentials; privileged access is JIT through Teleport Enterprise
Self-Hosted with SSO, phishing-resistant MFA, approval, short TTL, and recorded/
audited sessions. Workload identity remains Istio/ServiceAccount based.

Security changes require positive and negative tests, including tenant crossing,
authorization deny/outage/overload, workload identity, CSRF/CORS/OIDC replay,
quota time failure, PII/log canary leakage, volumetric-edge controls, privileged
access expiry, secret leakage, WAF bypass, and unsigned-artifact admission denial.
