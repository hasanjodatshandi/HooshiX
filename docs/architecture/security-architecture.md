# Security Architecture

## 1. Security model

Security is layered and fail closed when identity assurance, authorization, or security-sensitive dependency state cannot be proven.

Primary controls:

- authenticated end-user/session context;
- tenant isolation;
- resource-service authorization and domain invariants;
- Istio workload identity + strict east-west mTLS;
- NetworkPolicy and native datastore security;
- OpenBao/External Secrets lifecycle;
- semantic security quotas;
- dedicated edge WAF plus upstream volumetric-DDoS controls;
- signed/provenanced artifact admission and continuous vulnerability response;
- PII/secret-safe telemetry;
- JIT privileged production access.

No single layer substitutes for another.

## 2. Multi-tenancy

ADR-0002 defines the current tenant model:

```text
Global User
  -> TenantMembership
      -> Tenant
```

A user may belong to multiple tenants. Every tenant-owned row contains non-null `tenant_id` unless explicitly global. Tenant-owned uniqueness normally includes `tenant_id`.

Trusted active tenant comes from validated authenticated context. Caller-controlled `X-Tenant-Id` never establishes trust. Application and persistence boundaries both enforce isolation and require negative cross-tenant tests.

Production tenant-owned PostgreSQL tables use `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`; runtime roles are `NOSUPERUSER NOBYPASSRLS`, are not table owners, and cannot cross another service database. RLS is defense in depth and is not claimed to defeat a PostgreSQL superuser.

## 3. Tenant lifecycle

Current v1 lifecycle:

- immutable UUID tenant ID;
- immutable canonical slug, never reused after deletion;
- `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DELETING`, `DELETED`;
- creator becomes initial owner;
- Tenant + creator membership + audit + owner-provisioning outbox commit in one local transaction;
- activation occurs only after idempotent Authorization owner-provisioning acknowledgement.

## 4. Deletion, retention, erasure, legal hold

Logical deletion baseline:

```text
deleted_at
deleted_by
deletion_reason
```

`deleted_at` is authoritative. Normal queries exclude deleted records by persistence/repository contract. Deleted-record access, restoration, purge, and hold operations are explicit, authorized, and audited.

Default retention is 360 days, but expiry creates purge/anonymization eligibility rather than automatic destruction. Physical purge is unavailable from normal repositories/APIs and is idempotent, tenant-safe, observable, and blocked by legal hold. Generated technical/security/event/audit IDs are never reused. Database `ON DELETE CASCADE` is prohibited by default for domain data.

Irreversible data-subject erasure is coordinated by Identity using a non-PII `erasure_request_id`; each service erases/anonymizes owned copies and returns durable non-PII evidence. Legal holds block incompatible actions. Restore procedures replay erasure evidence before traffic so backup restore cannot silently resurrect erased data. Crypto-shredding is valid only for independently envelope-encrypted material with destroyable keys; it is not a substitute for erasing ordinary relational PII.

## 5. Browser/BFF/OIDC security

Browser OIDC uses Authorization Code + PKCE S256. BFF keeps single-use `state`, `nonce`, and PKCE verifier server-side with <=10-minute authorization-transaction lifetime. Redirect URIs match exact registered values; post-login return targets are bounded same-origin relative paths.

The browser receives no provider token, Identity access/refresh token, internal gRPC credential, trusted role list, or permission list.

Primary browser cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
no Domain attribute
```

Session ID rotates after login, MFA completion, tenant switch, recovery, and security elevation. BFF session state is server-side in its ACL-isolated `security-redis` namespace. Idle lifetime <=7d; absolute <=30d.

If BFF retains an Identity refresh credential, it is AES-256-GCM encrypted with a BFF-specific local key ring sourced from OpenBao through External Secrets. Raw refresh credentials never enter browser storage or Redis.

State-changing browser requests require trusted Origin + session-bound synchronizer CSRF token (`X-CSRF-Token`). Fetch Metadata is defense in depth. Credentialed wildcard CORS is prohibited; same-origin is preferred.

## 6. Local credentials, external identities, and MFA

Passwords cross one explicit hashing boundary, are never persisted/logged/emitted/reversibly encrypted, and are handled through a provider-neutral Application security port.

Production password baseline:

- Argon2id: memory 19MiB, iterations 2, parallelism 1;
- unique random 16-byte salt and >=32-byte derived hash;
- versioned/self-describing encoded format with rehash-on-success when baseline increases;
- 15..128 Unicode code points; NFC normalization before hashing;
- spaces/Unicode/password-manager paste allowed; no arbitrary composition rule;
- no periodic forced rotation without compromise evidence;
- create/change/reset checks compromised-password service/blocklist;
- hashing/verification use bounded CPU/memory concurrency;
- raw password is never trimmed, lowercased, logged, emitted, or persisted.

External identity linking uses stable issuer + subject. Email address alone is never automatic account-linking authority.

TOTP v1:

- HMAC-SHA-256;
- 6 digits;
- 30-second step;
- ±1 step;
- issuer `SajTech`;
- secret encrypted with a local versioned AES-256-GCM key ring sourced from OpenBao;
- 10 independent 80-bit recovery codes, displayed once and stored only as domain-separated HMAC-SHA-256;
- enroll/disable/replace/recovery requires authentication age <=5 minutes;
- no trusted-device bypass in v1.

Production Iran SMS uses IPPanel Edge Webservice mode. SMS MFA is production-eligible only when semantic quotas, pinned provider contract/credentials, Notification encrypted exact-content lifecycle, provider ambiguity/delivery tests, and Identity MFA controls pass. Provider failure does not activate a local logging adapter or unreviewed fallback.

## 7. Authorization ownership

Authorization Service owns tenant roles, role permissions, membership-role assignments, direct grants/denies, evaluation, and audit. Permission keys are exact stable contracts semantically owned by the resource bounded context. Role inheritance and wildcard assignments are prohibited in v1.

Precedence:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Final enforcement occurs in the resource-owning service and never overrides resource tenant ownership or domain invariants.

## 8. Runtime authorization

Current online Authorization is defined by ADR-0039, ADR-0055, ADR-0056, ADR-0062, ADR-0063, and ADR-0066.

Each protected resource operation performs one final online `CheckPermission`:

```text
deadline: 300 ms
attempts: 1
wait-for-ready: off
retry: none
permission-result cache: none
stale fallback: none
fail closed
```

Authoritative deny -> `PERMISSION_DENIED`. Dependency failure/open breaker -> `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`. Healthy Authorization saturation -> `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED`, mapped by callers to fail-closed dependency unavailability rather than an authorization deny.

Routine duplicate BFF permission checks are prohibited when the resource service makes the authoritative check. Safe local JWT/claim/tenant/syntax prechecks may reject invalid traffic but never grant access. No Bloom filter, signed permission list, local permission cache, or Kafka invalidation path may make an authoritative decision.

Production target:

- >=3 replicas;
- PDB/topology spread;
- global + per-caller-principal bounded concurrency;
- <=25ms server queue wait before shedding;
- bounded PostgreSQL pool/query path;
- availability >=99.95% rolling 30d;
- p95<=100ms / p99<=200ms production SLO; 75/150ms engineering target;
- Hikari acquisition p99<25ms at validated steady target load;
- >=2x projected-peak launch evidence with >=30% resource/database headroom.

Paging uses paired multi-window burn. Caller breakers fail closed, de-correlate repeated reopen intervals per instance, serialize one real `CheckPermission` half-open probe at a time, and require three consecutive infrastructure-successful probes to close. A health endpoint never closes the breaker. Commercial/tenant tier does not change security breaker semantics.

## 9. Token signing and local verification

Identity signs locally with RSA-3072/RS256 private keys sourced from OpenBao through External Secrets. Private keys exist only in Identity's read-only local key ring.

Token-verifying workloads use a non-secret GitOps public JWK bundle locally; normal verification never calls Identity/OpenBao/JWKS over the network. Keys use immutable random `kid`, rotate every 90 days, pre-publish the next public key, and retain the previous public key for at least 24 hours. Emergency compromise rotation may invalidate remaining five-minute tokens.

Algorithm confusion, arbitrary JWKS URLs, unknown keys, issuer/audience mismatch, and fail-open key lookup are prohibited.

## 10. Platform capabilities

`platform_admin` is a separate global capability profile, not a synthetic tenant role. Every use is explicit/audited and does not silently grant tenant business permissions.

Tenant SYSTEM roles are immutable. Every active tenant has an owner; the last owner cannot be removed/demoted.

## 11. Semantic quotas

The operation-owning service enforces its own security quota; there is no quota microservice.

Production uses ACL-isolated namespaces on shared physical `security-redis`: one primary, two replicas, three Sentinel voters, TLS, `noeviction`.

A quota evaluation has a 75ms Redis budget, one attempt, no automatic retry, and fails protected security operations closed on dependency failure.

Atomic multi-dimension token-bucket/GCRA-equivalent enforcement uses a reviewed versioned Redis Function/Lua operation. Current time-safety rules use trusted application wall time + Redis `TIME`, <=2s allowed skew, `effective_now=min(redis_time, app_time)`, monotonic stored bucket time, and fail-closed `QUOTA_TIME_SOURCE_UNHEALTHY` behavior. Security-critical quota state does not reset merely because TTL expires; cleanup is bounded and non-authoritative. Raw PII/IDs are never Redis keys; HMAC-SHA-256 pseudonyms are purpose/domain separated.

Authentication avoids remote account lockout: source dimensions block before credential work, while identifier/account failed-attempt counters are charged on failed credentials and cannot alone reject a subsequently proven correct credential after source controls allow the request.

Traefik/WAF coarse limits remain defense in depth and never substitute for semantic quotas.

## 12. Workload identity and east-west security

End-user identity and workload identity are separate mandatory controls.

Production application workloads use independent Kubernetes ServiceAccounts and Istio Ambient strict mTLS. Kubernetes `default` ServiceAccount is prohibited for production application workloads.

AuthorizationPolicy is deny-by-default and identity-based. Network location/IP does not replace workload identity. NetworkPolicy remains independent defense in depth. Positive and negative authorization tests are required for changed service-to-service edges.

## 13. Secrets and cryptographic key delivery

OpenBao 2.6.1 is the authoritative external secret source. External Secrets Operator is the normal Kubernetes synchronization boundary.

Secret values never enter Git, images, Helm/Kustomize values, logs, traces, or metrics. Rotating key rings are mounted read-only rather than injected through ordinary environment variables. Key purposes are separated; key IDs are immutable and never rebound to different bytes.

Notification and BFF use purpose-specific local AES-256-GCM key rings sourced from OpenBao via External Secrets. Application hot paths do not make routine OpenBao network calls.

OpenBao is security-sensitive control-plane infrastructure with encrypted hourly snapshots and tested recovery.

## 14. Edge security

Canonical public path:

```text
Internet / upstream L3/L4 mitigation
-> external load balancing
-> Traefik
-> Caddy + Coraza WAF
-> web-bff
```

Direct Internet/Traefik application access to BFF is denied by routing + NetworkPolicy + Istio policy. WAF uses CRS PL1, DetectionOnly tuning before blocking, pinned rule versions, bounded payload inspection, and PII-safe telemetry.

WAF never replaces authentication, authorization, semantic quotas, validation, output encoding, or secure coding. Upstream volumetric DDoS mitigation/scrubbing must protect the origin link; the in-cluster WAF is not bandwidth-saturation protection.

## 15. Supply-chain security and vulnerability response

CI produces signed CycloneDX SBOM, provenance, vulnerability results, and Cosign signature/attestations for immutable image digests. SBOMs are indexed by service + image digest so newly disclosed vulnerabilities can be correlated to deployed artifacts without rebuilding them.

Production admission verifies approved registry, immutable digest, organization signature, provenance/source/builder, and required attestations through the current Kyverno image-validation policy. Kyverno runs HA before fail-closed admission enforcement.

Deployed digest inventory is continuously rescanned/correlated with approved vulnerability/advisory/threat-intelligence inputs. Current response policy includes frequent targeted correlation on new relevant advisories, expiring exceptions, deterministic component ownership, and production remediation/escalation targets. Scanner/feed success is never described as proof of zero unknown vulnerabilities and never authorizes an unsigned artifact.

## 16. Runtime/container security

Production application workloads use:

- immutable digest;
- non-root execution;
- `allowPrivilegeEscalation=false`;
- Linux capabilities dropped by default;
- `RuntimeDefault` seccomp;
- read-only root filesystem where compatible;
- finite resources/probes/graceful shutdown;
- dedicated ServiceAccount;
- deny-by-default NetworkPolicy;
- least-privilege Istio authorization.

Privileged containers, host networking, `hostPath`, added Linux capabilities, or relaxed security contexts require an explicit current security decision plus automated policy validation.

## 17. Security telemetry and privileged access

Logging is allow-list based. Raw passwords/OTP/recovery codes/tokens/cookies/keys/secrets/payment/bank/high-risk identity data/full sensitive payloads/SQL binds/complete gRPC metadata/Kafka headers/provider payloads are never logged.

Ordinary PII appears only for an approved purpose with masking/tokenization or managed-key HMAC pseudonymization when correlation is required. Input-derived fields are protected against CR/LF/log-injection. Exception/cause/provider text is treated as untrusted until explicitly sanitized.

Metric labels are bounded and do not contain raw or pseudonymous user/tenant/session/request/resource identifiers, trace IDs, raw URLs, or free-form errors.

Source Semgrep rules, structured-field allow-lists, telemetry-pipeline redaction, seeded canary tests through the real sink, and runtime leak detection provide defense in depth. Raw canary/sensitive values are never copied into detector alerts.

Human production access has no standing admin/root/DB-superuser credential. Privileged access is JIT through Teleport Enterprise Self-Hosted with SSO, phishing-resistant MFA, approvals, short TTL, least privilege, and recorded/audited sessions. Workload identity remains Istio/ServiceAccount based.

Security changes require applicable positive and negative tests including tenant crossing, Authorization deny/outage/overload, workload identity, CSRF/CORS/OIDC replay, quota time failure, secret/PII logging canaries, edge bypass, volumetric controls, privileged-access expiry, secret leakage, and unsigned-artifact admission denial.
