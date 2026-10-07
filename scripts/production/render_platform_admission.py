"""Render fail-closed admission for four pinned platform imports; never apply it."""
from __future__ import annotations

import argparse
import copy
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import publish_mesh_candidate as mesh_publication
import render_mesh_candidate as mesh
import render_openbao_candidate as bao
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER

API = "policies.kyverno.io/v1"
NAMESPACES = ("istio-system", "hooshix-secrets")
PULL_SECRET = "hooshix-ghcr-read"
BOUND = 32 * 1024
BAO_PIN = mesh.ROOT / "infrastructure/production/secrets/openbao-image.json"


def reporting_permissions() -> dict:
    # Kyverno 1.18 checks get/list/watch for every matched resource, including
    # subresources. This adds no update/debug permission and no Secret access.
    return {"apiVersion": "v1", "kind": "List", "items": [{
        "apiVersion": "rbac.authorization.k8s.io/v1", "kind": "ClusterRole",
        "metadata": {"name": "hooshix-platform-ephemeral-report-reader", "labels": {
            "rbac.kyverno.io/aggregate-to-reports-controller": "true"}},
        "rules": [{"apiGroups": [""], "resources": ["pods/ephemeralcontainers"],
                   "verbs": ["get", "list", "watch"]}]}]}


def publication(path: Path, component: str, now: datetime) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > BOUND:
        raise ValueError("bounded regular public publication receipt required")
    data = json.loads(path.read_bytes())
    if (data.get("schema_version") != 1 or data.get("component") != component
            or data.get("publication") != "Passed"
            or data.get("provenance_kind") != "unchanged-upstream-import"
            or data.get("signer") != EXPECTED_CERTIFICATE_IDENTITY
            or data.get("issuer") != EXPECTED_OIDC_ISSUER
            or not re.fullmatch(r"[a-f0-9]{40}", data.get("repository_revision", ""))):
        raise ValueError("publication identity or schema rejected")
    observed = datetime.fromisoformat(data["observed_at"])
    if observed.tzinfo is None or not timedelta(0) <= now - observed <= timedelta(days=5):
        raise ValueError("fresh publication required")
    expected = mesh_publication.targets() if component == "mesh" else {
        "openbao": {"image": "ghcr.io/hasanjodatshandi/hooshix/platform-openbao-private@"
                    + json.loads(BAO_PIN.read_bytes())["image"].split("@")[1]}}
    parts = data.get("components", {}) if component == "mesh" else {"openbao": data}
    if set(parts) != set(expected):
        raise ValueError("exact publication component inventory required")
    for name, target in expected.items():
        record = parts[name]
        built = datetime.fromisoformat(record["database_built_at"])
        if (record.get("image") != target["image"] or record.get("scan") != "Passed"
                or record.get("signature_provenance") != "Passed"
                or record.get("wrong_signer") != "Passed"
                or record.get("approved_exceptions") != []
                or record.get("severity_counts", {}).get("High") != 0
                or record.get("severity_counts", {}).get("Critical") != 0
                or record.get("registry_visibility") not in ("private", "public")
                or (name == "openbao" and record["registry_visibility"] != "private")
                or built.tzinfo is None
                or not timedelta(0) <= now - built <= timedelta(days=5)):
            raise ValueError("exact signed scan-clean platform import required")
    # This validates public metadata, NOT cryptographic authenticity. Native
    # Kyverno verifies signatures/attestations; GitHub run identity is verified
    # separately before an operator may use downloaded evidence for deployment.
    return data


def match_constraints(namespace: str) -> dict:
    return {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": namespace}},
            "resourceRules": [{"apiGroups": [""], "apiVersions": ["v1"],
                               "operations": ["CREATE", "UPDATE"], "resources": ["pods"]}]}


def policy(kind: str, name: str, namespace: str, validations: list[dict]) -> dict:
    return {"apiVersion": API, "kind": kind, "metadata": {"name": name}, "spec": {
        "failurePolicy": "Fail", "validationActions": ["Deny"],
        # Enforce every actual Pod, including controller-created Pods. Do not
        # synthesize controller rules: Kyverno 1.18 autogen rebuilds constraints
        # without this namespace selector, widening this bootstrap boundary.
        "autogen": {"podControllers": {"controllers": []}},
        "evaluation": {"admission": {"enabled": True}, "background": {"enabled": True}},
        "matchConstraints": match_constraints(namespace), "validations": validations}}


def validation(message: str, expression: str) -> dict:
    return {"message": message, "expression": expression}


def literal(value) -> str:
    return json.dumps(value, separators=(",", ":"))


def scalar_tree(path: str, value) -> str:
    """CEL typed Kubernetes objects cannot be compared to map literals."""
    if isinstance(value, dict):
        terms = []
        for key, child in value.items():
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", key):
                raise ValueError("typed security field required")
            field = path + "." + key
            terms.append("has(" + field + ") && (" + scalar_tree(field, child) + ")")
        return " && ".join(terms) or "true"
    if isinstance(value, list):
        return path + ".size() == " + str(len(value)) + "".join(
            " && (" + scalar_tree(path + "[" + str(i) + "]", child) + ")"
            for i, child in enumerate(value))
    return path + " == " + literal(value)


def hardening(namespace: str, pods: dict[str, dict]) -> dict:
    options = []
    for account, pod in pods.items():
        container = pod["containers"][0]
        context = container["securityContext"]
        # Never allow a new security-context field to broaden a reviewed exception.
        terms = ["object.spec.serviceAccountName == " + literal(account),
                 "object.spec.containers.size() == 1", "!has(object.spec.initContainers)",
                 "!has(object.spec.ephemeralContainers)",
                 *["(!has(object.spec." + field + ") || !object.spec." + field + ")"
                   for field in ("hostNetwork", "hostPID", "hostIPC", "shareProcessNamespace")],
                 "object.spec.containers[0].name == " + literal(container["name"]),
                 "object.spec.containers[0].image == " + literal(container["image"]),
                 "has(object.spec.securityContext) && "
                 "has(object.spec.securityContext.seccompProfile) && "
                 "object.spec.securityContext.seccompProfile.type == 'RuntimeDefault'",
                 "(!has(object.spec.securityContext.sysctls) || object.spec.securityContext.sysctls.size() == 0)",
                 "!has(object.spec.securityContext.windowsOptions)",
                 "has(object.spec.containers[0].securityContext)",
                 scalar_tree("object.spec.containers[0].securityContext", context),
                 "(!has(object.spec.containers[0].securityContext.procMount) || "
                 "object.spec.containers[0].securityContext.procMount == 'Default')",
                 "!has(object.spec.containers[0].securityContext.windowsOptions)"]
        terms.append("(!has(object.spec.containers[0].securityContext.seccompProfile) || "
                     "object.spec.containers[0].securityContext.seccompProfile.type == 'RuntimeDefault')")
        for field in ("privileged", "allowPrivilegeEscalation", "runAsNonRoot", "runAsUser", "runAsGroup"):
            if field not in context:
                safe = {"privileged": "false", "allowPrivilegeEscalation": "false",
                        "runAsNonRoot": "true", "runAsUser": "10001", "runAsGroup": "10001"}[field]
                terms.append("(!has(object.spec.containers[0].securityContext." + field + ") || "
                             "object.spec.containers[0].securityContext." + field + " == " + safe + ")")
        if "add" not in context.get("capabilities", {}):
            terms.append("(!has(object.spec.containers[0].securityContext.capabilities.add) || "
                         "object.spec.containers[0].securityContext.capabilities.add.size() == 0)")
        mounts = {volume["name"]: volume["hostPath"] for volume in pod.get("volumes", [])
                  if "hostPath" in volume}
        allowed = " || ".join("(v.name == " + literal(name) + " && "
                               + scalar_tree("v.hostPath", source) + ")" for name, source in mounts.items())
        terms.append("(!has(object.spec.volumes) || object.spec.volumes.all(v, "
                     "!has(v.hostPath)" + (" || (" + allowed + ")" if allowed else "") + "))")
        terms.append("object.spec.containers.all(c, !has(c.ports) || "
                     "c.ports.all(p, !has(p.hostPort) || p.hostPort == 0))")
        # Pin the privileged executable/argv, not just the image and UID/capability.
        if account in ("istio-cni", "ztunnel"):
            for field in ("command", "args", "env", "envFrom", "volumeMounts"):
                if field in container:
                    terms.append("has(object.spec.containers[0]." + field + ") && ("
                                 + scalar_tree("object.spec.containers[0]." + field, container[field]) + ")")
                else:
                    terms.append("!has(object.spec.containers[0]." + field + ")")
        if namespace == "hooshix-secrets":
            terms.append("has(object.spec.securityContext) && ("
                         + scalar_tree("object.spec.securityContext", pod["securityContext"]) + ")")
            terms.append("has(object.spec.automountServiceAccountToken) && "
                         "object.spec.automountServiceAccountToken == false")
        options.append("(" + " && ".join("(" + term + ")" for term in terms) + ")")
    result = policy("ValidatingPolicy", "hooshix-" + namespace + "-bootstrap-boundary", namespace,
                    [validation("platform bootstrap identity/security exception rejected",
                                "object.metadata.namespace != " + literal(namespace)
                                + " || (" + " || ".join(options) + ")")])
    # v1.18 merges clustered ValidatingPolicy selectors into one shared webhook.
    # Give both policies the SAME bounded selector so merge order cannot exclude
    # either namespace. Each CEL guard still enforces only its own namespace.
    result["spec"]["matchConstraints"]["namespaceSelector"] = {"matchExpressions": [{
        "key": "kubernetes.io/metadata.name", "operator": "In", "values": list(NAMESPACES)}]}
    # A plain "pods" match does not match the ephemeral-container update edge.
    # The same no-ephemeral-container boundary must cover that subresource too.
    result["spec"]["matchConstraints"]["resourceRules"][0]["resources"].append("pods/ephemeralcontainers")
    return result


def image_policy(namespace: str, images: list[str], revision: str) -> dict:
    result = policy("ImageValidatingPolicy", "hooshix-" + namespace + "-supply-chain", namespace, [
        validation("platform image signature verification failed", "images.containers.map(image, "
                   "verifyImageSignatures(image, [attestors.cosign])).all(e, e > 0)"),
        validation("platform provenance signature verification failed", "images.containers.map(image, "
                   "verifyAttestationSignatures(image, attestations.provenance, [attestors.cosign])).all(e, e > 0)"),
        validation("platform import source revision rejected", "images.containers.map(image, "
                   "extractPayload(image, attestations.provenance).predicate.buildDefinition.externalParameters.gitRevision == "
                   + literal(revision) + ").all(e, e)"),
        validation("platform signed SBOM verification failed", "images.containers.map(image, "
                   "verifyAttestationSignatures(image, attestations.sbom, [attestors.cosign])).all(e, e > 0)"),
        validation("platform signed SBOM is not CycloneDX", "images.containers.map(image, "
                   "extractPayload(image, attestations.sbom).predicate.bomFormat == 'CycloneDX').all(e, e)")])
    result["spec"].update({"webhookConfiguration": {"timeoutSeconds": 15},
        "credentials": {"secrets": [PULL_SECRET]},
        "matchImageReferences": [{"glob": image} for image in images],
        "validationConfigurations": {"mutateDigest": False, "required": True, "verifyDigest": True},
        "attestors": [{"name": "cosign", "cosign": {"keyless": {"identities": [{
            "subject": EXPECTED_CERTIFICATE_IDENTITY, "issuer": EXPECTED_OIDC_ISSUER}]},
            "ctlog": {"url": "https://rekor.sigstore.dev"}}}],
        "attestations": [{"name": "provenance", "intoto": {"type": "https://slsa.dev/provenance/v1"}},
                         {"name": "sbom", "intoto": {"type": "https://cyclonedx.org/bom"}}]})
    return result


def render(mesh_receipt: dict, bao_receipt: dict, mesh_candidate: dict) -> dict:
    if (mesh_candidate.get("installation_id") != "hooshix-production"
            or mesh_candidate.get("profile") != "production-single-server"
            or [s["component"] for s in mesh_candidate["stages"]] != list(mesh.COMPONENTS)):
        raise ValueError("reviewed mesh candidate required")
    candidate = copy.deepcopy(mesh_candidate)
    pods = {}
    for stage in candidate["stages"]:
        for resource in stage["manifest"]["items"]:
            if resource["kind"] not in ("Deployment", "DaemonSet"):
                continue
            pod = resource["spec"]["template"]["spec"]
            component = stage["component"]
            if pod["containers"][0]["image"] != mesh.values(component, mesh.pins())["image"]:
                raise ValueError("exact upstream template required")
            if component in ("cni", "ztunnel"):
                mesh.validate_node_exception(component, pod, mesh.pins())
            pod["containers"][0]["image"] = mesh_receipt["components"][component]["image"]
            pod["imagePullSecrets"] = [{"name": PULL_SECRET}]
            if component == "cni":
                pod["containers"][0]["securityContext"]["allowPrivilegeEscalation"] = False
            # Explicit RuntimeDefault for all platform pods, including the two
            # narrowly approved root network components. No Unconfined fallback.
            pod.setdefault("securityContext", {})["seccompProfile"] = {"type": "RuntimeDefault"}
            pods[pod["serviceAccountName"]] = pod
    openbao = bao.candidate("hooshix-openbao-local")
    bao_pod = openbao["items"][-1]["spec"]["template"]["spec"]
    bao_pod["containers"][0]["image"] = bao_receipt["image"]
    bao_pod["imagePullSecrets"] = [{"name": PULL_SECRET}]
    policies = [hardening("istio-system", pods),
                image_policy("istio-system", [p["containers"][0]["image"] for p in pods.values()],
                             mesh_receipt["repository_revision"]),
                hardening("hooshix-secrets", {"openbao": bao_pod}),
                image_policy("hooshix-secrets", [bao_receipt["image"]], bao_receipt["repository_revision"])]
    return {"schema_version": 1, "profile": "production-single-server",
            "installation_id": "hooshix-production", "mesh": candidate, "openbao": openbao,
            "admission_prerequisites": reporting_permissions(),
            "admission": {"apiVersion": "v1", "kind": "List", "items": policies},
            "runtime_admission": "Not verified", "staging": "Not verified", "deployment": "Not verified"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mesh-publication", type=Path, required=True)
    parser.add_argument("--openbao-publication", type=Path, required=True)
    args = parser.parse_args()
    try:
        now = datetime.now(timezone.utc)
        result = render(publication(args.mesh_publication, "mesh", now),
                        publication(args.openbao_publication, "openbao", now), mesh.candidate())
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except (ValueError, KeyError, TypeError, OSError):
        print("PLATFORM_ADMISSION=Failed; no cluster writes performed")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
