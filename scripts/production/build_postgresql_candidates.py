"""Build isolated fixed-Go PostgreSQL candidates; never publish, adopt or install."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from publish_openbao_candidate import run
from verify_openbao_artifact import ROOT, MAX_DB_AGE, load
from verify_postgresql_artifacts import (BLOCKED, pins, require_unsuppressed_scan,
                                         require_complete_inventory, scan_inventory)

RECIPE = ROOT / 'infrastructure/production/data/postgresql-source-builds.json'
COMPONENTS = ('operator', 'barman-plugin', 'barman-sidecar')
BINARIES = {'operator': {'amd64': '/operator/manager_amd64', 'arm64': '/operator/manager_arm64'},
            'barman-plugin': {'amd64': '/manager'}, 'barman-sidecar': {'amd64': '/manager'}}
MODULES = {'golang.org/x/net': 'v0.60.0', 'golang.org/x/crypto': 'v0.57.0',
           'golang.org/x/sys': 'v0.48.0', 'golang.org/x/term': 'v0.46.0',
           'golang.org/x/text': 'v0.42.0'}


def recipe():
    selected, catalog = load(RECIPE), pins()['components']
    expected = {'operator': ('cloudnative-pg/cloudnative-pg', catalog['operator']['source_revision']),
                'barman': ('cloudnative-pg/plugin-barman-cloud', catalog['barman-plugin']['upstream_tag_revision'])}
    if (selected['schema_version'] != 1 or selected['production_promotion'] != BLOCKED
            or selected['go_version'] != '1.26.9' or selected['modules'] != MODULES
            or selected['builder'] != 'docker.io/library/golang@sha256:'
               'bcef992b77b1e2031aaaa51da75cebc32da8e9c182e12e633ea79023c79d9eff'
            or selected['plugin_runtime'] != 'gcr.io/distroless/static-debian13@sha256:'
               'e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3'
            or set(selected['sources']) != set(expected)
            or catalog['barman-sidecar']['upstream_tag_revision'] != expected['barman'][1]):
        raise ValueError('reviewed coordinated blocked source build required')
    for key, (repository, revision) in expected.items():
        source = selected['sources'][key]
        if (source['repository'] != repository or source['revision'] != revision
                or not re.fullmatch(r'[a-f0-9]{40}', revision)
                or not re.fullmatch(r'[a-f0-9]{64}', source['archive_sha256'])):
            raise ValueError('exact official source and integrity required')
    return selected


def dockerfile(component, selected):
    if component not in COMPONENTS:
        raise ValueError('known fixed-source component required')
    source = selected['sources']['operator' if component == 'operator' else 'barman']
    modules = ' '.join(name + '@' + version for name, version in selected['modules'].items())
    lines = [f"FROM {selected['builder']} AS build", 'WORKDIR /src',
        'ENV CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2',
        'ENV GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org',
        'COPY source.tar.gz /tmp/source.tar.gz',
        f"RUN echo '{source['archive_sha256']}  /tmp/source.tar.gz' | sha256sum -c - && "
        'tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src',
        f"RUN test \"$(go env GOVERSION)\" = go{selected['go_version']} && go get {modules} && go mod download && go mod verify",
        'RUN mkdir /out && sha256sum go.mod go.sum > /out/module-files.sha256 && go list -m -json all > /out/modules.json']
    flags = '-trimpath -buildvcs=false -mod=readonly'
    ldflags = '-buildid='
    if component == 'operator':
        prefix = 'github.com/cloudnative-pg/cloudnative-pg/pkg/versions.'
        ldflags += f' -X {prefix}buildVersion=1.30.1 -X {prefix}buildCommit=' + source['revision']
    for architecture, destination in BINARIES[component].items():
        name = Path(destination).name
        lines.append(f"RUN GOARCH={architecture} go build {flags} -ldflags '{ldflags}' "
                     f"-o /out/{name} ./cmd/manager && go version -m /out/{name} > /out/{name}.buildinfo")
    base = (selected['plugin_runtime'] if component == 'barman-plugin'
            else pins()['components'][component]['image'])
    lines.append('FROM ' + base)
    if component == 'barman-plugin':
        lines += ['WORKDIR /', 'COPY --from=build /src/LICENSE /licenses/LICENSE',
                  'USER 65532:65532', 'ENTRYPOINT ["/manager"]']
    for destination in BINARIES[component].values():
        lines.append(f'COPY --from=build --chmod=0555 /out/{Path(destination).name} {destination}')
    lines += ['COPY --from=build /out/*.sha256 /out/*.json /out/*.buildinfo /usr/share/hooshix-source-build/',
        'LABEL io.hooshix.provenance.kind="patched-upstream-source-build"',
        f'LABEL io.hooshix.source.revision="{source["revision"]}"',
        f'LABEL io.hooshix.recipe.sha256="{hashlib.sha256(RECIPE.read_bytes()).hexdigest()}"']
    return '\n'.join(lines) + '\n'


def validate_local(directory, component, image_id, now):
    if component not in COMPONENTS or not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id):
        raise ValueError('known component and exact configuration required')
    selected = recipe()
    source = selected['sources']['operator' if component == 'operator' else 'barman']
    syft, cdx, report, db = (load(directory / name) for name in
        ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json'))
    require_complete_inventory(syft)
    metadata = syft['source']['metadata']
    if (syft['source']['type'] != 'image' or metadata['imageID'] != image_id
            or metadata['os'] != 'linux' or metadata['architecture'] != 'amd64'
            or metadata['labels'].get('io.hooshix.provenance.kind') != 'patched-upstream-source-build'
            or metadata['labels'].get('io.hooshix.source.revision') != source['revision']
            or metadata['labels'].get('io.hooshix.recipe.sha256') != hashlib.sha256(RECIPE.read_bytes()).hexdigest()
            or report['source']['target']['imageID'] != image_id
            or not cdx.get('components') or cdx.get('bomFormat') != 'CycloneDX'):
        raise ValueError('exact built configuration, provenance and SBOM required')
    built = datetime.fromisoformat(db['built'].replace('Z', '+00:00'))
    if (db.get('valid') is not True or db.get('error') or not db.get('schemaVersion')
            or built.tzinfo is None or not timedelta(0) <= now - built <= MAX_DB_AGE
            or not isinstance(report.get('matches'), list)):
        raise ValueError('fresh valid scanner database and complete matches required')
    for match in report['matches']:
        if match['vulnerability']['severity'] not in ('Negligible', 'Low', 'Medium', 'Unknown'):
            raise ValueError('High/Critical and invalid severity rejected')
    require_unsuppressed_scan(report)
    standard = [item for item in syft['artifacts'] if item['name'] == 'stdlib']
    locations = {place['path'] for item in standard for place in item['locations']}
    net = [item for item in syft['artifacts'] if item['name'] == 'golang.org/x/net']
    if (not standard or any(item['version'] != 'go' + selected['go_version'] for item in standard)
            or not set(BINARIES[component].values()) <= locations or not net
            or any(item['version'] != MODULES['golang.org/x/net'] for item in net)):
        raise ValueError('every embedded Go executable and network dependency must be fixed')
    return {'schema_version': 1, 'component': component, 'scan': 'Passed',
        'image_config_digest': image_id, 'provenance_kind': 'patched-upstream-source-build',
        'recipe_sha256': hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
        'staging': 'Not verified', 'signature_provenance': 'Not verified',
        'deployment': 'Not verified', 'production_promotion': 'Not verified'}


def build(component, directory):
    if component not in COMPONENTS:
        raise ValueError('known component required')
    selected = recipe()
    endpoint = json.loads(run(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']))
    if not endpoint.startswith('unix://') or not os.environ.get('DOCKER_HOST', 'unix://').startswith('unix://'):
        raise ValueError('local Unix-socket Docker required; no remote daemon')
    directory.mkdir(mode=0o700)  # Never reuse prior success or evidence.
    source = selected['sources']['operator' if component == 'operator' else 'barman']
    url = f"https://codeload.github.com/{source['repository']}/tar.gz/{source['revision']}"
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read(128 * 1024 * 1024 + 1)
    if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != source['archive_sha256']:
        raise ValueError('bounded official source integrity required')
    (directory / 'source.tar.gz').write_bytes(data)
    (directory / 'Dockerfile').write_text(dockerfile(component, selected))
    (directory / '.dockerignore').write_text('*\n!source.tar.gz\n!Dockerfile\n')
    tag = 'hooshix-postgresql-' + component + ':candidate'
    run(['docker', 'build', '--platform', 'linux/amd64', '--tag', tag, str(directory)], timeout=2700)
    image_id = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag]).strip()
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id):
        raise ValueError('immutable local image configuration required')
    config = json.loads(run(['docker', 'image', 'inspect', '--format', '{{json .Config}}', image_id]))
    expected_user = '26:26' if component == 'barman-sidecar' else '65532:65532'
    if config.get('User') != expected_user or config.get('Entrypoint') != ['/manager']:
        raise ValueError('non-root identity and native manager entrypoint required')
    run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
         '--security-opt', 'no-new-privileges',
         '--memory', '256m', '--cpus', '1', '--pids-limit', '64', image_id, '--help'], timeout=30)
    # Entrypoint smoke is not Kubernetes reconciliation, backup or recovery evidence.
    scan_inventory(directory, image_id, 'docker')
    receipt = validate_local(directory, component, image_id, datetime.now(timezone.utc))
    receipt['native_entrypoint'] = 'Passed'
    (directory / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=COMPONENTS, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        receipt = build(args.component, args.directory)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        parser.exit(1, 'POSTGRESQL_SOURCE_CANDIDATE=Failed; state retained; no publication or installation\n')
    print(json.dumps(receipt, sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
