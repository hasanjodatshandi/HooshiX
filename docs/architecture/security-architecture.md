# Security Architecture — Current State

Security is layered and fails closed when identity assurance, authorization, or security-significant dependency state cannot be proven. No infrastructure profile may use reduced availability as justification to reduce security assurance.

ADR-0042 selects `production-single-server` as the initial production topology. It changes selected infrastructure topology only. **OpenBao, end-user MFA, tenant isolation, workload identity, signed-artifact admission, semantic quota correctness, WAF/DDoS controls and authorization semantics remain security invariants.**

`threat-model.md` is the formal design-time threat-model authority. `network-architecture.md` and ADR-0043 define production client-address trust, public network boundaries, and the single-server management plane. Documentation is not runtime evidence; applicable rows in `security-verification-matrix.md` and `PRODUCTION-READINESS-CHECKLIST.md` must execute before production approval.

## 1. Tenant and identity trust

- User identity is global; tenant authority is membership/context scoped.
- Active tenant context is derived only from validated authenticated state.
- Tenant-owned PostgreSQL tables use forced RLS plus application/repository tenant checks.
- Runtime PostgreSQL roles are non-owner `NOSUPERUSER NOBYPASSRLS` and cannot access another service database.
- Tenant context is installed only with parameterized transaction-local semantics; session-scoped tenant state on pooled connections is prohibited.
- Missing/malformed tenant context fails closed.
- External identities bind by stable issuer + subject. Email alone never auto-links an external identity.
- Logical deletion, erasure, legal-hold and authority-removal semantics are owned by current Identity/erasure ADRs and are unchanged by production profile.

The single-server shared physical PostgreSQL instance increases the blast radius of host/superuser compromise. It does **not** reduce database/role/RLS separation. Human physical/superuser access is therefore time-bounded, approved and audited under ADR-0030. Root compromise of the only physical host remains a documented residual risk in `threat-model.md`; same-host workload boundaries are not represented as protection from host root.

## 2. Browser and BFF trust boundary

Browser-facing security remains owned by ADR-0016 and `services/web-bff.md`:

- browser uses only the Web BFF public API;
- OAuth/OIDC uses Authorization Code + PKCE S256 with exact state/nonce/pre-auth controls;
- provider/internal service tokens are never exposed to browser JavaScript;
- sessions/pre-auth state are server-side and located through purpose/version-separated HMAC identifiers;
- refresh credentials are protected with current AES-256-GCM key-ring rules;
- session rotation/revocation is atomic under the current BFF contract;
- synchronizer CSRF + Fetch Metadata rules remain mandatory for unsafe authenticated methods;
- v1 is same-origin and uses the current strict CORS/CSP/cache rules;
- BFF token brokerage uses server-owned exact-audience mapping; callers cannot choose arbitrary audiences or downstreams;
- final protected-resource authorization remains in the resource-owning service;
- browser/public forwarding headers are not client-network authority; BFF accepts only the ADR-0043 server-derived internal client address on its WAF-only ingress path.

ADR-0042 changes none of these browser/security rules.

## 3. Authentication and MFA — unchanged

Identity authentication/MFA semantics remain owned by ADR-0012 and `services/identity-service.md`.

- current password, compromised-password, Google external identity, session and recovery controls remain unchanged;
- TOTP remains the required second factor where the current Identity state requires it;
- Email/SMS verification/recovery is not a freely selectable weaker bypass around active TOTP;
- enrollment, disable and recovery remain protected by current proof/quota/audit rules;
- responses remain non-enumerating where the current authentication contract requires it.

A production infrastructure profile MUST NOT alter factor assurance. Any future change to factor selection requires a separate threat-model/security decision.

ADR-0009 intentionally defines case-insensitive HooshiX email identity equality while preserving a case-preserving delivery representation. This is an application identity rule and does not authorize email-only external-identity linking or weaker verification.

## 4. Compromised Password and reference-data security

Compromised Password remains self-contained and fail closed:

- Identity computes SHA-256 locally and sends only the 20-bit/five-uppercase-hex prefix;
- Compromised Password performs bounded exact lookup in the immutable read-only SQLite artifact;
- the full digest remains in Identity for final comparison;
- no raw password/full digest/subject identity is stored in the dataset or sent to an external provider;
- no runtime HIBP/Internet lookup, PostgreSQL, Redis or Kafka authority exists for this service;
- only the approved Identity workload may call the lookup;
- dataset corruption/incompatibility/unavailability fails closed.

Reference Data remains global, immutable, non-tenant and image-bundled. It has no runtime standards-source Internet synchronization and no database/Redis/Kafka authority. Its initial internal caller is Web BFF.

Service-doc replicated deployment targets apply to `production-ha`; the single-server profile uses one replica without weakening these security semantics.

## 5. Authorization

Authorization remains online, authoritative and fail closed under ADR-0013/0026/0032/0036:

- success-is-ALLOW; errors/timeout/breaker state do not fabricate allow;
- one authoritative `CheckPermission` for protected resource operations;
- one attempt, no permission-result cache, no Kafka invalidation authority, no stale-allow fallback and no automatic retry;
- safe local prechecks may reject but never grant authority;
- resource owner performs final resource authorization;
- platform capability checks are separate and cannot bypass tenant/resource authorization;
- administration paths prevent privilege escalation and retain idempotency/audit rules;
- owner-safety/membership-removal invariants remain atomic under their current contract.

The single-server profile may reduce availability but MUST NOT convert Authorization dependency failure to fail open.

## 6. Semantic security quotas and Redis

ADR-0024 owns semantic quota correctness. In both production profiles:

- service owning the operation owns the quota policy;
- Redis uses TLS and per-owner ACL/key namespaces;
- raw subject/contact/session/network identifiers do not appear where pseudonymous HMAC keys are required;
- quota decisions are atomic;
- dual trusted time validates app/Redis skew with the current <=2s bound;
- authoritative quota state is not reset merely by TTL expiry;
- one Redis attempt, no retry/fallback; dependency/time-source failure is distinct from quota denial and fails closed according to the operation contract;
- `noeviction` is mandatory;
- anti-lockout semantics remain mandatory.

For a public operation with a network quota dimension, only ADR-0043 trusted client-network context is accepted. Internet-supplied `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, or the internal private header name are not quota authority. Missing, malformed, or proxy-address network context fails closed when that dimension is required. IPv4/IPv6 canonicalization is completed before HMAC derivation, and the raw address is not ordinary telemetry or durable application state.

`production-single-server` uses one Redis instance with AOF `appendfsync everysec`. It has no failover claim. AOF reduces restart loss but does not create HA. Lost session state requires re-authentication.

`production-ha` uses the current primary/replicas/Sentinel topology.

## 7. Workload identity, mTLS and NetworkPolicy

Production application workloads use:

- dedicated ServiceAccounts;
- Istio Ambient STRICT mTLS;
- trust domain `prod.sajtech.internal`;
- least-privilege Istio authorization policies;
- Calico deny-by-default NetworkPolicy with explicit ingress/egress;
- positive and negative authorization/connectivity tests.

Kubernetes `default` ServiceAccount is prohibited for production application workloads.

The single-server K3s profile disables Flannel and the K3s network-policy controller so Calico remains authoritative. Ambient is retained and benchmark-gated. If the host cannot fit Ambient while maintaining the approved capacity envelope, production readiness fails. Workload identity/mTLS MUST NOT be silently disabled for memory savings.

Waypoints are absent by default and require an explicit L7 need plus capacity/security evidence.

## 8. Public edge, client-address trust, and abuse protection

The public path remains:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> external L4
-> repository-pinned Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

ADR-0043 defines source-address authority:

- external L4 preserves the validated original client source with PROXY protocol v2;
- Traefik trusts PROXY only from exact reviewed external-L4 source CIDRs;
- Traefik insecure PROXY/forwarded-header trust is prohibited;
- public/client-supplied `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`, `X-Forwarded-Host`, and private client-IP header values are not authority;
- Caddy trusts only the required Traefik source range and uses strict trusted-proxy parsing;
- Caddy replaces the internal `X-HooshiX-Client-IP` value with its server-derived client IP before BFF;
- BFF accepts one server-derived IP literal only on the WAF-only ingress path;
- backend network quotas accept only the typed BFF-derived network context from the approved BFF workload;
- missing or invalid trusted network identity fails closed for an operation that requires a network quota.

Independent path controls remain:

- direct Internet -> BFF is prohibited;
- direct Traefik -> BFF application routing that bypasses WAF is prohibited;
- K3s bundled Traefik/ServiceLB is disabled in the single-server profile;
- WAF uses current Coraza/CRS policy with reviewed DetectionOnly-to-blocking promotion and narrow exceptions;
- WAF is not a substitute for upstream volumetric protection;
- BFF request/body/header/security validation remains independent defense in depth.

Semantic quotas protect application-specific abuse and do not replace WAF/DDoS controls.

## 9. Secrets and OpenBao — unchanged

**OpenBao is explicitly outside ADR-0042 simplification scope.**

ADR-0011 remains the authority. OpenBao 2.6.1 remains the production secret authority with its current topology, Shamir/recovery, encrypted snapshot, Kubernetes Auth, External Secrets and mounted/local secret workflows.

Mandatory principles:

- production secrets never enter Git, images, Helm/Kustomize values, logs, traces, metrics or unapproved CI output;
- application hot paths use validated mounted/local key material rather than per-request OpenBao RPC;
- purpose-separated rotating key rings follow their owning ADR/service lifecycle;
- secret reload is atomic where current service contracts require it;
- stale/local snapshot behavior is bounded and fail-closed according to the owning secret contract;
- OpenBao/secret-source outage MUST NOT be converted to plaintext persistence or a bypass;
- ADR-0042 MUST NOT remove, replace, bypass or weaken OpenBao.

ADR-0043 does not alter OpenBao topology, authority, or secret-delivery semantics.

## 10. Supply chain and Kyverno

Production artifacts remain immutable and admission-controlled:

- image reference is digest-only;
- approved Cosign/Sigstore-compatible signer identity is exact, not broad wildcard;
- signed build provenance/attestation is bound to trusted CI/source revision/workflow;
- signed CycloneDX SBOM attestation is required;
- vulnerability/advisory correlation remains continuous;
- production admission is deny/fail-closed for protected creates/updates;
- ordinary emergency deployment is not allowed to bypass signatures/provenance/SBOM.

Kyverno remains the production admission engine.

`production-single-server` may use one Kyverno replica and a reduced high-value policy inventory. It MUST still enforce digest/signature/provenance/SBOM and critical workload security/identity invariants. Policy reduction is allowed only when removed controls are proven redundant/non-critical or enforced by another blocking control. Removing Kyverno or changing production to audit-only is prohibited.

`production-ha` retains >=3 Kyverno replicas/topology protection.

Policy authoring is restricted to controlled GitOps/CI identities. Unneeded external HTTP context remains disabled. Any approved external context has destination allow-listing, bounded response/timeout/failure semantics, credential protection and SSRF-negative tests.

## 11. Human privileged production access

ADR-0030 preserves the security invariant across profiles:

- no standing root, unrestricted Kubernetes admin or PostgreSQL superuser access;
- attributable human identity;
- phishing-resistant hardware-backed authentication;
- explicit reason/ticket/incident reference;
- at least two reviewers for write/admin/database-write elevation;
- maximum 30-minute privileged write elevation with automatic expiry;
- separately scoped read-only elevation up to one hour;
- protected break-glass path;
- durable audit outside ordinary requester modification rights.

### `production-single-server`

ADR-0043 selects normal management network admission through a dedicated WireGuard overlay:

```text
approved operator device
-> WireGuard
-> host management address
-> OpenSSH
-> hardware-backed FIDO2
-> separate JIT privilege
```

Network reachability, human identity, and privilege are independent gates.

Mandatory controls:

- public-interface/Internet TCP/22 is denied;
- SSH is reachable only through the host management address/interface;
- each approved operator device has its own attributable WireGuard peer key; shared peer keys are prohibited;
- peer `AllowedIPs` are minimal and do not grant broad workload/application-network reachability by default;
- WireGuard key possession never grants SSH/root/Kubernetes/database authority by itself;
- no direct root login;
- password authentication disabled;
- no shared SSH accounts or shared SSH keys;
- privileged OpenSSH FIDO2 authentication requires user presence and user verification;
- authentication does not itself grant root/Kubernetes/database write authority;
- JIT grant is least privilege and expires automatically;
- static shared kubeconfigs/database passwords and permanent `cluster-admin` are prohibited;
- `sudo` I/O/session logging is used for privileged interactive activity where applicable;
- OS audit records authentication, execution, privilege and security-relevant configuration events;
- Kubernetes/database boundary audit remains enabled;
- required access/audit evidence is exported off-host to append-only/tamper-resistant storage;
- `.bashrc`, shell history, `PROMPT_COMMAND` or other user-controlled shell logging is **not** authoritative audit;
- provider emergency console access, if available, is break-glass only and cannot become normal management.

The management network does not depend on a workload-cluster pod/service that the operator may need to recover.

### `production-ha`

Teleport Enterprise Self-Hosted remains the privileged human access plane with current SSO/WebAuthn/JIT/session-recording controls.

## 12. Logging, PII and audit

Logging is allow-list based and structured.

Never log raw passwords, OTP/recovery codes, tokens, cookies, API keys, private keys, provider credentials, full request/response bodies, unreviewed SQL binds, complete gRPC metadata or unreviewed provider payloads.

Ordinary PII appears only for an approved purpose with masking/tokenization or managed-key HMAC pseudonymization where correlation is required. Raw client IP used transiently for trusted network quota identity is not an ordinary logging/metric/trace field. Input-derived fields are protected against CR/LF/log injection. Metric labels remain low-cardinality and exclude user/tenant/session/request/resource IDs, trace IDs, raw URLs and free-form errors.

Required security/audit evidence is not ordinary best-effort telemetry and MUST NOT be silently dropped/reclassified. In the single-server profile, privileged-access audit is exported off-host so loss/compromise of the only server does not erase the only audit copy.

## 13. Security availability and non-HA interpretation

`production-single-server` explicitly accepts loss of availability for host/node/PostgreSQL/Redis/Kafka/Kyverno failures. It does **not** accept weaker security decisions.

Examples:

- Redis unavailable -> covered quota/session operation follows fail-closed/re-authentication contract, not local bypass;
- trusted client-network identity unavailable -> quota-required public operation fails closed; caller forwarding header is not fallback authority;
- Authorization unavailable -> no fabricated ALLOW;
- Kyverno unavailable -> protected new/updated workload is not admitted through a bypass;
- OpenBao unavailable -> current bounded local-key/stale-snapshot rules apply; no plaintext or Git secret fallback;
- Ambient capacity failure -> production readiness fails; strict mTLS is not silently disabled;
- management overlay unavailable -> public SSH remains disabled;
- audit export failure -> privileged work follows incident/continuity rules; shell history does not become audit authority.

## 14. Verification

Security verification includes, as applicable:

- current `threat-model.md` review and mitigation-to-test mapping;
- authentication/MFA downgrade-prevention and recovery tests;
- tenant/RLS/pool-reuse negatives;
- Authorization allow/deny/error/timeout/breaker tests;
- semantic quota atomicity/time/anti-lockout/Redis failure tests;
- public client-address forged-header/untrusted-PROXY/proxy-address/IPv4/IPv6 negative tests;
- workload mTLS/ServiceAccount/NetworkPolicy/Istio positive and negative tests;
- WAF/direct-bypass/upstream protection evidence;
- secret scans and OpenBao/External Secrets/key-rotation/recovery tests;
- signed image/provenance/SBOM + wrong-signer/missing-attestation Kyverno negatives;
- policy-authoring RBAC and policy-engine SSRF/egress tests;
- single-server WireGuard public-SSH-denial/peer-revocation/minimal-route tests plus OpenSSH FIDO2 no-password/no-root/shared-key negatives, JIT expiry/two-reviewer flow, `sudo` I/O, OS/boundary audit and off-host audit integrity;
- HA Teleport SSO/WebAuthn/JIT/session-recording tests when HA profile is selected;
- logging/PII/raw-client-address canary/static/runtime leak tests;
- `../runbooks/production-cold-dr.md` exercise including security-control restoration and erasure/legal-hold replay;
- full-stack single-server benchmark proving security controls fit with >=30% validated resource headroom;
- explicit proof that ADR-0042/ADR-0043 did not change OpenBao or end-user MFA semantics.
