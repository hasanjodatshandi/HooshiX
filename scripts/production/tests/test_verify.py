from __future__ import annotations
import copy,json,sys,unittest
from pathlib import Path
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
    def test_rescan_requires_precommissioning_inventory_guard(self):
        workflow=(verify.ROOT/".github/workflows/production-vulnerability-rescan.yml").read_text(encoding="utf-8")
        self.assertEqual([],verify.validate_rescan_workflow_contract(workflow))
        weakened=workflow.replace("if: steps.production_inventory.outputs.present == 'true'", "if: always()", 1)
        self.assertTrue(any("conditional on tracked inventory" in x for x in verify.validate_rescan_workflow_contract(weakened)))
if __name__=="__main__":unittest.main()
