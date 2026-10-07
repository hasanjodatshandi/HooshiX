"""Publish the three pinned mesh imports on reviewed main; never deploy them."""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from publish_openbao_candidate import context, run, verify_payload
from render_mesh_candidate import ROOT, pins, values
from verify_openbao_artifact import MAX_BYTES, load
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER

COMPONENTS = ('istiod', 'cni', 'ztunnel')
REPOSITORIES = {component: 'ghcr.io/hasanjodatshandi/hooshix/platform-istio-' + component + '-private'
                for component in COMPONENTS}
BUILD_TYPE = 'https://github.com/hasanjodatshandi/HooshiX/istio-upstream-import/v1'


def targets():
    pin = pins()
    if pin['ISTIO_VERSION'] != '1.30.5':
        raise ValueError('reviewed mesh baseline required')
    return {component: {'upstream': values(component, pin)['image'],
                        'image': REPOSITORIES[component] + '@' +
                        values(component, pin)['image'].split('@')[1]}
            for component in COMPONENTS}


def visibility(component):
    # Owner approval applies ONLY to these three fixed upstream-import packages.
    if component not in COMPONENTS:
        raise ValueError('unapproved mesh component')
    package = 'hooshix%2Fplatform-istio-' + component + '-private'
    result = run(['gh', 'api', 'users/hasanjodatshandi/packages/container/' + package,
                  '--jq', '.visibility'], timeout=20).strip()
    if result not in ('private', 'public'):
        raise ValueError('unapproved mesh package visibility')
    return result


def validate(directory, image, now):
    names = ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json')
    syft, cdx, scan, database = (load(directory / name) for name in names)
    metadata = syft['source']['metadata']
    if (syft['source']['type'] != 'image' or metadata['manifestDigest'] != image.split('@')[1]
            or metadata['architecture'] != 'amd64' or metadata['os'] != 'linux'
            or not isinstance(metadata['repoDigests'], list) or image not in metadata['repoDigests']
            or not isinstance(syft.get('artifacts'), list) or not syft['artifacts']
            or cdx.get('bomFormat') != 'CycloneDX'
            or not isinstance(cdx.get('components'), list) or not cdx['components']):
        raise ValueError('exact imported image and nonempty SBOM required')
    built = datetime.fromisoformat(database['built'].replace('Z', '+00:00'))
    if (database.get('valid') is not True or database.get('error')
            or not database.get('schemaVersion') or built.tzinfo is None
            or not timedelta(0) <= now - built <= timedelta(days=5)):
        raise ValueError('fresh valid scanner database required')
    matches = scan.get('matches')
    if not isinstance(matches, list):
        raise ValueError('scanner schema required')
    severities = ('Negligible', 'Low', 'Medium', 'High', 'Critical', 'Unknown')
    counts = dict.fromkeys(severities, 0)
    for match in matches:
        severity = match['vulnerability']['severity']
        if severity not in counts:
            raise ValueError('scanner severity schema required')
        counts[severity] += 1
    if counts['High'] or counts['Critical']:
        raise ValueError('mesh vulnerability gate failed')
    return {'image': image, 'scan': 'Passed', 'database_built_at': built.isoformat(),
            'severity_counts': counts, 'approved_exceptions': [],
            'sha256': {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in names}}


def scan(directory):
    """Keep bounded JSON on Grype threshold exit; never turn it into success."""
    argv = ['grype', 'sbom:' + str(directory / 'syft.json'),
            '--fail-on', 'high', '-o', 'json']
    try:
        output = run(argv, timeout=180)
    except subprocess.CalledProcessError as error:
        if error.returncode == 2 and isinstance(error.stdout, str):
            output = error.stdout
            if len(output.encode('utf-8')) <= MAX_BYTES:
                try:
                    report = json.loads(output)
                except ValueError:
                    report = None
                if isinstance(report, dict) and isinstance(report.get('matches'), list):
                    (directory / 'grype.json').write_text(output)
                    print('MESH_SCAN=Failed; threshold reached; inspect grype.json', flush=True)
        raise  # Includes empty/malformed reports; no signing or success receipt.
    if len(output.encode('utf-8')) > MAX_BYTES:
        raise ValueError('bounded scanner report required')
    (directory / 'grype.json').write_text(output)


def provenance(component, target, revision, invocation):
    return {'buildDefinition': {'buildType': BUILD_TYPE, 'externalParameters': {
        'component': component, 'version': '1.30.5', 'gitRevision': revision,
        'image': target['image'], 'upstreamImage': target['upstream'],
        'platform': 'linux/amd64', 'operation': 'unchanged-upstream-import'},
        'internalParameters': {}, 'resolvedDependencies': [
            {'uri': target['upstream'], 'digest': {'sha256': target['upstream'].split('@sha256:')[1]}},
            {'uri': 'git+https://github.com/hasanjodatshandi/HooshiX.git',
             'digest': {'gitCommit': revision}}]},
        'runDetails': {'builder': {'id': EXPECTED_CERTIFICATE_IDENTITY},
                       'metadata': {'invocationId': invocation}}}


def publish(directory: Path, env: dict):
    revision, invocation = context(env)
    selected = targets()
    directory.mkdir(mode=0o700)
    (directory / 'attempt.json').write_text(json.dumps({
        'schema_version': 1, 'repository_revision': revision,
        'publication': 'Not verified', 'purpose': 'attempt-only-not-success-receipt'}) + '\n')
    for name, version in (('syft', '1.51.0'), ('grype', '0.117.0'), ('cosign', 'v3.0.6')):
        if not re.search(r'(?<![\w.])' + re.escape(version) + r'(?![\w.])', run([name, 'version'])):
            raise ValueError('pinned release tools required')
    run(['grype', 'db', 'update'], timeout=180)
    database = run(['grype', 'db', 'status', '-o', 'json'])
    results = {}
    # Validate ALL three copies/scans before signing ANY component.
    for component, target in selected.items():
        print('MESH_PUBLICATION_STEP=' + component + '-copy', flush=True)
        folder = directory / component
        folder.mkdir(mode=0o700)
        digest = target['image'].split('@sha256:')[1]
        tag = REPOSITORIES[component] + ':candidate-' + digest + '-' + invocation.removeprefix('github:').replace(':', '-')
        run(['cosign', 'copy', target['upstream'], tag], timeout=300)
        print('MESH_PUBLICATION_STEP=' + component + '-visibility', flush=True)
        initial_visibility = visibility(component)
        (folder / 'database.json').write_text(database)
        print('MESH_PUBLICATION_STEP=' + component + '-sbom', flush=True)
        run(['syft', 'scan', target['image'], '--from', 'registry', '--platform', 'linux/amd64',
             '-o', 'syft-json=' + str(folder / 'syft.json'),
             '-o', 'cyclonedx-json=' + str(folder / 'cyclonedx.json')], timeout=300)
        print('MESH_PUBLICATION_STEP=' + component + '-scan', flush=True)
        scan(folder)
        print('MESH_PUBLICATION_STEP=' + component + '-validation', flush=True)
        results[component] = validate(folder, target['image'], datetime.now(timezone.utc))
        results[component]['registry_visibility'] = initial_visibility
    flags = ['--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY,
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER]
    for component, target in selected.items():
        print('MESH_PUBLICATION_STEP=' + component + '-sign-verify', flush=True)
        folder, image = directory / component, target['image']
        predicate = provenance(component, target, revision, invocation)
        path = folder / 'import-provenance.json'
        path.write_text(json.dumps(predicate, sort_keys=True) + '\n')
        run(['cosign', 'sign', '--yes', image])
        for kind, filename, uri, expected in (
                ('slsaprovenance1', path, 'https://slsa.dev/provenance/v1', predicate),
                ('cyclonedx', folder / 'cyclonedx.json', 'https://cyclonedx.org/bom', load(folder / 'cyclonedx.json'))):
            run(['cosign', 'attest', '--yes', '--predicate', str(filename), '--type', kind, image])
            verify_payload(run(['cosign', 'verify-attestation', '--type', kind, *flags, image]),
                           uri, expected, image.split('@sha256:')[1])
        try:
            run(['cosign', 'verify', '--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY + '.wrong',
                 '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER, image])
        except subprocess.CalledProcessError:
            pass
        else:
            raise ValueError('wrong signer unexpectedly accepted')
        run(['cosign', 'verify', *flags, image])
        if visibility(component) != results[component]['registry_visibility']:
            raise ValueError('mesh package visibility changed during publication')
        results[component].update({'signature_provenance': 'Passed', 'wrong_signer': 'Passed',
                                   'upstream_image': target['upstream']})
        results[component]['sha256'][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {'schema_version': 1, 'component': 'mesh', 'owner': 'platform',
            'version': '1.30.5', 'repository_revision': revision,
            'observed_at': datetime.now(timezone.utc).isoformat(), 'publication': 'Passed',
            'signer': EXPECTED_CERTIFICATE_IDENTITY, 'issuer': EXPECTED_OIDC_ISSUER,
            'components': results, 'provenance_kind': 'unchanged-upstream-import',
            'upstream_build_provenance': 'Not verified', 'runtime_admission': 'Not verified',
            'staging': 'Not verified', 'deployment': 'Not verified', 'production_promotion': 'Not verified'}


def main():
    try:
        env = dict(os.environ)
        revision, _ = context(env)
        if run(['git', 'rev-parse', 'HEAD']).strip() != revision:
            raise ValueError('checkout revision mismatch')
        directory = Path(env['RUNNER_TEMP']) / 'mesh-release-evidence'
        receipt = publish(directory, env)
        (directory / 'receipt.json').write_text(json.dumps(receipt, sort_keys=True, indent=2) + '\n')
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        print('MESH_PUBLICATION=Failed; no successful bundle receipt', file=sys.stderr)
        return 1
    print('MESH_PUBLICATION=Passed; candidate only; deployment Not verified')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
