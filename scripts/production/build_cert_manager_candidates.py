"""Build three fixed-source cert-manager candidates; never publish or install."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from build_postgresql_candidates import MODULES as NETWORK_MODULES, local_endpoint
from publish_openbao_candidate import run
from verify_openbao_artifact import ROOT, MAX_DB_AGE, load
from verify_postgresql_artifacts import (scan_inventory, require_complete_inventory,
                                         require_unsuppressed_scan)

RECIPE = ROOT / 'infrastructure/production/data/cert-manager-sources.json'
COMPONENTS = ('controller', 'cainjector', 'webhook')
MODULES = NETWORK_MODULES | {'google.golang.org/grpc': 'v1.83.1'}
BLOCKED = 'blocked-until-signed-digest-admission-native-and-recovery-evidence'


def recipe():
    selected = load(RECIPE)
    baseline = load(ROOT / 'infrastructure/production/profile.json')['postgresql']['cert_manager']
    if (selected['schema_version'] != 1 or selected['version'] != baseline or baseline != '1.20.3'
            or selected['platform'] != 'linux/amd64' or selected['go_version'] != '1.26.9'
            or selected['builder'] != 'docker.io/library/golang@sha256:'
               'bcef992b77b1e2031aaaa51da75cebc32da8e9c182e12e633ea79023c79d9eff'
            or selected['modules'] != MODULES or selected['production_promotion'] != BLOCKED
            or selected['source'] != {'repository': 'cert-manager/cert-manager',
                'revision': '1e1d16d1744e6d9c80e464e58e8d9ab1caed222b',
                'tag_object': '904f9feddaf7739a78d1a0c56bdb4ff61a4ff65b',
                'archive_sha256': 'ec89ef9c50ddf5a86015b84545f53138f35d32b79bf0b778dee5e135842bb5f9'}
            or selected['manifest'] != {'url': 'https://github.com/cert-manager/cert-manager/'
                'releases/download/v1.20.3/cert-manager.yaml',
                'sha256': '7ee74ba06845213e96d8ceaff3d20dd51e682765c1418eddda4e8780ba082261'}
            or set(selected['components']) != set(COMPONENTS)):
        raise ValueError('reviewed blocked three-component cert-manager source recipe required')
    for component, image in selected['components'].items():
        if not re.fullmatch(r'quay\.io/jetstack/cert-manager-' + component + r'@sha256:[a-f0-9]{64}', image):
            raise ValueError('immutable official runtime input required')
    return selected


def dockerfile(component, selected):
    if component not in COMPONENTS:
        raise ValueError('unapproved cert-manager component')
    source = selected['source']
    modules = ' '.join(name + '@' + version for name, version in selected['modules'].items())
    prefix = 'github.com/cert-manager/cert-manager/pkg/util.'
    flags = f'-buildid= -X {prefix}AppVersion=v1.20.3 -X {prefix}AppGitCommit={source["revision"]} -X {prefix}AppGitState=Modified'
    return '\n'.join([
        f"FROM {selected['builder']} AS build", 'WORKDIR /src',
        'ENV CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2',
        'ENV GOPROXY="https://proxy.golang.org|direct" GOSUMDB=sum.golang.org',
        'COPY source.tar.gz /tmp/source.tar.gz',
        f"RUN echo '{source['archive_sha256']}  /tmp/source.tar.gz' | sha256sum -c - && "
        'tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src',
        f'RUN test "$(go env GOVERSION)" = go1.26.9 && go get {modules} && go mod verify',
        f'WORKDIR /src/cmd/{component}',
        f'RUN go get {modules} && go mod verify',
        f"RUN mkdir /out && go build -trimpath -buildvcs=false -mod=readonly -ldflags '{flags}' "
        f'-o /out/{component} . && go mod verify && go version -m /out/{component} > /out/{component}.buildinfo',
        'WORKDIR /src',
        f'RUN sha256sum go.mod go.sum cmd/{component}/go.mod cmd/{component}/go.sum > /out/module-files.sha256',
        f"FROM {selected['components'][component]}",
        'WORKDIR /',
        f'COPY --from=build --chmod=0555 /out/{component} /app/cmd/{component}/{component}',
        'COPY --from=build /out/*.buildinfo /out/*.sha256 /usr/share/hooshix-source-build/',
        'USER 65532:65532', f'ENTRYPOINT ["/app/cmd/{component}/{component}"]', 'CMD []',
        'LABEL org.opencontainers.image.source="https://github.com/hasanjodatshandi/HooshiX"',
        'LABEL io.hooshix.provenance.kind="patched-upstream-source-build"',
        f'LABEL io.hooshix.source.revision="{source["revision"]}"',
        f'LABEL io.hooshix.recipe.sha256="{hashlib.sha256(RECIPE.read_bytes()).hexdigest()}"'
    ]) + '\n'


def validate_inventory(directory, component, image, now, config_digest=None):
    if component not in COMPONENTS:
        raise ValueError('known component required')
    if config_digest is None:
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', image): raise ValueError('immutable local engine required')
    elif (not re.fullmatch(r'[a-z0-9./_-]+@sha256:[a-f0-9]{64}', image)
          or not re.fullmatch(r'sha256:[a-f0-9]{64}', config_digest)):
        raise ValueError('immutable registry/config identities required')
    selected = recipe()
    syft, cdx, report, db = (load(directory / name) for name in
                            ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json'))
    require_complete_inventory(syft)
    require_unsuppressed_scan(report)
    metadata = syft['source']['metadata']
    built = datetime.fromisoformat(db['built'].replace('Z', '+00:00'))
    if (syft['source']['type'] != 'image' or metadata['userInput'] != image
            or metadata['os'] != 'linux' or metadata['architecture'] != 'amd64'
            or not re.fullmatch(r'sha256:[a-f0-9]{64}', metadata['imageID'])
            or config_digest is not None and metadata['imageID'] != config_digest
            or report['source']['target']['imageID'] != metadata['imageID']
            or report['source']['target']['userInput'] != image
            or metadata['labels'].get('io.hooshix.provenance.kind') != 'patched-upstream-source-build'
            or metadata['labels'].get('io.hooshix.source.revision') != selected['source']['revision']
            or metadata['labels'].get('io.hooshix.recipe.sha256') != hashlib.sha256(RECIPE.read_bytes()).hexdigest()
            or metadata['labels'].get('org.opencontainers.image.source') != 'https://github.com/hasanjodatshandi/HooshiX'
            or cdx.get('bomFormat') != 'CycloneDX' or not cdx.get('components')
            or db.get('valid') is not True or db.get('error') or not db.get('schemaVersion')
            or built.tzinfo is None or not 0 <= (now - built).total_seconds() <= MAX_DB_AGE.total_seconds()
            or not isinstance(report.get('matches'), list)):
        raise ValueError('exact complete fresh candidate inventory required')
    for match in report['matches']:
        if match['vulnerability']['severity'] not in ('Negligible', 'Low', 'Medium', 'Unknown'):
            raise ValueError('High/Critical or unknown severity rejected')
    standard = [item for item in syft['artifacts'] if item['name'] == 'stdlib']
    if (not standard or any(item['version'] != 'go1.26.9' for item in standard)
            or f'/app/cmd/{component}/{component}' not in
               {place['path'] for item in standard for place in item['locations']}):
        raise ValueError('every Go executable must use fixed compiler')
    for module, version in MODULES.items():
        items = [item for item in syft['artifacts'] if item['name'] == module]
        if (module == 'golang.org/x/net' and not items
                or any(item['version'] != version for item in items)):
            raise ValueError('fixed linked dependencies required')
    return {'schema_version': 1, 'component': component, 'version': '1.20.3', 'scan': 'Passed',
        'image_config_digest': metadata['imageID'], 'provenance_kind': 'patched-upstream-source-build',
        'recipe_sha256': hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
        ('local_engine_digest' if config_digest is None else 'registry_image'): image,
        'signature_provenance': 'Not verified', 'staging': 'Not verified',
        'deployment': 'Not verified', 'production_promotion': 'Not verified'}


def native_entrypoint(component, engine, endpoint):
    if (component not in COMPONENTS or not re.fullmatch(r'sha256:[a-f0-9]{64}', engine)
            or not endpoint.startswith('unix://')):
        raise ValueError('fixed component, engine and local endpoint required')
    config = json.loads(run(['docker', '--host', endpoint, 'image', 'inspect', '--format', '{{json .Config}}', engine]))
    if (config['User'] != '65532:65532' or config['Entrypoint'] != [f'/app/cmd/{component}/{component}']
            or config.get('Cmd') not in (None, [])):
        raise ValueError('fixed non-root native entrypoint required')
    run(['docker', '--host', endpoint, 'run', '--rm', '--network', 'none', '--read-only',
         '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '256m',
         '--cpus', '1', '--pids-limit', '64', engine, '--help'], timeout=30)


def build(component, directory, *, docker_host=None):
    if component not in COMPONENTS: raise ValueError('unapproved cert-manager component')
    selected = recipe()
    endpoint = local_endpoint() if docker_host is None else docker_host
    if not endpoint.startswith('unix://'): raise ValueError('local Unix Docker required')
    directory.mkdir(mode=0o700)
    source = selected['source']
    url = f"https://codeload.github.com/{source['repository']}/tar.gz/{source['revision']}"
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read(128 * 1024 * 1024 + 1)
    if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != source['archive_sha256']:
        raise ValueError('bounded official source integrity required')
    (directory / 'source.tar.gz').write_bytes(data)
    (directory / 'Dockerfile').write_text(dockerfile(component, selected))
    (directory / '.dockerignore').write_text('*\n!source.tar.gz\n!Dockerfile\n')
    identity = directory / 'image-id.txt'
    try:
        run(['docker', '--host', endpoint, 'build', '--platform', 'linux/amd64',
             '--iidfile', str(identity), str(directory)], timeout=2700)
    except subprocess.CalledProcessError as error:
        # Classify public failure causes without printing child arguments or raw output.
        reason = 'native-build-failure'
        for phrase, category in (('403 Forbidden', 'dependency-transport-denied'),
                ('undefined:', 'source-compatibility'), ('cannot use ', 'source-compatibility'),
                ('missing go.sum entry', 'module-integrity'),
                ('updates to go.mod needed', 'module-lock-drift'),
                ('no space left on device', 'build-capacity')):
            if phrase in (error.stderr or ''):
                reason = category
                break
        print('CERT_MANAGER_BUILD_FAILURE=' + reason, flush=True)
        raise
    engine = identity.read_text().strip()
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', engine): raise ValueError('immutable engine identity required')
    native_entrypoint(component, engine, endpoint)
    scan_inventory(directory, engine, 'docker', docker_host=endpoint)
    result = validate_inventory(directory, component, engine, datetime.now(timezone.utc))
    result['native_entrypoint'] = 'Passed'
    (directory / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', required=True, choices=COMPONENTS)
    parser.add_argument('--directory', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = build(args.component, args.directory)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'CERT_MANAGER_CANDIDATE=Failed; no publication or install\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
