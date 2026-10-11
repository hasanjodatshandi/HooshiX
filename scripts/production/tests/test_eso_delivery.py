import base64
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rehearse_eso_delivery as delivery


class ESODeliveryTest(unittest.TestCase):
    def test_contract_is_service_scoped_owned_and_has_no_secret_value(self):
        for service in delivery.auth.SERVICES:
            obj = delivery.external_secret(service)
            self.assertEqual('hooshix-' + service, obj['spec']['secretStoreRef']['name'])
            self.assertEqual('Owner', obj['spec']['target']['creationPolicy'])
            self.assertEqual('production/' + service + '/commissioning-probe',
                             obj['spec']['data'][0]['remoteRef']['key'])
            self.assertNotIn(service + '/commissioning-probe', delivery.external_secret(
                service, denied=True)['spec']['data'][0]['remoteRef']['key'])
        with self.assertRaises(ValueError):
            delivery.external_secret('foreign')

    def fixture(self, *, leak=False, leaked_secret=False):
        selected = delivery.published_pin()
        pods = [{'metadata': {'name': 'fixture-' + str(n)}, 'spec': {'containers': [
            {'image': selected['image']}]}, 'status': {'containerStatuses': [
            {'imageID': selected['image']}]}} for n in range(3)]
        contents, requests, applied = {}, [], []
        def api_call(path, method, body, root):
            self.assertEqual('synthetic-root', root)
            contents[path] = body['data']['value']
        def kube(*args, data=None, **_):
            requests.append(args)
            if data:
                applied.append(json.loads(data))
            if 'pods' in args:
                return json.dumps({'items': pods}).encode()
            if 'logs' in args:
                return (next(iter(contents.values())) if leak else '').encode()
            if 'secrets' in args:
                return json.dumps({'items': [{'metadata': {'name': delivery.external_secret(
                    delivery.auth.SERVICES[0], denied=True)['metadata']['name']}}] if leaked_secret else []}).encode()
            if 'secret' in args:
                name = args[args.index('secret') + 1].removeprefix('delivery-')
                value = contents['hooshix/data/production/' + name + '/commissioning-probe']
                return json.dumps({'data': {'value': base64.b64encode(value.encode()).decode()}}).encode()
            return b''
        crds = b'\n---\n'.join(json.dumps({'metadata': {'name': name}}).encode() for name in (
            'externalsecrets.external-secrets.io', 'secretstores.external-secrets.io'))
        api = Mock(call=api_call)
        with patch.object(delivery, 'render', return_value={'kind': 'List', 'items': []}):
            result = delivery.rehearse(kube, lambda check: self.assertTrue(check()),
                Mock(return_value=crds), Path('/fixture/chart'), '/fixture/helm', api,
                'synthetic-root', 'public-ca', ['kubernetes'], {'10.96.0.1', '172.18.0.2'})
        return result, requests, applied

    def test_six_deliveries_rotations_denial_cleanup_and_bounded_network_edges(self):
        result, requests, applied = self.fixture()
        self.assertEqual('Passed', result['six_identity_delivery_rotation'])
        self.assertEqual('Not verified', result['production_target'])
        self.assertEqual(6, len([args for args in requests if 'delete' in args]))
        for obj in applied:
            if obj.get('kind') == 'NetworkPolicy':
                self.assertNotIn('0.0.0.0/0', json.dumps(obj))
                self.assertNotIn('"ingress": [{}]', json.dumps(obj))
        self.assertEqual(6, len([obj for obj in applied if obj.get('kind') == 'SecretStore']))

    def test_native_log_leak_and_cross_service_delivery_fail_closed(self):
        for options in ({'leak': True}, {'leaked_secret': True}):
            with self.assertRaises(delivery.RehearsalFailed):
                self.fixture(**options)

    def test_ci_runs_native_fixture_with_hashes_and_no_real_credentials(self):
        workflow = (Path(__file__).resolve().parents[3] / '.github/workflows/repository-baseline.yml').read_text()
        job = workflow.split('  openbao-kubernetes:', 1)[1].split('  compromised-password-security:', 1)[0]
        self.assertIn('--eso-chart', job)
        self.assertIn(delivery.published_pin()['chart']['sha256'], job)
        self.assertIn('--require-hashes', job)
        self.assertNotIn('secrets.', job)


if __name__ == '__main__':
    unittest.main()
