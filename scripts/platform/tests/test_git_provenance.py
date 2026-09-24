from __future__ import annotations
import importlib.util, subprocess, tempfile, unittest
from pathlib import Path
REPO_ROOT=Path(__file__).resolve().parents[3]
MODULE_PATH=REPO_ROOT/"scripts/platform/git_provenance.py"
spec=importlib.util.spec_from_file_location("git_provenance",MODULE_PATH); assert spec and spec.loader
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
class GitProvenanceTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)
  subprocess.run(["git","init","-q"],cwd=self.root,check=True)
  subprocess.run(["git","config","user.email","test@example.invalid"],cwd=self.root,check=True)
  subprocess.run(["git","config","user.name","HooshiX Test"],cwd=self.root,check=True)
  (self.root/".gitignore").write_text("ignored.txt\n",encoding="utf-8")
  (self.root/"tracked.txt").write_text("one\n",encoding="utf-8")
  subprocess.run(["git","add",".gitignore","tracked.txt"],cwd=self.root,check=True)
  subprocess.run(["git","commit","-qm","fixture"],cwd=self.root,check=True)
 def tearDown(self): self.temp.cleanup()
 def test_clean_snapshot_and_ignored_file(self):
  first=module.snapshot(self.root); self.assertEqual("clean",first["source_state"])
  (self.root/"ignored.txt").write_text("runtime-only\n",encoding="utf-8")
  self.assertEqual(first,module.snapshot(self.root)); self.assertEqual(first,module.verify(self.root,first))
 def test_tracked_change_invalidates_snapshot(self):
  first=module.snapshot(self.root); (self.root/"tracked.txt").write_text("two\n",encoding="utf-8"); second=module.snapshot(self.root)
  self.assertEqual("dirty",second["source_state"]); self.assertNotEqual(first["worktree_sha256"],second["worktree_sha256"])
  with self.assertRaises(SystemExit): module.verify(self.root,first)
 def test_untracked_change_is_fingerprinted(self):
  (self.root/"new.txt").write_text("one\n",encoding="utf-8"); first=module.snapshot(self.root); self.assertEqual("dirty",first["source_state"])
  (self.root/"new.txt").write_text("two\n",encoding="utf-8"); self.assertNotEqual(first["worktree_sha256"],module.snapshot(self.root)["worktree_sha256"])
class StagingProvenanceWiringTest(unittest.TestCase):
 def test_build_deploy_verify_are_provenance_bound(self):
  build=(REPO_ROOT/"scripts/platform/staging_build_all.sh").read_text(); self.assertIn("BUILD_WORKTREE_SHA256",build); self.assertIn("images.env",build)
  for rel in ("staging_build_image.sh","staging_deploy_all.sh","staging_deploy_service.sh","staging_verify.sh"):
   text=(REPO_ROOT/"scripts/platform"/rel).read_text(); self.assertIn("git_provenance.py",text); self.assertIn("BUILD_GIT_REVISION",text); self.assertIn("BUILD_WORKTREE_SHA256",text)
 def test_staging_security_controls_precede_workloads(self):
  install=(REPO_ROOT/"scripts/platform/staging_data_install.sh").read_text()
  self.assertLess(install.index("networkpolicy.yaml"),install.index("data.yaml")); self.assertLess(install.index("authorizationpolicy.yaml"),install.index("data.yaml"))
  policy=(REPO_ROOT/"infrastructure/staging/networkpolicy.yaml").read_text(); self.assertIn("name: postgres-bootstrap",policy); self.assertIn("policyTypes: [Ingress, Egress]",policy)
  data=(REPO_ROOT/"infrastructure/staging/data.yaml").read_text()
  self.assertIn("apache/kafka:4.2.1@sha256:9916d60e",data)
  self.assertIn("KAFKA_AUTO_CREATE_TOPICS_ENABLE, value: \"false\"",data)
  self.assertIn("KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE, value: \"false\"",data)
  self.assertIn("readOnlyRootFilesystem: true",data)
  authorization=(REPO_ROOT/"infrastructure/staging/authorizationpolicy.yaml").read_text()
  self.assertIn("name: kafka",authorization)
  self.assertIn("sa/notification-service",authorization)
  for text,needle in ((policy,"app.kubernetes.io/name: conversation-service"),(authorization,"sa/conversation-service")):
   documents=text.split("\n---\n")
   by_name={next(line for line in document.splitlines() if line.startswith("metadata: {name:")): document for document in documents}
   self.assertIn(needle,by_name["metadata: {name: postgresql, namespace: platform-data}"])
   self.assertIn(needle,by_name["metadata: {name: kafka, namespace: platform-data}"])
   self.assertNotIn(needle,by_name["metadata: {name: security-redis, namespace: platform-data}"])
 def test_identity_staging_binds_every_required_key_ring(self):
  values=(REPO_ROOT/"deploy/staging/identity-service.yaml").read_text()
  for secret in ("identity-fingerprint","identity-challenge","identity-handoff","identity-mfa","identity-quota","identity-refresh","identity-jwt-private"):
   self.assertIn(secret,values)
 def test_web_bff_staging_owns_distinct_database_credentials(self):
  values=(REPO_ROOT/"deploy/staging/web-bff.yaml").read_text()
  for value in ("/web_bff","web-bff-db-migration","web-bff-db-runtime"):
   self.assertIn(value,values)
  secrets=(REPO_ROOT/"scripts/platform/staging_secrets_apply.sh").read_text()
  self.assertEqual(2,secrets.count("web-bff-db-migration")); self.assertEqual(2,secrets.count("web-bff-db-runtime"))
 def test_notification_provider_secret_is_optional_and_mode_checked(self):
  secrets=(REPO_ROOT/"scripts/platform/staging_secrets_apply.sh").read_text()
  self.assertIn("private/notification-providers.properties",secrets)
  self.assertIn("regular non-symlink file",secrets)
  self.assertIn("parent must be user-owned mode 0700",secrets)
  self.assertIn("must have mode 0600",secrets)
  self.assertIn('--from-file="providers.properties=$provider_file"',secrets)
 def test_google_oidc_secret_is_optional_and_mode_checked(self):
  secrets=(REPO_ROOT/"scripts/platform/staging_secrets_apply.sh").read_text()
  self.assertIn("private/web-bff-google-client-secret",secrets)
  self.assertIn('verify_private_file "$google_oidc_secret" "staging Google OIDC client secret"',secrets)
  self.assertIn('--from-file="client-secret=$google_oidc_secret"',secrets)
 def test_staging_erasure_runtime_uses_service_specific_kafka_secrets(self):
  for service in ("authorization-service","identity-service","notification-service","web-bff"):
   values=(REPO_ROOT/"deploy/staging"/f"{service}.yaml").read_text()
   self.assertIn("erasureRuntimeEnabled: true",values)
   self.assertIn(f"connectionSecretName: {service.removesuffix('-service')}-kafka",values)
  secrets=(REPO_ROOT/"scripts/platform/staging_secrets_apply.sh").read_text()
  for name in ("authorization-kafka","identity-kafka","notification-kafka","web-bff-kafka"):
   self.assertIn(name,secrets)
 def test_conversation_is_wired_through_waypoint_and_authorization(self):
  waypoint=(REPO_ROOT/"infrastructure/istio/platform-apps-waypoint-networkpolicy.yaml").read_text()
  self.assertEqual(2,waypoint.count("app.kubernetes.io/name: conversation-service"))
  conversation=(REPO_ROOT/"services/conversation-service/deploy/helm/conversation-service/templates/networkpolicy.yaml").read_text()
  self.assertGreaterEqual(conversation.count("gateway.networking.k8s.io/gateway-name"),2)
  authorization=(REPO_ROOT/"deploy/staging/authorization-service.yaml").read_text()
  for value in ("resourceCallers:","app.kubernetes.io/name: conversation-service","sa/conversation-service","callerId: conversation-service"):
   self.assertIn(value,authorization)
  conversation_values=(REPO_ROOT/"deploy/staging/conversation-service.yaml").read_text()
  self.assertIn("providerRuntimeEnabled: false",conversation_values)
  self.assertIn("providerCanaryPercent: 0",conversation_values)
  verifier=(REPO_ROOT/"scripts/platform/staging_verify.sh").read_text()
  self.assertIn('false/0 ]] || fail "Conversation safe-disabled runtime mismatch',verifier)
if __name__=="__main__": unittest.main()
