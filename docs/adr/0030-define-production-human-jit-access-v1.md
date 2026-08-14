# ADR-0030: Production Human Just-in-Time Access v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-14

## Decision

Production human infrastructure access preserves one invariant across deployment profiles: **zero standing privileged access with phishing-resistant authentication, time-bounded elevation, approval, durable audit, and protected break glass**.

Teleport is not workload identity and never replaces Istio ServiceAccount identity or application authorization.

### Standing privilege

Normal engineers have no standing production administrator, database-superuser, root SSH, or unrestricted Kubernetes credentials.

Production write/admin access requires:

- attributable per-human identity;
- phishing-resistant hardware-backed authentication;
- explicit reason/ticket/incident reference;
- approval by at least two authorized reviewers for production administrator or database-write elevation;
- time-bounded role/session, maximum 30 minutes for privileged write access;
- automatic privilege removal at expiry.

Read-only production access is separately scoped and may use a maximum one-hour session under an approved role policy.

### `production-single-server`: hardened OpenSSH + FIDO2

ADR-0042 does not deploy Teleport in the selected single-server profile. Human host access uses the supported host OpenSSH package, hardened and managed as code.

Mandatory authentication/path rules:

- SSH is reachable only through the approved management network/path; no general public SSH exposure;
- `PermitRootLogin no` equivalent behavior;
- password authentication is disabled;
- shared accounts/shared SSH keys are prohibited;
- privileged human authentication uses hardware-backed OpenSSH FIDO2 security-key algorithms and requires user presence plus user verification;
- ordinary non-hardware keys MUST NOT satisfy privileged production access;
- static shared kubeconfigs, shared database passwords, and permanent `cluster-admin` assignments are prohibited.

JIT elevation is separate from authentication. A successful FIDO2 SSH login does not itself grant root/Kubernetes/database write authority. Approved automation grants the minimum required `sudo`/Kubernetes/database privilege for the approved scope and expires it automatically at the defined deadline. Permanent manual `sudoers`, group, kubeconfig, or database-role edits are not an acceptable substitute for expiry automation.

### Single-server session and audit

`.bashrc`, shell history, `PROMPT_COMMAND`, or other user-controlled shell logging is not an authoritative audit mechanism and MUST NOT be used to claim session recording.

The single-server access path requires:

- protected SSH authentication logs;
- OS audit (`auditd` or reviewed equivalent) for authentication, process execution, privilege changes, and security-relevant configuration changes;
- `sudo` I/O/session logging for privileged interactive activity where protocol/tooling supports it;
- Kubernetes/database audit evidence for privileged operations at those boundaries;
- off-host append-only/tamper-resistant retention of required access/audit records outside ordinary requester modification rights;
- alerting for privileged-login, JIT-grant, break-glass, audit-pipeline failure, and unexpected root/administrator activity.

An audit export failure does not authorize disabling local protected audit or continuing privileged work indefinitely. Required audit evidence has explicit retention and incident handling.

### `production-ha`: Teleport

When the HA profile is selected, production human infrastructure access uses Teleport Enterprise Self-Hosted on the approved Technology Baseline line.

- Kubernetes API human access uses Teleport Kubernetes Access;
- production database human access uses Teleport Database Access;
- unavoidable host access uses Teleport-issued short-lived identity and recording;
- the Teleport management plane is not reachable only through the workload cluster it must help recover;
- SSO + phishing-resistant MFA/WebAuthn, JIT approvals, time bounds, and audit/session recording remain mandatory.

### Break glass

Both profiles maintain a separately protected hardware-backed break-glass identity only for recovery from the normal access path.

Its use requires two-person custody/approval where operationally possible, short lifetime, immediate incident notification, protected audit, and post-use credential rotation/review. Break glass is not an ordinary administration path.

## Verification requirements

Both profiles verify no standing production admin roles, two-reviewer elevation, automatic expiry, denial of static/shared privileged credentials, protected audit evidence, and proof that application workloads continue to use Istio/ServiceAccount identity rather than human credentials.

`production-single-server` additionally verifies management-path-only SSH, password/root/shared-key denial, OpenSSH FIDO2 user-presence + user-verification positive/negative cases, automatic JIT privilege expiry, `sudo` I/O/session audit, OS audit coverage, off-host audit integrity/access restrictions, audit-pipeline failure behavior, and break-glass exercise. Shell-history logging MUST NOT satisfy any audit test.

`production-ha` additionally verifies Teleport SSO/MFA, Kubernetes/database/SSH access, session recording, management-plane outage, and break-glass behavior.

## Rollback considerations

Rollback MUST preserve zero standing production privilege, phishing-resistant privileged authentication, bounded elevation, two-reviewer approval, durable protected audit evidence, and denial of static/shared privileged credentials. It MUST NOT replace real audit with shell history, enable password/root/shared-key production SSH, replace workload identity with human credentials, or make break-glass access an ordinary administration path.
