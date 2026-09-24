# Local Production-Fidelity Staging Lane

## Purpose

This runbook operates the repository-owned local production-fidelity integration lane in the canonical WSL checkout `/home/coder/workspace/Hooshix`. It verifies Kubernetes, Calico, Istio Ambient, Kyverno admission, Traefik/WAF, local staging PostgreSQL/Redis/Kafka, the five fully integrated pre-Conversation application services, and the local observability stack together. The Stage 9 Conversation foundation is not added to this lane until its protected API/lifecycle activation requirements exist.

This lane is **integration fidelity only**. It is not the selected `production-single-server` K3s runtime and does not prove production readiness, production HA, complete-stack capacity, backup/DR, external host-down monitoring, production secret delivery, production provider delivery, or the final Syft/Grype/Cosign release chain.

## Repository interface

```bash
cd /home/coder/workspace/Hooshix
make production-fidelity-up
make production-fidelity-verify
```

Stop the lane with:

```bash
make production-fidelity-down
```

`production-fidelity-up` stops the fast host-JVM lane before creating the cluster, verifies the required inotify limits, creates the pinned kind/Calico/Gateway API foundation and local registry, builds the pinned WAF image, installs Istio Ambient and Kyverno, installs the edge, creates the generated Compromised Password staging fixture, installs local staging PostgreSQL/Redis/Kafka, installs observability, builds the six application images, deploys the six Helm releases, and runs the composite verifier.

## Implemented local stack

The current repository automation uses these reviewed local pins:

```text
kind 0.32.0
Kubernetes node image v1.35.5 by exact digest
Calico 3.32.1
Gateway API 1.5.1
Istio Ambient 1.30.3
Kyverno 1.18.2
Traefik 3.7.10 / chart 41.2.0
Caddy 2.11.4 + coraza-caddy 2.5.0 + Coraza 3.7.0 + CRS 4.25.1
PostgreSQL 18.4
Redis 8.2.8
Kafka 4.2.1, one combined KRaft broker/controller
otelcol-contrib 0.157.0
Prometheus 3.13.2
Loki 3.7.4
Tempo 3.0.2
Grafana 13.1.3
Alertmanager 0.33.1
```

Application images are built from the current source with Eclipse Temurin 25.0.4 and pushed to the loopback local registry. Deployments use the exact recorded image digest. The browser-facing staging origin is exactly `https://localhost:8443`; `localhost` is used because Google permits it for local OAuth clients while private pseudo-TLDs such as `.local` are not valid Google OAuth web origins.
The staging image state also records exact Git `HEAD`, clean/dirty source state, and a SHA-256 over tracked plus non-ignored untracked worktree files. Build, deploy, and verification fail if that source provenance changes between stages. Dirty development work remains testable, but only a `clean` source state is exact commit evidence. A new all-service build replaces the prior image-state file before the first image build, so a partial build cannot silently reuse stale service digests.

## Security and data boundaries

- kind uses Calico; kindnet is prohibited. The `linux/amd64` local lane resolves the reviewed Calico content by its pinned AMD64 manifest digests from `docker.io/calico`, caches each exact image once on the WSL host, and sideloads it into all kind nodes before applying the byte-identical vendored manifest with local registry/digest substitution.
- Application and edge identities use independent Kubernetes ServiceAccounts.
- Selected namespaces are explicitly Ambient-enrolled; application workloads do not use sidecars.
- STRICT mTLS and Istio authorization enforce the reviewed caller paths.
- Traefik can reach the WAF but cannot reach Web BFF directly.
- WAF can reach Web BFF on the approved path.
- An unauthorized workload cannot reach the protected WAF/BFF path.
- Kyverno uses stable CEL policy APIs and blocks mutable image references and unsafe workload shapes. Its five local `linux/amd64` controller/init images are host-cached, mirrored into the loopback kind registry with digest-preservation checks, and referenced by those reviewed platform-specific manifest digests when the byte-identical vendored installer is rendered.
- The node Collector exception is restricted to the exact `otel-collector` identity/image/security context and read-only `/var/log/pods`; other host paths are denied.
- Local PostgreSQL uses distinct migration/runtime roles and databases for Authorization, Identity, Notification, and the Web BFF erasure participant. Runtime roles are non-superuser/non-owner and cross-service `CONNECT` is denied.
- Staging datastore NetworkPolicy and Istio authorization are applied before datastore workloads. The PostgreSQL bootstrap job denies ingress and permits egress only for DNS and PostgreSQL/HBONE. WAF NetworkPolicy, strict mTLS, and AuthorizationPolicy resources are created before the WAF pod and public route.
- Local Redis uses `noeviction`, AOF, and `appendfsync everysec`.
- Local staging Kafka uses the exact Kafka 4.2.1 image digest, one combined KRaft
  broker/controller, RF1/minISR1, `acks=all` clients, no automatic topic creation, no unclean leader
  election, explicit versioned erasure topics, and strict Ambient workload-identity policy. It is
  ephemeral, non-HA, and uses mesh-protected plaintext inside this local lane; it does not satisfy
  Production native Kafka TLS, per-service authentication/ACL, quota, durability, or recovery gates.
- Staging credentials, TLS/key material, generated image state, and verification logs remain under Git-ignored `.platform-runtime/` or Kubernetes Secrets created from local generated state. They are not production secrets.
- Compromised Password uses a deterministic `GENERATED_TEST_FIXTURE` by default, not the production HIBP corpus. The exact generated manifest SHA-256 is bound into the deployed service at runtime and verified against the mounted manifest. The separate complete-corpus evidence procedure below uses its own 128-GiB static claim and private values overlay, so normal staging cannot silently claim HIBP evidence.

## Optional complete-corpus HIBP staging evidence

Keep the raw official SHA-1/count download and the generated SQLite release outside Git and container images. The builder accepts the UTF-8 BOMs emitted at official-downloader range boundaries, validates every canonical record, and keeps those bytes inside the verified source SHA-256. Select compatibility bounds only after measuring the complete current source; the 2026-09-09 profile selected `4096` records and `131072` serialized bytes from observed maxima `2509` and `102932`.

Build the reviewed release with `buildCompromisedPasswordDataset` as documented in `services/compromised-password-service/dataset/README.md`, then install only its SQLite artifact and manifest:

```bash
scripts/platform/staging_hibp_dataset_install.sh \
  /approved-local-release/compromised-password.sqlite \
  /approved-local-release/compromised-password.manifest.json
scripts/platform/staging_deploy_service.sh compromised-password-service
make staging-verify
```

The installer rejects symlinks, stale or wrong-source manifests, incompatible bounds, a builder revision different from current `HEAD`, and artifact-digest mismatch. It scales the current service to zero before replacing the verified node-local immutable pair, uses the dedicated `compromised-password-hibp-dataset` claim, and publishes its Git-ignored mode-`0600` overlay only after the copied artifacts verify. Complete-corpus startup retains full artifact digest and SQLite integrity verification. The first real two-billion-row staging run showed that the earlier 30-minute allowance was insufficient for both scans on the local Docker volume, so the chart now uses a bounded 120-minute startup-probe window, approximately 125-minute Helm wait, and 130-minute Deployment progress deadline without weakening readiness. Running `scripts/platform/staging_dataset_install.sh` removes that overlay and restores the default fixture identity on the next deployment.

## Optional provider staging credentials

The default lane keeps Google OIDC and Notification delivery disabled unless their optional private files are present. External credentials are
owner-created state and MUST remain outside Git. Do not paste them into chat, shell arguments,
environment variables, Helm values, or logs. The containing `.platform-runtime/staging/private`
directory must be user-owned mode `0700`; every credential file must be a regular, non-symlink,
user-owned mode-`0600` file.

### SMS.ir Sandbox

Place only the Sandbox API key in:

```text
.platform-runtime/staging/private/smsir-api-key
```

Run the identifier-free contract probe with:

```bash
scripts/performance/smsir_sandbox_probe.py \
  --api-key-file .platform-runtime/staging/private/smsir-api-key \
  --output .platform-runtime/stage7/smsir-sandbox-probe.json
```

The probe uses the official predefined Verify template `123456`, checks successful authentication,
input rejection, explicit authentication failure, TLS hostname validation, and the simulated response
shape. SMS.ir states that Sandbox sends no real SMS, consumes no credit, and keeps no report. The
receipt therefore always records `real_delivery_claimed=false`; it neither enables Notification
delivery nor satisfies production acceptance/delivery evidence.

### Gmail SMTP and production-capable SMS.ir

The delivery runtime requires both live channels to start fail-closed. For a bounded Gmail test plus
a production-capable SMS.ir account, create:

```text
.platform-runtime/staging/private/notification-providers.properties
```

with this exact schema:

```properties
email.provider=GOOGLE_GMAIL
email.smtp.username=<dedicated-staging-mailbox@gmail.com>
email.smtp.password=<dedicated-16-character-Google-App-Password>
email.from-name=Hooshix
sms.provider=SMSIR
smsir.api-key=<production-capable-SMS.ir-key>
smsir.line-number=<approved-SMS.ir-sender-line>
```

When the panel does not display a usable sender line, retrieve the account-scoped list with
authenticated `GET https://api.sms.ir/v1/line` and select only a positive decimal value returned in
the successful `data` array. Listing a line is not proof that bulk sending is active: provider status
`123` means the selected sender line still requires account-side activation, and the real delivery
exercise must stop without retry until the owner resolves that condition in SMS.ir.

A later reviewed authenticated STARTTLS provider can use the same file with the generic Email profile:

```properties
email.provider=GENERIC_SMTP
email.smtp.host=<reviewed-provider-dns-name>
email.smtp.port=587
email.smtp.username=<provider-username>
email.smtp.password=<provider-password>
email.from-address=<approved-sender-mailbox>
email.from-name=Hooshix
sms.provider=SMSIR
smsir.api-key=<production-capable-SMS.ir-key>
smsir.line-number=<approved-SMS.ir-sender-line>
```

This is a configuration contract, not blanket approval for arbitrary SMTP destinations. Google
requires two-step verification before a dedicated App Password can be created. Gmail SMTP is Email
transport only and is unrelated to Google OIDC. Allowed test recipients remain owner-controlled and
bounded. `scripts/platform/staging_secrets_apply.sh` imports the optional combined file without
printing its contents and rejects unsafe ownership, mode, or symlinks.

### Google OIDC

Google OIDC is only the optional browser “Sign in with Google” path. It is unrelated to Gmail SMTP or
SMS.ir and is not required for Notification-provider evidence. Create a Google OAuth 2.0 **Web
application** client with these exact entries:

```text
Authorized JavaScript origin: https://localhost:8443
Authorized redirect URI:      https://localhost:8443/api/v1/auth/oidc/google/callback
```

The JavaScript origin contains only scheme, host, and port; the callback path belongs only in the
redirect URI. Download the client JSON directly into the user-owned mode-`0700` private directory as
a regular mode-`0600` file:

```text
.platform-runtime/staging/private/google-oidc-client.json
```

`scripts/platform/staging_secrets_apply.sh` validates the Web-client shape and atomically derives a
mode-`0600` client-secret file plus a private client-ID Helm overlay without printing either value.
`staging_deploy_service.sh web-bff` detects that overlay, enables OIDC, mounts the independent client
Secret, and renders only the fixed Google HTTPS egress. The Gmail App Password and OAuth client secret
remain purpose-separated and must never be substituted for one another. Local-password authentication
remains available when the optional file is absent. Trust the repository-generated localhost test
certificate in the test browser before an interactive provider exercise; bypassing TLS validation is
not provider evidence.

## Verification

The composite command is:

```bash
make production-fidelity-verify
```

It verifies at least:

- three-node kind/Kubernetes readiness, Calico digests, Gateway API CRDs, and inotify prerequisites;
- Istio control plane/CNI/ztunnel and non-blocking `istioctl analyze`;
- Kyverno CEL policy positives/negatives;
- PostgreSQL/Redis/Kafka images, runtime policy, Kafka topic retention, roles, and the complete runtime-role/database `CONNECT` isolation matrix;
- all six application Helm releases, exact image digests, ServiceAccounts, waypoint readiness, Flyway counts, browser bootstrap, and BFF-to-Identity non-enumerating negative authentication;
- public Traefik -> WAF -> BFF traversal, direct-bypass denial, STRICT mTLS, workload-identity positives/negatives, and edge secret-canary log absence;
- Prometheus targets for all six services and all Collector instances;
- Collector -> Tempo trace canary;
- Collector -> Loki safe-log canary and privacy-canary rejection;
- Grafana hardened datasource health;
- application readiness and public bootstrap while Tempo and Loki are independently unavailable;
- no remaining non-ready platform pods after verification cleanup.

Info-level `istioctl analyze` diagnostics are reported but are not equivalent to blocking configuration errors. The verifier fails on blocking analysis findings.

## Staging erasure recovery rehearsal

After all six images have been built and deployed from a clean current commit and the composite
verifier passes, run the destructive-to-test-state rehearsal:

```bash
make staging-erasure-recovery
```

The runner refuses a dirty worktree, a non-`kind-platform-local` current context, stale/dirty image
provenance, missing one-replica readiness, or any database outside the five participant-owned
databases. It scales the six applications to zero, inserts one synthetic identifier-only Identity
request, snapshots Authorization/Conversation/Identity/Notification/Web BFF with PostgreSQL custom-format dumps,
and then:

1. starts the exact deployed images and requires all five durable participant receipts plus Identity
   `COMPLETED/DELETED` state;
2. restarts every application deployment and rechecks the immutable terminal evidence;
3. stops applications, restores all five pre-completion snapshots in bounded single transactions,
   starts the same images, and requires normal Outbox/Kafka/Inbox replay to reach the same terminal
   state without reappearance.

Snapshots are mode `0600`, temporary, limited to the explicit database allow-list, and removed after
the run. On a recoverable failure after snapshots exist, the runner stops applications, attempts the
same bounded restore, and restarts only after restore success. If automatic restore also fails, it
leaves applications stopped and reports that exact condition rather than starting against partially
restored state.

A passing run atomically writes only an identifier-free, mode-`0600` aggregate receipt to
`.platform-runtime/stage7/staging-erasure-recovery.json`. It is local staging evidence, not a
Production PITR/DR claim.

## Local WSL etcd storage

The kind control-plane etcd data directory is bind-mounted from the WSL host tmpfs path:

```text
/dev/shm/hooshix-kind/etcd -> /var/lib/etcd
```

The dedicated parent `/dev/shm/hooshix-kind` may become root-owned if Docker restores a kind node after WSL has cleared tmpfs. Repository cleanup therefore mounts only that dedicated parent into the pinned kind node image, deletes only its contents, restores the parent to the invoking numeric UID/GID, and then removes it. It never mounts all of `/dev/shm` writable and does not require broad host elevation. The storage remains intentionally ephemeral and is not a production durability design. The staging verifier checks both the exact Docker mount source and that `/dev/shm` is `tmpfs`.

## Evidence boundary

A passing local composite verifier proves that this repository slice can run together in the local kind production-fidelity lane and that the listed local security/integration negatives passed for that execution. It does **not** prove:

- deployment on the production K3s profile;
- CloudNativePG/Barman PITR or production PostgreSQL recovery;
- production Redis TLS/ACL/recovery;
- Production Kafka native TLS/per-service authentication/ACL/quota/durable-disk/rebuild evidence;
- OpenBao/External Secrets production delivery;
- Argo CD production reconciliation;
- WireGuard/FIDO2/JIT host access;
- production HIBP corpus provenance/freshness/full-corpus bounds;
- Google Gmail delivery, production SMS.ir acceptance/delivery, or optional Google OIDC execution;
- external host-down detection;
- Syft/Grype/Cosign signature/provenance/SBOM promotion;
- complete-stack capacity/headroom or cold-DR targets;
- production readiness.

## Diagnostics

Use the component verifiers to isolate failures:

```bash
make local-cluster-verify
make verify-local-istio-ambient
make verify-local-kyverno
make verify-local-traefik-edge
make staging-verify
make verify-local-observability
```

Do not disable STRICT mTLS, Kyverno blocking policy, WAF traversal, database isolation, quota fail-closed semantics, or telemetry privacy controls to make a local verification pass.
