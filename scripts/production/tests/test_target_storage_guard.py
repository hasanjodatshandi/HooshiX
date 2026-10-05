import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stdout
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_storage_guard as target


class Failed(Exception):
    pass


class MaintenanceTest(unittest.TestCase):
    def test_installed_source_pin_matches_reviewed_provisioner(self):
        source = Path(__file__).resolve().parents[1] / 'provision_openbao_storage.py'
        self.assertEqual(target.SOURCE_SHA256, hashlib.sha256(source.read_bytes()).hexdigest())

    def test_public_failures_never_expose_arbitrary_exception_text(self):
        output = io.StringIO()
        with patch.object(sys, 'argv', ['verify_storage_guard.py']), \
                patch.object(target, 'load_storage', side_effect=RuntimeError('private-error-detail')), \
                redirect_stdout(output):
            self.assertEqual(1, target.main())
        receipt = json.loads(output.getvalue())
        self.assertEqual('MAINTENANCE_CHECK_FAILED', receipt['reason'])
        self.assertNotIn('private-error-detail', output.getvalue())
        self.assertEqual('Not verified', receipt['production_readiness'])

    def exercise(self, *, approved=True, pvc=False, data=False, bind=True,
                 timer_failure=False, kill_failure=False, api_failure=False,
                 preserved_changed=False, unexpected_rearm=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mount = root / 'mount'
            mount.mkdir()
            (mount / 'data').mkdir()
            if data:
                (mount / 'data' / 'raft').write_text('never delete')
            unit, dropin = root / 'unit', root / 'dropin'
            unit.write_text('guard')
            dropin.write_text('bind')
            state = {'guard': 'active', 'k3s.service': 'active', 'timer': 'not-found'}
            calls = []
            def require(condition, code):
                if not condition:
                    raise Failed(code)
            def native(argv, **kwargs):
                calls.append(tuple(argv))
                if argv[0].endswith('k3s'):
                    if 'pvc' in argv:
                        return json.dumps({'items': [1] if pvc else []})
                    if api_failure and any('kill' in call for call in calls):
                        raise Failed('K3S_API_NOT_READY')
                    return 'ok'
                if argv[0].endswith('systemd-run'):
                    if timer_failure:
                        raise Failed('NATIVE_COMMAND_FAILED')
                    state['timer'] = 'active'
                    return ''
                if argv[0].endswith('umount'):
                    (mount / 'data').rmdir()
                    state['guard'] = state['k3s.service'] = 'inactive'
                    return ''
                action = argv[1]
                if action == 'show':
                    name, prop = argv[2], argv[3]
                    if prop.endswith('LoadState'):
                        return 'not-found'
                    if prop.endswith('MainPID'):
                        return '101' if preserved_changed and any('kill' in c for c in calls) else '100'
                    if prop.endswith(('BindsTo', 'After')):
                        return 'guard' if bind else 'none'
                    if prop.endswith('Restart'):
                        return 'no'
                    return state.get('timer' if name.endswith('.timer') else name, 'active')
                if action == 'kill':
                    if kill_failure:
                        raise Failed('NATIVE_COMMAND_FAILED')
                    state['guard'] = 'failed'
                    state['k3s.service'] = 'inactive'
                if action == 'start' and argv[2] == 'mount':
                    (mount / 'data').mkdir(exist_ok=True)
                    if unexpected_rearm and any(c[0].endswith('umount') for c in calls):
                        state['k3s.service'] = 'active'
                if action == 'start' and argv[2] == 'k3s.service':
                    state['guard'] = state['k3s.service'] = 'active'
                if action == 'stop':
                    state['timer'] = 'inactive'
                return ''
            storage = {'execute': lambda _: {'storage_foundation': 'Passed'},
                       'guard_check': lambda: None, 'guard_loop': lambda: None,
                       'GUARD_UNIT': unit, 'GUARD_TEXT': 'guard', 'K3S_DROPIN': dropin,
                       'DROPIN_TEXT': 'bind', 'GUARD_NAME': 'guard', 'UNIT_NAME': 'mount',
                       'GUARD': root / 'guard.py', 'MOUNT': mount, 'run': native,
                       'protected': lambda *a, **kw: None, 'parents': lambda _: None,
                       'require': require, 'StorageFailed': Failed,
                       'check_worker': lambda _: None}
            with patch.object(target, 'load_storage', return_value=storage), \
                    patch.object(target.time, 'sleep'):
                try:
                    receipt = target.verify(approved)
                    return receipt, calls, None
                except Failed as error:
                    return None, calls, str(error)

    def test_readonly_has_no_mutations(self):
        receipt, calls, error = self.exercise(approved=False)
        self.assertIsNone(error)
        self.assertFalse(receipt['mutation'])
        self.assertFalse(any(c[1] in ('start', 'stop', 'kill') for c in calls if 'systemctl' in c[0]))
        self.assertEqual('Not run', receipt['target_mount_loss'])

    def test_real_flow_requires_recovery_before_timer_cancel(self):
        receipt, calls, error = self.exercise()
        self.assertIsNone(error)
        for field in ('target_process_fault', 'target_mount_loss', 'target_startup_and_recovery'):
            self.assertEqual('Passed', receipt[field])
        self.assertEqual('Not verified', receipt['reboot_persistence'])
        kill = next(i for i, c in enumerate(calls) if 'kill' in c)
        timer = next(i for i, c in enumerate(calls) if c[0].endswith('systemd-run'))
        self.assertLess(timer, kill)
        self.assertEqual(('/usr/bin/systemctl', 'stop', target.RECOVERY + '.timer'), calls[-1])
        self.assertFalse(any('mkfs' in c[0] or 'reboot' in c for c in calls))

    def test_pvc_or_existing_data_or_bad_dependency_prevents_fault(self):
        for setting in ({'pvc': True}, {'data': True}, {'bind': False}):
            _, calls, error = self.exercise(**setting)
            self.assertIsNotNone(error)
            self.assertFalse(any('kill' in c or c[0].endswith('systemd-run') for c in calls))

    def test_timer_failure_prevents_fault(self):
        _, calls, error = self.exercise(timer_failure=True)
        self.assertIsNotNone(error)
        self.assertFalse(any('kill' in c for c in calls))

    def test_failed_kill_is_not_success_and_still_recovers(self):
        _, calls, error = self.exercise(kill_failure=True)
        self.assertIsNotNone(error)
        self.assertIn(('/usr/bin/systemctl', 'start', 'k3s.service'), calls)

    def test_failed_api_recovery_retains_safety_timer(self):
        _, calls, error = self.exercise(api_failure=True)
        self.assertIsNotNone(error)
        self.assertNotIn(('/usr/bin/systemctl', 'stop', target.RECOVERY + '.timer'), calls)

    def test_changed_preserved_service_retains_safety_timer(self):
        _, calls, error = self.exercise(preserved_changed=True)
        self.assertEqual('PRESERVED_SERVICE_CHANGED', error)
        self.assertNotIn(('/usr/bin/systemctl', 'stop', target.RECOVERY + '.timer'), calls)

    def test_unexpected_rearm_is_not_success(self):
        _, _, error = self.exercise(unexpected_rearm=True)
        self.assertEqual('UNEXPECTED_AUTOMATIC_REARM', error)

    def test_wrong_host_or_nonroot_denied_before_file_read(self):
        with patch.object(target.os, 'geteuid', return_value=1000):
            with self.assertRaisesRegex(ValueError, 'WRONG_TARGET'):
                target.load_storage()


if __name__ == '__main__':
    unittest.main()
