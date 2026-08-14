# ADR-0043: Production Network Trust Boundaries v1

## Status

Accepted — current effective decision

## Date

2026-08-15

## Decision

Production network identity is explicit. Caller-controlled forwarding headers are never security authority.

This ADR defines:

1. public client-address authority from external L4 through Traefik and WAF to Web BFF;
2. exact client-address context supplied to quota-owning services;
3. privileged host-management reachability for `production-single-server`.

It does not change application authentication, MFA, Authorization, tenant isolation, OpenBao, or workload identity.

## 1. Public client-address authority

Production public path:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> external L4
-> Traefik
-> Caddy + Coraza WAF
-> Web BFF
```

External L4 is source-address authority. It MUST preserve the validated original client source to Traefik using PROXY protocol v2. A provider that cannot supply an equivalent authenticated source-preservation path is not production-eligible without a revised decision.

Traefik requirements:

- PROXY protocol trusted only from exact reviewed external-L4 source CIDRs;
- `proxyProtocol.insecure` prohibited;
- `forwardedHeaders.insecure` prohibited;
- caller `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-HooshiX-Client-IP` never become client identity;
- downstream forwarding state is reconstructed from validated connection/client address;
- application entrypoint accepts public application traffic only from approved external-L4 source ranges;
- direct non-L4 Internet access is denied by provider/origin firewall, security group, routing, or an equivalently effective network control.

A deployment that cannot restrict the Traefik origin to the approved external-L4 path is not production-eligible. Header checks are not a substitute for that network restriction.

## 2. WAF-to-BFF contract

Only approved Traefik workload may reach Caddy/Coraza application ingress. Only approved Caddy/Coraza workload may reach Web BFF. NetworkPolicy and Istio authorization enforce these paths independently of header processing.

Caddy requirements:

- trusted proxies limited to the exact Traefik source path;
- strict trusted-proxy parsing selects the nearest untrusted address from the trusted chain;
- use only the reviewed Traefik-produced client-IP source, normally `X-Forwarded-For`;
- never trust arbitrary public `Forwarded`, `X-Real-IP`, or private client-IP headers;
- overwrite internal `X-HooshiX-Client-IP` with the server-derived client IP;
- never pass through caller-supplied `X-HooshiX-Client-IP`.

Web BFF requirements:

- accept network identity only from internal `X-HooshiX-Client-IP` on the WAF-only ingress path;
- never derive authority from browser `Forwarded`, `X-Forwarded-*`, or `X-Real-IP`;
- parse exactly one IP literal; reject hostnames, ports, CIDR notation, zone IDs, lists, and malformed values;
- normalize IPv4-mapped IPv6 to IPv4;
- represent IPv4/IPv6 as canonical binary values;
- treat an external-L4/Traefik/WAF/configured-proxy address as `NETWORK_IDENTITY_UNAVAILABLE` for a public operation requiring network security quota;
- never log/persist raw client IP as ordinary telemetry.

If trusted client address is missing, malformed, or resolves to a proxy address, a public operation requiring network quota fails closed. It never falls back to caller header, random ID, or shared proxy address.

## 3. Downstream quota context

When a backend service owns a public-operation network quota, Web BFF forwards only a server-created typed **exact client-address context** over the authenticated internal call.

The context:

- is created only after WAF-only trusted-address validation;
- carries one exact canonical binary IPv4 or IPv6 address plus explicit family;
- is never a browser/public API field;
- is accepted only from approved BFF workload for the applicable operation;
- is not logged, persisted, put in Kafka, or used as a metric label;
- does **not** pre-collapse the address to `/24` or `/64` in BFF.

ADR-0024 is authoritative for quota derivation inside the owning service:

```text
exact hard-gate identity:
  IPv4 /32
  IPv6 /128

aggregate pressure identity:
  IPv4 /24
  IPv6 /64
```

The exact and aggregate pseudonyms are domain-separated. Aggregate prefix is not the sole v1 hard quota-denial identity.

Missing/invalid trusted context on an operation requiring a network quota is an availability/security-dependency failure and fails closed.

## 4. Negative tests for public identity

Production verification includes at least:

- forged `X-Forwarded-For`, `Forwarded`, `X-Real-IP`, and `X-HooshiX-Client-IP` do not become authority;
- direct Internet/non-approved-source Traefik origin access denied before application routing;
- PROXY protocol from untrusted source not trusted;
- missing/misconfigured external-L4 source preservation detected and quota-required operation does not use L4/Traefik/WAF address as client;
- IPv4, IPv6, IPv4-mapped IPv6, malformed, multi-value, and proxy-address cases produce canonical/fail-closed result;
- BFF-to-service exact network context accepted only from approved BFF workload and cannot be public input;
- service derives exact `/32`/`/128` hard identity and separate `/24`/`/64` aggregate pressure identity under ADR-0024;
- NAT/campus/VPN-style clients do not share one aggregate-prefix-only hard budget;
- raw client address does not appear in normal logs, metrics, traces, Redis keys, Kafka payloads, or durable business state.

## 5. `production-single-server` management network

Normal production SSH is not reachable on the public application interface.

Selected path:

```text
approved operator device
-> authenticated WireGuard peer
-> host management address
-> OpenSSH TCP/22
-> FIDO2 human authentication
-> separate JIT privilege elevation
```

WireGuard is network admission only. It is not human identity and grants no root/Kubernetes/database privilege.

Mandatory controls:

- dedicated management address on WireGuard interface;
- TCP/22 accepted only on management interface/address and denied on public interface by host plus provider firewall/security-group control where available;
- public application edge cannot route to SSH listener;
- independent per-device WireGuard peer keys; shared peer keys prohibited;
- minimal `AllowedIPs`; no broad workload/cluster reachability by default;
- attributable peer enrollment/ownership/review/revocation;
- lost/retired device key revoked independently from FIDO2 credential;
- WireGuard possession never bypasses OpenSSH FIDO2 presence/verification or JIT privilege;
- exact host WireGuard package/kernel/config pinned in provisioning before production;
- configuration reviewed as code and included in host recovery evidence;
- normal management does not depend on a workload-cluster component operators may need to recover.

Provider emergency console, if available, is break-glass only and separately protected/audited/incident-linked.

## 6. Management verification

Before production prove:

- Internet/public-interface TCP/22 unreachable;
- only approved WireGuard peers reach management address;
- revoked/unapproved peers denied;
- SSH still rejects root/password/keyboard-interactive/shared/non-FIDO credentials;
- valid WireGuard peer without FIDO2 gains no SSH session;
- valid FIDO2 session without approved JIT gains no privileged write authority;
- effective host/provider firewall state matches management-only rule;
- management overlay can be restored without private keys in Git;
- provider-console break glass does not silently bypass incident/audit policy.

## 7. Failure behavior

Network trust controls fail safe:

- client-IP resolution failure does not disable network quotas;
- proxy/header parsing failure does not accept caller headers;
- aggregate-prefix pressure does not replace exact trusted-address identity;
- management-overlay failure does not enable public SSH;
- incidents do not justify insecure forwarded/PROXY trust, public SSH, shared WireGuard keys, password SSH, or WAF/BFF bypass.

## Verification requirements

Production Readiness, security verification, edge tests, quota tests, and threat model MUST cover this ADR. Exact deployment CIDRs, peer inventory, firewall rules, provider L4 settings, and executed negative tests remain `NOT VERIFIED` until implemented.

## Rollback considerations

Rollback MUST NOT restore ambiguous client-IP authority, trust caller forwarding headers, collapse all hard network quotas back to aggregate `/24`/`/64`, use untrusted proxy chains, expose public SSH, share management peer keys, or substitute network reachability for FIDO2/JIT privilege.