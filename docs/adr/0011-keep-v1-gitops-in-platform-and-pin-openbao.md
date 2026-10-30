# ADR-0011: Current GitOps and OpenBao Baseline

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Argo CD uses this repository as the v1 desired-state source. Environment roots are:

```text
deploy/clusters/staging
deploy/clusters/production
```

Service manifests, platform infrastructure, WAF, Istio policies, NetworkPolicies, datastore/operator resources, and admission/security policy live under reviewed `deploy/` configuration with environment composition through Helm/Kustomize as appropriate.

Promotion uses pull-request review, immutable image digests, automated sync, self-heal, prune, `allowEmpty=false`, `PruneLast=true`, and `Prune=confirm` for explicitly destructive critical resources. Direct unreviewed production cluster mutation is prohibited. Production rollback is Git revert only when rollback is safe for the corresponding schema/data state.

OpenBao is exactly `2.6.4`, pinned by immutable image digest. v1 uses a single OpenBao Raft instance/PVC with manual Shamir seal (`3` shares, threshold `2`) and hourly encrypted off-PVC snapshots. Normal application hot paths consume mounted/local key material; they do not make routine per-request OpenBao calls.

External Secrets Operator is the normal Kubernetes synchronization boundary. Secret values never enter Git, images, Helm/Kustomize values, logs, traces, metrics, or CI output. Logical secret/key names are stable; physical endpoint/materialization paths are typed configurable values.

The offline Istio Root CA private key is never stored in OpenBao or Kubernetes.

## Security patch selection

The 2026-10-04 artifact review selects upstream `2.6.4` within the existing 2.6
line, rather than introducing 2.7 or a custom rebuilt image. The previous 2.6.1
digest failed Grype with High/Critical OpenSSL matches. The official 2.6.4
security release also fixes audit-failure response handling, expired AppRole
SecretID authentication and Kubernetes JWT validation on renewal. Exact registry
manifest/config hashes, platform, version and source revision are checked when
pinning; final-image scanning and native TLS/Shamir/Raft recovery remain required
before merge. Current commissioning evidence does not establish a live OpenBao
store; this change performs no live datastore upgrade. This source
selection does not approve installation, signing, staging or production promotion.

The selected runtime flavor is official `openbao-distroless` (static nonroot),
not a local rebuild. Alpine and UBI candidates failed the required scan. Upstream
uses the same `/usr/bin/bao` binary layer across flavors. The explicit UID,
read-only filesystem, TLS mounts, resources and no-dev entrypoint override remain.
Shell-free startup/liveness use `bao read -field=sealed sys/seal-status`, an
unauthenticated read-only endpoint available sealed or uninitialized; readiness
uses `bao status -format=json` and succeeds only with exit 0 (unsealed). All probes
use verified loopback TLS, mounted CA, 3s CLI timeout and zero retries. No HTTP/TCP
probe, TLS bypass, token, shell, custom binary or sidecar is introduced. Native CI
must verify sealed/unsealed/restart behavior and rejection of wrong TLS hostname
on both command paths. Only public seal/status metadata can enter probe output.
Compressed artifact size is 77,272,197 bytes; target-disk and complete-stack
resource evidence remain deployment gates. Exact-digest scan, signature/provenance
and staging approval remain mandatory; no scanner suppression is added.

Sources: [official 2.6.4 release](https://github.com/openbao/openbao/releases/tag/v2.6.4),
[official flavor Dockerfile](https://github.com/openbao/openbao/blob/v2.6.4/Dockerfile),
[native read command](https://github.com/openbao/openbao/blob/v2.6.4/command/read.go),
[seal-status API](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/api/system/seal-status.mdx),
[zlib upstream fix discussion](https://github.com/madler/zlib/issues/1310).

## Verification Requirements

### Bounded single-server storage foundation

For the owner's current Ubuntu 26.04 amd64 VPS, OpenBao's proposed 8GiB PVC
is backed by a separately mounted, fully preallocated 8GiB ext4 file filesystem.
This is a non-HA host provisioning foundation, not a Kubernetes deployment or
a general storage provisioner. Existing partitions are never resized/formatted.
The backing file is root-only, protected against replacement and hard links;
mount flags include nodev/nosuid/noexec, with no discard. A dedicated systemd
mount unit persists the mount configuration; actual reboot persistence remains
unverified until observed on the target. The unmounted directory is root-only
and empty; OpenBao's data subdirectory on the mounted filesystem is mode 0700,
owned by UID/GID 10001. Kubelet may change only that dedicated group's permissions
to 2770 (group rwx plus setgid) when applying fsGroup=10001; the runtime guard accepts
0700, transitional 0770, or 2770 with
the exact same owner/group, never other-user access. Existing/partial files are
preserved, never reformatted.

Later reviewed GitOps may bind a static **local** PV (not hostPath) with Retain,
exact node affinity and a no-provisioner WaitForFirstConsumer StorageClass.
That activation must enforce mount/backing identity before K3s/workload startup
and runtime mount-loss failure; the host mount unit alone is NOT that guard.
The reviewed host guard uses systemd notify readiness after a successful bounded
identity check, BindsTo+After mount/guard dependencies, no automatic restart, and
a watchdog. Checks cover exact mount flags, mounted UUID, reserved backing size,
loop backing pathname AND inode/device/geometry, data ownership and filesystem
limit. Failure stops K3s control-plane/kubelet management; it does NOT claim that
K3s stopping terminates every existing container. Existing local-volume bind
mounts retain their filesystem; absent host mount leaves an empty root-only
directory without a data subdirectory, so a new local-volume mount cannot bind
a root-disk fallback. Explicit recovery and owner-approved maintenance are
required; no native SSH/MCP/mail service dependency is added.
Until those fail-closed activation, target storage, strict mesh/admission, TLS,
staging and recovery checks pass, no PV/workload is enabled. A directory-based
local-path capacity request alone does not satisfy the filesystem limit.

The host installer reserves at most 8GiB and requires at least 30% host-filesystem
space remaining immediately afterwards. This install safeguard does not prove
complete-stack headroom, backup durability or disk-I/O SLOs. CI uses only a 64MiB
disposable filesystem to test non-root ENOSPC, remount persistence and cleanup;
it never downloads a corpus or allocates 8GiB on the developer machine. Existing
host tool package versions are explicitly checked; changes require review.
See the [operator guide](../operations/production-openbao-storage-fa.md).

Native semantics: [systemd mount](https://github.com/systemd/systemd/blob/v259.5/man/systemd.mount.xml),
[dependency and readiness](https://github.com/systemd/systemd/blob/v259.5/man/systemd.unit.xml),
[notify/watchdog](https://github.com/systemd/systemd/blob/v259.5/man/systemd.service.xml),
[local-volume bind behavior](https://github.com/kubernetes/kubernetes/blob/v1.35.6/pkg/volume/local/local.go),
[fsGroup directory permissions](https://github.com/kubernetes/kubernetes/blob/v1.35.6/pkg/volume/volume_linux.go),
[loop identity columns](https://github.com/util-linux/util-linux/blob/v2.41.3/sys-utils/losetup.c),
[ext4 formatting](https://github.com/tytso/e2fsprogs/blob/v1.47.2/misc/mke2fs.8.in),
[loop mount](https://github.com/util-linux/util-linux/blob/v2.41.3/sys-utils/mount.8.adoc).

### Supervised first-initialization recovery

During the bounded ADR-0030 commissioning bootstrap, a lost `sys/init` response
does not permit automatic retry, intent removal, PVC deletion or ordinary
datastore reset. If initialization committed sealed with the operator's bound
intent but no encrypted response, and the owner explicitly confirms that shares
are unavailable and authorizes replacement of this fresh store, the reviewed
recovery tool may stop only OpenBao, preserve **all** stopped data and intent in
root-only recoverable quarantine, verify byte hashes/metadata, retain the exact
PVC/StatefulSet/mount/data-directory identity, and start the empty replacement.
The old store is never deleted; this is not snapshot/DR or a procedure for an
established production secret store. Partial recovery leaves durable intent that
blocks initialization; no reset is automatically repeated. Host guard, K3s,
CA, SSH, email, workload digest and public/private boundaries remain unchanged.

Supervised init/unseal uses one-attempt HTTPS with the existing pinned CA and
hostname verification through a temporary **host-loopback-only** kubectl forward,
not public ingress or a normal administration path. The write timeout is bounded
(60s init, 20s unseal); status and unchanged workload probes retain 3s/zero retries.
The response is PGP-encrypted by OpenBao; encrypted custody is exclusively written
and fsynced before forwarding teardown or post-checks. The root operation has a
180s deadline (300s for archive/restart), never logs secret request/response or
places credentials in argv/environment. A timeout/unknown response still requires
recovery review, never a second init. Native disposable CI must exercise this
HTTPS adapter, encrypted custody/quorum, stopped-data archival, fresh-store init,
and restoration of the retained original before target execution.

See the [activation/recovery guide](../operations/production-openbao-activation-fa.md).
API semantics: [OpenBao sys/init](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/api/system/init.mdx)
and [loopback ephemeral forwarding](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_port-forward/).

### Scoped authentication commissioning

The existing store's supervised bootstrap may establish dedicated secret-delivery
identities before ESO installation, without restarting OpenBao or revoking the
initial root credential. Six `platform-apps/eso-<service>` accounts are bound by
exact name, namespace and audience to read-only KV-v2 paths for their owning
service. They receive no default policy and OpenBao tokens have a five-minute
explicit maximum lifetime. ESO may read `auth/token/lookup-self` and update
`auth/token/revoke-self` to validate and retire only its own short-lived token;
no general token lookup/create/renew/revoke or default policy is granted. The
supervised reconciler upgrades only the exact prior service-read-only ACL to
this contract; unknown ACLs fail before mutation, and current ACLs are no-write
reentries. Native delivery validates these self-only operations as well as
cross-service denial. Client TokenRequest JWTs carry the API audience plus
the explicit OpenBao audience. Kubernetes TokenReview remains authoritative;
issuer validation is delegated to that API, not an unverified decoded JWT.
Only `create tokenreviews.authentication.k8s.io` is granted to those six accounts;
the broader `system:auth-delegator` permission set is not required. No static
reviewer JWT or automatically mounted OpenBao pod token is added.

This step adds only the existing OpenBao pod's bounded API egress. Application
namespaces retain restricted PSA, default-deny network and STRICT/default-deny
mesh boundaries. Prepared namespace-scoped SecretStore contracts reference a
public CA ConfigMap and a dedicated account; they do not install ESO, grant its
controller TokenRequest permissions or open client ingress. Those are separately
verified commissioning requirements. Native synthetic tests must cover six
service-specific canary reads, cross-service/write/admin denial, wrong identity,
namespace and audience denial, short lifetime and token revocation. Root/PVC/
custody and host services are preserved. Owner-local root use remains confined to
the ADR-0030 supervised bootstrap; no hourly root job is introduced.

See the [scoped-auth guide](../operations/production-openbao-auth-fa.md).

PostgreSQL credential delivery adds ten separate `platform-data/eso-pg-<database>-<mode>`
identities (`runtime`/`migration` for the five mutable service databases). Each
reads only its exact KV-v2 leaf `production/postgresql/<database>/<mode>`, with
the same audience, five-minute lifetime and self-only token contract. Existing
six application roles and their controller's namespace remain unchanged. A
separate controller using the existing signed ESO digest watches only
`platform-data`: its Secret access is namespace-wide there, never cluster-wide;
TokenRequest is restricted to these ten exact accounts. Its projected API JWT
expires in ten minutes; automatic token mounting is disabled. The existing
API-only validation webhook serves exactly `platform-apps` and `platform-data`,
with verified HTTPS/Fail and unchanged CA, Pod enrollment and network ingress.
No extra webhook or cert-controller is installed. Only the new controller Pod is
admitted in `platform-data`; database Pods remain closed. Credential creation is
whole-set preflight plus KV-v2 CAS=0, with no adoption/rotation on reentry. ESO
projects typed namespace-local basic-auth Secrets; secret values never enter
public plans, receipts or logs. Native evidence must include delivery, reentry,
all cross-identity/write/admin denials, revoked tokens, source attestation
negatives, existing authority preservation and log privacy. This prerequisite
does not create PostgreSQL, approve provider backup, or revoke root.

Redis ACL delivery reuses that already approved `platform-data` ESO controller
and its namespace-wide Secret cache; no additional controller, webhook, network
edge or application ingress is granted. Five independent credentials are created
with the same whole-set preflight/CAS=0/no-rotation contract. Three application
credentials live under their existing owning service path; health, monitor and
the hash-only server ACL use separate `production/security-redis` leaves. Six
new exact-account, exact-leaf read-only roles retain the five-minute explicit
maximum, explicit audience and self-only token behavior. A separate additive
Role delegates TokenRequest only for these six accounts, leaving the ten
PostgreSQL accounts/permissions unchanged. ESO projects five basic-auth Secrets
and one private ACL Secret into `platform-data` only. Application identities can
read only their own credential through their existing policy; they cannot read
the ACL, health/monitor or another owner's credential. Native tests must prove
delivery, password/hash binding, no-write reentry, all cross-identity/write/admin/
audience/namespace negatives, unchanged existing identities/data/mesh/admission,
and log privacy. TLS issuance, Redis/PVC creation, client Secret projections,
production capacity and opening traffic are separate unverified operations.

The following bounded Redis TLS bootstrap issues only its first server leaf from
the existing imported cluster intermediate, as with the initial OpenBao server
leaf. It does not create/import a new Root, move the intermediate key out of its
existing encrypted host custody, change Istio's 24-hour workload certificate
rotation, or broaden cert-manager's `cnpg-system` scope. An owner-local hidden
intermediate passphrase unlocks signing through a pipe descriptor. A new RSA-3072
server-only leaf has an exact loopback IP and two Redis service DNS names, a
30-day lifetime, and no wildcard/client-auth/CA capability. Plaintext leaf work is
private tmpfs with host swap disabled, removed on exit; issuer material is never
modified or placed in OpenBao. Only the leaf key/chain/public Root enter one
create-only KV-v2 leaf and one typed ESO TLS Secret in `platform-data`. A seventh
exact-leaf identity and a separate one-name TokenRequest Role leave the original
six bindings and all credentials/ACL unchanged. Reentry validates key/chain/name/
expiry and never reissues, adopts, overwrites or rotates material. Unknown or
deleted state stops with all data retained. Native evidence includes encrypted
issuer use, chain/hostname/usage/key/lifetime negatives, ESO delivery, old-identity
key-read denials, no-write reentry, initialized-volume preservation and log privacy.
This initial leaf is not continuous renewal, a Redis workload, application
permission or Production readiness; renewal/reload/expiry-fail-closed evidence
remains mandatory before opening traffic.

The bounded Kafka node bootstrap uses the same existing encrypted intermediate
and create-only OpenBao/ESO delivery, not a new CA or cert-manager authority.
One RSA-3072, 30-day leaf has exactly `CN=kafka-node-1`, loopback IP and the four
reviewed Kafka service/ordinal DNS names. Both serverAuth and clientAuth are
required because the combined node authenticates internal broker/controller
connections. This is one infrastructure-node identity, not an application client
credential or application ACL grant. One five-minute, exact-leaf identity and
one-name TokenRequest Role isolate its key from all existing application,
PostgreSQL and Redis identities. Initial delivery preserves the unclaimed
reserved Kafka volume, running Redis/PostgreSQL, existing CA, admission, mesh,
ESO and root custody. It never reissues on reentry or overwrites foreign/deleted
state. Native evidence must prove real ESO delivery, identity denials, unchanged
bound datastores, no-write reentry and log privacy. Kafka runtime installation,
renewal/reload, capacity, recovery and opening traffic remain separate gates.

### Existing deployment gates

For this unchanged upstream OpenBao import, `openbao-image.json/import_provenance`
binds the exact owned image digest to its reviewed original import revision
`6131ed82e22e4990efe33c9433d01d76a65d1265`. Native admission still verifies the
exact keyless signer/issuer and that exact signed payload revision; no wildcard,
unverified payload or multiple-revision fallback is allowed. A later authenticated
main publication/rescan receipt retains its own execution revision and must pass
the unchanged five-day publication/database freshness and vulnerability gates.
Refreshing scan evidence is not a claim that immutable upstream bytes were rebuilt.
This separation does not apply to rebuilt application artifacts or mesh imports.
Changing the image requires a separately reviewed import anchor and native tests.

- render staging and production desired state;
- run Helm/Kustomize/Kubernetes schema/policy validation;
- verify immutable image/chart digests and scan rendered output for secrets;
- run `istioctl analyze` where mesh resources are affected;
- verify OpenBao exact version/digest, Raft/PVC, Shamir configuration, snapshot/restore procedure, and recovery evidence;
- prove that production application hot paths do not depend on live OpenBao RPCs for routine cryptographic operations;
- verify the offline Root CA private key is absent from Kubernetes/OpenBao.

## Rollback Considerations

GitOps changes use reviewed Git revert when the resulting application/database state remains backward compatible. OpenBao rollback/recovery follows tested snapshot/restore and secret-rotation procedures; unsafe loss of newer secret/key state is not accepted merely to restore an older binary/configuration.

### Application PostgreSQL credential projection

The existing ten KV-v2 PostgreSQL runtime/migration credentials may be projected by
namespace-scoped ESO into `platform-apps` as separate Opaque configtree Secrets.
Each new `eso-app-pg-<secret-name>` account has one exact existing KV leaf, a
namespace/audience-bound five-minute role, TokenReview only, and no default policy.
Only the existing application ESO controller receives TokenRequest for these ten
exact names. Existing data-namespace deliveries and six application identities
remain unchanged; no KV writes, credential generation/rotation, database/migration,
workload, network permission, image or public traffic activation is part of this step.
Whole-set ownership/source preflight, scoped auth negatives, unchanged source
readback and no-write reentry in real signed ESO/OpenBao staging are required.
Migration credentials remain absent from runtime container mounts. Native evidence
is not target execution; target execution remains separately supervised and receipted.
