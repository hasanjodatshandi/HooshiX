# Threat Model — Current State

This is the formal design-time threat model for HooshiX. It does not prove implementation/runtime controls. Production Readiness and Security Verification remain evidence gates.

## 1. Scope

Covers:

- public edge/browser/BFF;
- Identity/MFA/sessions/token issuance;
- Authorization/tenant isolation;
- service-to-service identity;
- PostgreSQL/Redis/Kafka/reference datasets;
- OpenBao/secrets;
- Git/CI/source-secret/supply-chain controls;
- Git-native AI-agent context/bootstrap/routing/checkpoint/retrieval tooling;
- Day-One logging/metrics/tracing;
- privileged access;
- backup/restore/erasure/DR;
- single-server host concentration.

## 2. Security objectives

1. only authenticated/authorized actors gain authority;
2. tenant data/authority do not cross tenants;
3. credentials/secrets/MFA/private keys remain confidential, including in Git history and CI/security-tool output;
4. mutable business state remains correct/recoverable;
5. security dependencies fail closed instead of fabricating success;
6. audit/recovery evidence remains attributable/durable;
7. artifacts are reviewed/immutable/signed/traceable with digest-bound SBOM/vulnerability/provenance evidence;
8. public traffic cannot bypass approved edge/WAF/BFF;
9. restored history cannot revive erased/legally blocked authority;
10. non-HA availability trade-offs never become security downgrades;
11. observability cannot become a new security authority or secret-exfiltration path;
12. abuse controls remain safe under clock faults and adversarial state cardinality;
13. source/secret/scanner failures cannot silently bypass required merge/promotion controls;
14. retrieved/checkpoint/model context cannot outrank current Git authority, convert untrusted repository text into higher-priority instruction, or expose repository mutation authority.

## 3. High-value assets

| Asset | Primary property |
| --- | --- |
| Password/MFA/recovery material | confidentiality + integrity |
| Sessions/refresh/signing keys | confidentiality + revocation correctness |
| Git/CI credentials and committed-secret exposure state | confidentiality + detection + revocation correctness |
| Current repository architecture/policy/context authority | integrity + provenance + freshness |
| Agent context/checkpoint provenance | integrity + bounded confidentiality + non-authority |
| Tenant Membership/Authorization state | integrity + isolation |
| PostgreSQL business state | confidentiality + integrity + recovery |
| OpenBao/recovery material | confidentiality + integrity + recovery |
| Redis session/quota state | integrity + fail-closed availability |
| Trusted client address/quota identity | integrity + privacy |
| HIBP-derived compromised-password corpus/release evidence | integrity + freshness + provenance |
| Kafka publication/replay evidence | integrity + reconstructability |
| Final image/SBOM/vulnerability decision/signature/provenance/GitOps | integrity + provenance + digest binding |
| Logs/metrics/traces | confidentiality + bounded integrity/availability |
| Security/privileged audit | integrity + durability + attribution |
| Backups/WAL/snapshots | confidentiality + integrity + recoverability |
| Erasure/legal-hold state | integrity + ordering correctness |
| Human production identities/JIT grants | integrity + attribution |

## 4. Trust boundaries

### TB-01 Browser -> public edge

Browser input is untrusted, including headers, URLs/bodies, OIDC callbacks, forwarding headers, trace/correlation headers, and baggage.

### TB-02 External L4 -> Traefik -> WAF -> BFF

Only reviewed proxy chain derives trusted exact client address. Caller forwarding headers are not authority. Direct origin/BFF bypass is prohibited.

### TB-03 BFF -> internal services

BFF workload identity can carry typed trusted context only under reviewed contracts. It does not own backend business authority.

For quota, BFF sends one exact canonical client address. Backend derives exact hard `/32`/`/128` and separate aggregate `/24`/`/64` pressure identities.

### TB-04 Service -> Authorization

Authorization is authoritative for permission decision. Failure is never ALLOW. Business DENY remains explicit current contract behavior.

### TB-05 Service -> owned persistence

Database identity/tenant context trusted only after validated service/application context. Cross-service SQL prohibited; tenant tables use forced RLS.

### TB-06 Workloads -> Redis/Kafka/OpenBao/providers

Every dependency has explicit identity, data classification, deadlines/failure behavior, and no hidden authority.

### TB-07 Application -> observability Collector/backends

Telemetry is untrusted from a security-authority perspective. Trace/baggage/log attributes cannot grant identity/permission/tenant/quota/idempotency/audit status.

Collector ingress is internal-only. Telemetry is filtered/redacted before export. Ordinary telemetry is best-effort/bounded; required security audit remains separate durable/off-host evidence.

### TB-08 Collector -> node log files

ADR-0044 permits a narrow read-only mount to exact pod/container log paths. A compromised Collector MUST NOT gain general host filesystem, host network, or privileged-container authority.

### TB-09 Git/CI -> production

PR/build inputs, current source, Git history, dependency metadata, scanner databases, generated SBOMs, signatures, provenance, and CI-produced security evidence are untrusted until their owning controls verify them.

- Gitleaks scans current tree and protected Git history and must not reveal discovered secret values in output;
- a deleted latest-tree secret remains an exposure until the real credential is revoked/rotated and history/incident handling is complete;
- Semgrep owns first-party source SAST/repository policy;
- Syft/Grype/Cosign/Kyverno evidence stays bound to the exact final image digest;
- privileged workflows cannot execute unreviewed PR-controlled code with production-capable privilege;
- scanner/feed/tool failure or stale evidence cannot silently permit promotion beyond current policy;
- production uses the exact staged, signed, provenance-bound, SBOM-attested immutable artifact.

### TB-10 Human operator -> management plane

WireGuard network admission, FIDO2 identity, and JIT privilege are separate gates.

### TB-11 Backup/recovery -> restored production

Restored state is not current authority until integrity/schema/RLS/erasure/legal-hold/secrets/workload/edge/security gates pass.

### TB-12 Repository -> AI agent/context client

ADR-0046 Context Engine output is derived developer context, not a new authority.

- current Git authority outranks task routing, retrieval results, checkpoints, and model/chat memory;
- retrieved comments, fixtures, generated text, and checkpoint prose are data and do not become higher-priority agent instruction merely because retrieval returns them;
- task routing fails conservatively to `full-read` for unknown/ambiguous/global-trigger cases;
- dirty configured authority state cannot be represented as verified targeted context;
- retrieval is repository-root confined, tracked-file-only, bounded, and provenance-bearing;
- tracked-file-only retrieval does not prove the repository is secret-free; Gitleaks remains the committed-secret control;
- the MCP surface is read-only/stdio-only and has no file/Git/checkpoint/deployment/credential mutation tool or network listener in v1;
- caller query/revision values are data passed through bounded validation and fixed Git argument arrays, never shell command text.

## 5. Threat actors

- Internet attacker: arbitrary requests, spoofed forwarding/trace headers, credential stuffing, high-cardinality abuse, DDoS participation.
- Compromised browser/user: valid session plus malicious client input.
- Malicious tenant user/admin: horizontal/vertical escalation attempts.
- Compromised workload/service credential: lateral DB/Redis/Kafka/OpenBao/provider/telemetry access attempts.
- Compromised telemetry component: attempts secret collection, host-file access, or data manipulation.
- Compromised CI/GitHub identity: source/history/artifact/GitOps/security-evidence manipulation.
- Malicious or compromised repository content: attempts prompt injection, stale-context substitution, secret exfiltration, path escape, or command-like input against an AI/context client.
- Developer or automation mistake: commits a real credential, removes it from the latest tree, or publishes it through scanner/log/context output.
- Compromised operator device: may hold WireGuard key but not necessarily FIDO2/JIT.
- Privileged insider: time-bounded legitimate capability with misuse risk.
- Compromised host/root: broad single-server process/storage visibility.
- Compromised provider/source/scanner feed: malformed/replayed/delayed/false data within protocol scope.
- Storage/backup attacker: tamper/delete/exfiltrate recovery artifacts.

## 6. Representative STRIDE mapping

| Category | Threat | Primary controls | Evidence |
| --- | --- | --- | --- |
| Spoofing | forged XFF/client header changes quota identity | ADR-0043 trusted PROXY chain; exact BFF context | header/PROXY negatives |
| Spoofing | forged trace/baggage becomes identity/tenant/permission | telemetry context classified correlation-only | forged-context auth/quota negatives |
| Spoofing | wrong workload calls internal service/Collector | strict mTLS + SA + NetworkPolicy/Istio | connectivity/authz negatives |
| Spoofing | stale checkpoint/model memory is presented as current repository authority | Git HEAD/blob/worktree provenance + authority precedence + diff inspection | clean/stale checkpoint + dirty-authority tests |
| Tampering | tenant context changes on pooled DB | transaction-local context + FORCE RLS | cross-tenant pool tests |
| Tampering | build/deploy artifact changes | digest/signature/provenance/SBOM/admission | wrong-artifact negatives |
| Tampering | image/SBOM/vulnerability/signature evidence refers to different digests | Syft/Grype/Cosign/Kyverno exact-digest binding | cross-digest/mismatch negatives |
| Tampering | compromised-password corpus/source altered/stale | HIBP source identity + manifest/digest/freshness/full-corpus validation | dataset release tests |
| Tampering | repository text attempts prompt injection or command/path mutation through context tooling | retrieved-data classification + root/tracked-file bounds + fixed Git argv + read-only MCP | routing/search/revision/write-tool negatives |
| Repudiation | operator denies privileged action | FIDO2/JIT + OS/sudo/K8s/DB audit off-host | audit exercise |
| Information disclosure | real secret is committed then deleted but remains in Git history/clones | Gitleaks tree+history + revoke/rotate + incident/history remediation | commit-delete fixture + rotation evidence |
| Information disclosure | scanner/log output republishes discovered secret | Gitleaks redaction + CI output policy | redaction fixture |
| Information disclosure | context retrieval/checkpoint exposes sensitive repository material | sensitive-name exclusion + bounded tracked-file retrieval + no secret fields/private-key material in checkpoints + Gitleaks remains authoritative | retrieval/checkpoint negatives + Gitleaks evidence |
| Information disclosure | secrets/PII in telemetry | source allow-list + Collector redaction + canary | Loki/Tempo/Prometheus/Grafana negatives |
| Information disclosure | Collector reads arbitrary host files | exact read-only pod-log mount; no broad host privilege | render/runtime mount negatives |
| DoS | Authorization/Redis failure blocks work | bounded deadlines/bulkheads + fail closed | overload/failure tests |
| DoS | attacker creates unbounded Redis security keys | low-cardinality allocation guard + memory reserve + upstream throttling | unique-key attack test |
| DoS | `/24` hard gate blocks legitimate shared networks | exact-IP hard identity + aggregate pressure only | NAT/campus/VPN tests |
| DoS | host clock jumps app+Redis together and refills tokens | wall-vs-monotonic guard + Redis TIME + host sync gate | common-mode jump tests |
| DoS | telemetry consumes single-host resources | finite queues/retention/cardinality + complete-stack benchmark | pressure/load tests |
| DoS | total host loss also removes local monitoring | independent external black-box signal | host-loss exercise |
| DoS | scanner/feed outage is used to bypass release policy | fail-closed freshness/promotion gates under ADR-0035/0038/0045 | stale/unavailable scanner/feed negatives |
| Elevation | tenant admin grants stronger authority | Authorization privilege-escalation/owner safety | admin concurrency negatives |
| Elevation | network access becomes root | WireGuard != FIDO2 != JIT | separation tests |
| Elevation | read-only AI context channel is treated as repository/production mutation authority | no write MCP tools/no network listener + normal Git/PR/production access controls remain separate | MCP tool-list/unknown-write negatives |

## 7. Critical abuse cases

### TM-01 Client-IP spoofing

Attacker sends forwarding/private headers or alternate address encodings to reset/shift quota identity.

Required: caller headers ignored; one canonical server-derived exact IP; mapped IPv6 normalized; proxy/missing identity fails closed; raw IP not ordinary telemetry.

### TM-02 Shared-network collateral lockout

Attacker from one address exhausts an aggregate `/24`/`/64` and tries to deny all legitimate users behind the same network.

Required: hard v1 bucket is exact `/32`/`/128`; aggregate prefix is separate pressure and not sole hard 429 gate. NAT/campus/VPN/IPv6 tests validate behavior.

Residual: exact IPv6 privacy-address rotation can reduce per-address hard-gate effectiveness; contact/subject/browser dimensions plus aggregate pressure/upstream controls provide defense in depth. Any future hard aggregate gate needs evidence.

### TM-03 Common-mode clock jump

Host wall clock jumps forward/backward and both JVM wall time and Redis TIME move together, so app-vs-Redis skew alone appears healthy.

Required: quota JVM wall-vs-monotonic guard detects abrupt >2s step; time becomes unhealthy; boot requires host synchronization; re-arm requires 60s stable healthy window. No premature refill.

### TM-04 High-cardinality Redis exhaustion

Attacker generates many unique contacts/addresses/OIDC contexts to grow non-TTL security state until `noeviction` writes fail.

Required: aggregate active-bucket/allocation/cleanup telemetry, >=30% reserve, bounded low-cardinality new-allocation guard, upstream coarse protection, `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM, no fail-open/local fallback.

Residual: a sufficiently large attack may intentionally make quota-protected operations unavailable. This is preferable to fail-open; capacity/upstream mitigation must keep the attack envelope acceptable.

### TM-05 WAF/origin bypass

Attacker/operator attempts direct origin or Traefik->BFF route.

Required: firewall/routing + NetworkPolicy/Istio deny; WAF outage never creates bypass.

### TM-06 Cross-tenant access

Authenticated actor modifies tenant/resource identifiers.

Required: trusted tenant context + Authorization + local domain checks + forced RLS deny.

### TM-07 Authorization dependency manipulation

Attacker induces timeout/breaker/overload expecting fail-open.

Required: no ALLOW fabricated; no permission cache/retry/stale fallback.

### TM-08 Credential stuffing/MFA downgrade

Required: semantic quota defense, compromised-password screening, TOTP/recovery semantics, no provider login MFA bypass.

### TM-09 Session/token replay

Required: server-side BFF state, secure cookies, HMAC locators, rotation/revocation, OIDC/preauth single-use/lifetimes.

### TM-10 Compromised workload lateral movement

Required: SA/mTLS/NetworkPolicy/Istio + DB/Redis/Kafka/OpenBao/provider/telemetry least privilege.

### TM-11 Telemetry injection/exfiltration

Attacker sends crafted headers/inputs causing high-cardinality attributes, log injection, PII/secret export, or trace-baggage authority confusion.

Required: bounded allow-list logging/attributes, CRLF protection, baggage allow-list, low-cardinality metrics, Collector redaction, canary tests, and security-context non-authority tests.

### TM-12 Telemetry backend failure

Collector/Loki/Tempo/Prometheus becomes unavailable or fills disk/memory.

Required: ordinary business path is not synchronously dependent on telemetry export; finite queues/drop; alert on loss; capacity gate; required audit remains separate durable/off-host.

### TM-13 Collector compromise

Required: no public OTLP, wrong-workload denial, dedicated SA/RBAC, restricted egress, exact read-only pod-log mount, no host network/privilege/general hostPath.

### TM-14 Supply-chain substitution/evidence mismatch

Attacker or compromised CI substitutes source/dependency/tool/final image/SBOM/vulnerability/signature/provenance evidence or binds valid evidence to the wrong image digest.

Required: repository review, pinned tools/actions/dependencies, Gradle verification/locks, Semgrep static checks, final-image Syft CycloneDX, Grype exact-image/SBOM decision, Cosign exact-digest signature/provenance/signed-SBOM, Kyverno CEL admission, and same-digest staging->production promotion.

A vulnerability exception cannot authorize missing signature/provenance/SBOM evidence. A scanner outage cannot become a release bypass.

### TM-15 Privileged access compromise

Required: independent WireGuard/FIDO2/JIT factors, expiry/revocation, protected audit.

### TM-16 Backup/restore rollback

Required: isolated restore, schema/RLS/integrity, erasure/legal-hold replay, current secrets/policy, traffic gate.

### TM-17 Single-host compromise/loss

Root compromise has broad blast radius. Required: containment, credential rotation, off-host audit, trusted rebuild, protected backups, no claim that same-host workload isolation protects against root.

Total host loss also removes local telemetry; independent external monitoring detects availability loss.

### TM-18 External provider/source ambiguity

Required: provider-specific validation/idempotency/ambiguity handling. HIBP is offline dataset source only; production requests never depend on HIBP availability.

### TM-19 Committed-secret persistence

A developer, automation, generated fixture, or compromised actor commits a real API key/token/password/private key and later deletes it from the latest tree. The secret remains available in Git history, clones/forks, caches, CI logs/artifacts, or downstream mirrors.

Required:

- blocking Gitleaks current-tree and protected Git-history scans;
- a synthetic commit-then-delete fixture that proves history detection;
- fully redacted scanner output;
- revoke/rotate the credential when exposure is plausible before treating source/history cleanup as remediation;
- preserve forensic evidence without copying the secret value into incident systems;
- approved Git-history remediation when required;
- verify replacement credentials do not enter Git/CI/logs/images/values.

Residual: a history rewrite cannot recall an already copied credential. Rotation/revocation is therefore the primary containment action for real exposed secrets.

### TM-20 Agent-context prompt injection/staleness/mutation

A malicious or stale repository/checkpoint fragment attempts to make an agent trust obsolete architecture, ignore current policy, execute command-like text, read outside the repository, reveal secret material, or invoke a write-like MCP capability.

Required:

- current Git authority precedence and live HEAD/blob/worktree provenance;
- conservative route escalation for ambiguous/unknown/full-read-trigger tasks;
- dirty configured authority state cannot claim verified targeted context;
- retrieved source is classified as data, not automatically instruction;
- tracked-file-only repository-root-confined bounded retrieval;
- configured sensitive filename exclusion and no secret/private-key fields in checkpoints, while Gitleaks remains the actual committed-secret control;
- revision/query validation plus fixed Git argument arrays without `shell=True`;
- read-only stdio MCP tool surface with no network listener or mutation/checkpoint-create tool;
- checkpoint `subject_commit` comparison plus intervening Git diff before continuity use.

Residual: an allowed tracked source file can still contain secret or adversarial text. The engine therefore cannot claim content safety from retrieval alone; secret scanning, current authority hierarchy, review, and client-side instruction separation remain required.

## 8. Single-server residual risks

- one host failure can stop complete platform and local observability;
- host/root compromise has broad local blast radius;
- one PostgreSQL physical failure domain affects multiple DBs;
- Redis/Kafka have no failover;
- Kyverno/telemetry/control-plane availability is lower;
- fail-closed security capacity exhaustion can cause user-visible outage;
- maintenance can consume error budget.

These risks do not permit weaker MFA, Authorization, RLS, OpenBao, WAF, source/secret scanning, signed final-artifact admission, audit, backup, trusted client identity, quota safety, or telemetry privacy.

ADR-0046 Context Engine is outside the production runtime profile. Its failure cannot justify weakening product/runtime controls or treating external/model memory as current architecture authority.

## 9. Verification mapping

Material threats map to executable service/security/database/network/CI/observability/chaos/recovery/context evidence. In particular:

- TM-02/03/04 -> ADR-0024 tests + Redis load/chaos;
- TM-11/12/13 -> ADR-0031/0044 tests + Collector/render/canary/fault evidence;
- TM-14 -> ADR-0017/0035/0038/0039/0045 + Semgrep/Syft/Grype/Cosign/Kyverno digest/evidence/failure fixtures;
- TM-19 -> ADR-0045 Gitleaks current-tree/history/redaction + incident revoke/rotate/history-remediation evidence;
- TM-20 -> ADR-0046 bootstrap/router/search/checkpoint/MCP tests + generated-route parity + SEC-033/AFF-051;
- TM-17 -> external host-down monitor + cold DR;
- compromised-password source risk -> ADR-0040 corpus build/freshness/provenance tests;
- policy-engine migration risk -> Kyverno CEL manifest gate.

A documented mitigation without executed evidence remains `NOT VERIFIED`.

## 10. Change triggers

Review this threat model when public proxy/L4/WAF/client-address, quota identity/time/capacity, authentication/MFA/session/token, service boundary, Authorization, tenant persistence, datastore/provider/Internet egress, observability/Collector/storage, OpenBao/secrets, Git-history secret scanning, AI-agent context/bootstrap/routing/checkpoint/retrieval/MCP behavior, Semgrep/Syft/Grype/Cosign/Kyverno toolchain authority, CI/admission, privileged access, production topology, backup/erasure, or a real incident changes.