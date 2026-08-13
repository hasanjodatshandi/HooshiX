# ADR-0051: Define Self-Hosted Kubernetes HA Topology v1

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR closes the production Kubernetes control-plane and worker-failure-domain
availability gap. It does not change ADR-0050's Kubernetes 1.35.6, Calico OSS
3.32.1, upstream Istio Ambient, Argo CD, or other platform selections.

ADR-0027 cold disaster recovery remains the disaster/site-loss model. This ADR
covers ordinary node/control-plane failure inside the active production cluster.

## Decision

### Control plane

Production uses a kubeadm-compatible highly available **stacked control-plane +
etcd** topology:

- exactly 3 control-plane nodes initially;
- one local etcd member on each control-plane node;
- control-plane/etcd members placed on distinct physical failure domains when
  the infrastructure provides them;
- a stable redundant L4 `controlPlaneEndpoint` fronts the API servers;
- no single API-server/control-plane node may be a required management endpoint;
- control-plane nodes are reserved for control-plane/system duties and do not
  run normal application workloads.

External etcd is not selected in v1. It would require another three dedicated
hosts without providing enough benefit for the current scale. A later ADR may
separate etcd if measured control-plane resource isolation or compliance needs
justify the additional footprint.

The concrete redundant L4 implementation is an environment/network deployment
input. It must not be a single process/host whose loss makes every API server
unreachable.

### Worker topology

Production starts with at least **3 schedulable worker nodes**.

Replicated request-path workloads, PostgreSQL instances, Kafka brokers/
controllers, Redis replicas/Sentinels, WAF, Authorization replicas, Kyverno, and
other HA workloads use topology spread/anti-affinity so that the loss of one
worker does not intentionally place every replica of one availability boundary
on the failed node.

A workload may run fewer replicas only where an accepted ADR explicitly permits
the availability trade-off, such as the current single-node OpenBao or non-HA
Argo CD v1 decisions.

### Cluster state and recovery

Git remains the desired-state source of truth for managed resources. Kubernetes
etcd snapshots are taken encrypted on a regular automated schedule and before
control-plane upgrades. They are operational recovery artifacts, not a
replacement for GitOps or service-owned database backups.

At minimum:

- etcd snapshot every 6 hours;
- snapshot before every Kubernetes control-plane upgrade;
- encrypted off-node retention for 7 days;
- monthly isolated control-plane restore/rebuild exercise or equivalent tested
  automation;
- expired bootstrap/join credentials and certificates are never treated as the
  recovery mechanism.

Full site disaster recovery may rebuild a clean cluster from automation/GitOps
rather than restoring etcd when that is safer and faster. Application business
data recovery continues to follow PostgreSQL/OpenBao/Kafka DR decisions.

### Capacity and failure-domain rule

Production may not claim HA merely because a workload has replica count >1.
Release evidence must prove replica placement across actual schedulable nodes
and must test one-node loss.

The platform reserves enough worker CPU/memory/storage headroom that one worker
can be unavailable without immediately violating the critical request-path
capacity plan. Initial capacity planning targets N+1 worker capacity for Class A
and Authorization paths.

## Verification Requirements

Before production traffic:

- all 3 control-plane nodes and etcd members are healthy;
- API endpoint remains usable after one control-plane node loss;
- etcd quorum remains healthy after one control-plane node loss;
- at least 3 schedulable workers are present;
- critical replica placement is verified across workers/failure domains;
- one-worker drain/failure preserves required request-path availability;
- CloudNativePG, Authorization, WAF, Redis, Kyverno, and applicable Kafka
  disruption tests pass under one-node loss;
- etcd snapshot creation, encryption, off-node storage, and restore procedure
  are tested;
- NetworkPolicy/Istio/Calico behavior remains correct after rescheduling.

## Consequences

The active production cluster no longer has a hidden single-node Kubernetes
control-plane dependency. Stacked etcd provides the best v1 balance between HA
and infrastructure footprint. The topology adds three dedicated control-plane
nodes and at least three workers, but avoids the extra three hosts required by
external etcd.

## Rollback or Migration Considerations

A rollback must not collapse production to a single control-plane node while
claiming the same HA/SLO posture. Control-plane upgrades are one node at a time
with quorum preserved. Changing to external etcd, fewer control-plane nodes, or
converged application/control-plane scheduling requires explicit review and, if
it changes the production availability model, a superseding ADR.
