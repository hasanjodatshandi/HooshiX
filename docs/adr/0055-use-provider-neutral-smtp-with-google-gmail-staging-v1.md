# ADR-0055: Provider-Neutral SMTP with Google Gmail Staging v1

## Status

Accepted — current effective decision

## Date

2026-09-09

## Decision

### Provider boundary

Notification keeps one provider-neutral Application outbound port per channel. Email transport is
implemented by one authenticated SMTP + required STARTTLS Infrastructure adapter. Provider choice,
credentials, sender identity, endpoint, and egress remain typed server-owned configuration; callers
cannot select or override them.

The supported Email configuration profiles are:

```text
GOOGLE_GMAIL  -> temporary non-production/staging Gmail SMTP profile
GENERIC_SMTP  -> reviewed authenticated STARTTLS SMTP provider profile
```

`GENERIC_SMTP` is an adapter capability, not automatic approval of an arbitrary provider. A provider
change still requires reviewed configuration, destination/egress, credentials, sender-domain policy,
failure fixtures, and environment evidence. Production Email provider selection is deferred to
Production Commissioning; no free personal Gmail account is presented as a production delivery
service.

ADR-0010 remains authoritative for versioned database templates and exact rendered content. Its
former Liara provider selection is superseded by this ADR. ADR-0006/0007 delivery lifecycle,
ambiguity, retry, reconciliation, evidence, escrow, and telemetry semantics remain unchanged.

### Google Gmail staging profile

The owner-selected current staging profile uses:

```text
host:       smtp.gmail.com
port:       587
transport:  SMTP with required STARTTLS
auth:       dedicated Google App Password for a Gmail test account
from:       exactly the authenticated Gmail mailbox
reply-to:   omitted
```

The Gmail host and port are fixed in code for this profile. Configuration cannot substitute another
endpoint or sender. The Google account MUST have two-step verification enabled before a dedicated App
Password is created. The App Password is temporary staging credential material and is revoked when no
longer required. It is never a Google OIDC client secret and never grants HooshiX users the ability to
sign in with Google.

Google OIDC remains the independent browser authentication path owned by Web BFF under ADR-0016.
Gmail SMTP sends Notification Email; OIDC proves an end-user Google identity. Their credentials,
scopes, tokens, runtime edges, and evidence are purpose-separated.

Personal Gmail sending limits and anti-abuse controls make this profile suitable only for bounded
owner-approved staging recipients. Staging tests use a small allow-list and never bulk-send. Quota or
provider throttling is an external provider failure, not authority to bypass Notification lifecycle or
switch providers automatically.

### Generic authenticated SMTP profile

`GENERIC_SMTP` requires an explicit reviewed DNS host, port, username, password, From mailbox, and
display name. It always uses authentication, required STARTTLS, server-certificate hostname
verification, TLS 1.2 or newer, a 500 ms connection timeout, 1,500 ms read/write timeouts, and no
transport retry. It cannot disable TLS/authentication or use an IP-literal/localhost endpoint through
provider configuration.

Configuration parsing rejects missing, duplicate, unknown, legacy Liara, and profile-incompatible
keys. Secret values are read from one mounted secret file; they are not command-line arguments,
ordinary environment variables, Git values, logs, traces, metrics, or evidence receipts.

### Outcome and reconciliation semantics

A successful SMTP send completion after the server's final success response is
`DEFINITIVE_ACCEPTED` and moves the Notification only to `PROVIDER_ACCEPTED`. It never proves mailbox
delivery or human open/read. Authentication rejection is definitive permanent failure. Transport
timeout, connection loss, or an otherwise uncertain result is `AMBIGUOUS` and is never blindly
resubmitted.

SMTP alone supplies no authenticated recipient-level delivery receipt. Reconciliation therefore
remains inconclusive and closes as `DELIVERY_STATUS_UNKNOWN` after the existing Email observation
window unless a future reviewed provider supplies authenticated, attempt-correlated evidence. Subject,
recipient, or timestamp correlation is prohibited.

### SMS scope

This decision does not replace SMS. ADR-0056 owns the current SMS.ir selection for Iran. Gmail
provides no SMS capability, and lack of a production SMS.ir credential/sender cannot activate the
local logging adapter in staging or production. The SMS.ir Sandbox proves only a simulated contract;
phone registration remains server-gated until real production-provider evidence exists. Email
registration may be evaluated independently under ADR-0009.

## Verification requirements

Required repository and staging evidence includes:

- generic and Google profile configuration parsing, exact-key and duplicate-key rejection;
- fixed Gmail endpoint/port/sender and invalid endpoint/sender/App Password negatives;
- required STARTTLS/authentication, certificate-hostname verification, TLS-version floor, and finite
  timeout configuration;
- definitive SMTP acceptance, authentication rejection, transport ambiguity, and no fabricated
  delivery evidence;
- mounted provider secret and delivery-enabled Helm render, restricted public-IP SMTP/SMS egress,
  and reviewed Istio ServiceEntry destinations;
- real owner-authorized Gmail test-recipient execution without secret/recipient leakage;
- Gmail quota/throttling and credential revocation behavior recorded without blind resend or fallback;
- proof that Gmail/OIDC secrets and runtime paths remain purpose-separated;
- unchanged SMS.ir/local-adapter staging and production safety gates.

## Rollback considerations

Rollback cannot restore Liara-specific configuration as current authority, weaken STARTTLS or hostname
verification, expose a credential, fabricate delivery, retry an ambiguous send, or activate the local
logging adapter in staging/production. A rollback may disable delivery runtime and revoke the Gmail App
Password. A future provider change uses the existing generic boundary only after its own reviewed
configuration, egress, sender, failure, and delivery-evidence semantics pass.
