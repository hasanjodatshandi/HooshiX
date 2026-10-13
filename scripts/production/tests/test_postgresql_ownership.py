from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_postgresql_ownership as ownership
import render_gitops
import test_postgresql_isolation_native as native


class PostgreSQLServiceOwnershipTest(unittest.TestCase):
    def test_exact_service_ownership_retains_data_and_roles(self):
        items = ownership.render()['items']
        self.assertEqual(len(items), 20)
        self.assertEqual(len({(i['kind'], i['metadata']['name']) for i in items}), 20)
        self.assertTrue(all(i['metadata']['namespace'] == 'platform-data' for i in items))
        for database in ownership.DATABASES:
            service = database.replace('_', '-')
            for role in ('migration', 'runtime'):
                spec = next(i['spec'] for i in items if i['kind'] == 'DatabaseRole'
                            and i['metadata']['name'] == f'{service}-{role}')
                self.assertEqual(spec['name'], f'{database}_{role}')
                self.assertEqual(spec['passwordSecret']['name'], f'{service}-db-{role}')
                self.assertEqual(spec['databaseRoleReclaimPolicy'], 'retain')
                for flag in ('superuser', 'createdb', 'createrole', 'inherit', 'replication', 'bypassrls'):
                    self.assertIs(spec[flag], False)
                self.assertEqual(spec['inRoles'], [])
            spec = next(i['spec'] for i in items if i['kind'] == 'Database'
                        and i['metadata']['name'] == service)
            self.assertEqual(spec['owner'], f'{database}_migration')
            self.assertEqual(spec['databaseReclaimPolicy'], 'retain')

    def test_isolation_sql_is_bounded_and_rejects_non_reviewed_names(self):
        for database in ownership.DATABASES:
            sql = ownership.isolation_sql(database)
            self.assertTrue(sql.startswith('BEGIN;') and sql.endswith('COMMIT;\n'))
            for marker in ('statement_timeout', 'lock_timeout', 'SERVICE_DATABASE_BOUNDARY_CONFLICT',
                           'rolbypassrls', 'pg_auth_members', 'cnpg_metrics_exporter'):
                self.assertIn(marker, sql)
            self.assertNotIn('DROP ', sql)
            self.assertNotIn('PASSWORD', sql)
        for name in ('postgres', 'identity; DROP DATABASE identity', '', 'web-bff'):
            with self.assertRaises(ValueError):
                ownership.isolation_sql(name)

    def test_application_secret_and_writer_contract_is_preserved(self):
        source = Path(render_gitops.__file__).read_text()
        for database in ownership.DATABASES:
            self.assertIn(f':5432/{database}', source)
            for role in ('runtime', 'migration'):
                self.assertIn(f'{database.replace("_", "-")}-db-{role}', source)
        self.assertIn('postgresql-rw.platform-data.svc.cluster.local', source)

    def test_render_has_no_cluster_workload_or_secret_values(self):
        rendered = ownership.render()
        self.assertEqual({i['kind'] for i in rendered['items']}, {'DatabaseRole', 'Database', 'ConfigMap'})
        self.assertNotIn('stringData', json.dumps(rendered))
        self.assertNotIn('compromised-password', json.dumps(rendered))

    def test_native_fixture_rejects_remote_docker_without_container_creation(self):
        response = SimpleNamespace(returncode=0, stdout='"ssh://production"')
        with patch.object(sys, 'argv', ['fixture', '--disposable']), \
                patch.object(native, 'docker', return_value=response) as command:
            with self.assertRaisesRegex(RuntimeError, 'remote daemons are forbidden'):
                native.main()
            command.assert_called_once_with('context', 'inspect', '--format',
                                            '{{json .Endpoints.docker.Host}}')

    def test_candidate_native_boundary_rejects_remote_or_mutable_images(self):
        for image, endpoint in [('postgres:latest', 'unix:///var/run/docker.sock'),
                                ('sha256:' + 'a' * 64, 'ssh://production')]:
            with patch.object(native, 'docker', return_value=SimpleNamespace(returncode=0,
                    stdout=json.dumps(endpoint))) as command, self.assertRaises(RuntimeError):
                native.exercise_candidate(image)
            self.assertEqual(1, command.call_count)

    def test_candidate_native_failure_cleans_only_its_volatile_container(self):
        container = 'b' * 64
        calls = []
        def execute(*args, **kwargs):
            calls.append(args)
            output = ''
            if args[0] == 'context': output = json.dumps('unix:///var/run/docker.sock')
            if args[0] == 'run': output = container
            if args[-2:] == ('postgres', '--version'): output = 'postgres (PostgreSQL) 18.6'
            return SimpleNamespace(returncode=0, stdout=output)
        with patch.dict(os.environ, {'DOCKER_HOST': 'unix:///var/run/docker.sock'}), \
                patch.object(native, 'docker', side_effect=execute), \
                patch.object(native, 'exercise', side_effect=RuntimeError('synthetic failure')), \
                self.assertRaisesRegex(RuntimeError, 'synthetic failure'):
            native.exercise_candidate('sha256:' + 'a' * 64)
        started = next(args for args in calls if args[0] == 'run')
        for marker in ('--read-only', '--cap-drop', 'ALL', 'no-new-privileges', '--tmpfs'):
            self.assertIn(marker, started)
        self.assertNotIn('-v', started)
        self.assertEqual('none', started[started.index('--network') + 1])
        self.assertEqual(('rm', '-fv', container), calls[-1])


if __name__ == '__main__':
    unittest.main()
