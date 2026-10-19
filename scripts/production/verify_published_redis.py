"""Authenticate and freshly reaccept the existing protected Redis publication; never publish."""
import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import verify_platform_publication_run as publication
from build_redis_candidate import validate_candidate_scan
from build_patched_platform import published_image
from publish_openbao_candidate import verify_payload
from publish_redis_candidate import REPOSITORY, source_predicate
from render_redis_runtime import pin
from render_platform_admission import SIGSTORE_TUF_MIRROR
from verify_postgresql_artifacts import scan_inventory
from verify_published_postgresql import cosign
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER


def verify(output):
    selected = pin()
    run_id = selected['publication_run']
    record = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY + '/actions/runs/' + run_id]))
    publication.check_run(record)
    if record['head_sha'] != selected['repository_revision'] or record['run_attempt'] != selected['publication_attempt']:
        raise ValueError('EXACT_REDIS_PROTECTED_PUBLICATION_REQUIRED')
    name = 'redis-publication-' + run_id + '-' + str(selected['publication_attempt'])
    artifacts = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY + '/actions/runs/' + run_id + '/artifacts']))
    matches = [a for a in artifacts['artifacts'] if a['name'] == name]
    if (len(matches) != 1 or matches[0]['expired'] or not 0 < matches[0]['size_in_bytes'] <= 32 * 1024**2
        or output.exists() or output.is_symlink()):
        raise ValueError('FRESH_BOUNDED_REDIS_PUBLICATION_DOWNLOAD_REQUIRED')
    output.mkdir(mode=0o700)
    source = output / 'publication'
    publication.run(['run', 'download', run_id, '--repo', publication.REPOSITORY, '--name', name, '--dir', str(source)])
    data = (source / 'publication.json').read_bytes()
    if hashlib.sha256(data).hexdigest() != selected['publication_sha256']:
        raise ValueError('REVIEWED_REDIS_PUBLICATION_HASH_REQUIRED')
    value = json.loads(data)
    image, config = selected['image'], selected['image_configuration_digest']
    if value['image'] != image or value['image_configuration_digest'] != config:
        raise ValueError('REVIEWED_REDIS_DIGEST_REQUIRED')
    folder = output / 'registry'
    folder.mkdir(mode=0o700)
    scan_inventory(folder, image, 'registry')
    checked = validate_candidate_scan(folder, datetime.now(timezone.utc))
    metadata = json.loads((folder / 'syft.json').read_bytes())['source']
    if checked['image_configuration_digest'] != config or published_image(REPOSITORY, metadata, config) != image:
        raise ValueError('EXACT_REDIS_REGISTRY_CONFIG_REQUIRED')
    cache = output.resolve() / 'sigstore-tuf'
    if not re.search(r'(?<![\w.])v3\.0\.6(?![\w.])', cosign(['version'], cache)):
        raise ValueError('PINNED_COSIGN_REQUIRED')
    cosign(['initialize', '--mirror', SIGSTORE_TUF_MIRROR], cache)
    flags = ['--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY,
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER]
    cosign(['verify', *flags, image], cache)
    for kind, uri, expected in (
        ('slsaprovenance1', 'https://slsa.dev/provenance/v1', source_predicate(
            selected['repository_revision'], 'github:' + run_id + ':' + str(selected['publication_attempt']))),
        ('cyclonedx', 'https://cyclonedx.org/bom', json.loads((source / 'registry/cyclonedx.json').read_bytes()))):
        verify_payload(cosign(['verify-attestation', '--type', kind, *flags, image], cache),
                       uri, expected, image.split('@sha256:')[1])
    result = {'schema_version': 1, 'image': image, 'scan': 'Passed', 'signature_provenance_sbom': 'Passed',
        'publication_sha256': selected['publication_sha256'], 'observed_at': datetime.now(timezone.utc).isoformat(),
        'database_built_at': checked['database_built_at'], 'deployment': 'Not run'}
    (output / 'acceptance.json').write_text(json.dumps(result, sort_keys=True) + '\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    try: verify(a.output)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        p.exit(1, 'REDIS_PUBLICATION_ACCEPTANCE=Failed; no installation or publication\n')
    print('REDIS_PUBLICATION_ACCEPTANCE=Passed; unchanged image; target Not run')


if __name__ == '__main__': main()
