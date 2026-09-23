from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


class DeterministicBootstrapTest(unittest.TestCase):
    def test_kyverno_linux_amd64_images_are_pinned_and_mirrored_by_digest(self) -> None:
        pins = {}
        for line in (ROOT / "infrastructure/kyverno/pins.env").read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                pins[key] = value

        amd64_keys = (
            "KYVERNO_ADMISSION_AMD64_DIGEST",
            "KYVERNO_PRE_AMD64_DIGEST",
            "KYVERNO_BACKGROUND_AMD64_DIGEST",
            "KYVERNO_CLEANUP_AMD64_DIGEST",
            "KYVERNO_REPORTS_AMD64_DIGEST",
        )
        for key in amd64_keys:
            self.assertRegex(pins.get(key, ""), DIGEST_RE, key)

        installer = (ROOT / "scripts/platform/kyverno_install.sh").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/platform/kyverno_verify.sh").read_text(encoding="utf-8")
        self.assertIn("KYVERNO_LOCAL_REPOSITORY_PREFIX=localhost:5001/hooshix/vendor/kyverno", installer)
        self.assertIn("timeout 120s docker pull --platform linux/amd64", installer)
        self.assertIn("timeout 120s docker push", installer)
        self.assertIn('[[ "$mirrored" == "${repository}@${digest}" ]]', installer)
        self.assertIn("kubernetes.io/service-name=kyverno-svc", installer)
        self.assertIn("--dry-run=server", installer)
        self.assertIn("timeout 10s kubectl", installer)
        self.assertIn('[[ "$webhook_ready" -eq 1 ]]', installer)
        self.assertNotIn("kind load docker-image", installer)
        self.assertIn("localhost:5001/hooshix/vendor/kyverno", verifier)
        for key in amd64_keys:
            self.assertIn(key, installer)
            self.assertIn(key, verifier)

    def test_calico_host_pull_is_platform_specific_and_bounded(self) -> None:
        creator = (ROOT / "scripts/platform/kind_create.sh").read_text(encoding="utf-8")
        self.assertIn("timeout 120s docker pull --platform linux/amd64", creator)
        self.assertIn("CALICO_CNI_AMD64_DIGEST", creator)
        self.assertIn("CALICO_NODE_AMD64_DIGEST", creator)
        self.assertIn("CALICO_CONTROLLERS_AMD64_DIGEST", creator)
        self.assertIn("ETCD_TMPFS_PARENT=/dev/shm/hooshix-kind", creator)
        self.assertIn("ETCD_TMPFS_DIR=$ETCD_TMPFS_PARENT/etcd", creator)
        self.assertIn('-v "$ETCD_TMPFS_PARENT:/hooshix-kind"', creator)
        self.assertIn('find /hooshix-kind -mindepth 1 -delete', creator)
        self.assertIn('chown "$HOST_UID:$HOST_GID" /hooshix-kind', creator)
        self.assertIn('rmdir "$ETCD_TMPFS_PARENT"', creator)
        self.assertNotIn('-v /dev/shm:', creator)

    def test_kind_operator_authority_and_tmpfs_mount_are_verified(self) -> None:
        verifier = (ROOT / "scripts/platform/kind_verify.sh").read_text(encoding="utf-8")
        cluster = (ROOT / "infrastructure/kind/cluster.yaml").read_text(encoding="utf-8")
        staging = (ROOT / "scripts/platform/staging_verify.sh").read_text(encoding="utf-8")
        self.assertIn("auth can-i", verifier)
        self.assertIn("kind operator context lacks expected local cluster-admin authority", verifier)
        self.assertIn("hostPath: /dev/shm/hooshix-kind/etcd", cluster)
        self.assertIn("/dev/shm/hooshix-kind/etcd", staging)

    def test_staging_flyway_counts_match_owned_migrations(self) -> None:
        verifier = (ROOT / "scripts/platform/staging_verify.sh").read_text(encoding="utf-8")
        match = re.search(r"for spec in (.*?); do set -- \$spec", verifier)
        self.assertIsNotNone(match)
        declared = {
            database: int(count)
            for database, count, _owner in re.findall(r"'([a-z_]+) ([0-9]+) ([a-z_]+)'", match.group(1))
        }
        service_for_database = {
            "authorization": "authorization-service",
            "conversation": "conversation-service",
            "identity": "identity-service",
            "notification": "notification-service",
            "web_bff": "web-bff",
        }
        self.assertEqual(set(declared), set(service_for_database))
        for database, service in service_for_database.items():
            migrations = (ROOT / "services" / service / "src/main/resources/db/migration").glob("V*.sql")
            self.assertEqual(declared[database], sum(1 for _migration in migrations), database)

    def test_pre_edge_istio_verify_is_foundation_only(self) -> None:
        istio = (ROOT / "scripts/platform/istio_verify.sh").read_text(encoding="utf-8")
        edge = (ROOT / "scripts/platform/edge_verify.sh").read_text(encoding="utf-8")
        platform = (ROOT / "scripts/platform/platform_verify.sh").read_text(encoding="utf-8")
        self.assertIn("istio_foundation_verify.sh", istio)
        self.assertNotIn("mesh_identity_verify.sh", istio)
        self.assertIn("mesh_identity_verify.sh", edge)
        self.assertIn("edge_verify.sh", platform)

    def test_waf_privacy_tests_are_required_by_image_and_ci(self) -> None:
        dockerfile = (ROOT / "infrastructure/waf/Dockerfile").read_text(encoding="utf-8")
        policy = (ROOT / "infrastructure/waf/Caddyfile").read_text(encoding="utf-8")
        smoke = (ROOT / "scripts/platform/waf_image_smoke.py").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/repository-baseline.yml").read_text(encoding="utf-8")
        self.assertIn("go test -mod=readonly -run '^TestHooshix'", dockerfile)
        self.assertIn("h1:zdK/o14duUII/UCeJo5yozN1MhinASGULrJgQT+GIG4=", dockerfile)
        self.assertIn("git apply --check", dockerfile)
        self.assertIn("SecAuditEngine Off", policy)
        self.assertIn("SecDebugLogLevel 0", policy)
        self.assertIn("SecRuleEngine On", policy)
        self.assertLess(policy.index("opaque-cookie-exclusions.conf"), policy.index("rules/*.conf"))
        self.assertIn('"--network", "none"', smoke)
        self.assertNotIn('"--publish"', smoke)
        self.assertIn("- edge-waf-security", workflow)
        self.assertIn("${{ needs.edge-waf-security.result }}", workflow)
        self.assertIn('if [ "${EDGE_WAF_RESULT}" != \'success\' ]', workflow)

    def test_waf_build_normalizes_layer_timestamps(self) -> None:
        dockerfile = (ROOT / "infrastructure/waf/Dockerfile").read_text(encoding="utf-8")
        builder = (ROOT / "scripts/platform/waf_build.sh").read_text(encoding="utf-8")
        pins = (ROOT / "infrastructure/waf/pins.env").read_text(encoding="utf-8")
        self.assertIn("ARG SOURCE_DATE_EPOCH", dockerfile)
        self.assertIn('touch -h -d "@${SOURCE_DATE_EPOCH}" /usr/bin/caddy', dockerfile)
        self.assertIn('find /tmp/crs -exec touch -h -d "@${SOURCE_DATE_EPOCH}" {} +', dockerfile)
        self.assertRegex(pins, r"(?m)^WAF_SOURCE_DATE_EPOCH=[1-9][0-9]*$")
        self.assertIn('--build-arg "SOURCE_DATE_EPOCH=$WAF_SOURCE_DATE_EPOCH"', builder)
        self.assertIn("rewrite-timestamp=true,unpack=false", builder)

    def test_traefik_access_log_uses_chart_41_allowlist(self) -> None:
        values = (ROOT / "infrastructure/traefik/values-local.yaml").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/platform/edge_foundation_verify.sh").read_text(encoding="utf-8")
        access = values.split("accessLog:", 1)[1].split("resources:", 1)[0]
        self.assertIn("log:\n  format: json\n  level: INFO", values)
        self.assertNotIn("general:", access)
        self.assertIn("fields:\n    defaultMode: drop", access)
        self.assertIn("queryParameters:\n      defaultMode: drop", access)
        for field in ("RequestPath", "ClientAddr", "ClientHost", "RequestHost", "ClientUsername"):
            self.assertNotIn(field, access)
        self.assertIn("--accesslog.fields.defaultmode=drop", verifier)
        self.assertIn("--accesslog.fields.queryparameters.defaultmode=drop", verifier)

    def test_single_node_traefik_update_does_not_surge_host_ports(self) -> None:
        values = (ROOT / "infrastructure/traefik/values-local.yaml").read_text(encoding="utf-8")
        self.assertIn("maxSurge: 0", values)
        self.assertIn("maxUnavailable: 1", values)
        self.assertIn("replicas: 1", values)

    def test_traefik_restart_retains_exact_api_watch_egress(self) -> None:
        policy = (ROOT / "infrastructure/traefik/networkpolicy.yaml").read_text(encoding="utf-8")
        install = (ROOT / "scripts/platform/edge_install.sh").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/platform/edge_foundation_verify.sh").read_text(encoding="utf-8")
        edge = (ROOT / "scripts/platform/edge_verify.sh").read_text(encoding="utf-8")
        restart = (ROOT / "scripts/platform/edge_restart_verify.sh").read_text(encoding="utf-8")
        self.assertNotIn("0.0.0.0/0", policy)
        self.assertIn("traefik-kube-api-egress", install)
        self.assertIn('ipaddress.ip_address(addresses[0])', install)
        self.assertIn('f"{address}/32 {port}"', install)
        self.assertLess(install.index("traefik-kube-api-egress"), install.index("h upgrade"))
        self.assertIn("exact Kubernetes API endpoint egress missing", verifier)
        self.assertIn("edge_restart_verify.sh", edge)
        self.assertIn("rollout restart deployment/traefik", restart)
        self.assertIn('[[ "$code" == 403 ]]', restart)

if __name__ == "__main__":
    unittest.main()
