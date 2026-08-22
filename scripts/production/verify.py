#!/usr/bin/env python3
"""Static fail-closed verifier for production-single-server repository contracts."""
from __future__ import annotations
import json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PROFILE=ROOT/"infrastructure/production/profile.json"
BASELINE=ROOT/"docs/technology/technology-baseline.md"
EXPECTED={"platform.kubernetes":"1.35.6","platform.k3s":"v1.35.6+k3s1","platform.calico":"3.32.1","platform.istio_ambient":"1.30.3","platform.kyverno":"1.18.2","platform.argocd":"3.4.2","postgresql.postgresql":"18.4","postgresql.cloudnativepg":"1.30.0","postgresql.barman_plugin":"0.13.0","postgresql.cert_manager":"1.20.3","redis.version":"8.2.8","kafka.version":"4.2.1","secrets.openbao":"2.6.1","secrets.external_secrets_operator":"2.8.0","edge.traefik":"3.7.10","edge.traefik_chart":"41.2.0","edge.gateway_api":"1.5.1","observability.otel_collector":"0.157.0","observability.prometheus":"3.13.2","observability.alertmanager":"0.33.1","observability.loki":"3.7.4","observability.tempo":"3.0.2","observability.grafana":"13.1.3","supply_chain.cosign":"3.0.6","supply_chain.syft":"1.51.0","supply_chain.grype":"0.117.0"}
REQUIRED_INPUTS={"approved_public_hostname","application_release_digests","compromised_password_dataset_release","cosign_oidc_issuer","cosign_oidc_subject","external_blackbox_monitor","external_l4_source_cidrs","measured_service_capacity_values","offhost_security_audit_sink","offsite_postgresql_backup_target","openbao_unseal_custody","production_registry","production_tls_material","upstream_ddos_provider_evidence","wireguard_peer_inventory"}
def get(d,path):
    for part in path.split("."):
        if not isinstance(d,dict) or part not in d:return None
        d=d[part]
    return d
def add(e,ok,msg):
    if not ok:e.append(msg)
def validate_profile(d):
    e=[]
    add(e,d.get("schema_version")==1,"profile schema_version must be 1")
    add(e,d.get("profile")=="production-single-server","selected profile must be production-single-server")
    add(e,d.get("availability_claim")=="non-ha-single-host","single-server must not claim HA")
    for path,value in EXPECTED.items():add(e,get(d,path)==value,f"{path} must equal {value}")
    x=d.get("platform",{}); add(e,x.get("servers")==x.get("schedulable_workload_nodes")==1,"K3s topology must be one server/workload node"); add(e,x.get("embedded_datastore")=="sqlite" and x.get("secrets_encryption") is True,"K3s SQLite/secrets-encryption contract is invalid"); add(e,set(x.get("disabled_k3s_components",[]))=={"flannel","network-policy","servicelb","traefik"},"K3s bundled network/edge components must be disabled"); add(e,x.get("calico_dataplane")=="standard" and x.get("strict_mtls") is True,"Calico standard dataplane and strict Ambient mTLS are mandatory"); add(e,x.get("kyverno_policy_api")=="policies.kyverno.io/v1" and x.get("kyverno_enforcement")=="deny-fail-closed","Kyverno stable CEL v1 fail-closed enforcement is mandatory")
    add(e,d.get("workloads")=={"replicas":1,"hpa_enabled":False,"availability_pdb_enabled":False},"single-server replica/HPA/PDB contract is invalid")
    x=d.get("postgresql",{}); add(e,x.get("physical_clusters")==x.get("instances")==1,"PostgreSQL must use one shared physical one-instance CNPG cluster"); add(e,x.get("service_database_isolation") and x.get("runtime_migration_role_isolation"),"database/runtime/migration ownership isolation is mandatory"); add(e,x.get("application_pool_budget_percent_max")==70 and x.get("reserved_connections_percent_min")==30,"PostgreSQL 70/30 connection budget is mandatory"); add(e,x.get("continuous_wal_archive") and x.get("base_backup_minimum_frequency_hours")==24 and x.get("pitr_window_days")==35 and x.get("monthly_retained_artifact_months")==12 and x.get("monthly_isolated_restore") and x.get("quarterly_cold_dr"),"PostgreSQL WAL/PITR/restore/DR contract is incomplete")
    x=d.get("redis",{}); add(e,x.get("instances")==1 and x.get("tls_required") and x.get("acl_isolation") and x.get("maxmemory_policy")=="noeviction" and x.get("aof") and x.get("appendfsync")=="everysec","Redis single-server contract is invalid")
    x=d.get("kafka",{}); add(e,x.get("brokers")==x.get("controllers")==1 and x.get("combined_kraft_role") and x.get("replication_factor")==x.get("min_insync_replicas")==1 and x.get("producer_acks")=="all" and x.get("producer_idempotence") and not x.get("unclean_leader_election") and not x.get("business_source_of_truth"),"Kafka single-server KRaft/rebuildable-transport contract is invalid")
    x=d.get("secrets",{}); add(e,x.get("openbao_instances")==1 and x.get("storage")=="raft-pvc" and x.get("shamir_shares")==3 and x.get("shamir_threshold")==2 and x.get("encrypted_off_pvc_snapshot_frequency_hours")==1,"OpenBao topology/recovery contract is invalid"); add(e,x.get("secret_values_in_git") is False and x.get("application_hot_path_openbao_calls") is False,"OpenBao must not create Git secrets or request hot-path RPCs")
    x=d.get("edge",{}); add(e,x.get("proxy_protocol")=="v2" and x.get("proxy_protocol_insecure") is False and x.get("forwarded_headers_insecure") is False and x.get("exact_external_l4_cidr_trust_required") and x.get("direct_origin_internet_denied") and x.get("waf_required") and x.get("direct_bff_bypass_denied"),"public edge trust/bypass contract is invalid")
    x=d.get("human_access",{}); add(e,x.get("management_overlay")=="wireguard" and x.get("public_ssh_denied") and not x.get("root_login") and not x.get("password_authentication") and not x.get("keyboard_interactive_authentication") and not x.get("shared_keys") and x.get("fido2_required") and x.get("touch_required") and x.get("user_verification_required") and x.get("jit_write_minutes_max")==30 and x.get("jit_reviewers_min")==2 and x.get("off_host_audit_required"),"human production access contract is invalid")
    x=d.get("observability",{}); add(e,x.get("external_host_down_monitor_required") and not x.get("otlp_public_ingress") and not x.get("authoritative_audit_uses_ordinary_telemetry_only"),"observability failure-domain/security contract is invalid")
    x=d.get("supply_chain",{}); add(e,all(x.get(k) is True for k in ("immutable_digest_required","signature_required","provenance_required","signed_cyclonedx_sbom_required","exact_oidc_issuer_subject_required")),"production supply-chain admission contract is incomplete")
    add(e,set(d.get("required_external_inputs",[]))==REQUIRED_INPUTS,"required external production evidence/input contract is incomplete")
    return e
def validate_baseline():
    text=BASELINE.read_text(encoding="utf-8"); return [f"technology baseline does not contain {path} version {value}" for path,value in EXPECTED.items() if value not in text]
def validate_repository():
    try:d=json.loads(PROFILE.read_text(encoding="utf-8"))
    except (OSError,json.JSONDecodeError) as ex:return [f"cannot load production profile: {ex}"]
    return validate_profile(d)+validate_baseline()
def main():
    errors=validate_repository()
    if errors:
        print("Production infrastructure contract verification FAILED:")
        for error in errors:print(f"- {error}")
        return 1
    print("Production infrastructure contract verification PASSED."); return 0
if __name__=="__main__":sys.exit(main())
