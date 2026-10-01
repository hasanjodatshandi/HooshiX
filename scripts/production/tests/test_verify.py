from __future__ import annotations
import copy,json,sys,unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import verify
class ProductionProfileTest(unittest.TestCase):
    def setUp(self):self.profile=json.loads(verify.PROFILE.read_text(encoding="utf-8"))
    def test_repository_profile_passes(self):self.assertEqual([],verify.validate_repository())
    def test_rejects_false_ha_or_extra_replicas(self):
        d=copy.deepcopy(self.profile); d["availability_claim"]="ha"; d["workloads"]["replicas"]=2; e=verify.validate_profile(d); self.assertTrue(any("must not claim HA" in x for x in e)); self.assertTrue(any("replica/HPA/PDB" in x for x in e))
    def test_rejects_weakened_network_or_admission(self):
        d=copy.deepcopy(self.profile); d["platform"]["disabled_k3s_components"].remove("flannel"); d["platform"]["kyverno_enforcement"]="audit"; e=verify.validate_profile(d); self.assertTrue(any("bundled network/edge" in x for x in e)); self.assertTrue(any("fail-closed" in x for x in e))
    def test_rejects_weakened_data_durability(self):
        d=copy.deepcopy(self.profile); d["postgresql"]["continuous_wal_archive"]=False; d["redis"]["maxmemory_policy"]="allkeys-lru"; d["kafka"]["unclean_leader_election"]=True; e=verify.validate_profile(d); self.assertTrue(any("WAL/PITR" in x for x in e)); self.assertTrue(any("Redis" in x for x in e)); self.assertTrue(any("Kafka" in x for x in e))
    def test_rejects_edge_or_access_bypass(self):
        d=copy.deepcopy(self.profile); d["edge"]["proxy_protocol_insecure"]=True; d["human_access"]["public_ssh_denied"]=False; e=verify.validate_profile(d); self.assertTrue(any("edge trust" in x for x in e)); self.assertTrue(any("human production access" in x for x in e))
    def test_rejects_missing_external_evidence_contract(self):
        d=copy.deepcopy(self.profile); d["required_external_inputs"].remove("external_blackbox_monitor"); self.assertTrue(any("external production evidence" in x for x in verify.validate_profile(d)))
    def test_software_key_profile_retains_access_controls(self):
        self.assertFalse(self.profile["human_access"]["fido2_required"])
        for key, value in (("software_key_passphrase_required", False), ("software_key_algorithm", "ssh-rsa"), ("off_host_audit_required", False), ("jit_reviewers_min", 0), ("jit_reviewers_min", True), ("jit_write_minutes_max", 31), ("password_authentication", True), ("keyboard_interactive_authentication", True), ("root_login", True), ("shared_keys", True), ("touch_required", False), ("user_verification_required", False)):
            with self.subTest(key=key):
                d=copy.deepcopy(self.profile); d["human_access"][key]=value
                self.assertTrue(any("human production access" in x for x in verify.validate_profile(d)))
    def test_single_reviewer_policy_cannot_drift_from_host_contract(self):
        original_load = verify.load_json
        access_path = verify.PRODUCTION / "host/access-policy.json"
        for key, value in (("write_reviewers_min", 0), ("write_reviewers_min", 2), ("write_reviewers_min", True), ("write_minutes_max", 31), ("reason_or_ticket_required", False), ("standing_admin", True)):
            with self.subTest(key=key, value=value):
                def changed_load(path):
                    data = original_load(path)
                    if path == access_path:
                        data["jit"][key] = value
                    return data
                with patch.object(verify, "load_json", changed_load):
                    self.assertIn("production JIT host policy drifted", verify.validate_static_contracts(self.profile))
    def test_single_reviewer_exception_cannot_disable_audit(self):
        original_load = verify.load_json
        access_path = verify.PRODUCTION / "host/access-policy.json"
        for key, value in (("off_host_required", False), ("shell_history_authoritative", True), ("surfaces", ["sudo"])):
            with self.subTest(key=key):
                def changed_load(path):
                    data = original_load(path)
                    if path == access_path:
                        data["audit"][key] = value
                    return data
                with patch.object(verify, "load_json", changed_load):
                    self.assertIn("production privileged audit policy drifted", verify.validate_static_contracts(self.profile))
    def test_rescan_requires_precommissioning_inventory_guard(self):
        workflow=(verify.ROOT/".github/workflows/production-vulnerability-rescan.yml").read_text(encoding="utf-8")
        self.assertEqual([],verify.validate_rescan_workflow_contract(workflow))
        weakened=workflow.replace("if: steps.production_inventory.outputs.present == 'true'", "if: always()", 1)
        self.assertTrue(any("conditional on tracked inventory" in x for x in verify.validate_rescan_workflow_contract(weakened)))
    def test_forwarding_policy_cannot_omit_global_override_or_streamlocal_denial(self):
        original_read = Path.read_text
        sshd_path = verify.PRODUCTION / "host/sshd_config"
        for required in ("DisableForwarding yes", "AllowStreamLocalForwarding no", "X11Forwarding no"):
            with self.subTest(required=required):
                def changed_read(path, *args, **kwargs):
                    content = original_read(path, *args, **kwargs)
                    return content.replace(required + "\n", "") if path == sshd_path else content
                with patch.object(Path, "read_text", changed_read):
                    errors = verify.validate_static_contracts(self.profile)
                self.assertIn("sshd hardening missing: " + required, errors)
    def test_human_ssh_match_rejects_tunnel_scope_or_forwarding(self):
        original_read = Path.read_text
        match_path = verify.PRODUCTION / "host/sshd-human-match.tail"
        for old, new in (("Match User hooshixadmin", "Match User hooshixtunnel"),
                         ("AllowTcpForwarding no", "AllowTcpForwarding yes")):
            with self.subTest(old=old):
                def changed_read(path, *args, **kwargs):
                    content = original_read(path, *args, **kwargs)
                    return content.replace(old, new) if path == match_path else content
                with patch.object(Path, "read_text", changed_read):
                    errors = verify.validate_static_contracts(self.profile)
                self.assertIn("human SSH Match candidate must remain scoped and forwarding-denying", errors)
    def test_management_ssh_guard_rejects_scope_or_verdict_drift(self):
        original_read = Path.read_text
        guard_path = verify.PRODUCTION / "host/nftables-management-ssh.nft"
        for old, new in (
            ('iifname != "wg-hooshix"', 'iifname "wg-hooshix"'),
            (" } drop", " } accept"),
            ("priority -10", "priority 10"),
            ("22, 22022", "22, 2222"),
            ("destroy table inet hooshix_management_ssh_guard", "flush ruleset"),
        ):
            with self.subTest(old=old, new=new):
                def changed_read(path, *args, **kwargs):
                    content = original_read(path, *args, **kwargs)
                    return content.replace(old, new) if path == guard_path else content
                with patch.object(Path, "read_text", changed_read):
                    errors = verify.validate_static_contracts(self.profile)
                self.assertIn("management SSH guard must be the reviewed dedicated, scoped nftables transaction", errors)
if __name__=="__main__":unittest.main()
