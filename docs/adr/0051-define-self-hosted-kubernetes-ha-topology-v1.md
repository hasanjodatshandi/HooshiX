# ADR-0051: Self-Hosted Kubernetes HA Topology v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Control plane

Production uses a kubeadm-compatible highly available stacked control-plane + etcd topology:

- exactly three control-plane nodes initially;
- one local etcd member per control-plane node;
- independent physical failure domains where available;
- one stable redundant L4 `controlPlaneEndpoint` in front of all API servers;
- loss of one API-server/control-plane node MUST NOT remove cluster management access;
- normal application workloads do not schedule on control-plane nodes.

External etcd is not part of v1. Introducing it requires measured isolation/compliance/availability evidence that justifies the additional hosts/operations.

### Worker topology

Production has at least three schedulable workers. Critical replicated workloads use topology spread/anti-affinity so one worker loss does not intentionally remove the whole availability boundary.

The platform keeps N+1 worker CPU/memory/storage headroom for Class-A and Authorization projected critical-path capacity. Replica count alone is not HA evidence; placement and one-node-loss behavior must be proven.

### Cluster-state recovery

Git is the managed desired-state source of truth. Kubernetes etcd snapshots are encrypted operational recovery artifacts:

- snapshot every six hours;
- snapshot before control-plane upgrades;
- encrypted off-node retention at least seven days;
- monthly isolated control-plane restore/rebuild exercise or equivalent tested automation.

Clean-cluster GitOps rebuild may be preferred for site loss when safer/faster. Application business data recovery follows service-owned datastore/secret/event recovery policy.

## Verification requirements

Before production traffic verify all three control-plane/etcd members, API endpoint continuity through one control-plane loss, etcd quorum through one member loss, >=3 workers, critical replica placement, one-worker drain/loss with required capacity, CloudNativePG/Authorization/WAF/Redis/Kyverno/Kafka disruption behavior where applicable, encrypted snapshot creation/restore, and Calico/Istio policy behavior after rescheduling.

## Rollback considerations

Rollback MUST NOT collapse production to a single control-plane node while claiming the same HA posture, destroy quorum, co-locate all critical replicas on one worker, or reduce N+1 critical capacity without a revised current availability decision.