# ADR-0060: Define Production Human Just-in-Time Access v1

## Status

Accepted

## Date

2026-08-11

## Decision

Production human infrastructure access uses **Teleport Enterprise Self-Hosted**
as the privileged-access plane. The initial verified baseline is Teleport
18.10.x; the exact supported patch is pinned by the Technology Baseline/GitOps
before deployment and must be security-supported at release time.

Teleport is a human-access control plane and does not replace Istio workload
identity or application authorization.

### Standing privilege

Normal engineers have no standing production administrator, database-superuser,
root SSH, or unrestricted Kubernetes credentials.

Production write/admin access is just-in-time and requires:

- organization SSO;
- phishing-resistant MFA/WebAuthn for privileged sessions;
- explicit reason/ticket/incident reference;
- approval by at least two authorized reviewers for production administrator or
  database-write elevation;
- time-bounded role/session, maximum 30 minutes for privileged write access;
- automatic privilege removal at expiry.

Read-only production access is separately scoped and may use a maximum one-hour
session under an approved role policy.

### Access paths

- Kubernetes API human access goes through Teleport Kubernetes Access;
- production database human access goes through Teleport Database Access;
- direct SSH to production nodes is disabled for normal operations; when host
  access is unavoidable it uses Teleport-issued short-lived identity and
  recording;
- static kubeconfigs, shared SSH keys, shared database passwords, and permanent
  `cluster-admin` assignments are prohibited.

The Teleport access plane resides in a management failure domain rather than
being reachable only through the workload cluster it must help recover.

### Session and audit

Privileged SSH/Kubernetes/database sessions and access-request decisions are
audited; interactive sessions are recorded where the protocol supports it.
Audit/session storage is protected from ordinary requester modification.

### Break glass

A separately protected break-glass identity exists only for recovery from the
access plane itself. Its use requires two-person custody/approval where
operationally possible, short lifetime, immediate incident notification, and
post-use credential rotation/review.

## Verification Requirements

- SSO + MFA positive/negative tests;
- no standing production admin roles;
- two-reviewer JIT elevation and automatic expiry;
- direct SSH/static kubeconfig/shared DB credential denial;
- Kubernetes/database/SSH audit/session recording tests;
- Teleport access-plane outage and break-glass exercise;
- evidence that application workloads continue to use Istio/ServiceAccount
  identity rather than Teleport human credentials.

## Consequences

Developer/operator access now follows the same zero-standing-privilege principle
as service-to-service access. The platform accepts an additional privileged
access product because it removes long-lived SSH/kubeconfig/database credentials
and centralizes approval/audit/session evidence.
