import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("hooshix_local_runtime", ROOT / "scripts/local/runtime.py")
runtime = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(runtime)


class LocalRuntimeContractTest(unittest.TestCase):
    def test_runtime_state_is_outside_versioned_paths(self):
        self.assertEqual(runtime.RUNTIME, ROOT / ".local-runtime")
        self.assertTrue(str(runtime.COMPOSE).endswith("infrastructure/local/compose.yaml"))

    def test_service_ports_are_unique(self):
        ports = [port for mapping in runtime.SERVICE_PORTS.values() for port in mapping.values()]
        self.assertEqual(len(ports), len(set(ports)))

    def test_database_runtime_and_migration_roles_are_separate(self):
        seen = set()
        for database, (migration, runtime_role, service) in runtime.DATABASES.items():
            self.assertNotEqual(migration, runtime_role)
            self.assertNotIn(migration, seen)
            self.assertNotIn(runtime_role, seen)
            seen.update((migration, runtime_role))
            self.assertTrue(service.endswith("-service"))
            self.assertIn(database, {"authorization", "identity", "notification"})

    def test_compose_pins_loopback_dependencies_and_noeviction(self):
        compose = runtime.COMPOSE.read_text(encoding="utf-8")
        self.assertIn("postgres:18.4-bookworm@sha256:1961f96e", compose)
        self.assertIn("redis:8.2.8-bookworm@sha256:2f7462b9", compose)
        self.assertIn('127.0.0.1:15432:5432', compose)
        self.assertIn('127.0.0.1:16379:6379', compose)
        self.assertIn('noeviction', compose)

    def test_local_secret_validation_rejects_sql_or_env_metacharacters(self):
        self.assertEqual("A" * 32, runtime.validate_local_secret("sample", "A" * 32))
        for value in ("short", "A" * 31 + "'", "A" * 32 + "\nBAD=value", 7, None):
            with self.assertRaises(SystemExit):
                runtime.validate_local_secret("sample", value)

    def test_service_process_identity_requires_expected_java_jar_path(self):
        service = "identity-service"
        jar = ROOT / "services" / service / "build" / "libs" / "identity-service-0.1.0-SNAPSHOT.jar"
        self.assertTrue(runtime.service_command_matches(service, ["java", "-jar", str(jar)]))
        self.assertFalse(runtime.service_command_matches(service, ["java", "-jar", "/tmp/unrelated.jar"]))
        self.assertFalse(runtime.service_command_matches(service, ["python3", "-jar", str(jar)]))
        self.assertFalse(runtime.service_command_matches(service, ["java", "-jar", str(jar.parent / "authorization-service.jar")]))

    def test_symmetric_key_ring_is_reused_across_startups(self):
        original_keys = runtime.KEYS
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                runtime.KEYS = Path(temp_dir)
                first = runtime.write_symmetric_ring("sample")
                first_bytes = first.read_bytes()
                second = runtime.write_symmetric_ring("sample")
                self.assertEqual(first, second)
                self.assertEqual(first_bytes, second.read_bytes())
        finally:
            runtime.KEYS = original_keys

    def test_full_application_runtime_is_enabled_only_in_local_environment(self):
        values = {name: "test" for name in (
            "authorization_runtime", "identity_runtime", "notification_runtime", "web_bff_tls")}
        keys = {name: ROOT / ".local-runtime" / "keys" / f"{name}.properties" for name in (
            "authorization-intent", "authorization-quota", "identity-jwt-public",
            "identity-fingerprint", "identity-challenge", "identity-handoff", "identity-refresh",
            "identity-quota", "identity-jwt-private", "notification-fingerprint",
            "notification-delivery", "web-bff-locator", "web-bff-csrf", "web-bff-refresh")}
        keys["web-bff-tls"] = ROOT / ".local-runtime" / "tls" / "web-bff.p12"
        dataset = {"database": "/tmp/local.sqlite", "manifest": "/tmp/local.json", "manifest_sha": "a" * 64}
        envs = runtime.runtime_envs(values, keys, dataset)
        self.assertTrue(all(env["SPRING_PROFILES_ACTIVE"] == "local" for env in envs.values()))
        self.assertEqual(envs["authorization-service"]["AUTHORIZATION_RUNTIME_ENABLED"], "true")
        self.assertTrue(all(env["MANAGEMENT_SERVER_ADDRESS"] == "127.0.0.1" for env in envs.values()))
        self.assertEqual(envs["compromised-password-service"]["HOOSHIX_COMPROMISED_PASSWORD_GRPC_BIND_ADDRESS"], "127.0.0.1")
        self.assertEqual(envs["notification-service"]["NOTIFICATION_GRPC_BIND_ADDRESS"], "127.0.0.1")
        self.assertEqual(envs["authorization-service"]["AUTHORIZATION_GRPC_BIND_ADDRESS"], "127.0.0.1")
        identity = envs["identity-service"]
        self.assertEqual(identity["IDENTITY_REGISTRATION_RUNTIME_ENABLED"], "true")
        self.assertEqual(identity["IDENTITY_AUTHENTICATION_RUNTIME_ENABLED"], "true")
        self.assertEqual(identity["IDENTITY_TENANT_RUNTIME_ENABLED"], "true")
        self.assertEqual(identity["IDENTITY_GRPC_BIND_ADDRESS"], "127.0.0.1")
        bff = envs["web-bff"]
        self.assertEqual(bff["WEB_BFF_RUNTIME_ENABLED"], "true")
        self.assertTrue(bff["WEB_BFF_PUBLIC_ORIGIN"].startswith("https://localhost:"))
        self.assertEqual(bff["SERVER_SSL_ENABLED"], "true")
        self.assertEqual(bff["SERVER_ADDRESS"], "127.0.0.1")


if __name__ == "__main__":
    unittest.main()
