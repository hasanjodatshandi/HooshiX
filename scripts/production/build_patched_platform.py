"""Build and scan fixed-source candidates; never change production pins or deploy."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from publish_mesh_candidate import scan, validate as registry_scan
from publish_openbao_candidate import context, run, verify_payload
from render_mesh_candidate import pins, values
from verify_eso_artifact import pin as eso_pin
from verify_openbao_artifact import ROOT, MAX_DB_AGE, load
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER

COMPONENTS = ('eso', 'openbao', 'istiod', 'cni')
BUILD_TYPE = 'https://github.com/hasanjodatshandi/HooshiX/platform-security-source-build/v1'
RECIPE = ROOT / 'infrastructure/production/release/patched-platform-sources.json'
SOURCE = {'eso': 'eso', 'openbao': 'openbao', 'istiod': 'istio', 'cni': 'istio'}
BINARIES = {'eso': {'main.go': '/usr/bin/external-secrets'}, 'openbao': {'.': '/usr/bin/bao'},
    'istiod': {'./pilot/cmd/pilot-discovery': '/usr/local/bin/pilot-discovery'},
    'cni': {'./cni/cmd/install-cni': '/usr/local/bin/install-cni',
            './cni/cmd/istio-cni': '/opt/cni/bin/istio-cni'}}


def recipe():
    selected = load(RECIPE)
    if (selected['schema_version'] != 1 or selected['go_version'] != '1.26.9'
            or selected['builder'] != 'docker.io/library/golang@sha256:'
               'bcef992b77b1e2031aaaa51da75cebc32da8e9c182e12e633ea79023c79d9eff'
            or selected['modules'] != {'golang.org/x/net': 'v0.60.0',
                'golang.org/x/crypto': 'v0.57.0', 'golang.org/x/sys': 'v0.48.0',
                'golang.org/x/term': 'v0.46.0', 'golang.org/x/text': 'v0.42.0'}
            or selected['production_promotion'] !=
               'blocked-until-signed-digest-admission-native-and-recovery-evidence'):
        raise ValueError('reviewed blocked security build required')
    expected = {'eso': ('external-secrets/external-secrets', eso_pin()['upstream_tag_revision'], '2.12.0'),
        'openbao': ('openbao/openbao', load(ROOT / 'infrastructure/production/secrets/openbao-image.json')['source_revision'], '2.6.4'),
        'istio': ('istio/istio', '55b832fad2bc55f47e658b68cd1a7abf9540dbc9', pins()['ISTIO_VERSION'])}
    if set(selected['sources']) != set(expected):
        raise ValueError('exact source inventory required')
    for name, (repository, revision, version) in expected.items():
        source = selected['sources'][name]
        if (source['repository'] != repository or source['revision'] != revision
                or source['version'] != version
                or not re.fullmatch(r'[a-f0-9]{40}', revision)
                or not re.fullmatch(r'[a-f0-9]{64}', source['archive_sha256'])):
            raise ValueError('reviewed source and checksum required')
    return selected


def base_image(component):
    if component == 'eso':
        return eso_pin()['image']
    if component == 'openbao':
        return load(ROOT / 'infrastructure/production/secrets/openbao-image.json')['image']
    if component in ('istiod', 'cni'):
        return values(component, pins())['image']
    raise ValueError('unapproved component')


def dockerfile(component, selected):
    source = selected['sources'][SOURCE[component]]
    flags = '-trimpath -buildvcs=false -mod=readonly'
    ldflags = '-buildid='
    tags = 'all_providers' if component == 'eso' else ''
    if component == 'openbao':
        ldflags += ' -X github.com/openbao/openbao/version.fullVersion=2.6.4'
        ldflags += ' -X github.com/openbao/openbao/version.GitCommit=' + source['revision']
    if component in ('istiod', 'cni'):
        for key, value in {'buildVersion': '1.30.5', 'buildGitRevision': source['revision'],
                'buildStatus': 'Modified', 'buildTag': '1.30.5', 'buildHub': 'docker.io/istio',
                'buildOS': 'linux', 'buildArch': 'amd64'}.items():
            ldflags += ' -X istio.io/istio/pkg/version.' + key + '=' + value
    modules = ' '.join(name + '@' + version for name, version in selected['modules'].items())
    lines = [f"FROM {selected['builder']} AS build", 'WORKDIR /src',
        'ENV CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2',
        'ENV GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org',
        'COPY source.tar.gz /tmp/source.tar.gz',
        f"RUN echo '{source['archive_sha256']}  /tmp/source.tar.gz' | sha256sum -c - && "
        'tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src',
        f"RUN test \"$(go env GOVERSION)\" = go{selected['go_version']} && go get {modules} && go mod download && go mod verify",
        'RUN mkdir /out && sha256sum go.mod go.sum > /out/module-files.sha256 && go list -m -json all > /out/modules.json']
    for package, destination in BINARIES[component].items():
        name = Path(destination).name
        lines.append(f"RUN go build {flags} -tags '{tags}' -ldflags '{ldflags}' -o /out/{name} {package} && go version -m /out/{name} > /out/{name}.buildinfo")
    lines.append('FROM ' + base_image(component))
    for destination in BINARIES[component].values():
        lines.append(f'COPY --from=build --chmod=0555 /out/{Path(destination).name} {destination}')
    lines += ['COPY --from=build /out/*.sha256 /out/*.json /out/*.buildinfo /usr/share/hooshix-source-build/',
        'LABEL io.hooshix.provenance.kind="patched-upstream-source-build"',
        f'LABEL io.hooshix.source.revision="{source["revision"]}"',
        f'LABEL io.hooshix.recipe.sha256="{hashlib.sha256(RECIPE.read_bytes()).hexdigest()}"']
    return '\n'.join(lines) + '\n'


def validate_local(directory, component, image_id, now):
    selected = recipe()
    syft, cdx, report, db = (load(directory / name) for name in
        ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json'))
    metadata = syft['source']['metadata']
    if (syft['source']['type'] != 'image' or metadata['imageID'] != image_id
            or metadata['os'] != 'linux' or metadata['architecture'] != 'amd64'
            or metadata['labels'].get('io.hooshix.provenance.kind') != 'patched-upstream-source-build'
            or metadata['labels'].get('io.hooshix.source.revision') != selected['sources'][SOURCE[component]]['revision']
            or metadata['labels'].get('io.hooshix.recipe.sha256') != hashlib.sha256(RECIPE.read_bytes()).hexdigest()
            or not cdx.get('components') or cdx.get('bomFormat') != 'CycloneDX'):
        raise ValueError('exact built configuration and SBOM required')
    built = datetime.fromisoformat(db['built'].replace('Z', '+00:00'))
    if (db.get('valid') is not True or db.get('error') or not db.get('schemaVersion')
            or built.tzinfo is None or not timedelta(0) <= now - built <= MAX_DB_AGE):
        raise ValueError('fresh valid scanner database required')
    if not isinstance(report.get('matches'), list):
        raise ValueError('scanner report required')
    for match in report['matches']:
        if match['vulnerability']['severity'] not in ('Negligible', 'Low', 'Medium', 'Unknown'):
            raise ValueError('High/Critical or unknown severity rejected')
    artifacts = syft.get('artifacts', [])
    standard = [artifact for artifact in artifacts if artifact['name'] == 'stdlib']
    if not standard or any(item['version'] != 'go' + selected['go_version'] for item in standard):
        raise ValueError('every embedded Go binary must use fixed toolchain')
    locations = {location['path'] for item in standard for location in item['locations']}
    if not set(BINARIES[component].values()) <= locations:
        raise ValueError('complete executable inventory required')
    net = [artifact for artifact in artifacts if artifact['name'] == 'golang.org/x/net']
    if not net or any(item['version'] != selected['modules']['golang.org/x/net'] for item in net):
        raise ValueError('fixed network dependency required')
    return {'schema_version': 1, 'component': component, 'image_config_digest': image_id,
        'scan': 'Passed', 'recipe_sha256': hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
        'provenance_kind': 'patched-upstream-source-build', 'production_promotion': 'Not verified'}


def build(component, directory):
    selected = recipe()
    source = selected['sources'][SOURCE[component]]
    directory.mkdir(mode=0o700)
    url = f"https://codeload.github.com/{source['repository']}/tar.gz/{source['revision']}"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(128 * 1024 * 1024 + 1)
    if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != source['archive_sha256']:
        raise ValueError('bounded official source integrity required')
    (directory / 'source.tar.gz').write_bytes(data)
    (directory / 'Dockerfile').write_text(dockerfile(component, selected))
    (directory / '.dockerignore').write_text('*\n!source.tar.gz\n!Dockerfile\n')
    tag = 'hooshix-patched-' + component + ':candidate'
    run(['docker', 'build', '--platform', 'linux/amd64', '--tag', tag, str(directory)], timeout=2700)
    image_id = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag]).strip()
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id):
        raise ValueError('immutable local image configuration required')
    container = run(['docker', 'create', image_id]).strip()
    if not re.fullmatch(r'[a-f0-9]{64}', container):
        raise ValueError('owned stopped inspection container required')
    try:
        run(['docker', 'cp', container + ':/usr/share/hooshix-source-build/.',
             str(directory / 'module-evidence')])
    finally:
        run(['docker', 'rm', '--volumes', container])
    (directory / 'database.json').write_text(run(['grype', 'db', 'status', '-o', 'json']))
    run(['syft', 'scan', image_id, '--from', 'docker', '--platform', 'linux/amd64',
        '-o', 'syft-json=' + str(directory / 'syft.json'),
        '-o', 'cyclonedx-json=' + str(directory / 'cyclonedx.json')], timeout=300)
    scan(directory)
    receipt = validate_local(directory, component, image_id, datetime.now(timezone.utc))
    module_files = directory / 'module-evidence/module-files.sha256'
    locks = module_files.read_text().splitlines()
    if len(locks) != 2 or not all(re.fullmatch(r'[a-f0-9]{64}  go\.(mod|sum)', line) for line in locks):
        raise ValueError('actual module lock hashes required')
    receipt['module_files_sha256'] = {line.split()[1]: line.split()[0] for line in locks}
    if set(receipt['module_files_sha256']) != {'go.mod', 'go.sum'}:
        raise ValueError('both module lock hashes required')
    (directory / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return tag, receipt


def published_image(repository, source, image_id):
    metadata = source['metadata']
    digest = metadata['manifestDigest']
    if (source['type'] != 'image' or not re.fullmatch(r'sha256:[a-f0-9]{64}', digest)
            or metadata['imageID'] != image_id
            or metadata['os'] != 'linux' or metadata['architecture'] != 'amd64'
            or not isinstance(metadata['repoDigests'], list)
            or repository + '@' + digest not in metadata['repoDigests']):
        raise ValueError('exact published repository configuration and platform required')
    return repository + '@' + digest


def publish(directory, env):
    revision, invocation = context(env)
    if run(['git', 'rev-parse', 'HEAD']).strip() != revision:
        raise ValueError('exact protected checkout required')
    directory.mkdir(mode=0o700)
    built = {component: build(component, directory / component) for component in COMPONENTS}
    run([sys.executable, str(ROOT / 'scripts/production/rehearse_openbao_recovery.py'), '--ci',
         '--image-config-digest', built['openbao'][1]['image_config_digest']], timeout=420)
    (directory / 'openbao/native-recovery.json').write_text(json.dumps({
        'schema_version': 1, 'image_config_digest': built['openbao'][1]['image_config_digest'],
        'disposable_tls_shamir_raft_restore_acl_audit_root_revoke': 'Passed',
        'production_readiness': 'Not verified'}) + '\n')
    selected, targets, visibilities = recipe(), {}, {}
    for component, (tag, receipt) in built.items():
        print('PATCHED_PUBLICATION_STEP=' + component + '-push', flush=True)
        package_component = 'eso-v2' if component == 'eso' else component
        package = 'hooshix/platform-' + package_component + '-patched-private'
        repository = 'ghcr.io/hasanjodatshandi/' + package
        target = repository + ':candidate-' + revision + '-' + invocation.replace(':', '-')
        run(['docker', 'tag', tag, target])
        run(['docker', 'push', target], timeout=300)
        print('PATCHED_PUBLICATION_STEP=' + component + '-visibility', flush=True)
        visibility = run(['gh', 'api', 'users/hasanjodatshandi/packages/container/'
            + package.replace('/', '%2F'), '--jq', '.visibility']).strip()
        # Owner approval is limited to the fixed ESO package above.
        allowed = ('private', 'public') if component == 'eso' else ('private',)
        if visibility not in allowed:
            print('PATCHED_PACKAGE_VISIBILITY=Rejected; unapproved visibility', flush=True)
            raise ValueError('approved candidate package visibility required')
        visibilities[component] = visibility
        folder = directory / component
        print('PATCHED_PUBLICATION_STEP=' + component + '-registry-resolve', flush=True)
        # Resolve remotely: Docker's local RepoDigests is not registry authority.
        run(['syft', 'scan', target, '--from', 'registry', '--platform', 'linux/amd64',
             '-o', 'syft-json=' + str(folder / 'registry-resolution.json')], timeout=300)
        image = published_image(repository, load(folder / 'registry-resolution.json')['source'],
                                receipt['image_config_digest'])
        print('PATCHED_PUBLICATION_STEP=' + component + '-digest-scan', flush=True)
        run(['syft', 'scan', image, '--from', 'registry', '--platform', 'linux/amd64',
            '-o', 'syft-json=' + str(folder / 'syft.json'),
            '-o', 'cyclonedx-json=' + str(folder / 'cyclonedx.json')], timeout=300)
        if load(folder / 'syft.json')['source']['metadata']['imageID'] != receipt['image_config_digest']:
            raise ValueError('published configuration changed')
        scan(folder)
        registry_scan(folder, image, datetime.now(timezone.utc))
        targets[component] = image
    # Every final registry digest passes before any component is signed.
    flags = ['--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY,
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER]
    for component, image in targets.items():
        print('PATCHED_PUBLICATION_STEP=' + component + '-sign-verify', flush=True)
        folder = directory / component
        source = selected['sources'][SOURCE[component]]
        predicate = {'buildDefinition': {'buildType': BUILD_TYPE,
            'externalParameters': {'component': component, 'source': source,
                'recipe_sha256': built[component][1]['recipe_sha256'], 'modules': selected['modules'],
                'module_files_sha256': built[component][1]['module_files_sha256'],
                'builder': selected['builder'], 'runtime_base': base_image(component),
                'go_version': selected['go_version'], 'repository_revision': revision},
            'internalParameters': {},
            'resolvedDependencies': [{'uri': 'git+https://github.com/' + source['repository'],
                'digest': {'gitCommit': source['revision']}}]},
            'runDetails': {'builder': {'id': EXPECTED_CERTIFICATE_IDENTITY},
                           'metadata': {'invocationId': invocation}}}
        path = folder / 'source-provenance.json'
        path.write_text(json.dumps(predicate, sort_keys=True) + '\n')
        run(['cosign', 'sign', '--yes', image])
        for kind, filename, uri, expected in (
                ('slsaprovenance1', path, 'https://slsa.dev/provenance/v1', predicate),
                ('cyclonedx', folder / 'cyclonedx.json', 'https://cyclonedx.org/bom', load(folder / 'cyclonedx.json'))):
            run(['cosign', 'attest', '--yes', '--predicate', str(filename), '--type', kind, image])
            verify_payload(run(['cosign', 'verify-attestation', '--type', kind, *flags, image]),
                           uri, expected, image.split('@sha256:')[1])
        run(['cosign', 'verify', *flags, image])
        try:
            run(['cosign', 'verify', '--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY + '.wrong',
                 '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER, image])
        except subprocess.CalledProcessError:
            pass
        else:
            raise ValueError('wrong signer accepted')
    return {'schema_version': 1, 'provenance_kind': 'patched-upstream-source-build',
        'repository_revision': revision, 'images': targets, 'registry_visibility': visibilities,
        'publication': 'Passed',
        'runtime_admission': 'Not verified', 'production_promotion': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=COMPONENTS)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    try:
        if args.publish:
            import os
            if args.component:
                raise ValueError('whole-set publication required')
            result = publish(args.directory, dict(os.environ))
            (args.directory / 'publication.json').write_text(json.dumps(result, indent=2) + '\n')
        else:
            if not args.component:
                raise ValueError('component required')
            build(args.component, args.directory)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        parser.exit(1, 'PATCHED_PLATFORM=Failed; evidence preserved; no deployment\n')
    print('PATCHED_PLATFORM=Passed; candidate only; runtime Not verified')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
