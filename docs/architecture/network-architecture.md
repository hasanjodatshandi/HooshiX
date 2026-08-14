# Network Architecture — Current State

This is the implementation-facing network architecture. ADR-0043 owns production client-address trust and the single-server management path. ADR-0044 owns ordinary telemetry ingress/egress.

## 1. Network zones

```text
Public Internet
  -> upstream L3/L4 mitigation
  -> external L4
  -> Traefik public edge
  -> Caddy/Coraza inspection
  -> Web BFF application ingress
  -> internal gRPC workloads
  -> owned PostgreSQL / Redis / Kafka / OpenBao paths

Ordinary telemetry:
workloads -> internal OTLP Collector -> Loki / Tempo
Prometheus -> private management scrape targets

Management:
approved device -> WireGuard -> host management address -> OpenSSH -> FIDO2 -> JIT
```

Public, workload, data/control-plane, telemetry, and management paths are not interchangeable.

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

Traefik application origin accepts public application traffic only from exact approved external-L4 source ranges. Direct Internet/non-approved-source access is denied before application routing by provider/origin firewall, security group, routing, or equivalent network control.

Direct Internet->BFF and Traefik->BFF application routes are prohibited. NetworkPolicy, Istio authorization, routing, and origin controls enforce these paths independently.

External L4 preserves validated original client source with PROXY protocol v2. Traefik trusts PROXY only from exact reviewed L4 CIDRs. `proxyProtocol.insecure` and `forwardedHeaders.insecure` are prohibited.

## 3. Client-address trust chain

```text
external-L4 validated address
-> Traefik trusted PROXY-v2 result
-> Traefik-generated forwarding state
-> Caddy strict trusted-proxy resolution
-> Caddy-generated X-HooshiX-Client-IP
-> Web BFF exact canonical binary client address
-> approved backend exact-address context
```

Caller `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-HooshiX-Client-IP` are not authority.

BFF accepts exactly one server-derived client IP on the WAF-only ingress path. It does not parse arbitrary public forwarding chains.

When a backend owns a public quota, BFF sends the exact binary IP+family only from the approved BFF workload/operation. BFF does not pre-collapse it to a prefix.

ADR-0024 derives:

```text
client_ip_exact:
  IPv4 /32
  IPv6 /128
  -> v1 hard pre-auth network identity

client_network_aggregate:
  IPv4 /24
  IPv6 /64
  -> separate abuse/allocation pressure; not sole v1 hard 429 identity
```

Raw client IP is transient security context. It is not a Redis key, ordinary metric/log/trace field, Kafka payload, or durable business datum.

## 4. East-west application traffic

Internal synchronous traffic uses gRPC + Protobuf over Istio Ambient strict mTLS/workload identity.

Each production workload has:

- dedicated ServiceAccount;
- Calico deny-by-default NetworkPolicy;
- exact ingress/egress needs;
- least-privilege Istio authorization;
- finite deadlines/registered dependency semantics;
- positive/negative connectivity and identity tests.

Pod IP, namespace name, source header, trace context, or baggage is not workload/business authority.

## 5. Data/control-plane paths

### PostgreSQL

Only owning service identities can reach/authenticate to their database boundary. Single-server shares physical PostgreSQL only; DB/role/Flyway/RLS ownership remains distinct.

### Redis

Redis is internal only, TLS protected, and uses per-owner ACL/key namespaces. ADR-0024 capacity/time/network semantics apply to security quotas.

### Kafka

Kafka is internal only with TLS/authentication/per-service principals/ACLs/quotas. Broker/controller ports are not public.

### OpenBao

OpenBao is secret authority, not public application endpoint. Access is limited to approved secret-delivery/control-plane paths. Normal application hot paths do not add per-request OpenBao RPC.

## 6. Observability network path

ADR-0044 ordinary telemetry path is private:

```text
application workload -> internal OTLP -> otelcol-contrib
container log file -> exact read-only node-local Collector mount
Collector -> Loki / Tempo
Prometheus -> private management scrape endpoints
Grafana -> Prometheus / Loki / Tempo
Prometheus -> Alertmanager
```

Mandatory:

- OTLP receiver has no public Internet ingress;
- only approved workloads may reach Collector OTLP;
- Prometheus management scrape endpoints are not public application endpoints;
- Collector egress is limited to approved telemetry backends/DNS paths;
- Collector has no host network and no broad host filesystem path;
- the only node-log exception is the exact read-only Kubernetes pod/container log paths defined by ADR-0044;
- telemetry headers/baggage cannot become authentication, tenant, authorization, quota, idempotency, or audit authority;
- required security/privileged audit follows its separate durable off-host path.

Single-server local telemetry components share the host failure domain. The required external black-box monitor is outside that host and exercises the approved public edge; it does not receive an origin-bypass route.

## 7. Egress policy

Application workloads start deny-by-default. External egress is capability-specific and reviewed.

New external destination requires owner/use case, exact destination/protocol, authentication/credentials, DNS/IP-change strategy, deadline/retry/failure behavior, SSRF review where relevant, network-policy changes, observability, and negative tests.

Compromised Password runtime has no HIBP/provider Internet egress; HIBP corpus acquisition is offline release/build work.

## 8. Single-server management plane

```text
approved operator device
-> WireGuard
-> host management address
-> OpenSSH:22
-> FIDO2
-> JIT privilege
```

- public TCP/22 denied;
- SSH reachable only through management address/interface;
- independent per-device peer keys;
- minimal routes;
- network admission grants no human/privileged identity;
- FIDO2/JIT remain mandatory;
- provider console is incident-linked break glass only.

Management remains usable when workload-cluster services need recovery; normal management does not depend on a cluster workload.

## 9. DNS and name authority

Kubernetes service discovery uses cluster DNS and stable service names. External provider DNS is allowed only for reviewed adapter destinations and never permits caller-selected destination authority.

Production DNS/provider/resolver/DNSSEC/failover details are environment evidence, not guessed architecture values.

## 10. IPv4/IPv6

Both address families are explicit where exposed.

Security identity:

- parse exactly one IP literal;
- normalize IPv4-mapped IPv6 to IPv4;
- exact hard quota: IPv4 `/32`, IPv6 `/128`;
- aggregate pressure: IPv4 `/24`, IPv6 `/64`;
- HMAC inputs use canonical binary data + family/dimension/operation/key-version separation;
- textual spelling differences cannot create alias budgets.

Public IPv6 is production-eligible only when edge firewall/upstream mitigation/source preservation/WAF/quota/privacy/negative tests cover it.

## 11. MTU/kernel/network capacity

Deployment evidence records at least:

- effective MTU/PMTU and Calico/Ambient/HBONE overhead;
- conntrack high-water/drops;
- SYN/listen queue pressure;
- file-descriptor usage;
- ephemeral ports/TIME_WAIT;
- public/management packet/error/drop counters;
- L4/Traefik/WAF connection/handshake saturation;
- Collector OTLP/log export connection pressure;
- Prometheus scrape connection/timeout pressure.

Unknown provider-specific values are blockers until measured.

## 12. Negative tests

Verify:

- forged forwarding/private client-IP headers do not change identity;
- untrusted/missing PROXY cannot set/replace trusted client identity;
- exact `/32`/`/128` hard identity and separate `/24`/`/64` pressure behave correctly under NAT/IPv6 cases;
- direct Internet/non-L4 Traefik origin denied;
- direct Internet->BFF and Traefik->BFF denied;
- unapproved workload->internal service/data/control-plane denied;
- arbitrary application Internet egress denied;
- public OTLP and public management scrape access denied;
- wrong workload->Collector OTLP denied;
- Collector cannot read outside exact approved log paths;
- public SSH denied; revoked WireGuard peer denied; WireGuard without FIDO2/JIT grants no privilege;
- external black-box monitoring uses only approved public edge path;
- IPv6 follows equivalent controls when enabled.

## 13. Evidence state

Target network architecture is defined. Provider CIDRs/firewalls, WireGuard inventory, interfaces, DNS, MTU/conntrack limits, Collector policies, external-monitor provider/config, and runtime packet-path evidence remain `NOT VERIFIED` until implementation/environment tests exist.

Production traffic/privileged access remain blocked while mandatory network evidence is missing or failed.