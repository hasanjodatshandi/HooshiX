# ADR-0022: Self-Hosted Kubernetes Topology Profiles v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

Production has two explicit infrastructure profiles. `production-single-server` is the selected initial profile under ADR-0042. `production-ha` is the expansion profile for availability/capacity requirements.

Profile selection changes availability topology only. Calico NetworkPolicy, Istio workload identity/mTLS, signed-artifact admission, workload ServiceAccounts, security contexts, GitOps, backup/recovery, and application/domain isolation do not become optional.

### `production-single-server`

The selected initial profile uses one K3s server that is also the only schedulable workload node.

- Kubernetes line: 1.35.6 through pinned `v1.35.6+k3s1`;
- one control-plane/scheduler/workload failure domain;
- embedded K3s SQLite control-plane datastore;
- K3s secrets encryption enabled;
- Flannel and the K3s network-policy controller disabled so Calico remains the authoritative CNI/NetworkPolicy implementation;
- bundled K3s Traefik and ServiceLB disabled so the repository-pinned edge stack remains authoritative;
- K3s datastore directory plus server token backed up encrypted off-host;
- normal application/service replica count is one; HPA and availability PDBs are disabled by this profile unless a later measured revision says otherwise.

This profile is deliberately non-HA. Loss or maintenance of the server can remove the Kubernetes API and all workloads. Multiple pods on the same host do not create physical availability.

Git remains desired-state authority. K3s datastore backup is an operational recovery artifact, not business-data backup.

### `production-ha`

When the HA profile is selected, production uses a kubeadm-compatible highly available stacked control-plane + etcd topology:

- exactly three control-plane nodes initially;
- one local etcd member per control-plane node;
- independent physical failure domains where available;
- one stable redundant L4 `controlPlaneEndpoint` in front of all API servers;
- loss of one API-server/control-plane node MUST NOT remove cluster management access;
- normal application workloads do not schedule on control-plane nodes;
- at least three schedulable workers;
- critical replicated workloads use topology spread/anti-affinity;
- N+1 worker CPU/memory/storage headroom for critical request paths.

External etcd is not a default. Introducing it requires measured isolation/compliance/availability evidence that justifies additional hosts/operations.

HA cluster-state recovery uses encrypted etcd snapshots every six hours, before control-plane upgrades, with at least seven days off-node retention and monthly isolated control-plane restore/rebuild evidence.

### Shared recovery rules

For both profiles:

- clean-cluster GitOps rebuild may be preferred for site/host loss when safer/faster;
- application business data recovery follows service-owned datastore/secret/event policy;
- control-plane recovery artifacts and tokens are protected from ordinary application identities;
- production recovery is not considered proven until an isolated rebuild/restore exercise succeeds.

## Verification requirements

For `production-single-server`, verify pinned K3s/Kubernetes identity, Calico custom-CNI configuration, bundled Flannel/network-policy/Traefik/ServiceLB disablement, secrets encryption, encrypted off-host K3s DB+token recovery artifact, clean GitOps rebuild, one-replica/HPA/PDB render, Calico/Istio policy behavior after reboot, and explicit operator acceptance of whole-platform downtime on node loss.

For `production-ha`, verify all three control-plane/etcd members, API endpoint continuity through one control-plane loss, etcd quorum through one member loss, >=3 workers, critical replica placement, one-worker drain/loss with required capacity, applicable datastore/security-component disruption behavior, encrypted snapshot creation/restore, and Calico/Istio policy behavior after rescheduling.

## Rollback considerations

Moving from `production-single-server` to `production-ha` is a topology expansion. It MUST preserve GitOps state, security controls, workload identities, service data ownership, and recovery evidence.

Moving from `production-ha` to `production-single-server` requires explicit acceptance of whole-platform downtime and single-node failure risk. It MUST NOT be represented as preserving the HA posture or used to justify disabling Calico, Istio security, Kyverno admission, OpenBao, off-site recovery, or application data-isolation controls.
