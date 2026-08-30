#!/usr/bin/env python3
"""Render exact-digest production application manifests and release admission policy."""
from __future__ import annotations
import argparse,json,shutil,subprocess,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(Path(__file__).resolve().parent))
import verify_release
HELM_VERSION="v4.2.4"
OTLP="http://otel-collector.platform-observability-node.svc.cluster.local:4318/v1/traces"
PROM="prod.sajtech.internal/ns/platform-observability/sa/prometheus"
OBS=f"""observability:
  tracing:
    enabled: true
    otlpEndpoint: {OTLP}
    collector:
      namespace: platform-observability-node
      podLabels:
        app.kubernetes.io/name: otel-collector
      port: 4318
  metrics:
    enabled: true
    prometheus:
      namespace: platform-observability
      podLabels:
        app.kubernetes.io/name: prometheus
      principal: {PROM}
"""
SERVICE_ACCOUNTS={"authorization-service":"authorization-service","compromised-password-service":"compromised-password-service","identity-service":"identity-service","notification-service":"notification-service","web-bff":"web-bff","web-frontend":"web-frontend"}
def split_image(image:str)->tuple[str,str]: return tuple(image.rsplit("@",1))
def build_values(service:str,m:dict)->str:
    a=m["service_capacity"]["authorization"]; i=m["service_capacity"]["identity"]; d=m["compromised_password_dataset"]
    if service=="authorization-service":
        return f"""runtime:
  enabled: true
  maxConcurrentCallsPerConnection: 128
  checkPermission:
    globalConcurrency: {a["global_concurrency"]}
    perCallerConcurrency: {a["per_caller_concurrency"]}
    globalQueueCapacity: {a["global_queue_capacity"]}
    perCallerQueueCapacity: {a["per_caller_queue_capacity"]}
    maxCallerBuckets: {a["max_caller_buckets"]}
    queueWait: PT0.025S
database:
  jdbcUrl: jdbc:postgresql://postgresql-rw.platform-data.svc.cluster.local:5432/authorization
  podLabels: {{app.kubernetes.io/name: postgresql}}
  runtimeSecretName: authorization-db-runtime
  migrationSecretName: authorization-db-migration
quota:
  redis:
    podLabels: {{app.kubernetes.io/name: security-redis}}
    connectionSecretName: authorization-quota-redis
  maxActiveBuckets: {a["quota_max_active_buckets"]}
  maxNewBucketsPerMinute: {a["quota_max_new_buckets_per_minute"]}
  minimumMemoryHeadroomPercent: 30
  hostTimeStatus: {{configMapName: authorization-host-time}}
secrets:
  fingerprintSecretName: authorization-fingerprint
  quotaKeySecretName: authorization-quota-key
identity:
  jwtVerifierConfigMapName: authorization-identity-jwt
{OBS}"""
    if service=="compromised-password-service":
        return f"""dataset:
  existingClaim: compromised-password-dataset
  filename: corpus.sqlite
  manifestFilename: release-manifest.json
  expectedManifestSha256: {d["manifest_sha256"]}
  requiredSourceKind: HIBP_PWNED_PASSWORDS_SHA1
  maxPrefixCardinality: {d["max_prefix_cardinality"]}
  maxSerializedResponseBytes: {d["max_serialized_response_bytes"]}
{OBS}"""
    if service=="identity-service":
        return f"""runtime:
  registrationRuntimeEnabled: true
  authenticationRuntimeEnabled: true
  tenantRuntimeEnabled: true
  argon2MaxConcurrentHashes: {i["argon2_max_concurrent_hashes"]}
  compromisedPasswordMaxInFlight: {i["compromised_password_max_in_flight"]}
  phoneRegistrationEnabled: false
  notificationDispatchEnabled: true
database:
  jdbcUrl: jdbc:postgresql://postgresql-rw.platform-data.svc.cluster.local:5432/identity
  podLabels: {{app.kubernetes.io/name: postgresql}}
  runtimeSecretName: identity-db-runtime
  migrationSecretName: identity-db-migration
quota:
  redis:
    podLabels: {{app.kubernetes.io/name: security-redis}}
    connectionSecretName: identity-quota-redis
  maxActiveBuckets: {i["quota_max_active_buckets"]}
  maxNewBucketsPerMinute: {i["quota_max_new_buckets_per_minute"]}
  minimumMemoryHeadroomPercent: 30
  hostTimeStatus: {{configMapName: identity-host-time}}
secrets:
  fingerprintSecretName: identity-fingerprint
  challengeSecretName: identity-challenge
  handoffSecretName: identity-handoff
  mfaSecretName: identity-mfa
  quotaKeySecretName: identity-quota
  refreshSecretName: identity-refresh
  jwtPrivateSecretName: identity-jwt-private
jwt:
  publicVerifierConfigMapName: identity-jwt-public
  issuer: https://identity.sajtech.internal
  allowedAudiences: [authorization-service]
{OBS}"""
    if service=="notification-service":
        return f"""database:
  jdbcUrl: jdbc:postgresql://postgresql-rw.platform-data.svc.cluster.local:5432/notification
  podLabels: {{app.kubernetes.io/name: postgresql}}
  runtimeSecretName: notification-db-runtime
  migrationSecretName: notification-db-migration
secrets:
  fingerprintSecretName: notification-fingerprint
  deliverySecretName: notification-delivery
{OBS}"""
    if service=="web-bff":
        return f"""runtime:
  enabled: true
  requireFetchMetadata: true
  publicOrigin: https://{m["public_hostname"]}
redis:
  podLabels: {{app.kubernetes.io/name: security-redis}}
  connectionSecretName: web-bff-redis
secrets:
  locatorSecretName: web-bff-locator
  csrfSecretName: web-bff-csrf
  refreshEncryptionSecretName: web-bff-refresh
  quotaSecretName: web-bff-quota
quota:
  maxActiveBuckets: 200000
  maxNewBucketsPerMinute: 2000
  minimumMemoryHeadroomPercent: 30
  hostTimeConfigMapName: web-bff-host-time
edge:
  namespace: platform-edge
  podLabels: {{app.kubernetes.io/name: edge-waf}}
  principal: prod.sajtech.internal/ns/platform-edge/sa/edge-waf
{OBS}"""
    raise ValueError(f"unsupported service: {service}")
def verify_helm()->str:
    helm=shutil.which("helm")
    if not helm: raise RuntimeError("Helm 4.2.4 is required")
    out=subprocess.run([helm,"version","--short"],check=True,capture_output=True,text=True).stdout.strip()
    if not out.startswith(HELM_VERSION): raise RuntimeError(f"Helm must be {HELM_VERSION}; got {out}")
    return helm
def render_service(helm:str,service:str,m:dict,values:Path)->str:
    repo,digest=split_image(m["images"][service]); chart=ROOT/"services"/service/"deploy"/"helm"/service
    cmd=[helm,"template",service,str(chart),"--namespace","platform-apps","--values",str(values),"--set-string",f"image.repository={repo}","--set-string",f"image.digest={digest}"]
    rendered=subprocess.run(cmd,cwd=ROOT,check=True,capture_output=True,text=True).stdout
    if m["images"][service] not in rendered: raise RuntimeError(f"{service} is not bound to exact digest")
    if ":latest" in rendered: raise RuntimeError(f"{service} contains mutable latest image")
    return rendered
def admission_policy(m:dict)->str:
    identity=m["cosign"]["certificate_identity"]; issuer=m["cosign"]["certificate_oidc_issuer"]; revision=m["git_revision"]
    image_lines="\n".join(f"    - glob: '{m['images'][component]}'" for component in verify_release.RELEASE_COMPONENTS)
    mapping=", ".join(f"'{SERVICE_ACCOUNTS[component]}':'{m['images'][component]}'" for component in verify_release.RELEASE_COMPONENTS)
    application_service_accounts=",".join(f"'{SERVICE_ACCOUNTS[component]}'" for component in verify_release.RELEASE_COMPONENTS)
    return f"""apiVersion: policies.kyverno.io/v1
kind: ValidatingPolicy
metadata:
  name: hooshix-production-release-allowlist
spec:
  failurePolicy: Fail
  validationActions: [Deny]
  matchConstraints:
    resourceRules:
      - apiGroups: ['']
        apiVersions: ['v1']
        operations: [CREATE, UPDATE]
        resources: [pods]
        scope: Namespaced
  validations:
    - message: application ServiceAccounts must use the exact reviewed production release digest
      expression: >-
        !([{application_service_accounts}].exists(sa, sa == object.spec.serviceAccountName))
        || (object.spec.containers.size() == 1 && object.spec.containers[0].image == {{{mapping}}}[object.spec.serviceAccountName])
---
apiVersion: policies.kyverno.io/v1
kind: ImageValidatingPolicy
metadata:
  name: hooshix-production-supply-chain
spec:
  failurePolicy: Fail
  validationActions: [Deny]
  webhookConfiguration:
    timeoutSeconds: 15
  matchConstraints:
    resourceRules:
      - apiGroups: ['']
        apiVersions: ['v1']
        operations: [CREATE, UPDATE]
        resources: [pods]
        scope: Namespaced
  matchImageReferences:
{image_lines}
  validationConfigurations:
    mutateDigest: false
    required: true
    verifyDigest: true
  attestors:
    - name: cosign
      cosign:
        keyless:
          identities:
            - subject: '{identity}'
              issuer: '{issuer}'
        ctlog:
          url: https://rekor.sigstore.dev
  attestations:
    - name: provenance
      intoto:
        type: https://slsa.dev/provenance/v1
    - name: sbom
      intoto:
        type: https://cyclonedx.org/bom
  validations:
    - message: release image signature verification failed
      expression: images.containers.map(image, verifyImageSignatures(image, [attestors.cosign])).all(e, e > 0)
    - message: release provenance verification failed
      expression: images.containers.map(image, verifyAttestationSignatures(image, attestations.provenance, [attestors.cosign])).all(e, e > 0)
    - message: release provenance is not bound to the reviewed source revision
      expression: images.containers.map(image, extractPayload(image, attestations.provenance).buildDefinition.externalParameters.gitRevision == '{revision}').all(e, e)
    - message: signed CycloneDX SBOM verification failed
      expression: images.containers.map(image, verifyAttestationSignatures(image, attestations.sbom, [attestors.cosign])).all(e, e > 0)
    - message: signed SBOM is not CycloneDX
      expression: images.containers.map(image, extractPayload(image, attestations.sbom).bomFormat == 'CycloneDX').all(e, e)
"""
def render(m:dict,output:Path)->None:
    if output.exists() and (not output.is_dir() or any(output.iterdir())): raise RuntimeError("output directory must not exist or must be empty")
    output.mkdir(parents=True,exist_ok=True); helm=verify_helm()
    with tempfile.TemporaryDirectory(prefix="hooshix-production-values-") as tmp:
        tmpdir=Path(tmp)
        for service in verify_release.SERVICES:
            vp=tmpdir/f"{service}.yaml"; vp.write_text(build_values(service,m),encoding="utf-8")
            rendered=render_service(helm,service,m,vp)
            header=f"# Generated from reviewed production release metadata. Source revision: {m['git_revision']}\n"
            (output/f"{service}.yaml").write_text(header+rendered,encoding="utf-8")
    (output/"release-admission.yaml").write_text(admission_policy(m),encoding="utf-8")
def main()->int:
    p=argparse.ArgumentParser(); p.add_argument("--manifest",required=True,type=Path); p.add_argument("--output",required=True,type=Path); p.add_argument("--skip-git-reachability",action="store_true"); a=p.parse_args()
    try:
        m=verify_release.load_manifest(a.manifest); errors=verify_release.validate_manifest(m)
        if not errors and not a.skip_git_reachability: errors.extend(verify_release.validate_git_revision(m["git_revision"]))
        if errors:
            for e in errors: print(f"ERROR: {e}",file=sys.stderr)
            return 1
        render(m,a.output)
    except (OSError,json.JSONDecodeError,RuntimeError,ValueError,subprocess.CalledProcessError) as exc:
        print(f"Production GitOps render FAILED: {exc}",file=sys.stderr); return 1
    print(f"Production GitOps render PASSED: {a.output}"); return 0
if __name__=="__main__": raise SystemExit(main())
