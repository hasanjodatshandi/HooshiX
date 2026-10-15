"""Routing is conservative; skipped jobs never silently satisfy selected gates."""
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import select_pr_checks as routing

SERVICES = ('compromised-password', 'notification', 'identity', 'authorization', 'conversation', 'web-bff')


class CiSelectionTest(unittest.TestCase):
    def test_only_explicit_platform_paths_skip_java(self):
        allowed = ['scripts/production/install_barman_control_plane.py',
                   'scripts/production/build_cnpg_control_plane_bundle.py',
                   'scripts/production/tests/test_postgresql_storage.py', *routing.NEUTRAL, *routing.WRAPPERS]
        self.assertFalse(routing.application_tests(allowed))
        for path in ('services/identity-service/build.gradle.kts', '.github/workflows/repository-baseline.yml',
                     'scripts/ci/select_pr_checks.py', 'scripts/production/rehearsal_probes.py',
                     'docs/adr/0019-secrets.md', 'infrastructure/production/data/postgresql-images.json',
                     'scripts/production/install_unknown.py', '../scripts/production/install_barman_control_plane.py',
                     'scripts/production/install_barman_control_plane.py\nunknown'):
            self.assertTrue(routing.application_tests(allowed + [path]), path)
        self.assertTrue(routing.application_tests([]))
        self.assertTrue(routing.application_tests(allowed * 4000))

    def test_full_validation_for_other_events_and_uncertain_diffs(self):
        base, head = 'a' * 40, 'b' * 40
        run = Mock(return_value=SimpleNamespace(returncode=0,
            stdout=b'scripts/production/install_barman_control_plane.py\0'))
        self.assertFalse(routing.select('pull_request', base, head, run)['application_tests'])
        self.assertIn('--no-renames', run.call_args.args[0])
        self.assertIn(base + '...' + head, run.call_args.args[0])
        for event in ('push', 'schedule', 'workflow_dispatch', 'pull_request_target'):
            self.assertTrue(routing.select(event, base, head, run)['application_tests'])
        for invalid in ('', '--help', 'b' * 39):
            self.assertTrue(routing.select('pull_request', invalid, head, run)['application_tests'])
        for status, data in ((1, b'known\0'), (0, b''), (0, b'not-nul-terminated'),
                             (0, b'\xff\0'), (0, b'x' * (2 * 1024**2) + b'\0')):
            run.return_value = SimpleNamespace(returncode=status, stdout=data)
            self.assertTrue(routing.select('pull_request', base, head, run)['application_tests'])
        for error in (OSError(), subprocess.TimeoutExpired('git', 30)):
            run.side_effect = error
            self.assertTrue(routing.select('pull_request', base, head, run)['application_tests'])

    def test_workflows_keep_full_gates_and_pr_only_cancellation(self):
        baseline = (ROOT / '.github/workflows/repository-baseline.yml').read_text()
        for workflow in ('repository-baseline', 'platform-commissioning', 'web-frontend-e2e'):
            text = (ROOT / '.github/workflows' / (workflow + '.yml')).read_text()
            self.assertIn('group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.run_id }}', text)
            self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", text)
        for name in SERVICES:
            self.assertIn(name + '-security:\n    needs: ci-scope\n', baseline)
        self.assertEqual(6, baseline.count("application_tests: ${{ needs.ci-scope.outputs.application_tests == 'true' }}"))
        for file in ('compromised-password-service', 'notification-service', 'identity-service',
                     'authorization-service', 'conversation-service', 'web-bff'):
            workflow = (ROOT / '.github/workflows' / (file + '.yml')).read_text()
            self.assertIn('type: boolean\n        default: true', workflow)
            self.assertIn("if: ${{ !inputs.application_tests }}", workflow)
            for step in workflow.split('      - ')[1:]:
                if step.startswith(('name: Check out repository', 'name: Report explicitly unselected')):
                    continue
                self.assertIn('        if: ${{ inputs.application_tests }}', step)
        for name in ('openbao-artifact', 'eso-artifact', 'mesh-artifact', 'openbao-recovery', 'openbao-kubernetes'):
            section = re.split(r'\n  (?=\S)', baseline.split('  ' + name + ':\n', 1)[1], maxsplit=1)[0]
            self.assertNotIn('ci-scope', section)
        secret_job = baseline.split('  repository-secrets:\n', 1)[1].split('\n  contract-quality:', 1)[0]
        self.assertNotIn('if:', secret_job)
        for marker in ('fetch-depth: 0', 'gitleaks-fixtures', 'gitleaks-scan', 'GITLEAKS_IMAGE:'):
            self.assertIn(marker, secret_job)
        self.assertIn('      - scripts/production/rehearsal_probes.py',
                      (ROOT / '.github/workflows/platform-commissioning.yml').read_text())

    def test_real_aggregate_rejects_failed_missing_or_unexpected_skips(self):
        workflow = (ROOT / '.github/workflows/repository-baseline.yml').read_text()
        aggregate = workflow.split('  baseline:\n', 1)[1]
        self.assertIn('if: ${{ always() }}', aggregate)
        for job in ('ci-scope', 'repository-secrets', *(name + '-security' for name in SERVICES)):
            self.assertIn('      - ' + job + '\n', aggregate)
        env = dict(os.environ)
        for line in aggregate.splitlines():
            if '_RESULT: ${{ needs.' in line:
                env[line.strip().split(':', 1)[0]] = 'success'
        env['APPLICATION_TESTS'] = 'true'
        script = '\n'.join(line[10:] for line in aggregate.split('        run: |\n', 1)[1].splitlines())

        def passes(changes):
            return subprocess.run(['bash', '-c', script], env={**env, **changes},
                                  capture_output=True, timeout=5).returncode == 0

        self.assertTrue(passes({}))
        java = [name.upper().replace('-', '_') + '_RESULT' for name in SERVICES]
        unselected = dict(APPLICATION_TESTS='false')
        self.assertTrue(passes(unselected))
        for invalid in ('', 'unknown', 'TRUE'):
            self.assertFalse(passes({'APPLICATION_TESTS': invalid}))
        for name in [key for key in env if key.endswith('_RESULT')]:
            self.assertFalse(passes({name: 'failure'}), name)
            self.assertFalse(passes({name: 'skipped'}), name)
        for name in java:
            self.assertFalse(passes({**unselected, name: 'failure'}), name)
            self.assertFalse(passes({**unselected, name: 'skipped'}), name)
        self.assertFalse(passes({**unselected, 'SCOPE_RESULT': 'failure'}))
        self.assertFalse(passes({**unselected, 'SECRETS_RESULT': 'skipped'}))


if __name__ == '__main__': unittest.main()
