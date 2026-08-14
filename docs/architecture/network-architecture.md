# Network Architecture — Current State

This document is the implementation-facing network architecture. ADR-0043 is the durable authority for production client-address trust and the single-server management path.

## 1. Network zones

The production design has these distinct zones:

```text
Public Internet
    |
    v
Upstream L3/L4 mitigation/scrubbing
    |
    v
External L4
    |
    v
Public edge zone: Traefik
    |
    v
Inspection zone: Caddy + Coraza WAF
    |
    v
Application ingress: Web BFF
    |
    v
Internal service zone: gRPC workloads
    |
    +--> PostgreSQL / Redis / Kafka / OpenBao / telemetry as explicitly permitted

Separate management plane:
approved operator device -> management overlay -> host management address -> OpenSSH
```

Public, application, data, control-plane, and management paths are not interchangeable.

## 2. Public north-south path

The only production public application path is:

```text
Internet
-> upstream volumetric protection
-> external L4
-> Traefik
-> Caddy/Coraza WAF
-> Web BFF
```

The production Traefik application origin accepts public application traffic only from the exact approved external-L4 source ranges. Direct Internet/non-approved-source access to that origin is denied by provider/origin firewall, security-group, routing, or an equivalently effective network control. If the deployment cannot enforce this origin restriction, it is not production-eligible without a revised current decision.

Direct Internet -> BFF and Traefik -> BFF application paths are also prohibited. NetworkPolicy, Istio authorization, route configuration, and origin/provider network controls enforce these paths independently.

The external L4 preserves the validated original client source address to Traefik with PROXY protocol v2 under ADR-0043. Exact external-L4 source CIDRs are environment configuration and MUST be recorded before production approval.

Traefik trusts PROXY protocol only from those exact source CIDRs. Insecure PROXY or forwarded-header trust is prohibited. Application-layer header validation does not substitute for the external-L4-only origin restriction.

## 3. Client-address trust chain

The client-address chain is:

```text
external L4 validated source address
-> Traefik trusted PROXY-v2 result
-> Traefik-generated forwarding state
-> Caddy strict trusted-proxy resolution
-> Caddy-generated internal X-HooshiX-Client-IP
-> Web BFF typed client-network context
-> quota-owning service /24 or /64 HMAC canonicalization
```

Internet-supplied `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-HooshiX-Client-IP` are not authority.

Web BFF does not parse arbitrary forwarding chains. It accepts one server-derived internal client IP only on the WAF-only ingress path. Backend services that own public-operation network quotas accept only the typed BFF-derived network context from the approved BFF workload.

Raw client IP is transient security context. It is not a Redis key, metric label, Kafka field, or ordinary durable application datum. ADR-0024 defines HMAC pseudonymization for network quota state.

## 4. East-west application traffic

Internal synchronous traffic uses gRPC + Protobuf over Istio Ambient strict mTLS/workload identity.

Each production application workload has:

- a dedicated ServiceAccount;
- Calico deny-by-default NetworkPolicy;
- explicit ingress/egress rules;
- least-privilege Istio authorization;
- finite deadlines and registered dependency semantics;
- positive and negative connectivity/identity tests.

A pod IP, namespace name, or source header is not workload authority. Workload identity comes from the approved Istio/ServiceAccount identity.

## 5. Data and platform paths

### PostgreSQL

Only the owning service runtime/migration identities can reach and authenticate to the service-owned database boundary. In `production-single-server`, databases share one physical PostgreSQL instance but keep distinct DB/role/Flyway/RLS ownership.

### Redis

Only approved service owners may reach Redis. TLS and per-owner ACL/key namespaces are mandatory. Redis is not Internet reachable.

### Kafka

Kafka is internal only. TLS/authentication, per-service principals, ACLs, and quotas are mandatory. Broker/controller ports are not public.

### OpenBao

OpenBao is a production secret authority, not a public application endpoint. Its access is limited to approved control-plane/secret-delivery paths. Application request hot paths do not call it per request.

### Observability

Telemetry egress is allow-listed and bounded. Required security audit follows its durable off-host path and is not treated as ordinary best-effort telemetry.

## 6. Egress policy

Application workloads start with deny-by-default egress.

Allowed external destinations are capability-specific. Examples include the approved Google OIDC endpoints for Web BFF and approved Notification providers for Notification Service. Arbitrary URL/Internet egress is prohibited.

A new external destination requires:

- owning service/use case;
- exact destination/port/protocol;
- authentication/credential handling;
- DNS/IP-change strategy;
- timeout/retry/failure semantics;
- SSRF review where caller-controlled data could influence destination;
- NetworkPolicy/Istio/host/provider firewall updates;
- observability and negative tests.

## 7. Single-server management plane

ADR-0043 selects a WireGuard management overlay for normal `production-single-server` host reachability.

```text
approved operator device
-> WireGuard
-> host management address
-> OpenSSH:22
-> FIDO2 human authentication
-> JIT privilege
```

Requirements:

- public-interface TCP/22 is denied;
- SSH binds to or is firewalled to the management interface/address only;
- each operator device has an independent WireGuard peer key;
- peer routes are minimal;
- network admission does not grant human or privileged identity;
- ADR-0030 FIDO2 and JIT rules remain mandatory after network admission;
- provider console is break-glass only.

The management plane MUST remain usable when workload-cluster services need recovery. Normal management therefore does not depend on a pod/service inside the cluster being recovered.

## 8. DNS and name authority

Service discovery inside Kubernetes uses the cluster DNS path. Applications use stable service names, not pod IPs.

External provider endpoints use reviewed DNS names only where the owning adapter permits DNS-based egress. DNS resolution does not allow caller-controlled destination selection.

Production DNS providers, zones, resolver addresses, DNSSEC posture, and provider-specific failover behavior are deployment values. They MUST be recorded and tested before production when they affect public reachability or security-sensitive egress.

## 9. IPv4 and IPv6

Both address families are treated explicitly where the provider exposes them.

For security network identity:

- parse one exact IP literal only;
- IPv4-mapped IPv6 normalizes to IPv4;
- IPv4 quota network is `/24`;
- IPv6 quota network is `/64`;
- HMAC input uses canonical binary address/prefix plus family/domain separation;
- textual spelling differences cannot reset quota state.

A production deployment MUST NOT advertise IPv6 publicly unless edge firewall, upstream mitigation, client-address preservation, WAF path, quota identity, logging/privacy, and negative tests cover IPv6.

## 10. MTU, conntrack, ports, and host network capacity

Exact MTU, encapsulation, conntrack, file-descriptor, socket, and ephemeral-port settings depend on the selected host/provider and Calico/Ambient data path. They are not guessed in architecture documents.

Production capacity evidence records at least:

- effective pod/node/public path MTU and fragmentation/PMTU behavior;
- Calico/Ambient/HBONE overhead where applicable;
- conntrack usage/high-water mark and drop counters;
- SYN backlog/listen queue pressure;
- file-descriptor usage;
- ephemeral-port usage and TIME_WAIT pressure;
- public/management interface packet/error/drop counters;
- L4/Traefik/WAF connection and handshake saturation.

Unknown values are production blockers until measured and recorded in deployment evidence.

## 11. Required negative tests

Network verification includes:

- forged forwarding/client-IP headers do not change client identity;
- untrusted PROXY protocol cannot set client identity;
- missing source-address preservation does not collapse quota identity to a proxy address;
- direct Internet/non-approved-source access to the Traefik application origin is denied before application routing;
- direct Internet -> BFF denied;
- Traefik -> BFF WAF bypass denied;
- unapproved workload -> internal service denied;
- unapproved workload -> PostgreSQL/Redis/Kafka/OpenBao denied;
- arbitrary application Internet egress denied;
- public-interface SSH denied;
- unapproved/revoked management peer denied;
- WireGuard-only network access without valid FIDO2/JIT grants no privileged access;
- IPv6 follows the same controls when enabled.

## 12. Evidence state

The repository currently defines target network architecture. Provider CIDRs, firewall rules, WireGuard peer inventory, exact host interfaces, DNS values, MTU, conntrack limits, and runtime packet-path evidence remain `NOT VERIFIED` until implementation and environment tests exist.

Production traffic and privileged access remain blocked while mandatory network evidence is missing or failed.
