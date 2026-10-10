"""Validate public ESO scan evidence; never authorize installation or promotion."""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

from publish_mesh_candidate import validate as validate_scan
from verify_openbao_artifact import ROOT, load

PIN = ROOT / 'infrastructure/production/secrets/eso-image.json'
BLOCKED = 'blocked-until-supply-chain-and-native-delivery-evidence'


def pin():
    selected = load(PIN)
    baseline = load(ROOT / 'infrastructure/production/secrets/secrets-policy.json')
    chart = selected['chart']
    if (selected.get('schema_version') != 1
            or selected['version'] != baseline['external_secrets_operator']['version']
            or selected['version'] != '2.8.0' or chart['version'] != selected['version']
            or not re.fullmatch(r'ghcr\.io/external-secrets/external-secrets@sha256:[a-f0-9]{64}', selected['image'])
            or selected['platform'] != 'linux/amd64'
            or not re.fullmatch(r'sha256:[a-f0-9]{64}', selected['index_digest'])
            or not re.fullmatch(r'[a-f0-9]{40}', selected['upstream_tag_revision'])
            or type(selected['compressed_bytes']) is not int or not 0 < selected['compressed_bytes'] < 256 * 1024 * 1024
            or chart['url'] != 'https://github.com/external-secrets/external-secrets/releases/download/'
               'helm-chart-2.8.0/external-secrets-2.8.0.tgz'
            or not re.fullmatch(r'[a-f0-9]{64}', chart['sha256'])
            or selected['production_promotion'] != BLOCKED):
        raise ValueError('reviewed immutable blocked ESO candidate required')
    return selected


def validate(directory: Path, revision: str, now: datetime):
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('exact Git revision required')
    selected = pin()
    result = validate_scan(directory, selected['image'], now)
    result.update({'schema_version': 1, 'component': 'external-secrets', 'owner': 'platform',
        'version': selected['version'], 'platform': selected['platform'],
        'repository_revision': revision, 'observed_at': now.isoformat(),
        'maximum_database_age_seconds': 432000, 'signature_provenance': 'Not verified',
        'upstream_build_provenance': 'Not verified', 'native_secret_delivery': 'Not verified',
        'deployment': 'Not verified', 'production_promotion': 'Not verified'})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', required=True, type=Path)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    try:
        result = validate(args.evidence_dir, args.revision, datetime.now(timezone.utc))
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, 'ESO_ARTIFACT=Failed; inspect public scan evidence; no deployment\n')
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
