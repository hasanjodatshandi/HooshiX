"""Scan the four blocked PostgreSQL platform inputs; never publish or install."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from publish_mesh_candidate import validate as validate_scan
from verify_openbao_artifact import ROOT, MAX_BYTES, load

PIN = ROOT / 'infrastructure/production/data/postgresql-images.json'
BLOCKED = 'blocked-until-supply-chain-staging-and-recovery-evidence'
REPOSITORIES = {'operator': 'cloudnative-pg', 'postgresql': 'postgresql',
                'barman-plugin': 'plugin-barman-cloud',
                'barman-sidecar': 'plugin-barman-cloud-sidecar'}
MANIFEST_URLS = {
    'operator': 'https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/'
                'v1.30.1/releases/cnpg-1.30.1.yaml',
    'barman-plugin': 'https://github.com/cloudnative-pg/plugin-barman-cloud/releases/'
                     'download/v0.15.1/manifest.yaml'}


def require_unsuppressed_scan(report):
    ignored = report.get('ignoredMatches', [])
    if not isinstance(ignored, list):
        raise ValueError('ignored scanner matches schema required')
    for match in ignored:
        if match['vulnerability']['severity'] not in ('Negligible', 'Low', 'Medium', 'Unknown'):
            raise ValueError('suppressed High/Critical or invalid severity rejected')


def require_complete_inventory(syft):
    descriptor = syft['descriptor']
    config = descriptor['configuration']
    catalogers = config['catalogers']
    required = {'go-module-binary-cataloger', 'binary-classifier-cataloger',
                'python-installed-package-cataloger', 'dpkg-db-cataloger',
                'rpm-db-cataloger', 'elf-binary-package-cataloger'}
    if (descriptor['name'] != 'syft' or descriptor['version'] != '1.51.0'
            or config.get('exclude') not in (None, [])
            or config['search']['scope'] != 'squashed'
            or catalogers['requested'] != {'default': ['image', 'file']}
            or not isinstance(catalogers['used'], list) or not required <= set(catalogers['used'])):
        raise ValueError('complete pinned image inventory without exclusions required')


def scan_inventory(directory, image, source):
    if source not in ('docker', 'registry'):
        raise ValueError('known image source required')
    # Do not inherit user scanner config/exclusions or restricted catalogers.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(('SYFT_', 'GRYPE_'))}
    env.update({'SYFT_GOLANG_CAPTURE_SYMBOLS': 'all', 'SYFT_CHECK_FOR_APP_UPDATE': 'false',
                'GRYPE_DB_AUTO_UPDATE': 'false', 'GRYPE_DB_VALIDATE_AGE': 'true',
                'GRYPE_DB_MAX_ALLOWED_BUILT_AGE': '120h', 'GRYPE_CHECK_FOR_APP_UPDATE': 'false'})
    config = directory / 'scanner-config.json'
    config.write_text('{}\n')
    for tool, version in (('syft', '1.51.0'), ('grype', '0.117.0')):
        output = subprocess.run([tool, 'version'], check=True, capture_output=True,
                                text=True, timeout=15, env=env).stdout
        if not re.search(r'(?<![\w.])' + re.escape(version) + r'(?![\w.])', output):
            raise ValueError('pinned scanner version required')
    subprocess.run(['grype', 'db', 'update', '--config', str(config)], check=True,
                   capture_output=True, timeout=180, env=env)
    database = subprocess.run(['grype', 'db', 'status', '-o', 'json', '--config', str(config)],
                              check=True, capture_output=True, text=True, timeout=20, env=env).stdout
    (directory / 'database.json').write_text(database)
    subprocess.run(['syft', 'scan', image, '--from', source, '--platform', 'linux/amd64',
                    '--config', str(config), '--override-default-catalogers', 'image',
                    '--override-default-catalogers', 'file',
                    '-o', 'syft-json=' + str(directory / 'syft.json'),
                    '-o', 'cyclonedx-json=' + str(directory / 'cyclonedx.json')],
                   check=True, capture_output=True, timeout=300, env=env)
    require_complete_inventory(load(directory / 'syft.json'))
    argv = ['grype', 'sbom:' + str(directory / 'syft.json'), '--config', str(config),
            '--fail-on', 'high', '-o', 'json']
    result = subprocess.run(argv, capture_output=True, text=True, timeout=180, env=env)
    if result.returncode in (0, 2) and len(result.stdout.encode('utf-8')) <= MAX_BYTES:
        report = json.loads(result.stdout)
        if isinstance(report, dict) and isinstance(report.get('matches'), list):
            (directory / 'grype.json').write_text(result.stdout)
    result.check_returncode()  # Threshold exit remains failure; JSON survives for diagnosis.
    require_unsuppressed_scan(load(directory / 'grype.json'))


def pins():
    selected = load(PIN)
    policy = load(ROOT / 'infrastructure/production/data/data-policy.json')['postgresql']
    if (selected.get('schema_version') != 1 or selected['platform'] != 'linux/amd64'
            or selected['production_promotion'] != BLOCKED
            or set(selected['components']) != set(REPOSITORIES)):
        raise ValueError('exact blocked PostgreSQL artifact set required')
    versions = {'operator': ('cloudnativepg', '1.30.1'), 'postgresql': ('version', '18.6'),
                'barman-plugin': ('barman_plugin', '0.15.1'),
                'barman-sidecar': ('barman_plugin', '0.15.1')}
    for component, pin in selected['components'].items():
        key, version = versions[component]
        if (pin['version'] != version or policy[key] != version
                or not re.fullmatch('ghcr\\.io/cloudnative-pg/' + REPOSITORIES[component]
                                    + r'@sha256:[a-f0-9]{64}', pin['image'])
                or not re.fullmatch(r'sha256:[a-f0-9]{64}', pin['index_digest'])
                or type(pin['compressed_bytes']) is not int
                or not 0 < pin['compressed_bytes'] < 256 * 1024 * 1024
                or not pin['image_labels'] or any(not isinstance(value, str) or not value
                                                for value in pin['image_labels'].values())):
            raise ValueError('reviewed immutable upstream artifact required')
        for field in ('source_revision', 'upstream_tag_revision'):
            if field in pin and not re.fullmatch(r'[a-f0-9]{40}', pin[field]):
                raise ValueError('exact source revision required')
        if component in MANIFEST_URLS:
            if (pin['manifest']['url'] != MANIFEST_URLS[component]
                    or not re.fullmatch(r'[a-f0-9]{64}', pin['manifest']['sha256'])):
                raise ValueError('reviewed upstream manifest required')
    return selected


def validate(directory: Path, component: str, revision: str, now: datetime):
    if component not in REPOSITORIES or not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('known component and exact Git revision required')
    pin = pins()['components'][component]
    require_unsuppressed_scan(load(directory / 'grype.json'))
    require_complete_inventory(load(directory / 'syft.json'))
    result = validate_scan(directory, pin['image'], now)
    labels = load(directory / 'syft.json')['source']['metadata']['labels']
    if any(labels.get(key) != value for key, value in pin['image_labels'].items()):
        raise ValueError('reviewed upstream image labels required')
    result.update({'schema_version': 1, 'component': component, 'owner': 'platform',
        'version': pin['version'], 'platform': 'linux/amd64', 'repository_revision': revision,
        'observed_at': now.isoformat(), 'maximum_database_age_seconds': 432000,
        'signature_provenance': 'Not verified', 'upstream_build_provenance': 'Not verified',
        'staging': 'Not verified', 'deployment': 'Not verified', 'production_promotion': 'Not verified'})
    return result


def scan_candidate(directory: Path, component: str, revision: str):
    if component not in REPOSITORIES or not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('known component and exact Git revision required')
    pin = pins()['components'][component]
    directory.mkdir(mode=0o700)  # Fresh evidence; never reuse a stale success file.
    scan_inventory(directory, pin['image'], 'registry')
    return validate(directory, component, revision, datetime.now(timezone.utc))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', required=True, choices=tuple(REPOSITORIES))
    parser.add_argument('--evidence-dir', required=True, type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--scan', action='store_true')
    args = parser.parse_args()
    try:
        result = (scan_candidate(args.evidence_dir, args.component, args.revision) if args.scan
                  else validate(args.evidence_dir, args.component, args.revision,
                                datetime.now(timezone.utc)))
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        parser.exit(1, 'POSTGRESQL_ARTIFACT=Failed; candidate retained; no publication or installation\n')
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
