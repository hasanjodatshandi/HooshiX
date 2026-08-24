#!/usr/bin/env python3
"""Static fail-closed verifier for production-single-server repository contracts."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRODUCTION = ROOT / "infrastructure/production"
PROFILE = PRODUCTION / "profile.json"
BASELINE = ROOT / "docs/technology/technology-baseline.md"

EXPECTED = {
    "platform.kubernetes": "1.35.6", "platform.k3s": "v1.35.6+k3s1",
    "platform.calico": "3.32.1", "platform.istio_ambient": "1.30.3",
    "platform.kyverno": "1.18.2", "platform.argocd": "3.4.2",
    "postgresql.postgresql": "18.4", "postgresql.cloudnativepg": "1.30.0",
    "postgresql.barman_plugin": "0.13.0", "postgresql.cert_manager": "1.20.3",
    "redis.version": "8.2.8", "kafka.version": "4.2.1", "secrets.openbao": "2.6.1",
    "secrets.external_secrets_operator": "2.8.0", "edge.traefik": "3.7.10",
    "edge.traefik_chart": "41.2.0", "edge.gateway_api": "1.5.1",
    "observability.otel_collector": "0.157.0", "observability.prometheus": "3.13.2",
    "observability.alertmanager": "0.33.1", "observability.loki": "3.7.4",
    "observability.tempo": "3.0.2", "observability.grafana": "13.1.3",
    "supply_chain.cosign": "3.0.6", "supply_chain.syft": "1.51.0",
    "supply_chain.grype": "0.117.0",
}
REQUIRED_INPUTS = {
    "approved_public_hostname", "application_release_digests", "compromised_password_dataset_release",
    "cosign_oidc_issuer", "cosign_oidc_subject", "external_blackbox_monitor",
    "external_l4_source_cidrs", "measured_service_capacity_values", "offhost_security_audit_sink",
    "offsite_postgresql_backup_target", "openbao_unseal_custody", "production_registry",
    "production_tls_material", "upstream_ddos_provider_evidence", "wireguard_peer_inventory",
}
REQUIRED_FILES = (
    "README.md", "profile.json", "platform-contracts.json",
    "release-manifest.schema.json", "release-tools.env", "host/access-policy.json",
    "host/sshd_config", "k3s/config.yaml", "data/data-policy.json", "network/trust-policy.json",
    "observability/observability-policy.json", "recovery/recovery-policy.json",
    "release/release-policy.json", "secrets/secrets-policy.json", "gitops/application.yaml",
)
REQUIRED_SCRIPTS = (
    ROOT / "scripts/production/verify_release.py", ROOT / "scripts/production/readiness.py",
    ROOT / "scripts/production/render_gitops.py", ROOT / "scripts/production/release_supply_chain.sh",
    ROOT / "scripts/production/build_provenance.py", ROOT / "scripts/production/install_release_tools.sh",
    ROOT / ".github/workflows/production-release.yml",
    ROOT / ".github/workflows/production-vulnerability-rescan.yml",
)
RELEASE_TOOL_PINS = {
    "SYFT_VERSION": "1.51.0",
    "SYFT_LINUX_AMD64_SHA256": "2a2e837a2c8d59ec9af5472ee22d3b04ee463c4e44476ecf993fd1e5ab6ebc7f",
    "GRYPE_VERSION": "0.117.0",
    "GRYPE_LINUX_AMD64_SHA256": "38525dab1e06f162ebaa02f94d82d1f807076b011a44180cf2777edf1a7b9c26",
    "COSIGN_VERSION": "3.0.6",
    "COSIGN_LINUX_AMD64_SHA256": "c956e5dfcac53d52bcf058360d579472f0c1d2d9b69f55209e256fe7783f4c74",
}

def get(data: object, path: str) -> object:
    for part in path.split("."):
        if not isinstance(data, dict) or part not in data: return None
        data = data[part]
    return data

def add(errors: list[str], condition: bool, message: str) -> None:
    if not condition: errors.append(message)

def load_json(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict): raise ValueError(f"{path.relative_to(ROOT)} must contain a JSON object")
    return data

def validate_profile(data: dict) -> list[str]:
    errors: list[str] = []
    add(errors, data.get("schema_version") == 1, "profile schema_version must be 1")
    add(errors, data.get("profile") == "production-single-server", "selected profile must be production-single-server")
    add(errors, data.get("availability_claim") == "non-ha-single-host", "single-server must not claim HA")
    for path, value in EXPECTED.items(): add(errors, get(data, path) == value, f"{path} must equal {value}")
    platform = data.get("platform", {})
    add(errors, platform.get("servers") == platform.get("schedulable_workload_nodes") == 1, "K3s topology must be one server/workload node")
    add(errors, platform.get("embedded_datastore") == "sqlite" and platform.get("secrets_encryption") is True, "K3s SQLite/secrets-encryption contract is invalid")
    add(errors, set(platform.get("disabled_k3s_components", [])) == {"flannel","network-policy","servicelb","traefik"}, "K3s bundled network/edge components must be disabled")
    add(errors, platform.get("calico_dataplane") == "standard" and platform.get("strict_mtls") is True, "Calico standard dataplane and strict Ambient mTLS are mandatory")
    add(errors, platform.get("kyverno_policy_api") == "policies.kyverno.io/v1" and platform.get("kyverno_enforcement") == "deny-fail-closed", "Kyverno stable CEL v1 fail-closed enforcement is mandatory")
    add(errors, data.get("workloads") == {"replicas":1,"hpa_enabled":False,"availability_pdb_enabled":False}, "single-server replica/HPA/PDB contract is invalid")
    pg = data.get("postgresql", {})
    add(errors, pg.get("physical_clusters") == pg.get("instances") == 1, "PostgreSQL must use one shared physical one-instance CNPG cluster")
    add(errors, pg.get("service_database_isolation") and pg.get("runtime_migration_role_isolation"), "database/runtime/migration ownership isolation is mandatory")
    add(errors, pg.get("application_pool_budget_percent_max") == 70 and pg.get("reserved_connections_percent_min") == 30, "PostgreSQL 70/30 connection budget is mandatory")
    add(errors, pg.get("continuous_wal_archive") and pg.get("base_backup_minimum_frequency_hours") == 24 and pg.get("pitr_window_days") == 35 and pg.get("monthly_retained_artifact_months") == 12 and pg.get("monthly_isolated_restore") and pg.get("quarterly_cold_dr"), "PostgreSQL WAL/PITR/restore/DR contract is incomplete")
    redis = data.get("redis", {})
    add(errors, redis.get("instances") == 1 and redis.get("tls_required") and redis.get("acl_isolation") and redis.get("maxmemory_policy") == "noeviction" and redis.get("aof") and redis.get("appendfsync") == "everysec", "Redis single-server contract is invalid")
    kafka = data.get("kafka", {})
    add(errors, kafka.get("brokers") == kafka.get("controllers") == 1 and kafka.get("combined_kraft_role") and kafka.get("replication_factor") == kafka.get("min_insync_replicas") == 1 and kafka.get("producer_acks") == "all" and kafka.get("producer_idempotence") and not kafka.get("unclean_leader_election") and not kafka.get("business_source_of_truth"), "Kafka single-server KRaft/rebuildable-transport contract is invalid")
    secrets = data.get("secrets", {})
    add(errors, secrets.get("openbao_instances") == 1 and secrets.get("storage") == "raft-pvc" and secrets.get("shamir_shares") == 3 and secrets.get("shamir_threshold") == 2 and secrets.get("encrypted_off_pvc_snapshot_frequency_hours") == 1, "OpenBao topology/recovery contract is invalid")
    add(errors, secrets.get("secret_values_in_git") is False and secrets.get("application_hot_path_openbao_calls") is False, "OpenBao must not create Git secrets or request hot-path RPCs")
    edge = data.get("edge", {})
    add(errors, edge.get("proxy_protocol") == "v2" and edge.get("proxy_protocol_insecure") is False and edge.get("forwarded_headers_insecure") is False and edge.get("exact_external_l4_cidr_trust_required") and edge.get("direct_origin_internet_denied") and edge.get("waf_required") and edge.get("direct_bff_bypass_denied"), "public edge trust/bypass contract is invalid")
    access = data.get("human_access", {})
    add(errors, access.get("management_overlay") == "wireguard" and access.get("public_ssh_denied") and not access.get("root_login") and not access.get("password_authentication") and not access.get("keyboard_interactive_authentication") and not access.get("shared_keys") and access.get("fido2_required") and access.get("touch_required") and access.get("user_verification_required") and access.get("jit_write_minutes_max") == 30 and access.get("jit_reviewers_min") == 2 and access.get("off_host_audit_required"), "human production access contract is invalid")
    obs = data.get("observability", {})
    add(errors, obs.get("external_host_down_monitor_required") and not obs.get("otlp_public_ingress") and not obs.get("authoritative_audit_uses_ordinary_telemetry_only"), "observability failure-domain/security contract is invalid")
    supply = data.get("supply_chain", {})
    add(errors, all(supply.get(k) is True for k in ("immutable_digest_required","signature_required","provenance_required","signed_cyclonedx_sbom_required","exact_oidc_issuer_subject_required")), "production supply-chain admission contract is incomplete")
    add(errors, set(data.get("required_external_inputs", [])) == REQUIRED_INPUTS, "required external production evidence/input contract is incomplete")
    return errors

def validate_baseline() -> list[str]:
    text = BASELINE.read_text(encoding="utf-8")
    return [f"technology baseline does not contain {path} version {value}" for path,value in EXPECTED.items() if value not in text]

def parse_env(path: Path) -> dict[str,str]:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"): continue
        if "=" not in line: raise ValueError(f"invalid release-tools line: {line}")
        key,value = line.split("=",1); values[key] = value
    return values

def validate_static_contracts(profile: dict) -> list[str]:
    errors: list[str] = []
    for relative in REQUIRED_FILES: add(errors, (PRODUCTION/relative).is_file(), f"missing production artifact: infrastructure/production/{relative}")
    for script in REQUIRED_SCRIPTS: add(errors, script.is_file(), f"missing production verifier/tool: {script.relative_to(ROOT)}")
    if errors: return errors
    platform = load_json(PRODUCTION/"platform-contracts.json")
    recovery = load_json(PRODUCTION/"recovery/recovery-policy.json")
    access = load_json(PRODUCTION/"host/access-policy.json")
    data = load_json(PRODUCTION/"data/data-policy.json")
    network = load_json(PRODUCTION/"network/trust-policy.json")
    observability = load_json(PRODUCTION/"observability/observability-policy.json")
    secrets = load_json(PRODUCTION/"secrets/secrets-policy.json")
    release = load_json(PRODUCTION/"release/release-policy.json")
    schema = load_json(PRODUCTION/"release-manifest.schema.json")
    combined = {"platform":platform.get("platform",{}),"admission":platform.get("admission",{}),"gitops":platform.get("gitops",{}),"postgresql":data.get("postgresql",{}),"redis":data.get("redis",{}),"kafka":data.get("kafka",{}),"secrets":{"openbao":secrets.get("openbao",{}).get("version"),"external_secrets_operator":secrets.get("external_secrets_operator",{}).get("version")},"edge":platform.get("edge",{}),"observability":platform.get("observability",{}),"release":platform.get("release",{})}
    crosswalk = {"platform.kubernetes":"platform.kubernetes","platform.k3s":"platform.k3s","platform.calico":"platform.calico","platform.istio_ambient":"platform.istio_ambient","platform.kyverno":"admission.kyverno","platform.argocd":"gitops.argocd","postgresql.postgresql":"postgresql.version","postgresql.cloudnativepg":"postgresql.cloudnativepg","postgresql.barman_plugin":"postgresql.barman_plugin","redis.version":"redis.version","kafka.version":"kafka.version","secrets.openbao":"secrets.openbao","secrets.external_secrets_operator":"secrets.external_secrets_operator","edge.traefik":"edge.traefik","edge.traefik_chart":"edge.traefik_chart","edge.gateway_api":"edge.gateway_api","observability.otel_collector":"observability.otel_collector","observability.prometheus":"observability.prometheus","observability.alertmanager":"observability.alertmanager","observability.loki":"observability.loki","observability.tempo":"observability.tempo","observability.grafana":"observability.grafana","supply_chain.syft":"release.syft","supply_chain.grype":"release.grype","supply_chain.cosign":"release.cosign"}
    for profile_path, contract_path in crosswalk.items(): add(errors, get(profile,profile_path) == get(combined,contract_path), f"production contract drift: {profile_path} != {contract_path}")
    add(errors, recovery.get("postgresql",{}).get("rpo_minutes_max") == 5 and recovery.get("platform",{}).get("rto_minutes_max") == 240, "production RPO/RTO recovery contract is invalid")
    add(errors, access.get("management_overlay") == "wireguard" and access.get("public_ssh_denied") is True, "production management access must be WireGuard-only with public SSH denied")
    add(errors, network.get("public_path") == ["internet","upstream-ddos","external-l4","traefik","edge-waf","web-bff"], "production public edge path is invalid")
    add(errors, observability.get("external_host_down_monitor_required") is True and observability.get("authoritative_privileged_audit_separate") is True, "production observability failure-domain contract is invalid")
    add(errors, release.get("grype_severities_blocking") == ["Critical","High"] and release.get("signed_sbom_attestation_required") is True, "production final-artifact release policy is invalid")
    required_schema = set(schema.get("required",[]))
    add(errors, {"git_revision","images","cosign","external_l4_source_cidrs","capacity_evidence","service_capacity","compromised_password_dataset","external_evidence","secret_refs"} <= required_schema, "production release manifest schema is missing mandatory evidence fields")
    k3s = (PRODUCTION/"k3s/config.yaml").read_text(encoding="utf-8")
    for line in ("secrets-encryption: true","flannel-backend: none","disable-network-policy: true","  - servicelb","  - traefik"): add(errors, line in k3s, f"K3s config missing required setting: {line.strip()}")
    add(errors, "protect-kernel-defaults: true" in k3s, "K3s must protect kernel defaults")
    sshd = (PRODUCTION/"host/sshd_config").read_text(encoding="utf-8")
    for line in ("PermitRootLogin no","PasswordAuthentication no","KbdInteractiveAuthentication no","PubkeyAuthentication yes","PubkeyAcceptedAlgorithms sk-ssh-ed25519@openssh.com,sk-ecdsa-sha2-nistp256@openssh.com","PubkeyAuthOptions touch-required verify-required","AuthenticationMethods publickey","AllowAgentForwarding no","AllowTcpForwarding no","PermitTunnel no","GatewayPorts no"): add(errors, line in sshd, f"sshd hardening missing: {line}")
    argocd = (PRODUCTION/"gitops/application.yaml").read_text(encoding="utf-8")
    add(errors, "repoURL: https://github.com/hasanjodatshandi/HooshiX.git" in argocd and "targetRevision: main" in argocd and "path: deploy/clusters/production" in argocd, "Argo CD production root must reconcile reviewed HooshiX main desired state")
    add(errors, "Prune=confirm" in argocd and "allowEmpty: false" in argocd, "Argo CD production prune/empty safety contract is invalid")
    add(errors, parse_env(PRODUCTION/"release-tools.env") == RELEASE_TOOL_PINS, "production Syft/Grype/Cosign versions or official checksums drifted")
    release_workflow = (ROOT/".github/workflows/production-release.yml").read_text(encoding="utf-8")
    rescan_workflow = (ROOT/".github/workflows/production-vulnerability-rescan.yml").read_text(encoding="utf-8")
    upload_pin = "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"
    add(errors, upload_pin in release_workflow and "retention-days: 90" in release_workflow, "production release evidence must be retained by the pinned immutable artifact action")
    add(errors, "cron: '23 */2 * * *'" in rescan_workflow and "grype db update" in rescan_workflow and "--fail-on high" in rescan_workflow, "production deployed-digest rescan must run at least every two hours and fail closed on High/Critical findings")
    add(errors, upload_pin in rescan_workflow and "retention-days: 90" in rescan_workflow, "production rescan evidence must be retained by the pinned immutable artifact action")
    errors.extend(validate_rescan_workflow_contract(rescan_workflow))
    scan_paths = list(PRODUCTION.rglob("*.yaml")) + list(PRODUCTION.rglob("*.yml")) + list((ROOT/"deploy/clusters/production").rglob("*.yaml"))
    for path in scan_paths:
        text = path.read_text(encoding="utf-8")
        add(errors, "-----BEGIN PRIVATE KEY-----" not in text, f"private key material is prohibited: {path.relative_to(ROOT)}")
        if re.search(r"(?m)^apiVersion:\s*kyverno\.io/v1\s*$", text) and re.search(r"(?m)^kind:\s*(ClusterPolicy|Policy)\s*$", text): errors.append(f"legacy Kyverno policy API is prohibited: {path.relative_to(ROOT)}")
        if re.search(r"(?m)^apiVersion:\s*kyverno\.io/v2\s*$", text) and re.search(r"(?m)^kind:\s*(CleanupPolicy|ClusterCleanupPolicy)\s*$", text): errors.append(f"legacy Kyverno cleanup API is prohibited: {path.relative_to(ROOT)}")
        if re.search(r"(?m)^kind:\s*Secret\s*$", text): errors.append(f"production Git must not contain Kubernetes Secret value manifests: {path.relative_to(ROOT)}")
    return errors

def validate_rescan_workflow_contract(text: str) -> list[str]:
    errors: list[str] = []
    inventory = "- name: Detect tracked production inventory"
    install = "- name: Install pinned release tools"
    credentials = "- name: Configure protected registry credentials"
    rescan = "- name: Rescan tracked production digests with fresh Grype data"
    guard = "if: steps.production_inventory.outputs.present == 'true'"
    no_inventory = "No tracked production release manifests exist; no deployed digest inventory is claimed."
    add(errors, "id: production_inventory" in text and "present=false" in text and "present=true" in text,
        "production rescan must expose an explicit tracked-inventory decision")
    add(errors, no_inventory in text and "no-production-inventory.txt" in text,
        "production rescan must retain explicit no-inventory evidence before commissioning")
    newline_printf = "printf '%s" + chr(92) + "n'"
    add(errors, text.count(newline_printf) >= 2,
        "production rescan evidence lines must use literal newline printf formatting")
    try:
        positions = [text.index(marker) for marker in (inventory, install, credentials, rescan)]
        add(errors, positions == sorted(positions),
            "production rescan must detect inventory before tools, registry credentials, and image scanning")
    except ValueError:
        errors.append("production rescan workflow is missing a required inventory/tool/credential/scan step")
    add(errors, text.count(guard) >= 3,
        "production rescan tools, registry credentials, and digest scanning must be conditional on tracked inventory")
    return errors

def validate_repository() -> list[str]:
    try:
        profile = load_json(PROFILE)
        return validate_profile(profile) + validate_baseline() + validate_static_contracts(profile)
    except (OSError,json.JSONDecodeError,ValueError) as exc:
        return [f"cannot load production repository contract: {exc}"]

def main() -> int:
    errors = validate_repository()
    if errors:
        print("Production infrastructure contract verification FAILED:")
        for error in errors: print(f"- {error}")
        return 1
    print("Production infrastructure contract verification PASSED.")
    return 0

if __name__ == "__main__": sys.exit(main())
