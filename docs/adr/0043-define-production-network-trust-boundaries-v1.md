# ADR-0043: Production Network Trust Boundaries v1

## Status

Accepted — current effective decision

## Date

2026-08-15

## Decision

Production network identity is explicit. Caller-controlled forwarding headers are never security authority.

This ADR defines two boundaries:

1. public client-address authority from the external L4 through Traefik and the edge WAF to Web BFF;
2. privileged host-management reachability for `production-single-server`.

It does not change application authentication, MFA, Authorization, tenant isolation, OpenBao, or workload identity.

## 1. Public client-address authority

The production public path remains:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> external L4
-> Traefik
-> Caddy + Coraza WAF
-> Web BFF
```

The external L4 is the network source-address authority. It MUST preserve the validated original client source address to Traefik with PROXY protocol v2. A provider that cannot supply an equivalent authenticated source-address-preservation path is not production-eligible without a revised current decision.

Traefik requirements:

- enable PROXY protocol only for the exact reviewed external-L4 source CIDRs;
- `proxyProtocol.insecure` is prohibited;
- `forwardedHeaders.insecure` is prohibited;
- public/client-supplied `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-HooshiX-Client-IP` MUST NOT become trusted client identity;
- Traefik reconstructs downstream forwarding state from the validated connection/client address, not from arbitrary Internet-supplied header values;
- the production Traefik application entry point accepts public application traffic only from the approved external-L4 source ranges; direct non-L4 Internet access is denied by provider/origin firewall, security-group, routing or an equivalently effective network control.

A deployment that cannot restrict the Traefik application origin to the approved external-L4 path is not production-eligible without a revised current decision. Application-layer header checks do not substitute for this network restriction.

Traefik supports `proxyProtocol.trustedIPs` and `forwardedHeaders.trustedIPs`; insecure forwarded-header trust is not a production setting. Deployment validation MUST use the exact pinned Traefik line from Technology Baseline.

## 2. WAF-to-BFF client-address contract

Only the approved Traefik workload may reach the Caddy/Coraza application ingress. Only the approved Caddy/Coraza workload may reach Web BFF. NetworkPolicy and Istio authorization enforce these paths independently of header processing.

Caddy requirements:

- configure trusted proxies only for the exact Traefik source range required by the deployment;
- enable strict trusted-proxy parsing so the nearest untrusted address is selected from the trusted forwarding chain;
- use only the reviewed client-IP header source, normally `X-Forwarded-For` produced by Traefik;
- never trust arbitrary public `Forwarded`, `X-Real-IP`, or private application client-IP headers;
- overwrite the internal `X-HooshiX-Client-IP` header for the BFF with the server-derived client IP after trusted-proxy resolution;
- do not pass through a caller-supplied value of `X-HooshiX-Client-IP`.

Caddy documentation states that incoming `X-Forwarded-*` values are ignored by default for untrusted proxies, and supports explicit `trusted_proxies` plus strict right-to-left parsing. The deployed configuration MUST retain those anti-spoofing semantics.

Web BFF requirements:

- accept network identity only from the internal `X-HooshiX-Client-IP` attribute on the WAF-only ingress path;
- never derive authority from browser-supplied `Forwarded`, `X-Forwarded-*`, or `X-Real-IP`;
- parse exactly one IP literal; hostnames, ports, CIDR notation, zone identifiers, comma-separated lists, and malformed values are rejected;
- normalize IPv4-mapped IPv6 to IPv4 before quota network canonicalization;
- represent IPv4/IPv6 as canonical binary address values before prefixing or HMAC use;
- treat a resolved address that is an external-L4, Traefik, WAF, or other configured trusted-proxy address as `NETWORK_IDENTITY_UNAVAILABLE` on a public operation that requires network security quota;
- never log or persist the raw client IP unless a separate approved security/forensic purpose explicitly requires it.

If the trusted client address is missing, malformed, or resolves to a proxy address, a public operation that requires a network quota fails closed. It MUST NOT fall back to a caller header, a random identifier, or a shared proxy address.

## 3. Downstream security-quota context

When a backend service owns a public-operation network quota, Web BFF forwards only a server-derived typed client-network context over the authenticated internal call.

The context:

- is created only after the WAF-only trusted-address contract above succeeds;
- carries one exact binary IPv4 or IPv6 address plus an explicit address family;
- is never a browser/public API field;
- is accepted only from the approved BFF workload on the applicable public operation;
- is not logged, persisted, put in Kafka, or used as a metric label;
- is canonicalized by the quota-owning service to IPv4 `/24` or IPv6 `/64` before its purpose-separated HMAC key derivation under ADR-0024.

A missing or invalid trusted client-network context on an operation whose security policy requires a network quota is an availability/security-dependency failure and fails closed.

## 4. Negative tests for public client identity

Production verification includes at least:

- client sends a forged `X-Forwarded-For` and the forged value does not become quota identity;
- client sends forged `Forwarded`, `X-Real-IP`, and `X-HooshiX-Client-IP` values and none become authority;
- direct Internet/non-approved-source access to the Traefik application origin is denied before application routing;
- PROXY protocol from an untrusted source is not trusted;
- missing/misconfigured external-L4 source preservation is detected and a quota-required operation does not use the L4/Traefik/WAF address as the client;
- IPv4, IPv6, IPv4-mapped IPv6, malformed, multi-value, and proxy-address cases produce the defined canonical/fail-closed result;
- BFF-to-service typed network context is accepted only from the approved BFF workload and cannot be supplied through the public contract;
- raw client address does not appear in normal logs, metrics, traces, Redis keys, Kafka payloads, or durable business state.

## 5. `production-single-server` management network

Normal production SSH is not reachable on the public application interface.

The selected single-server management path is a dedicated WireGuard management overlay:

```text
approved operator device
-> authenticated WireGuard peer
-> host management address
-> OpenSSH TCP/22
-> FIDO2 human authentication
-> separate JIT privilege elevation
```

WireGuard is network admission only. It is not human identity and does not grant root, Kubernetes, or database privilege.

Mandatory controls:

- the host has a dedicated management address on the WireGuard interface;
- TCP/22 is accepted only on the management interface/address and is denied on the public interface by host firewall plus provider firewall/security-group control where available;
- the public application edge cannot route to the SSH listener;
- each approved operator device has an independent WireGuard peer key; shared peer keys are prohibited;
- peer `AllowedIPs` are minimal and do not provide broad application/cluster-network reachability by default;
- peer enrollment, ownership, last review, and revocation are attributable;
- lost/retired device keys are revoked independently from OpenSSH FIDO2 credentials;
- WireGuard key possession never bypasses ADR-0030 OpenSSH FIDO2 user-presence/user-verification or JIT privilege rules;
- the exact host WireGuard package/kernel support and configuration are pinned in provisioning metadata before production;
- configuration changes are reviewed as code and included in host recovery evidence;
- normal management does not depend on a workload-cluster component that the operator may need to recover.

Provider emergency console access, if available, is break-glass only. It is separately protected, incident-linked, audited where the provider supports it, and does not become the normal management path.

## 6. Management-network verification

Before production approval, prove:

- Internet/public-interface TCP/22 is unreachable;
- only approved WireGuard peers can reach the host management address;
- unapproved/revoked peer keys cannot reach SSH;
- SSH still rejects root/password/keyboard-interactive/shared/non-FIDO credentials under ADR-0030;
- a valid WireGuard peer without valid FIDO2 human authentication gains no SSH session;
- a valid FIDO2 SSH session without approved JIT elevation gains no privileged write authority;
- host/provider firewall render and effective state match the reviewed management-only rule;
- management-overlay configuration can be restored on a replacement host without putting private keys in Git;
- break-glass provider-console use does not silently bypass the incident/audit policy.

## 7. Availability and failure behavior

Client-address trust and management-network controls fail safe.

- A client-IP resolution failure does not disable network quotas.
- A proxy/header parsing failure does not accept caller forwarding headers as authority.
- A management-overlay failure does not cause public SSH to be enabled.
- An incident does not justify `forwardedHeaders.insecure`, `proxyProtocol.insecure`, public TCP/22, shared WireGuard keys, password SSH, or bypass of the WAF/BFF path.

## Verification requirements

`PRODUCTION-READINESS-CHECKLIST.md`, the security verification matrix, edge runbooks, and threat model MUST cover this ADR. Exact deployment CIDRs, WireGuard peer inventory, firewall rules, and provider-specific external-L4 settings are environment evidence and remain `NOT VERIFIED` until implemented and tested.

## Rollback considerations

Rollback MUST NOT restore ambiguous client-IP authority, trust caller forwarding headers, use an untrusted proxy chain for semantic quota identity, make public SSH reachable, share management peer keys, or make network access a substitute for FIDO2/JIT privilege. If the production deployment cannot preserve the required client source address, restrict the origin to the external-L4 path, or preserve management isolation, production traffic/privileged access remains blocked until a reviewed safe path exists.
