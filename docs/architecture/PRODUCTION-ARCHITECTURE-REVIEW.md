# Production Architecture Review

## Outcome

The current v1 architecture is approved as the repository production target,
subject to the implementation/evidence gates in `PRODUCTION-READINESS-CHECKLIST.md`.

The review deliberately favors **high security and correctness without adding
new synchronous services or custom distributed coordination unless evidence
requires it**.

## Decisions closed by this review

- ADR-0041: semantic quotas on service-owned, ACL-isolated Redis Sentinel;
- ADR-0042: online Authorization SLO/HA/capacity and one-final-check rule;
- ADR-0043: Notification local AES-GCM key ring; no Transit hot path;
- ADR-0044: Kafka durability + reconstructable cold DR;
- ADR-0045: BFF/OIDC/PKCE/session/CSRF/CORS browser security;
- ADR-0046: Cosign/Kyverno signed-provenance admission enforcement;
- ADR-0047: remove bespoke Notification clock-agent/dispatch-fence runtime;
- ADR-0048: CloudNativePG synchronous HA + Barman WAL/PITR;
- ADR-0049: IPPanel Webservice-mode Iran SMS;
- ADR-0050: pinned platform compatibility set + Calico standard CNI;
- ADR-0051: three-node stacked Kubernetes control-plane/etcd + >=3 workers;
- ADR-0052: local RS256/RSA-3072 signing lifecycle + GitOps public verifier bundle.
- ADR-0053: dedicated PostgreSQL database/roles per persistent microservice;
- ADR-0054: dual-clock fail-closed semantic-quota time safety;
- ADR-0055: semantic circuit-breaker/bulkhead policy for synchronous dependencies;
- ADR-0056: Authorization overload isolation and realistic production latency SLO;
- ADR-0057: dedicated production PostgreSQL cluster per persistent service + forced tenant RLS;
- ADR-0058: cross-service irreversible data-subject erasure execution/evidence;
- ADR-0059: upstream L3/L4 volumetric DDoS mitigation before origin;
- ADR-0060: Teleport-backed JIT privileged human production access;
- ADR-0061: static/pipeline/canary/runtime PII-safe logging detection.

ADR-0006's pending password-hash input is also closed in the Technology Baseline
with Argon2id and explicit password-policy/bulkhead rules.

## Main bottlenecks

The current highest-risk capacity boundaries are:

1. online Authorization + its PostgreSQL query path;
2. per-service PostgreSQL HA fleet capacity, synchronous-write latency, backup/restore load, and operational overhead;
3. security Redis latency/failover for quotas and BFF sessions;
4. password-hash CPU/memory under login attack/load;
5. WAF inspection on every public request;
6. Kafka broker/disk/partition footprint when production async flows are enabled;
7. IPPanel receipt polling/provider throttling and Liara provider latency;
8. worker-node capacity/replica placement during one-node loss.

The exact metrics, mitigations, and scale/split triggers are maintained in
`performance-and-bottlenecks.md`.

## Deliberate non-bottleneck choices

- BFF does not duplicate routine Authorization checks performed by resource services.
- No quota microservice exists.
- No runtime Schema Registry exists in v1.
- Notification does not call OpenBao Transit per message.
- Notification does not run the former Chrony sidecar/gRPC/fence/coordinator stack.
- Normal JWT verification is local; no per-request remote JWKS lookup exists.
- PgBouncer and Redis Cluster are not introduced without measured need.
- External etcd is not used in v1; stacked etcd provides HA with lower footprint.
- Argo CD and OpenBao are not made HA merely for symmetry because they are not
  normal request-path dependencies in the reviewed v1 design.

## Delivery-speed guardrail

Local development remains intentionally smaller than production. Domain and
application work must not require a full Kubernetes/Istio/OpenBao/WAF/Kafka HA
stack. Heavy failover, PITR, chaos, provider, and DR validation runs in staging,
release, or scheduled pipelines according to the testing architecture.

## 2026-08-11 operational-hardening follow-up

The follow-up review accepts ADR-0062 through ADR-0065:

- Authorization keeps its existing objective but uses paired multi-window burn instead of isolated-percentile paging; breaker recovery probes the real contract, not a health endpoint.
- Dependency criticality is documented per operation edge rather than globally labelling whole services critical/degradable.
- Dedicated service PostgreSQL clusters remain the production isolation model; fleet automation, not reconsolidation, is the operational response.
- SBOM is continuously operationalized through digest-indexed rescanning, automatic service/security routing, deployment gates, and remediation SLAs.

These changes add no new application request-path network hop.


## Follow-up SRE review — ADR-0066 through ADR-0068

The latest review accepts the need for stronger dependency-matrix governance, restore evidence, and vulnerability escalation, but rejects two unsafe simplifications:

- Authorization breaker recovery is not varied by tenant/commercial tier. Repeated recovery is de-correlated per caller breaker and half-open probes are serialized.
- PostgreSQL upgrades do not promise a universal automatic rollback within five minutes. Reversible GitOps/operator changes may revert, while irreversible/major database transitions use tested fail-forward/restore/migration strategies.

The dependency matrix is now machine-readable and CI-enforced; monthly restore drills produce queryable RPO/RTO/integrity evidence; vulnerability exceptions have active expiry escalation; KEV/advisory inputs trigger targeted rescans without claiming guaranteed zero-day discovery.


## Coding-quality hardening — ADR-0069

The current architecture now separates coding policy from implementation evidence. `docs/engineering/coding-standards.md` is the canonical Java coding standard and `docs/engineering/build-and-ci-quality-enforcement.md` defines the executable Gradle/Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions baseline. Until actual Java service source and required CI workflows exist and pass, code-compliance evidence remains `NOT VERIFIED`; this is an implementation gate, not an open architecture decision.
