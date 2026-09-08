# Local Traefik Edge

## Scope

This runbook defines the local north-south edge for the `platform-local` kind
cluster. It is a production-fidelity integration environment, not a production
exposure design.

Required local traffic path:

```text
localhost:8080 / localhost:8443
-> kind control-plane ports 80 / 443
-> Traefik (`traefik-system`)
-> edge-waf: Caddy + Coraza (`platform-edge`)
-> Web BFF (`platform-apps`)
```

Direct Traefik -> Web BFF application routing is prohibited.

## Status and evidence

```text
Architecture: DECIDED
Local implementation: IMPLEMENTED
Local execution evidence: PASSED through the repository verifier; evidence remains execution/commit specific
Production deployment evidence: NOT VERIFIED
```

Commands in this runbook define the required repository interface and must not
be reported as working until their scripts/targets exist and pass verification.

## Pinned components

Use `docs/technology/local-development-baseline.md`.

Primary local edge pins:

```text
Traefik Proxy 3.7.10
Traefik Helm chart 41.2.0
Gateway API 1.5.1 Standard channel
Caddy 2.11.4
coraza-caddy 2.5.0
Coraza 3.7.0
OWASP CRS 4.25.1 LTS
Istio Ambient 1.30.3
```

Chart 41 requires the repository values to use the current `log`/`accessLog` keys and object-form `providers.file.content`. Render/upgrade verification must reject stale chart-40 key shapes before cluster mutation.

Repository pin locations:

```text
infrastructure/traefik/pins.env
infrastructure/traefik/chart/41.2.0/
infrastructure/waf/pins.env
infrastructure/waf/
```

Charts/images/rules are verified by checksum/digest before cluster mutation.

## Local port mapping

The kind control-plane node exposes:

```text
localhost:8080 -> node port 80  -> Traefik `web`
localhost:8443 -> node port 443 -> Traefik `websecure`
```

The exact mapping belongs in `infrastructure/kind/cluster.yaml` and must be
reviewed with the local baseline.

This single ingress-ready node cannot run two replicas holding the same host ports.
Local upgrades therefore use `maxSurge: 0`, `maxUnavailable: 1`: the old instance
releases the ports before its replacement starts. A brief local ingress outage is
expected; this is not a zero-downtime or HA update guarantee. Do not bypass the WAF
or remove placement/security constraints to make a second host-port pod schedulable.

Traefik's Kubernetes Gateway provider needs long-lived list/watch connectivity to
the cluster API. Service destination rewriting occurs before the local Calico egress
decision, so a Service-ClusterIP rule does not authorize this path. Before creating
or updating Traefik, the installer resolves the one ready `kubernetes` EndpointSlice
address and HTTPS target port, validates a single IPv4 endpoint, and creates a
separate exact `/32` + TCP-port NetworkPolicy. DNS and WAF rules remain separate.
Without this exact rule, existing watch connections may mask the defect until a
restart, after which every application route degrades to Traefik 404. The verifier
compares current EndpointSlice and policy exactly; do not replace this with a node,
pod or Internet CIDR.

## Pod Security exception

The local Traefik deployment uses `hostPort` for ports 80 and 443 so host traffic
can enter the kind node without a separate cloud load balancer. This requires a
local Pod Security exception for `traefik-system`.

The exception is narrow:

- `traefik-system` may use the required Pod Security profile for host ports;
- the Traefik container remains non-root;
- privilege escalation is disabled;
- Linux capabilities are dropped except a specifically reviewed capability if
  the chart/runtime proves one is required;
- the root filesystem is read-only where the pinned image/chart supports it;
- `RuntimeDefault` seccomp is required;
- no `hostPath`, host network, or privileged container is introduced merely for convenience.

This is a local kind constraint, not a production security decision. Production
uses the reviewed external load-balancing architecture and its own Pod Security
configuration.

## Ambient identity

Unlike a simple routing demo, local Traefik is explicitly enrolled in Istio
Ambient so the Traefik -> WAF hop carries a ServiceAccount-derived identity and
mTLS. The WAF and BFF are also Ambient-enrolled.

Required identities are distinct:

```text
traefik-system/traefik
platform-edge/edge-waf
platform-apps/web-bff
```

Authorization must permit only:

```text
Traefik -> edge-waf
edge-waf -> web-bff
```

and deny:

```text
Traefik -> web-bff
unapproved namespace -> edge-waf
unapproved namespace -> web-bff
```

## Gateway API routing policy

Kubernetes Gateway API is the default and only general routing provider for this
local profile.

Rules:

- enable the Traefik Kubernetes Gateway provider;
- disable the Kubernetes Ingress provider;
- disable the Traefik CRD provider by default;
- enable a Traefik proprietary provider/CRD only through a narrowly documented
  local need that corresponds to an accepted architecture capability;
- Dashboard and insecure API remain disabled;
- public catch-all routes are prohibited;
- Routes use explicit hostnames and paths;
- allowed route namespaces require:

```text
platform.local/gateway-access=true
```

- cross-namespace backend references require the appropriate Gateway API
  authorization object/policy and are not implicitly allowed;
- the public HTTPRoute backend is the WAF service, never Web BFF directly.

## Local TLS

Local TLS uses developer-only certificates generated by the repository bootstrap
or an approved local certificate tool.

Rules:

- production private keys/certificates are never copied into local development;
- local private key files are gitignored and permission-restricted;
- HTTP redirects to HTTPS when the test profile requires browser/TLS behavior;
- TLS configuration remains versioned except for private key material.

## WAF profile

The local edge includes the same WAF component family as production so routing,
request-body limits, rule packaging, and security tests exercise the real
boundary.

Default developer mode may use detection-only rules for normal application work,
but CI/staging security verification must include controlled blocking tests.

The WAF must:

- receive all public application traffic from Traefik;
- forward only approved traffic to Web BFF;
- never log full bodies/secrets/tokens;
- record rule identifiers/reasons without copying sensitive request data;
- use pinned Coraza/CRS artifacts;
- never become a Java/Spring filter substitute.

Local WAF tests do **not** prove upstream L3/L4 DDoS mitigation.

### Opaque-cookie and logging profile

Traefik chart 41 uses flat `accessLog.fields.defaultMode/names`, not the older
`fields.general` shape. Access logs keep only response status, timing, retry count,
timestamp and configured router/service/entrypoint names. Request paths, queries,
client addresses, user names, host values and headers are not logged. Explicit
query-parameter dropping remains enabled as an additional guard.

`infrastructure/waf/opaque-cookie-exclusions.conf` is owned by the BFF/edge
boundary. It excludes only the value of one well-formed `__Host-sajtech-session`
or `__Host-sajtech-preauth` cookie from CRS rule 930120. The accepted shape is one
1–64 character alphanumeric/underscore/hyphen key ID, one dot, and exactly 43
base64url characters, matching the BFF opaque locator format. Duplicate cookies,
malformed values, other cookies, cookie names, arguments, and all other CRS rules
remain inspected. This is a format-bound permanent exception, not a route bypass;
review it with any cookie-format or CRS update. The BFF remains the only session
authority and validates the key, Redis state, expiry, Origin and CSRF independently.

The pinned coraza-caddy module receives the small repository-owned
`patches/safe-rule-logging.patch` before compilation. Upstream module bytes are
verified against the public Go checksum database and the exact recorded module hash;
the module cache is never patched. The patch replaces expanded rule/request text
with fixed events and numeric rule/severity/status fields. Raw transaction audit
logging and debug logging are off; safe rule/block events remain enabled. These are
ordinary WAF diagnostics, not the separately required durable business/security
audit. Caddy's native filter cannot redact its reserved message field, so merely
filtering structured cookie fields does not solve the upstream rule-text exposure.

The image build executes synthetic logging tests at every severity and tests the
exact pinned CRS/exclusion boundary. Protected baseline CI requires that build and
packaged configuration validation. Local edge verification additionally checks
blocked-request canaries and numeric rule evidence. Remove the patch only when an
upstream replacement passes the same privacy tests; never roll back to raw request
logging or disable required WAF rules to obtain a passing capacity run.

## Prerequisites

```bash
make baseline-verify
make local-cluster-verify
make verify-local-istio-ambient
```

The local cluster must already have:

- Calico ready;
- Gateway API 1.5.1 CRDs;
- Istio Ambient ready;
- required namespaces/ServiceAccounts/policies available or created by the edge install.

## Install

```bash
make local-traefik-edge-install
```

Required order:

1. validate pin/checksum files;
2. create/validate `traefik-system` and `platform-edge` namespaces;
3. create independent ServiceAccounts and RBAC;
4. render chart 41 values and reject stale chart-40 logging/file-provider keys;
5. install/upgrade Traefik from the pinned chart;
6. install/upgrade the pinned Caddy/Coraza WAF;
7. apply Ambient enrollment and least-privilege authorization;
8. apply the Gateway/GatewayClass/HTTPRoute resources;
9. apply local TLS material references;
10. wait for readiness and run verification.

The installer must not silently enable the dashboard, Kubernetes Ingress
provider, CRD provider, wildcard routes, or direct BFF backend routes.

## Verify

```bash
make verify-local-traefik-edge
```

The verifier must check at least:

- Traefik and WAF Helm/workload resources are ready;
- running image digests/chart metadata match repository pins;
- rendered Traefik values use chart-41 logging/file-provider key shapes and contain no stale chart-40 aliases;
- Traefik uses the expected independent ServiceAccount;
- WAF uses the expected independent ServiceAccount;
- both namespaces have the intended Ambient enrollment;
- Traefik Gateway API provider is enabled;
- Kubernetes Ingress provider is disabled;
- Traefik CRD provider is disabled unless an explicit expected exception exists;
- dashboard/insecure API are disabled;
- Gateway accepts Routes only from allowed namespaces;
- no catch-all public route exists;
- the public Route backend points to `edge-waf`, not `web-bff`;
- HTTP/HTTPS behavior matches local policy;
- WAF receives the request before BFF;
- after replacing the single Traefik pod, the exact API egress restores the
  Gateway/HTTPRoute watch and controlled WAF route without manual resource edits;
- a controlled WAF detection/blocking test produces the expected rule result;
- Traefik -> WAF is allowed by identity;
- WAF -> BFF is allowed by identity;
- Traefik -> BFF is denied;
- an unauthorized namespace -> WAF/BFF is denied;
- no full request/response body or secret appears in edge logs;
- temporary verification resources are deleted.

## Example local smoke checks

HTTP/TLS behavior:

```bash
curl -i http://localhost:8080/
curl -k -i https://localhost:8443/
```

Do not interpret a `200` alone as success. Verification must also prove the
selected route, WAF traversal, identity policy, and negative paths.

## Remove

```bash
make local-traefik-edge-delete
```

Required order:

1. Routes/Gateway resources;
2. local edge authorization policies;
3. WAF release/resources;
4. Traefik release/resources;
5. local-only TLS secrets;
6. namespaces only when they contain no unrelated resources.

Do not delete Gateway API CRDs, Istio, or Calico as part of normal edge removal.

## Diagnostics

Inspect Traefik:

```bash
helm list \
  --kube-context kind-platform-local \
  --namespace traefik-system

kubectl \
  --context kind-platform-local \
  get pods,service,gateway,httproute \
  --namespace traefik-system \
  --output=wide
```

Inspect WAF:

```bash
kubectl \
  --context kind-platform-local \
  get deployment,pods,service \
  --namespace platform-edge \
  --output=wide
```

Inspect routes across namespaces:

```bash
kubectl \
  --context kind-platform-local \
  get gateway,httproute,referencegrant \
  --all-namespaces
```

Inspect recent events:

```bash
kubectl \
  --context kind-platform-local \
  get events --all-namespaces \
  --sort-by=.metadata.creationTimestamp
```

Inspect logs without enabling unsafe debug body/header dumping:

```bash
kubectl --context kind-platform-local logs -n traefik-system deployment/traefik
kubectl --context kind-platform-local logs -n platform-edge deployment/edge-waf
```

## Failure handling

If edge verification fails:

1. stop downstream application acceptance testing;
2. preserve rendered manifests, Helm status, Events, route status, policy status,
   and minimal safe logs;
3. determine whether the failure is chart migration, hostPort, Gateway API, RBAC, Ambient identity,
   AuthorizationPolicy, WAF, TLS, or backend readiness;
4. do not route Traefik directly to BFF as a workaround;
5. do not disable WAF/STRICT mTLS/deny-by-default to obtain a green smoke test;
6. repair and rerun verification or remove the local edge through the controlled target.

## Security prohibitions

- public Traefik dashboard/insecure API;
- wildcard/catch-all routes;
- direct Traefik -> BFF public application route;
- default/shared ServiceAccounts;
- production TLS keys in local development;
- full body/token/cookie logging;
- unpinned mutable image/chart substitutions;
- manual cluster drift outside versioned repository automation;
- claiming local WAF tests prove volumetric DDoS protection.
