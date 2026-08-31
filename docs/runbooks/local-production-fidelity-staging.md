# Local Production-Fidelity Staging Lane

## Purpose

This runbook operates the repository-owned local production-fidelity integration lane in the canonical WSL checkout `/home/coder/workspace/Hooshix`. It verifies Kubernetes, Calico, Istio Ambient, Kyverno admission, Traefik/WAF, local staging PostgreSQL/Redis, all five current application services, and the local observability stack together.

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

`production-fidelity-up` stops the fast host-JVM lane before creating the cluster, verifies the required inotify limits, creates the pinned kind/Calico/Gateway API foundation and local registry, builds the pinned WAF image, installs Istio Ambient and Kyverno, installs the edge, creates the generated Compromised Password staging fixture, installs local staging PostgreSQL/Redis, installs observability, builds the five application images, deploys the five Helm releases, and runs the composite verifier.

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
otelcol-contrib 0.157.0
Prometheus 3.13.2
Loki 3.7.4
Tempo 3.0.2
Grafana 13.1.3
Alertmanager 0.33.1
```

Application images are built from the current source with Eclipse Temurin 25.0.4 and pushed to the loopback local registry. Deployments use the exact recorded image digest.
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
- Staging credentials, TLS/key material, generated image state, and verification logs remain under Git-ignored `.platform-runtime/` or Kubernetes Secrets created from local generated state. They are not production secrets.
- Compromised Password uses a deterministic `GENERATED_TEST_FIXTURE`, not the production HIBP corpus. The exact generated manifest SHA-256 is bound into the deployed service at runtime and verified against the mounted manifest.

## Verification

The composite command is:

```bash
make production-fidelity-verify
```

It verifies at least:

- three-node kind/Kubernetes readiness, Calico digests, Gateway API CRDs, and inotify prerequisites;
- Istio control plane/CNI/ztunnel and non-blocking `istioctl analyze`;
- Kyverno CEL policy positives/negatives;
- PostgreSQL/Redis images, runtime policy, roles, and the complete runtime-role/database `CONNECT` isolation matrix;
- all five application Helm releases, exact image digests, ServiceAccounts, waypoint readiness, Flyway counts, browser bootstrap, and BFF-to-Identity non-enumerating negative authentication;
- public Traefik -> WAF -> BFF traversal, direct-bypass denial, STRICT mTLS, workload-identity positives/negatives, and edge secret-canary log absence;
- Prometheus targets for all five services and all Collector instances;
- Collector -> Tempo trace canary;
- Collector -> Loki safe-log canary and privacy-canary rejection;
- Grafana hardened datasource health;
- application readiness and public bootstrap while Tempo and Loki are independently unavailable;
- no remaining non-ready platform pods after verification cleanup.

Info-level `istioctl analyze` diagnostics are reported but are not equivalent to blocking configuration errors. The verifier fails on blocking analysis findings.

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
- Kafka runtime/replay;
- OpenBao/External Secrets production delivery;
- Argo CD production reconciliation;
- WireGuard/FIDO2/JIT host access;
- production HIBP corpus provenance/freshness/full-corpus bounds;
- Liara/IPPanel provider delivery;
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
