# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

On 2026-09-27 the owner made FIDO2 optional for single-server human SSH access under
revised ADR-0030/0042/0043. Encrypted per-operator Ed25519 software keys are allowed;
this does not claim hardware assurance, change HA/end-user MFA, or waive JIT,
management isolation or off-host audit. Live non-privileged inspection found an
active Windows WireGuard tunnel, successful Ed25519 SSH to `10.77.47.1:22022`, and
rejection of the same connection without SSH public-key authentication. The local
client has `10.77.47.2/32` and only `10.77.47.1/32` in AllowedIPs. Manually
authenticated root inspection at `12:13:22Z` matched its public-key fingerprint
and exact route to the sole server peer, and `sshd -t` passed. Global `sshd -T`
reported root/password/keyboard-interactive disabled but agent/TCP/X11 forwarding
enabled; this is not connection-specific Match evidence. The host has wildcard
SSH socket listeners on 22/22022; public primary TCP/22022 was reachable from the
operator device. Host `auditd` was not installed. `sudo -n` rejects root inspection;
the tested Windows launcher obtains interactive sudo authentication, verifies
the exact source hash and returns a filtered receipt without storing passwords.
Management-only SSH, connection-specific effective configuration, JIT expiry,
revocation and off-host audit evidence remain NOT VERIFIED. These findings are
not permission to open production traffic.

Follow-up read-only inspection on 2026-09-27 again reported `auditd` inactive,
`rsyslog` active and non-interactive sudo unavailable. No audit/JIT/SSH hardening
was applied to the VPS by that follow-up. The installed host package is
`openssh-server 1:10.2p1-2ubuntu3.6`; its installed project manual documents
`DisableForwarding` as the override for Agent/TCP/X11/StreamLocal forwarding.
The repository SSH policy now includes that override and explicit StreamLocal
denial, with inspection and negative policy tests. These are repository controls,
not live effective-config evidence. The owner selected a minimum of one authorized
JIT reviewer for single-server on 2026-09-27; ADR-0030/0042, the selected profile,
host access policy, readiness checklist and executable static policy gate now
reflect that exception. Requester and reviewer may be the same named owner;
independent separation of duties is not claimed, and HA still requires two reviewers.
The ParsPack endpoint was provided; a previously chat-disclosed credential must
never be provisioned, and any production writer credential must be fresh and private.
Restricted-writer access, enforced destination retention
and denial of deletion/overwrite remain unverified inputs. The Persian prerequisites
guide explains these inputs and active-session/child-process expiry tests.
An unsafe automatic commissioning/JIT draft was withdrawn before any host execution;
no executable JIT runtime or off-host audit receiver is claimed.

Follow-up read-only SSH on 2026-09-27 confirmed `hooshixadmin`, unavailable
non-interactive sudo, inactive `auditd`, and active `rsyslog`, K3s and WireGuard.
No privilege, SSH, firewall or audit configuration was changed in this policy update.
ParsPack's public connection guide documents S3-compatible clients; its reviewed
connection guide is not retention/immutability or successful-upload evidence.
On 2026-09-30 the owner reported that the ParsPack panel shows `Compliance` with
40-day retention and confirmed the read-only bucket probe shows Object Lock
enabled with default `COMPLIANCE / 40 days` plus Versioning enabled. This is
owner-reported configuration evidence, not an independent receipt or a behavior
test. Object-version immutability, writer least privilege, actual off-host audit
delivery, and the JIT audit gate remain `NOT VERIFIED`; the exact verification
boundary is in `../operations/production-audit-sink-parspack-fa.md`.

A fresh owner-authenticated, read-only root inventory on 2026-09-30 at
`09:02:45Z` passed `sshd -t`, K3s API readiness and a seven-pod
Calico/CoreDNS/Kyverno inventory (all ready, zero restarts). The restricted
private receipt is outside Git at
`.platform-runtime/production/private/evidence/host-inspection-20260930T090245Z.json`
(SHA-256 `aff9764d5bc016002d69d182c87213df9237ca155edd625ef8667229dd3fe939`).
The global effective SSH configuration still permits agent/TCP/X11/StreamLocal
forwarding (`DisableForwarding no`); connection-specific `Match` and firewall
behavior were not tested. `auditd` remained inactive, and a separate read-only
package query found it not installed; `rsyslog` and WireGuard were active.
No host configuration was changed. Management-only SSH, JIT, off-host audit,
deployment, recovery and Production readiness remain `NOT VERIFIED`.

On 2026-09-30 a scoped, transient `inet hooshix_management_ssh_guard` trial was
syntax-checked on the host (`nft -c -f`, exit zero) against a SHA-256-matched
repository candidate, then installed only after an independent 15-minute
systemd rollback timer was confirmed active. A new SSH connection through
WireGuard to `10.77.47.1:22022` passed; public TCP/22 and TCP/22022 from the
operator device were denied; public TCP/2222 remained denied; K3s, mail, web,
WireGuard and nftables services remained active. The timer then removed only
the dedicated guard table (`Result=success`, exit zero). Private SSH still
passed and public TCP/22022 became reachable again, proving rollback. The
trial did **not** modify `/etc/nftables.conf`, change the SSH socket bind,
install persistent firewall policy, or satisfy JIT/off-host audit. It was
rolled back before the subsequent persistent installation.

After PR #154 merged at `main@53ca35030a8a543a4be0917e9dc9b0eea8b3eb29`,
the owner installed the SHA-256-matched guard
(`d6b8829854a0ef74aaaf30951262e56fc6b1e2371af59ce6e88747888dd231fd`)
at `/etc/nftables.d/hooshix-management-ssh.nft` and included it from
`/etc/nftables.conf`. The installed full configuration passed `nft -c -f`
(SHA-256 `9eaaa0a2fef3b6088da179d01a8070787bbf12af07743ddab95132fb8c96ee63`).
An independent 30-minute rollback timer was active before the live apply.
Fresh private SSH over WireGuard passed and public TCP/22 and TCP/22022 were
denied from the operator device. After the owner-approved reboot on
2026-09-30 at `19:04:01Z`, `nftables.service` successfully loaded
`/etc/nftables.conf` at `19:04:07Z`, before the SSH socket and separate tunnel
daemon became active at `19:04:09Z`. The rollback timer was inactive; the
dedicated guard table and its reviewed rules remained loaded; fresh private
SSH passed; public TCP/22 and TCP/22022 remained denied from the operator
device. TCP/2222 is excluded from the guard and its separate daemon remained
active/listening; public TCP/2222 remained unreachable as it was before the
change. The relevant host services were active after reboot, but external
web/mail reachability was not baselined and is not inferred from service state.
This is `Passed` evidence for the scoped guard's reboot persistence on this
one VPS, not proof of all management paths. Provider firewall, SSH bind and
forwarding policy, peer revocation, JIT, off-host audit, recovery, and
Production readiness remain `NOT VERIFIED`.

Owner-run root inventory on 2026-10-01 at `07:18:46Z` added one synthetic
connection-specific `sshd -T -C` sample for `hooshixadmin` over
`10.77.47.2 -> 10.77.47.1:22022`. The v1 receipt's `status: Passed` means
only that the probe returned the expected fields. Its actual effective values
were `DisableForwarding no`, with agent/TCP/StreamLocal/X11 forwarding enabled;
this sampled human SSH policy is **Failed**. Root/password/keyboard-interactive
were disabled, public-key authentication enabled, and syntax passed. K3s API and
seven-pod inventory passed with each observed pod ready 1/1 and restart count
1; the restart cause is not established. `auditd` remained `Not verified`.
No host SSH configuration was changed by this inspection. The updated v2
inspector separates probe execution from sampled policy status, but it does
not assert full SSH/JIT/audit or Production readiness.
Read-only follow-up over the existing private SSH alias found active
`ssh.socket` with generated listeners on IPv4/IPv6 wildcard addresses at
22/22022, from `/run/systemd/generator/ssh.socket.d/addresses.conf`.
The main SSH configuration includes drop-ins before its final
`Match User hooshixtunnel`; an earlier tunnel-user drop-in also permits
scoped remote TCP forwarding. A separate tunnel daemon currently listens on
TCP/2222. Thus copying the repository's global SSH policy onto this shared
host configuration without connection-specific testing could disrupt the
tunnel. No listener/configuration was changed by this follow-up.
The same read-only follow-up found two enabled tunnel SSH units targeting one
dedicated configuration: `sshd-hooshix-tunnel.service` was active, while
`hooshix-tunnel-sshd.service` was in auto-restart with `ExecMainStatus=255`
and 14,480 recorded restarts. A TCP/2222 listener was present, but an
end-to-end MCP request was not tested. The duplicate/failing unit was not
stopped; its precise failure cause and ownership need review before any
change. Non-root disk inventory showed 40 MiB of journald storage and
129 GiB free on the VPS root filesystem at inspection time; it does not
prove full storage/retention health.
For PR #158, the reviewed human-only Match template and candidate preparer
were copied to an operator-owned `0700` cache directory and both transfer
hashes matched the local source. The preparer verified the unchanged source
`sshd_config` SHA-256
`01c79f1385e2ec9b5b09e4995cfcbd2f6b21e6e05c7906a8a2b1f1b18a5c5fbb`
and wrote one uninstalled `0600` candidate (SHA-256
`41076da5cb933c3be885c912a6b26ade7169c9c1f4dcba9a2e048d3cd08b9656`).
Unprivileged `sshd -t -f` could not read `50-cloud-init.conf` and returned
`Permission denied`. On 2026-10-01 the owner reran that exact candidate's
`sudo /usr/sbin/sshd -t -f` in the local Windows SSH terminal and reported
exit code zero after the second sudo authentication attempt: candidate syntax
is `Passed` by owner-run evidence. A fresh read-only SSH check independently
confirmed both hashes above and candidate mode `0600` were unchanged.
Non-interactive sudo explicitly requires interactive authentication, so a
successful syntax receipt does not grant subsequent unattended root access.
The first owner-run multi-line PowerShell/SSH command for `sshd -T` failed
in the remote shell with a quoting-related Bash syntax error and exit code 2;
it produced no effective-configuration evidence. A hash-pinned, read-only
operator-side preflight script now replaces that multi-line command.
The script was copied to the operator's private VPS cache, set to mode `0600`,
and its SHA-256 `a967f8fc361ba374116630e81e36382e177e833a18f3316e21b557e1afd8a596`
matched the reviewed repository source. The owner subsequently ran that exact
script in the local SSH terminal: hash/mode and syntax checks passed, both
human-port samples (22 and 22022) passed, the complete tunnel-account renders
were unchanged on both ports, and `PREFLIGHT=Passed` ended with exit code zero.
This is owner-run candidate evidence, not a live forwarding or tunnel test.
The operator-side apply script and root-owned rollback script now prepare an
atomic, hash-verified replacement with a ten-minute system-manager rollback
timer. They refuse an existing rollout directory or changed source/candidate
and preserve the nftables guard, SSH socket listeners and dedicated TCP/2222
daemon. The timer is rollout recovery only, not JIT access implementation.
Both scripts were transferred to the private operator cache with mode `0600`;
the apply SHA-256 `5d15b2c671dc42d12f349b9b7962f7f02864b309319c96bf938e984b5004172f`
and rollback SHA-256 `6ed728fca74841557f2138aae7748e8f5518d3f387e4bcb06d9a88df3f5ab3e7`
matched repository source. Host-side Bash syntax and local source/static gates
passed. On 2026-10-01 the owner executed the reviewed apply script with local
sudo authentication: preflight passed, the rollback timer became active and
`SSH_CANDIDATE=Applied` ended with exit code zero. Independent fresh private
SSH then passed and a remote loopback forwarding request was rejected with
exit code 255 (`remote port forwarding failed`). The installed main-config
hash matched the candidate above. Both `ssh.service` and the separate
`sshd-hooshix-tunnel.service` remained active, with TCP/2222 still listening;
this is not an end-to-end public MCP connectivity claim.
After these checks the owner stopped the rollback timer with exit code zero.
An independent fresh connection confirmed timer `inactive`, rollback service
`inactive`, the candidate hash still installed, both SSH services active and
TCP/2222 still listening. The scoped human forwarding rollout is `Passed`.
Automatic rollback execution was `Not run`; no reboot was performed during
this trial. Root-owned recovery artifacts remain available. The next access
work is protected OS/sudo audit with off-host delivery, followed by real JIT
expiry/revocation; neither is implemented by this rollout.
On 2026-10-01 the owner supplied fresh audit credentials in a local Git-ignored
mode-0600 JSON file and selected `https://c892683.parspack.net`, replacing the
previous destination for continuation. The parent private directory is mode 0700.
Signed path-style GET probes returned Object Lock `403; AccessDenied` and
Versioning `200; not configured`, unchanged after owner-reported activation of
Compliance/40 days and versioning on the new bucket. The report is accepted as
owner configuration evidence, not independently verified settings. One unique
113-byte synthetic object round-trip passed (PUT/GET 200 and matching hash),
but responses supplied no non-null version ID and retention GET returned 403.
Unversioned DELETE on that same probe object returned 204 and subsequent GET 404;
this proves removal from the current view, not deletion of a locked version.
The object is no longer visible; recoverability of any hidden version is
`Not verified`. No other objects, host configuration or SSH/MCP settings changed.
The read-only probe now supports bounded owner-private JSON input and an optional
fail-closed exact-COMPLIANCE-days/versioning configuration check; this is not a
runtime JIT audit gate, receiver or OpenBao provisioner. Writer delete permission,
version/retention evidence and immutable audit behavior remain unresolved, so
off-host audit/JIT and Production readiness remain `NOT VERIFIED`. Subsequently
on 2026-10-01 the owner reported fixing the new destination and explicitly directed
that no further bucket checks be performed. This is accepted owner-attested
configuration; the preceding observations are historical, not a post-fix assessment.
No additional bucket calls are made for this continuation. JIT development proceeds
without re-probing the provider, while real grant-time durable audit acknowledgement
remains mandatory. The continuation is in `production-audit-sink-parspack-fa.md`.

The same work now includes an **uninstalled JIT core** in
`scripts/production/jit_runtime.py`: strict versioned/canonical requests, named
Ed25519 SSHSIG approval with an exact namespace, boot-bound elapsed-time admission,
durable bounded replay prevention, mandatory audit acknowledgement validation, and
fixed service-operation command construction. It does not grant a root shell or
change sudoers/groups/SSH/MCP. `test_jit_systemd_expiry.py` checks native transient
service lifetime and background-child termination; Repository baseline runs this
on a disposable runner in addition to unit tests. These are implementation/test
artifacts, not an installed broker, audit receiver or production grant. Protected
caller/key enrollment, the real OS/off-host audit adapter, revoke/controller-loss
integration, zero-standing-admin cutover and live commissioning remain unfinished.
Broker source now exists in `scripts/production/jit_broker.py`: request generation,
exact-byte signature bundling, fixed-path root-owned loading, protected sudo
caller mapping, bounded stdin/helper output/time, execute and operator-only revoke.
Native jobs have request-unique names and an in-job `flock` for operator exclusivity;
revoke stops jobs before audit delivery, and outcome/revoke events are durably
recorded locally. No broker/adapter is installed on the VPS; real audit coverage,
OpenBao provisioning, pending-event reconciliation, safe sudo cutover and full live
expiry/recovery evidence remain required. Copyable local commands, expected output,
limitations and troubleshooting are in `production-jit-usage-fa.md`.
Protected baseline run `36883186086` and frontend run `36883185616` passed
all twelve checks at implementation head `c31e6ae59513c3946d07b2247431dceeafc098c9`.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains architecture documentation, the repository-governance baseline, the ADR-0046 Git-native Agent Context Engine and project context metadata, and executable service implementations. Under ADR-0051, Context/Ops/Desktop MCP runtime source is independently versioned on Windows and is not part of HooshiX. The canonical application checkout is `/home/coder/workspace/Hooshix` on native WSL storage. Executable services are under:

The repository-wide engineering audit remediation sequence completed Stages 1-9 in
`ENGINEERING-HARDENING-ROADMAP.md`; the owner reactivated Stage 10 on 2026-09-24 and it is
`IN PROGRESS`. Production remains `NOT VERIFIED` until the real environment, release, recovery,
capacity, provider, and traffic gates pass. Its audit baseline is
`main@68cf66cf24c07dd6fca010ddae2789f42608aa31`. Protected `Repository baseline`
run `33105936814` passed at that exact commit, including neutral contract validation,
all five Java service security suites, and the final baseline aggregator. Protected
`Web frontend E2E` run `33105936555` also passed at that exact commit. These are
repository/CI results, not Production runtime evidence.

The neutral Protobuf contract artifact is version 1.9.1. It includes versioned Protovalidate
request rules, generic fail-closed gRPC server enforcement, tested protobuf-JSON consumer examples
for every published service contract, the typed Identity ExternalIdentity and data-subject-erasure
surfaces, versioned non-PII Kafka command/receipt and tenant-lifecycle events, the private
Conversation/ModelRun transport surface, dependency locks/checksums, and repository gates against
version, validation, example, and server-wiring drift.

```text
services/compromised-password-service/
services/notification-service/
services/identity-service/
services/authorization-service/
services/web-bff/
services/conversation-service/
```

ADR-0054/0057, `services/conversation-service.md`, and `mlops-evaluation-and-safety.md` now define
the first core AI-product boundary: private text Conversation plus asynchronous ModelRun through a platform-approved, stateless
`store=false` OpenAI adapter with no tools. Versioned model/prompt/price catalogs, a bilingual
synthetic non-PII adversarial suite, promotion/canary/rollback thresholds, safety/feedback/drift and
provider-data-control requirements, schemas, and a deterministic repository validator are present.
The v3.1 signed content-free real-provider evaluation passed 12/12, including 8/8 critical
cases with zero errors and $0.034753 total measured cost. Private provider-account approval and
content-free canary receipts bind the approved staging controls to the exact tuple. Exactly one
synthetic one-percent staging run succeeded for $0.001210, with 430 input tokens, 9 output tokens,
and 3717ms latency; a 206-minute observation had no failed/unknown run, critical incident, threshold
breach, or telemetry leak. Rollback restored runtime `false/0`, and a fresh negative request failed
before run creation/provider I/O. The neutral Conversation transport contract
and permission catalog are present. The first
executable `services/conversation-service/` foundation now owns a Java 25/Spring Boot build and CI
boundary, a distinct Flyway history with encrypted Conversation/Message/ModelRun columns and forced
tenant RLS, a versioned AES-256-GCM content key ring with authenticated tenant/conversation/content/
purpose binding, authoritative Authorization checks, private Conversation CRUD/history and
ModelRun RPCs, atomic reservation/acceptance/cancellation, a lease-backed bounded worker, fixed
OpenAI Responses adapter, conditional secret-file boundary, integer usage/cost reconciliation,
private readiness/Prometheus/OTLP configuration, and hardened Helm packaging. The global worker
queue contains technical identifiers/timestamps only; provider I/O occurs after claim commit.
Current v3 governance permits only staging `CANARY_1`; the post-canary deployment flags and conditional
Kubernetes egress keep provider execution disabled. Ordered fail-closed
tenant lifecycle projection and rollout-gated ADR-0028 erasure participation are implemented.
The validated OpenAPI Web BFF facade and bilingual browser Conversation journey are implemented,
including fixed audience brokerage, one-attempt deadlines, bounded concurrency, workload policy,
abortable polling, cancellation, enum-only feedback, and text-only model-output rendering. Provider
refusal/safety mapping, local transport cancellation, low-cardinality provider telemetry and alerts,
the signed content-free evaluation runner, exact activation egress, and tenant-stable
`CANARY_1/5/25/APPROVED_100` mechanics are implemented. Real provider evaluation, staging-only
provider-account data-control approval, retained owner references, deployed `CANARY_1` observation,
and rollback rehearsal passed the Stage 9 boundary. Runtime stays fail-closed after the exercise;
later canary steps, real-user data, and Production activation remain unauthorized and `NOT VERIFIED`.

Implemented repository-governance artifacts are:

```text
Makefile
context/
scripts/baseline/
scripts/context/context_engine.py
scripts/context/post_merge_checkpoint.py
.github/workflows/repository-baseline.yml
```

The repository baseline verifies file-index consistency, ADR/register coverage, dependency-registry/view consistency, current source references, guarded structure, ADR-0046 project Context Engine/bootstrap/routing/retrieval/checkpoint contracts, and the ADR-0051 externalized-MCP path guard. The independent Windows MCP runtime owns Context adapter, Ops, and Desktop runtime tests and is versioned at `https://github.com/hasanjodatshandi/HooshiXMcpRuntime.git`. The repository workflow invokes all six implemented service security suites; protected Stage 9 branch run `36000377069` passed Conversation and the other five service jobs plus the final baseline aggregator, and frontend E2E run `36000377338` passed at implementation head `39ad9a8d41539ddff01df27f8e3b05a3cf258552`. Independent runtime `main@3be8d1d723d95691bccc978034505940d90473af` passed Context 14/14, Ops 32/32, and Desktop 60/60 unit/security tests after persistent process jobs and the job-state fail-closed follow-up merged.

The Agent Context Engine is developer/repository tooling only. HooshiX owns its Git-native engine, bounded retrieval, routing, and checkpoints. ADR-0051 places only the read-only MCP adapter in the independent Windows runtime. Linux Git in `/home/coder/workspace/Hooshix` is repository authority.

ADR-0047 defines ChatGPT Web Context access through Secure MCP Tunnel. Under ADR-0051, the independent Windows adapter invokes the project Context Engine in `/home/coder/workspace/Hooshix` through fixed WSL policy. Migration evidence on 2026-08-19 verified live `project.bootstrap`, clean Linux Git authority, and `repository_transport=windows-mcp-wsl-exec`. Earlier tunnel integrity/readiness/tool-discovery evidence remains host evidence.

ADR-0048 defines the separate developer-host Ops MCP. Under ADR-0051, its implementation, schemas, and tests are owned by the independent Windows MCP runtime. Runtime PR #1 added `process.start`, `process.status`, `process.logs`, and `process.cancel`; runtime PR #2 closed the fail-closed state-path reread found during final review. Their authoritative merge commits are `03e4516ddbbc6b671d2e29043bf40172aa1daeca` and `3be8d1d723d95691bccc978034505940d90473af`. GitHub reported no configured/reported status checks for the runtime PRs, so runtime evidence is the executed local/security/host verification, not a CI-green claim. The live elevated Ops policy retains the finite 300-second local command ceiling and the Context/Ops wrappers retain the one-hour MCP connection-TTL request. Live `ops.status` reports persistent bounds of 4 active jobs, 16 retained records, 24-hour cleanup age, 1 MiB maximum per persistent stdout/stderr stream, and 64 KiB maximum per log page. Direct live MCP discovery returned the reviewed 13-tool Ops surface; loopback `/healthz` and `/readyz` returned 200. A persistent WSL job completed after `135127 ms` with exit code 0 while observation used short polling calls, and a separate job reached terminal `cancelled` through job-ID-only cancellation. Protected Ops/job-state ACLs were inspected and no broad `Everyone` grant was observed. Earlier synchronous evidence still shows a shorter tunnel/control-plane response lifetime, including one exercised failure at about `122603 ms`; persistent-job completion therefore proves decoupled local execution, not a longer synchronous response SLA.

ADR-0049/0050 define the Desktop MCP and optional credential broker. Under ADR-0051, their implementation, helpers, schemas, and tests are owned by the independent Windows MCP runtime. Migration smoke verified the repointed live Desktop runtime and `desktop.status`. Earlier UI/tunnel/credential host evidence remains host evidence.

The Compromised Password service repository implementation includes service-owned Java/Gradle source and wrapper, Protobuf/gRPC contract, immutable SQLite lookup adapter, deterministic tests, dependency locks/verification metadata, container definition, Helm/security policy package, Day-One service telemetry code, and service CI/static/architecture/deployment gates including pinned Gitleaks current-tree/Git-history scanning with negative/current-tree-positive/commit-then-delete fixtures. It also includes the service-owned offline/local SHA-1 source-to-SQLite dataset builder, version-2 release-manifest schema, generated-fixture integration/CLI verification, explicit build/runtime prefix-cardinality and serialized-response compatibility bounds, exact runtime manifest SHA-256 binding to the SQLite artifact digest, raw-corpus/generated-database Git guards, privacy/architecture regression enforcement, and a runtime-JAR exclusion that keeps builder tooling out of the deployed application artifact. The builder has no URL/network/downloader path and normal PR CI uses only generated fixtures marked `GENERATED_TEST_FIXTURE`. Runtime image construction verifies the exact official Temurin 25.0.4+7 Linux/x64 archive SHA-256 before placing that JDK in the image.

The Notification service repository implementation now includes the durable SubmitNotification handoff, seven Flyway migrations, bounded transactional dispatch claiming with 30-second leases and SKIP LOCKED, one-record-at-a-time lease freshness for delivery/reconciliation/result-callback cycles, durable pre-provider DISPATCHING identity, authenticated AES-GCM exact-content escrow read/erasure, bounded provider-attempt retry planning, stale-dispatch recovery, ambiguity-safe reconciliation and observation windows, terminal result outbox, the 750ms one-attempt Identity result callback with seven-day durable retry ownership, and low-cardinality delivery-worker metrics. It is also an ADR-0028 erasure participant with validated Kafka consumption, atomic durable Inbox/idempotency, Identity-owned target paging, service-owned subject-state deletion, a non-PII receipt Outbox, finite retry/exhaustion metrics, and alerting. Purpose-specific registration, contact-verification, and password-recovery content remains caller-selected semantic data with active English/Persian email/SMS templates. Provider code is present for provider-neutral authenticated SMTP with required STARTTLS, a constrained Google Gmail staging profile, and SMS.ir exact-text bulk sending with one-recipient submission, bounded response parsing, and correlated message-level delivery reports. All service consumers are locked to neutral Protobuf-contracts 1.9.0. Runtime credentials and the generic SMTP endpoint remain secret-file owned; Google Gmail and SMS.ir destinations are fixed by their profiles. Delivery stays disabled by default. Local service quality gates and provider fixtures verify repository behavior; the live SMS.ir Sandbox probe passes TLS/authentication/input/error/response checks while explicitly proving no real delivery. Bounded real Google Gmail staging execution has final SMTP-acceptance evidence without a mailbox-delivery claim. A Production SMS.ir credential and the owner-supplied replacement sender line now pass authenticated listing and real exact-text bulk acceptance: HTTP `200`, provider status `1`, and one positive message identifier moved Notification to `PROVIDER_ACCEPTED`. Authenticated correlated reconciliation then returned permanent delivery state `7` for the allow-listed recipient, so actual SMS delivery was correctly not claimed and remains NOT VERIFIED. SPF/DKIM/DMARC, Production Email-provider selection, deployed Production delivery, and production readiness also remain NOT VERIFIED.

The Identity service repository implementation includes registration, local-password and Google-evidence primary authentication, server-side Session/RefreshFamily authority, rotating digest-only refresh credentials, ExternalIdentity establish/link/unlink/status, logout, password change/recovery/reset, Profile/Contact lifecycle, TOTP MFA/recovery codes, ADR-0024 quota controls, local ADR-0023 RSA-3072/RS256 signing machinery with key-identifier rebinding rejection, bounded global zero-queue gRPC admission independent from the per-connection transport cap, and operation-profiled transaction/statement/lock deadlines with safe gRPC capacity mapping. V11 adds issuer+subject binding and ExternalIdentity; V12 completes Tenant/Invitation lifecycle. V13 implements ADR-0028 global erasure coordination: atomic authenticated acceptance and authentication shutdown, Membership/invitation preconditions, legal-hold ledger, snapshotted participant policy, command/receipt Outbox/Inbox state, 35-day evidence, finite retry/exhaustion, non-PII Kafka events, Identity-local erasure, receipt-gated completion, metrics/alerts, and restore/replay procedures. V14 adds the ordered tenant-lifecycle event Outbox and rollout-gated Conversation participant policy without changing existing four-participant requests before activation. The current repository implementation also includes durable Identity-to-Authorization provisioning/removal coordination, selectable-tenant queries, explicit/automatic selection, tenant-scoped audience-token issuance, the dedicated Notification result callback, and one-record-at-a-time lease freshness across Notification, Authorization, erasure-command, and lifecycle-event dispatch. Phone registration remains server-gated off by default. The fast developer runtime and historical four-participant Kafka erasure smoke pass locally. The production-fidelity staging lane additionally passed the six-application/five-participant erasure restart/restore/reconciliation exercise. Real Google-provider execution, production Kafka/erasure deployment, production key rotation, production quota thresholds, real host-time synchronization integration, load/recovery, and production readiness remain NOT VERIFIED.

The implemented service security suites install digest-verified OSV-Scanner 2.4.0 and scan locked Gradle dependencies for known vulnerabilities. The repository baseline invokes all six reusable service security suites, so the same locked-dependency advisory scans run on the scheduled repository security cadence. All six implemented Java service suites configure immutable-digest Gitleaks 8.30.0 with mandatory positive detection controls plus redacted current-tree and full-Git-history scans for the repository. Historical protected five-service evidence remains `Repository baseline` run `33105936814` on `main@68cf66cf24c07dd6fca010ddae2789f42608aa31`; protected six-service Stage 9 branch evidence passed in run `36000377069`. This is repository/early dependency-advisory evidence only; it is not final-image/SBOM vulnerability or deployed-runtime evidence.

This repository evidence includes the executed local kind/staging integration lane described below. The completed local Stage 7 evidence covers a fresh official complete HIBP acquisition, complete-corpus cardinality/response bounds, exact-commit disk-backed latency/load, rebuild/redeploy/recovery, and the bounded provider evidence described above. It is not proof of Production corpus approval/licensing/release, successful Production Notification delivery, Production environment deployment, final-image SBOM/vulnerability correlation, artifact signing, release-evidence admission, or Production readiness.

ADR-0045 defines the repository target for DevSecOps source/secret/dependency-advisory/final-artifact security: Semgrep SAST, Gitleaks current-tree/Git-history secret scanning, OSV-Scanner early declared/locked dependency advisory scanning, Syft CycloneDX SBOM, Grype final-artifact vulnerability correlation, Cosign signature/provenance/signed-SBOM attestation, and Kyverno admission. This architecture decision does not by itself prove repository implementation or production execution; the current evidence below records those states separately.

Current repository and developer-host evidence is:

- project Agent Context Engine source/contracts/tests are present; Context MCP adapter source is external under ADR-0051; CI evidence remains commit-specific;
- ADR-0047/0051 Context tunnel integration is external; current migration evidence verifies live Context authority at `/home/coder/workspace/Hooshix` through `windows-mcp-wsl-exec`;
- ADR-0048/0051 Ops source/schema/tests are external in `hasanjodatshandi/HooshiXMcpRuntime`; Context 14/14, Ops 32/32, and Desktop 60/60 runtime suites passed on current runtime main; live 13-tool discovery, 135-second persistent execution with short polling, runner-owned cancellation, ACL, health/readiness, and audit-redaction evidence passed;
- ADR-0049/0050/0051 Desktop source/schema/tests are external; the HooshiX runbook remains, external runtime tests and live `desktop.status` passed, and prior GUI/credential host evidence remains valid; remaining host negatives stay NOT VERIFIED;
- service-specific Semgrep enforcement exists for Compromised Password, Notification, Identity, Authorization, and Web BFF; a separate frontend JS/TS policy and positive/negative fixtures are wired into frontend CI; all protected service jobs passed in run `33301549810` and the frontend policy passed in run `33301549573` on implementation commit `209684a`;
- OSV-Scanner locked-dependency advisory scanning exists for Compromised Password, Notification, Identity, Authorization, Web BFF, and the frontend npm lockfile; all protected service scans passed in run `33301549810` and the frontend scan passed in run `33301549573` on implementation commit `209684a`;
- Gitleaks 8.30.0 redacted current-tree and full-Git-history scanning plus negative/current-tree-positive/commit-then-delete history fixtures is PRESENT in all six implemented Java service security workflows; the prior five protected service jobs passed their configured Gitleaks steps on `main@68cf66c` in run `33105936814`, and all six Stage 9 branch jobs passed in run `36000377069`;
- Syft/Grype/Cosign production release automation covers all six Java services and `web-frontend` as seven exact-digest artifacts in the protected main-only evidence workflow with checksum-pinned tools, retained SBOM/scan/database metadata, signature, SLSA provenance, signed CycloneDX attestation, and a two-hour deployed-digest rescan workflow; PR #142 head `940bab4e` passed protected baseline `36155528078` and frontend `36155527676`, while merged `main@9d98bb21` passed baseline `36156349257` and frontend `36156348709`. No real production release/deployed-digest execution is yet VERIFIED;
- production Kyverno admission generation binds all seven release digests and the signer identity using stable policies.kyverno.io/v1 fail-closed ValidatingPolicy/ImageValidatingPolicy controls; the production frontend Helm workload and WAF route exist in repository source, while production cluster admission and deployed routing remain NOT VERIFIED.

Trivy and OWASP Dependency-Check are not selected current-baseline tools under ADR-0045. Their absence is not an implementation gap unless a later reviewed decision changes the selected control chain.

A developer-only fast application-integration infrastructure is present under `infrastructure/local/` with the WSL runtime supervisor under `scripts/local/runtime.py`. It runs pinned PostgreSQL, Redis, and a single combined KRaft Kafka broker plus the established application set, provisions versioned erasure command/receipt/DLT topics, and exposes an executable UUID-only erasure smoke. A separate local production-fidelity kind/staging lane is implemented under root `deploy/`, the versioned platform infrastructure roots, and `scripts/platform/`; current evidence covers the three-node kind/Calico/Gateway API foundation, Istio Ambient STRICT mTLS/workload identity, Kyverno CEL admission, Traefik/WAF, local staging PostgreSQL/Redis/Kafka, all six services, and the observability stack. The current lane passed exact-digest six-service deployment and five-participant erasure restart/restore/reconciliation; the historical exact `f19746f` four-participant/five-application rehearsal remains valid evidence for its revision only. Stage 9 additionally exercised the exact Conversation image at `afb738d6623316e876ed938e0869bba39b8a21a7` and returned it to safe-disabled state. This is local evidence only and its mesh-protected plaintext Kafka is not Production native TLS/authentication/ACL or Production PITR/DR evidence. Production K3s runtime deployment, CloudNativePG/Barman, Kafka, OpenBao/External Secrets, Argo CD reconciliation, host access, external host monitoring, real final-artifact release execution, capacity/DR, and production readiness remain absent or NOT VERIFIED as listed below.

Authorization and Web BFF application services are implemented as current repository slices. BFF OpenAPI 3.1 version 2.2.0 covers all 68 implemented public controller method/path mappings, including self-erasure, Conversation, and enum-only Conversation feedback, with schema validation, consumer examples, controller/OpenAPI parity, and generated frontend transport-type drift enforcement. Its breaking pre-Production contract boundary uses UUIDv4 `Idempotency-Key` for business replay identity and leaves mutable `X-Request-Id` as non-authoritative telemetry only. Web BFF also provides trusted-client-address forwarding, RFC 9457 public errors, encrypted server-side security state, semantic quota, and an ADR-0028 Kafka participant that removes indexed subject sessions and emits durable non-PII receipts. Authorization V4 removes subject-linked tenant/platform authority through its own atomic Inbox participant while preserving tenant-owned policy. Local Gradle/contract/frontend/Helm/Prometheus/baseline gates and the complete local Kafka smoke pass for this revision passed; protected evidence is commit-specific. Production deployment remains environment-specific and `NOT VERIFIED`. No production platform runtime, real Google Gmail or production SMS.ir delivery, production provider/corpus approval, restore exercise, complete-stack load test, executed production artifact-signing release/admission chain, or production traffic readiness is claimed. Google OIDC remains implemented but its external execution is owner-deferred and unrelated to Notification-provider readiness.

## Capability/service status

| Capability | Architecture | Independent implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | IMPLEMENTED registration/authentication/ExternalIdentity/Session/JWT/password/Profile/Contact/MFA/Tenant plus ADR-0028 erasure coordinator and participant | current local revision: Java 25 strict full check, PostgreSQL/Redis integration, fourteen Flyway migrations through V14 Conversation lifecycle/erasure rollout, legal hold, Outbox/Inbox/idempotency, non-PII command/receipt/lifecycle events, finite retry and receipt-gated completion, Helm/Prometheus, historical four-participant local Kafka smoke, and current five-participant production-fidelity staging rehearsal pass; protected Stage 9 branch service evidence passed in run `36000377069` | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | IMPLEMENTED current repository slice plus ADR-0028 participant | Java 25 strict full check, PostgreSQL/Redis integration, four Flyway migrations through V4 erasure, subject-authority removal, atomic Inbox/receipt Outbox, Buf compatibility, hardened Helm/Kafka network policy and erasure alert rules pass locally; current protected service suite passed on `main@68cf66c`; prior kind/staging V3 evidence predates this revision and production runtime remains NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | IMPLEMENTED delivery runtime plus ADR-0028 participant | Java 25 strict full check, PostgreSQL integration, seven Flyway migrations through V7 reconciliation lookup indexing, bounded delivery/reconciliation, encrypted escrow, subject-target paging/deletion, atomic Inbox/receipt Outbox, provider fixtures, Buf, Helm/Prometheus and complete local Kafka smoke pass. Real Gmail SMTP acceptance and SMS.ir Production credential/sender/bulk acceptance plus authenticated terminal reconciliation pass in local staging; actual SMS `DELIVERED` and deployed Production delivery remain NOT VERIFIED. Protected Stage 7 service suite passed at `fb18a08` in run `34674964250` | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | IMPLEMENTED public facade plus ADR-0028 session-state participant | OpenAPI 3.1 version 2.2.0 covers all 68 public operations including self-erasure, Conversation, and enum-only Conversation feedback and separates UUIDv4 `Idempotency-Key` business identity from mutable `X-Request-Id` telemetry; Conversation uses exact audience/workload policy, one 900ms attempt, bounded concurrency, and fail-closed safe mapping; schema/examples/parity/generated-type drift, PostgreSQL V1 participant state, Redis indexed-session erasure, atomic Inbox/receipt Outbox, strict full check, Helm/Prometheus and local Kafka smoke pass locally; deployed Production evidence remains NOT VERIFIED | NOT VERIFIED | `services/web-bff` |
| Web Frontend | DESIGNED | IMPLEMENTED foundation/onboarding/profile/password/MFA/Tenant-lifecycle/erasure/Conversation repository slices plus bounded request/privacy, quality, and release-path hardening | The bilingual Conversation journey includes create/select/archive/delete, history, asynchronous run polling/cancel, abort cleanup, and text-only output rendering. Exact React/ReactDOM pins, OSV/Semgrep, digest-pinned hardened Caddy, component/API/build/accessibility/browser and release-contract gates are present. No frontend staging/Production workload, route, executed signing/admission, or deployed browser evidence is claimed; those remain NOT VERIFIED | NOT VERIFIED | `apps/web-frontend` |
| Conversation Service | DESIGNED under ADR-0054/0057 | IMPLEMENTED for the accepted first private Conversation/ModelRun slice; post-canary runtime safe-disabled | Local evidence covers exact-audience JWT, one-attempt Authorization, validated gRPC/OpenAPI, ownership/RLS/encryption, reservation/replay/cancellation, bounded worker/provider, conservative ambiguity and reconciliation, ordered lifecycle/erasure, exact BFF workload routing, browser polling/text rendering, signed v3.1 evaluation/provider-approval/canary receipts, one successful synthetic `CANARY_1`, a 206-minute clean observation, rollback to false/0, and a no-provider-call negative proof. Production activation remains NOT VERIFIED | NOT VERIFIED | `services/conversation-service`; `mlops/` |
| Compromised Password Service | DESIGNED | IMPLEMENTED | canonical WSL Java 25 strict Gradle/integration/bootJar, Buf, Semgrep, OSV, Gitleaks tree/history, Helm/render, and observability-artifact gates pass locally. The exact `f19746f` local production-fidelity image mounted the fresh official 2,068,408,781-record complete corpus; compatibility bounds, 256-sample cold/warm p99, 2x configured-concurrency load under read-only storage pressure, and three-start rebuild/redeploy/recovery passed. The run observed no capacity rejection and makes no exact saturation-point claim. Production corpus approval/release and Production runtime remain NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` |
| Reference Data capability | DESIGNED | local immutable adapter permitted when needed | NOT VERIFIED | NOT VERIFIED | owning deployable bundle/module |
| Reference Data independent service | DESIGNED / GATED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` only after ADR-0041 trigger |

`IMPLEMENTED` means the repository artifacts for the implemented slice exist. It does not mean the service, production corpus, production provider integration, or release artifact has been deployed or approved.

## Platform and DevSecOps status

| Platform/control area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| Local integrated WSL application runtime | DESIGNED as fast application lane | IMPLEMENTED under `infrastructure/local/` + `scripts/local/runtime.py` | pinned PostgreSQL/Redis/Kafka, isolated DB roles/Flyway, versioned erasure topics/DLT, generated Git-ignored security/TLS material, all five service readiness/gRPC checks, local HTTPS bootstrap/unauthenticated-negative route, and executable four-participant erasure completion smoke passed; not staging/production evidence |
| Local production-fidelity kind/staging lane | DESIGNED as integration-fidelity lane | IMPLEMENTED under root `deploy/`, versioned platform infrastructure roots, and `scripts/platform/` | current local evidence passes kind/Calico/Gateway API, Istio Ambient, Kyverno CEL admission, Traefik/WAF, PostgreSQL/Redis/Kafka, exact-digest six-service deployment, full observability, complete-corpus HIBP latency/load/recovery, five-participant erasure restart/restore/reconciliation, and the Stage 9 Conversation canary/rollback; local secrets and node-local corpus remain non-Production evidence |
| Repository governance baseline | DESIGNED | IMPLEMENTED | CI evidence is commit-specific; `make baseline-verify` is the local entry point |
| AI model evaluation/safety governance | DESIGNED under ADR-0057 | IMPLEMENTED as versioned Git-owned catalogs/schemas/prompt/synthetic suite, deterministic gate, signed content-free evaluation/provider-approval/canary tooling, and safe-disabled runtime | signed v3.1 real-provider evaluation passed 12/12, 8/8 critical, zero errors, p95 4.782s, and $0.034753 total measured cost. Private staging account/owner approval and exact-runtime one-percent canary/rollback evidence passed. Later canary promotion, real-user data, and Production model runtime remain NOT VERIFIED |
| Git-native Agent Context Engine | DESIGNED under ADR-0046/0051 | IMPLEMENTED project engine | bootstrap/router/checkpoint/retrieval source + deterministic tests present; MCP adapter is external; CI evidence is commit-specific |
| ChatGPT Web Context Engine tunnel bridge | DESIGNED under ADR-0047/0051 | IMPLEMENTED external Windows runtime + host integration | current migration path VERIFIED with `/home/coder/workspace/Hooshix` through `windows-mcp-wsl-exec`; prior tunnel evidence remains host-specific |
| ChatGPT Web developer-host Ops MCP | DESIGNED under ADR-0048/0051 | IMPLEMENTED external Windows runtime + host integration | runtime `main@3be8d1d` passes Context 14/14, Ops 32/32, Desktop 60/60; live Ops discovery has 13 reviewed tools; local timeout remains 300s and wrappers request 1h MCP connection TTL; a 135127ms persistent WSL job completed through short polling and job-ID-only cancellation reached `cancelled`; synchronous tunnel lifetime remains a separate shorter bound and is not extended by persistent jobs; production authority NOT APPLICABLE |
| ChatGPT Web developer-host Desktop MCP | DESIGNED under ADR-0049/0050/0051 | IMPLEMENTED external Windows runtime + host integration | independent runtime unit/security suite and live `desktop.status` passed during migration; prior UI/tunnel/credential-use evidence remains host-specific; real logoff/logon, selected negative cases, and revocation/rollback NOT VERIFIED |
| Cross-project/central agent memory service | NOT SELECTED / GATED under ADR-0046 | NOT APPLICABLE | NOT APPLICABLE until evidence trigger + new ADR |
| Compromised Password service CI/architecture/security/dataset-build gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Notification service CI/architecture/security/migration/deployment gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Identity registration/authentication CI/architecture/security/migration/quota/deployment gates | DESIGNED | IMPLEMENTED for current repository slices | protected merged-main execution passed Gitleaks, OSV, strict Gradle verification, unit/integration/architecture/SpotBugs, Buf, Semgrep, Helm render hardening, Prometheus/dashboard, runtime-image, generated-file, and final baseline gates on `main@68cf66c` in run `33105936814`; local authentication/session/JWT and kind/staging evidence also passed; production deployed-runtime evidence remains NOT VERIFIED |
| Semgrep source SAST/policy | DESIGNED under ADR-0039/0045 | IMPLEMENTED for the current code boundary | rules/workflows are present for Compromised Password, Notification, Identity, Authorization, Web BFF, and the frontend; protected service run `33301549810` and frontend run `33301549573` passed on implementation commit `209684a` |
| Gitleaks current-tree/Git-history secret scanning | DESIGNED under ADR-0045 | IMPLEMENTED | immutable-digest Gitleaks 8.30.0 is wired into all six Java service workflows with negative/current-tree-positive/commit-then-delete fixtures and reviewed narrow false-positive policy; historical five-service evidence passed on `main@68cf66c`, and protected six-service Stage 9 branch evidence passed in run `36000377069` |
| OSV-Scanner declared/locked dependency advisory scan | DESIGNED under ADR-0045 | IMPLEMENTED for the current code boundary | OSV-Scanner 2.4.0 is checksum-pinned for all six Java service lockfiles and the frontend npm lockfile; historical protected evidence predates Conversation, while all six current Stage 9 branch scans passed in run `36000377069` |
| Syft final-image CycloneDX SBOM generation | DESIGNED under ADR-0035/0045 | IMPLEMENTED in protected production release workflow | repository unit/static verification PASSED; real production image execution NOT VERIFIED |
| Grype final-image/SBOM vulnerability correlation | DESIGNED under ADR-0035/0038/0045 | IMPLEMENTED in protected release + two-hour deployed-digest rescan workflows with retained DB/scan evidence | repository unit/static verification PASSED; production registry/feed/deployed-digest execution NOT VERIFIED |
| Cosign image signature/provenance/signed-SBOM release automation | DESIGNED under ADR-0017/0045 | IMPLEMENTED with exact protected main workflow identity and GitHub OIDC issuer | repository unit/static verification PASSED; real production signing/verification execution NOT VERIFIED |
| Kyverno production release admission | DESIGNED under ADR-0017/0045 | IMPLEMENTED as stable CEL release-policy generation | repository render/static verification PASSED; production cluster enforcement NOT VERIFIED |
| Trivy / OWASP Dependency-Check | NOT SELECTED under ADR-0045 | NOT APPLICABLE | NOT APPLICABLE |
| Production K3s/Kubernetes/Calico | DESIGNED | PARTIAL host-specific bootstrap | 2026-09-30 read-only root inventory again passed API readiness and found all seven Calico/CoreDNS/Kyverno pods ready with zero restarts; the previously observed K3s version was `v1.35.6+k3s1`. Admission/network negative tests, reproducible production provisioning, backup/recovery and complete readiness remain NOT VERIFIED by this inventory. Local kind evidence does not replace them |
| Istio Ambient runtime | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local 1.30.3 foundation plus STRICT mTLS/workload-identity positive/negative verification PASSED; production runtime NOT VERIFIED |
| Kyverno CEL policy/admission set | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local 1.18.2 stable CEL digest/workload hardening positives/negatives PASSED, including exact Collector hostPath denial; release signature/provenance/SBOM admission and production runtime NOT VERIFIED |
| Traefik + Caddy/Coraza edge | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local exact-pinned route, direct-bypass denial, workload identity, WAF, and secret-canary verification PASSED; upstream production L4/DDoS/client-address environment evidence NOT VERIFIED |
| WireGuard management overlay | DESIGNED | PARTIAL host-specific bootstrap, persistent SSH ingress guard and scoped human forwarding hardening | 2026-09-30 post-reboot evidence passed for the host guard restricting human SSH ports 22/22022 to `wg-hooshix`; fresh private SSH passed and public 22/22022 were denied from the operator device. On 2026-10-01 the human-only forwarding policy was applied and fresh private login/remote-forward denial passed; rollback timer cancellation and installed config hash were independently confirmed. Separate TCP/2222 service/listener remained active and unchanged; public MCP execution is not claimed. Protected OS audit, SSH bind/key policy, peer revocation, JIT, off-host audit, provider firewall, recovery and full Production readiness remain NOT VERIFIED |
| Reproducible production operator tooling | DESIGNED | PARTIAL | Git-owned offline CA package builder, Persian installation/WireGuard lifecycle guides and allow-list read-only host inventory exist. Five inventory negative/privacy tests and local baseline/static checks passed; real-host non-privileged and manually authenticated read-only cluster inventory passed. This is tooling evidence, not approval of root custody, privileged access, deployment, provider delivery, restore or production readiness |
| CloudNativePG/PostgreSQL | DESIGNED | local staging PostgreSQL IMPLEMENTED; production CloudNativePG/Barman NOT PRESENT | local PostgreSQL 18.4 role/database isolation and Flyway evidence PASSED; production CNPG/PITR/restore NOT VERIFIED |
| Security Redis | DESIGNED | local staging Redis IMPLEMENTED; production deployment NOT VERIFIED | local Redis 8.2.8 `noeviction`/AOF policy and application integration PASSED; production TLS/ACL/recovery/capacity evidence NOT VERIFIED |
| Kafka | DESIGNED | developer-only local integrated runtime IMPLEMENTED; kind/staging and production deployment NOT PRESENT | pinned local KRaft broker, explicit command/receipt/DLT topics, host/internal listeners and real four-participant erasure replay passed; production durability/ACL/capacity/recovery NOT VERIFIED |
| OpenBao + External Secrets | DESIGNED | NOT PRESENT | NOT VERIFIED |
| GitOps/Argo CD | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Cross-service CI/security/supply-chain release gates | DESIGNED | Stage 10 seven-artifact source merged; protected head and merged-main CI PASSED | Semgrep/OSV/Gitleaks-capable service workflows plus frontend Semgrep/OSV/image/Helm gates are present; Syft/Grype/Cosign exact-digest release automation, two-hour deployed-digest rescanning, and stable Kyverno release-admission generation cover all seven application release components. PR #142 head runs `36155528078`/`36155527676` and merged-main runs `36156349257`/`36156348709` passed; real production release/rescan/admission execution remains NOT VERIFIED |
| OpenTelemetry Collector | DESIGNED under ADR-0044 | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | three-node local Collector DaemonSet, bounded queues/privacy config, exact read-only pod-log hostPath, metrics, and OTLP integration PASSED |
| Prometheus/Alertmanager/Grafana | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local exact-pinned deployments, five-service/Collector target health, Grafana datasource, and no-plugin-update hardening PASSED |
| Loki log backend | DESIGNED under ADR-0044 | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | Collector -> Loki safe-log canary, privacy-filter negative, and backend-outage non-authority behavior PASSED |
| Tempo trace backend | DESIGNED under ADR-0044 | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | Collector -> Tempo OTLP trace canary and backend-outage non-authority behavior PASSED |
| External host-down monitoring | REQUIRED / PROVIDER TBD | NOT PRESENT | NOT VERIFIED |
| Authoritative privileged/security audit | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Backup/PITR/cold-DR automation | DESIGNED | NOT PRESENT | NOT VERIFIED |

## Repository governance now enforced

The bootstrap baseline makes these current repository invariants executable:

- `FILE_INDEX.txt` must exactly match the clean repository file set and remain sorted;
- the automation-safe final-report contract retains the exact `Outcome`, `Remaining work`, `Continuation action`, `Retryable`, and `Human action required` keys and canonical token sets used by local task-supervision automation;
- ADR file identifiers, headings, and the Decision Register must remain consistent and non-reused;
- dependency-registry version/classes/required edge fields/policy references must match the current schema constraints enforced by the bootstrap verifier;
- the dependency Markdown operation list must match canonical YAML exactly and in canonical order;
- current architecture source references checked by the baseline must resolve to repository files;
- `context/bootstrap.json`, `context/routes.json`, and checkpoint contracts resolve to current tracked repository authorities;
- `docs/architecture/TASK-REVIEW-MATRIX.md` must exactly match canonical `context/routes.json` generation;
- Context Engine targeted review trust fails safe when configured authority state is dirty/invalid or routing is ambiguous;
- Context Engine retrieval remains tracked-file/local/bounded/provenance-bearing and Context MCP remains read-only/stdio-only;
- object-shaped Context MCP successes preserve matching JSON text and `structuredContent`, so the tunnel/client boundary does not depend on reparsing the only result representation;
- Context MCP startup is independent of caller working directory; protected local policy fixes the WSL repository and project Context Engine path; Linux Git inside WSL is authority;
- ADR-0047 tunnel integration must remain an external stdio bridge and cannot add a HooshiX network listener/write/general-shell authority;
- ADR-0048 Ops MCP must remain separate from Context MCP, require local fail-closed policy, use explicit UTF-8 stdio, bound filesystem/process/audit behavior, sanitize child credential environment, and remain developer-host only;
- ADR-0049/0050 Desktop MCP must remain separate from Context/Ops, require strict local WinApp/session/app/HWND/capability policy, keep general text non-secret, allow credential use only through explicit bounded local app/executable-path/SHA-256/password-target bindings with no credential value in MCP/Python/audit/argv/environment, use explicit UTF-8 stdio, bound/redact transient capture/input/audit behavior, and remain developer-host only;
- the ADR-0041-gated `services/reference-data-service` path is rejected until the architecture/trigger evidence is intentionally revised;
- root `services/common` and `services/shared` dumping grounds are rejected;
- the Compromised Password Gradle wrapper must retain executable state.

Service-specific CI adds stricter checks for implemented code, OSV locked-dependency advisory scanning, migrations where applicable, offline dataset-build tooling where applicable, runtime compatibility validation, telemetry/privacy controls, contracts, and deployment/runtime-image artifacts. Repository governance does not replace runtime/staging/release evidence.

ADR-0045 documents the selected control chain. All five implemented Java service Gitleaks workflows are implemented, and repository Syft/Grype/Cosign release workflows plus stable Kyverno release-policy generation now exist and pass repository verification. These repository controls must not be reported as real production execution/enforcement until the production registry, signer, vulnerability feed, deployed digests, and production admission controller produce the required evidence. OSV-Scanner must not be reported as final-image vulnerability evidence.

## Implementation/release gates still not evidenced

Current architecture still requires evidence that this repository slice does not create by itself:

- real Windows/ChatGPT Web ADR-0049 remaining evidence for an actual logoff/logon cycle and stop/revoke/rollback behavior; protected policy/session/tunnel/`desktop.status`/GUI smoke/persistent-task/recovery evidence is already recorded above;
- ADR-0050 remaining host evidence for provisioned wrong-window/focus/ambiguous-password negatives and rollback/rotation behavior; EOrgsetad process-image/SHA-256, legacy native-password-control diagnosis, Medium-integrity `asInvoker` credential injection, and application login are verified for the exercised host/session, while the initial inherited High-integrity launch remains correctly incompatible with lower-integrity Desktop `SendInput`;
- real production final-image Syft CycloneDX generation bound to the exact deployed/released image digest;
- real production Grype final-image/SBOM vulnerability execution, feed freshness, exception/VEX behavior, and deployed-digest rescanning;
- real production Cosign exact-digest signature/provenance/signed-SBOM execution plus production Kyverno admission enforcement positives/negatives;
- Production approval/licensing and a signed reviewed Production dataset release artifact for the locally verified official complete HIBP source;
- Production-host complete-corpus latency/capacity/recovery evidence; local exact-commit complete-corpus row count, compatibility bounds, disk-backed p95/p99, 2x configured-concurrency load, and rebuild/redeploy/recovery have PASSED;
- production SMS.ir delivery to an owner-approved non-blacklisted recipient and deployed production runtime evidence; real Gmail staging acceptance and SMS.ir production credential/sender/acceptance/authenticated-report execution pass locally, while the SMS.ir Sandbox contract probe is simulated only;
- production-environment Collector/Loki/Tempo/Prometheus deployment, external host-loss detection, production capacity/storage/retention evidence, and authoritative off-host audit; the local kind/staging Collector/Loki/Tempo/Prometheus canary/privacy/backend-fault integration has PASSED;
- signed final image/dataset release artifacts as applicable, CycloneDX SBOM, final-artifact vulnerability correlation, provenance, admission validation, and staging-to-production digest promotion;
- production K3s/Calico/Istio/Kyverno/OpenBao/edge/observability deployment and complete-stack capacity evidence; the repository local kind/Calico/Istio/Kyverno/edge/observability integration lane has PASSED;
- deployed Identity ADR-0024 Redis quota evidence, measured production capacity/allocation thresholds, real host-time synchronization integration, NAT/IPv6/collateral tests, and complete-stack cardinality/load/failure evidence;
- Reference Data deployable trigger evidence before any independent Reference Data service creation.
- executable ADR-0054 Conversation vertical-slice evidence: service/database/contracts/BFF/frontend,
  fixed-egress provider adapter and credential/data-control approval, cost/quota reconciliation,
  tenant lifecycle/erasure, privacy/failure/load evidence, and deployed runtime.

These are implementation/release gates, not evidence that production is ready.

## Repository-level vocabulary

```text
Architecture:
  DESIGNED
  NOT DESIGNED

Implementation:
  IMPLEMENTED
  PARTIAL
  PLANNED / GATED
  NOT PRESENT
  NOT APPLICABLE

Evidence:
  PASS
  FAIL
  NOT RUN
  NOT VERIFIED
  NOT APPLICABLE
```

`IMPLEMENTED` means required repository artifact exists. It is not runtime proof.

`PASS` requires executed evidence from the applicable build/test/security/restore/load/environment gate.

## Update rule

When implementation is added/removed, update this file in the same coherent PR when repository-level status changes materially.

Runtime evidence remains in owning CI/report/environment artifact. This file may summarize but cannot replace evidence.

`PRODUCTION-READINESS-CHECKLIST.md` remains the traffic gate.
