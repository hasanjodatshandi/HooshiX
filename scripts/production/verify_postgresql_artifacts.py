"""Scan the four blocked PostgreSQL platform inputs; never publish or install."""
from __future__ import annotations

import argparse
import contextlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from publish_mesh_candidate import scan, validate as validate_scan
from verify_openbao_artifact import ROOT, load

PIN = ROOT / 'infrastructure/production/data/postgresql-images.json'
BLOCKED = 'blocked-until-supply-chain-staging-and-recovery-evidence'
REPOSITORIES = {'operator': 'cloudnative-pg', 'postgresql': 'postgresql',
                'barman-plugin': 'plugin-barman-cloud',
                'barman-sidecar': 'plugin-barman-cloud-sidecar'}
MANIFEST_URLS = {
    'operator': 'https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/'
                'v1.30.0/releases/cnpg-1.30.0.yaml',
    'barman-plugin': 'https://github.com/cloudnative-pg/plugin-barman-cloud/releases/'
                     'download/v0.13.0/manifest.yaml'}


def pins():
    selected = load(PIN)
    policy = load(ROOT / 'infrastructure/production/data/data-policy.json')['postgresql']
    if (selected.get('schema_version') != 1 or selected['platform'] != 'linux/amd64'
            or selected['production_promotion'] != BLOCKED
            or set(selected['components']) != set(REPOSITORIES)):
        raise ValueError('exact blocked PostgreSQL artifact set required')
    versions = {'operator': ('cloudnativepg', '1.30.0'), 'postgresql': ('version', '18.4'),
                'barman-plugin': ('barman_plugin', '0.13.0'),
                'barman-sidecar': ('barman_plugin', '0.13.0')}
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
    for tool, version in (('syft', '1.51.0'), ('grype', '0.117.0')):
        output = subprocess.run([tool, 'version'], check=True, capture_output=True,
                                text=True, timeout=15).stdout
        if not re.search(r'(?<![\w.])' + re.escape(version) + r'(?![\w.])', output):
            raise ValueError('pinned scanner version required')
    subprocess.run(['grype', 'db', 'update'], check=True, capture_output=True, timeout=180)
    database = subprocess.run(['grype', 'db', 'status', '-o', 'json'], check=True,
                              capture_output=True, text=True, timeout=20).stdout
    (directory / 'database.json').write_text(database)
    subprocess.run(['syft', 'scan', pin['image'], '--from', 'registry', '--platform', 'linux/amd64',
                    '-o', 'syft-json=' + str(directory / 'syft.json'),
                    '-o', 'cyclonedx-json=' + str(directory / 'cyclonedx.json')],
                   check=True, capture_output=True, timeout=300)
    with contextlib.redirect_stdout(sys.stderr):
        scan(directory)
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
