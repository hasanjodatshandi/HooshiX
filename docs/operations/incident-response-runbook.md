# Production Incident Response Runbook

## Purpose

This runbook defines the minimum production incident workflow. Service-specific
runbooks may add detail but must not weaken these rules.

## Severity

- `SEV-1` — security compromise, widespread outage, known-exploited/Critical
  vulnerability with credible production exposure, confirmed cross-tenant data
  exposure, or material data-integrity risk.
- `SEV-2` — major degraded production capability, sustained SLO burn, failed
  HA/failover, High vulnerability requiring urgent remediation, or isolated
  security-control failure without confirmed compromise.
- `SEV-3` — bounded degradation with workaround and no immediate security/data
  integrity risk.

## Roles

Every SEV-1/SEV-2 assigns:

- Incident Commander;
- Technical Lead;
- Communications owner;
- Security Lead when security/privacy/supply-chain scope exists;
- Scribe/evidence owner.

The same person may hold multiple roles only when team size requires it.

## First actions

1. Declare severity and start the incident record.
2. Preserve logs/audit/evidence; do not expose secrets/PII in the incident
   channel.
3. Stop risky releases and automation that can amplify impact.
4. Identify affected services, tenants/environments, image digests, data stores,
   and dependency edges.
5. Apply the safest bounded containment that preserves data integrity.
6. Verify containment through production telemetry and contract-level checks.
7. Communicate impact and next decision point.

## Security / vulnerability branch

For ADR-0065/ADR-0068 findings:

- correlate the advisory/finding to deployed image digests and environments;
- determine exploitability and CISA KEV/known-exploited status;
- stop promotion of affected artifacts;
- do not automatically kill healthy production pods merely because a finding
  exists;
- build, scan, sign, attest, and promote a replacement artifact when patching;
- use compensating controls only with explicit owner/expiry/approval.

Expired Critical/KEV exceptions in production are SEV-1 unless Security records
why exposure is not credible. Expired High exceptions are at least SEV-2 until
triaged.

## Database / recovery branch

For PostgreSQL incidents:

- preserve the dedicated service-cluster boundary;
- stop unsafe upgrade waves;
- distinguish reversible GitOps/operator state from irreversible database state;
- do not perform an unsupported binary/data downgrade;
- use the ADR-0067 restore evidence/runbook when restoration is required;
- replay erasure/legal-hold requirements before re-enabling traffic where
  applicable.

## Resolution

Before resolving SEV-1/SEV-2:

- user/security impact is bounded and verified;
- temporary mitigations have explicit owner and expiry;
- affected SLO/error-budget state is recorded;
- follow-up actions have owners and due dates;
- any architecture deviation is represented by an ADR rather than left as an
  undocumented incident workaround.

## Post-incident review

SEV-1 and material SEV-2 incidents require a blameless post-incident review with
root cause, detection gap, containment effectiveness, recovery evidence,
architecture/test/runbook improvements, and action-item closure tracking.
