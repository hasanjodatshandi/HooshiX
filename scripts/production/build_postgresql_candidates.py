"""Build isolated fixed-Go/runtime PostgreSQL candidates; never publish or install."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from publish_openbao_candidate import run
from verify_openbao_artifact import ROOT, MAX_DB_AGE, load
from verify_postgresql_artifacts import (BLOCKED, pins, require_unsuppressed_scan,
                                         require_complete_inventory, scan_inventory)

RECIPE = ROOT / 'infrastructure/production/data/postgresql-source-builds.json'
COMPONENTS = ('operator', 'postgresql', 'barman-plugin', 'barman-sidecar')
BINARIES = {'operator': {'amd64': '/operator/manager_amd64', 'arm64': '/operator/manager_arm64'},
            'barman-plugin': {'amd64': '/manager'}, 'barman-sidecar': {'amd64': '/manager'}}
MODULES = {'golang.org/x/net': 'v0.60.0', 'golang.org/x/crypto': 'v0.57.0',
           'golang.org/x/sys': 'v0.48.0', 'golang.org/x/term': 'v0.46.0',
           'golang.org/x/text': 'v0.42.0'}
RUNTIME_PACKAGES = {'libcrypto3': '3.5.9-r0', 'libssl3': '3.5.9-r0', 'zlib': '1.3.2-r1'}
SIDECAR_PACKAGES = {'libpq': '18.6-r0', 'lz4-libs': '1.10.0-r1', 'snappy': '1.2.2-r0',
                    'libstdc++': '15.2.0-r5', 'tar': '1.35-r5'}
BUILD_PACKAGES = {'build-base': '0.5-r4', 'cargo': '1.96.1-r0', 'postgresql18-dev': '18.6-r0',
                  'lz4-dev': '1.10.0-r1', 'snappy-dev': '1.2.2-r0',
                  'libffi-dev': '3.5.2-r1', 'openssl-dev': '3.5.9-r0'}
CERTIFI_VERSION = '2026.10.19'
CERTIFI_HASHES = ('035c1e85572b141bf1c3a3eff0e33a8eb74917522f2559075ff8d2fea780a1ae',
                  '42d35340cc5b9ba78e818ddbff0c5b1c2b779de448a340fd1b989f5fa8670e1b')
COMPROMISED_ROOT = '86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b'
RETAINED_ROOTS = ('40f6af0346a99aa1cd1d555a4e9cce62c7f9634603ee406615833dc8c8d00367',
                  '125609aa301da0a249b97a8239cb6a34216f44dcac9f3954b14292f2e8c8608f',
                  'bc4d809b15189d78db3e1d8cf4f9726a795da1643ca5f1358e1ddb0edc0d7eb3')


def certifi_lock_patch():
    old = ('certifi==2026.7.22 \\\n'
           '    --hash=sha256:62f22742b58a1a33014a2b6b706588a8d7e2a88ae7bd1a6ebe8c992928483775 \\\n'
           '    --hash=sha256:741e2c3b351ddf169a738da9f2c048608ff7f2c5cc02f1ebc6b118bb090d5d55\n')
    new = ('certifi==' + CERTIFI_VERSION + ' \\\n    --hash=sha256:'
           + ' \\\n    --hash=sha256:'.join(CERTIFI_HASHES) + '\n')
    return ('from pathlib import Path; import sys; p=Path(sys.argv[1]); s=p.read_text(); '
            f'old={old!r}; new={new!r}; '
            'p.write_text(s.replace(old,new)) if s.count(old)==1 else '
            'sys.exit("exact upstream certifi lock required")')


def trust_material_check():
    return f'''import certifi, hashlib, re, ssl, sys
from pathlib import Path
bad = {COMPROMISED_ROOT!r}
retained = set({RETAINED_ROOTS!r})
def roots(path):
    ctx = ssl.create_default_context(cafile=str(path))
    if not ctx.check_hostname or ctx.verify_mode != ssl.CERT_REQUIRED:
        raise SystemExit("verified TLS required")
    return {{hashlib.sha256(c).hexdigest() for c in ctx.get_ca_certs(binary_form=True)}}
if certifi.__version__ != {CERTIFI_VERSION!r}:
    raise SystemExit("fixed certifi required")
paths = [Path(certifi.where()), Path(sys.prefix + '/lib/python3.14/site-packages/pip/_vendor/certifi/cacert.pem'),
         Path(sys.base_prefix + '/lib/python3.14/site-packages/pip/_vendor/certifi/cacert.pem'),
         Path('/etc/ssl/cert.pem')]
for path in paths:
    values = roots(path)
    if bad in values or not retained <= values:
        raise SystemExit("compromised or incomplete trust store")
ctx = ssl.create_default_context()
if bad in {{hashlib.sha256(c).hexdigest() for c in ctx.get_ca_certs(binary_form=True)}}:
    raise SystemExit("compromised default trust")
for directory in ('/etc/ssl/certs', '/usr/share/ca-certificates', '/usr/local/share/ca-certificates'):
    for path in Path(directory).rglob('*'):
        if path.is_file():
            for block in re.findall(br'-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----', path.read_bytes()):
                pem = block.decode('ascii')
                if hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest() == bad:
                    raise SystemExit("compromised certificate-directory route")
import botocore, requests.certs
if bad in roots(Path(botocore.__file__).with_name('cacert.pem')):
    raise SystemExit("compromised provider trust store")
if requests.certs.where() != certifi.where():
    raise SystemExit("unexpected Requests trust store")
'''


def trust_material_patch():
    # Pip has two vendored stores; system regeneration must also remove the hashed G3 route.
    return f'''import certifi, hashlib, shutil, ssl, sys
from pathlib import Path
source = Path(certifi.where())
if certifi.__version__ != {CERTIFI_VERSION!r}:
    raise SystemExit("fixed certifi required")
anchor = Path('/usr/share/ca-certificates/mozilla/emSign_ECC_Root_CA_-_G3.crt')
if anchor.is_symlink() or not anchor.is_file() or hashlib.sha256(
        ssl.PEM_cert_to_DER_cert(anchor.read_text())).hexdigest() != {COMPROMISED_ROOT!r}:
    raise SystemExit("reviewed compromised system anchor required")
for prefix in (sys.prefix, sys.base_prefix):
    target = Path(prefix + '/lib/python3.14/site-packages/pip/_vendor/certifi/cacert.pem')
    if target.is_symlink() or not target.is_file():
        raise SystemExit("reviewed pip trust store required")
    shutil.copyfile(source, target)
anchor.unlink()
'''


def source_key(component):
    return component if component in ('operator', 'postgresql') else 'barman'


def provenance(component):
    return 'patched-upstream-runtime-build' if component == 'postgresql' else 'patched-upstream-source-build'


def recipe():
    selected, catalog = load(RECIPE), pins()['components']
    expected = {'operator': ('cloudnative-pg/cloudnative-pg', catalog['operator']['source_revision']),
                'barman': ('cloudnative-pg/plugin-barman-cloud', catalog['barman-plugin']['upstream_tag_revision']),
                'postgresql': ('docker-library/postgres', 'e00e1bd34ec5c8a8e7ad89b273b3d42efaf6d5bc')}
    if (selected['schema_version'] != 1 or selected['production_promotion'] != BLOCKED
            or selected['go_version'] != '1.26.9' or selected['modules'] != MODULES
            or selected['builder'] != 'docker.io/library/golang@sha256:'
               'bcef992b77b1e2031aaaa51da75cebc32da8e9c182e12e633ea79023c79d9eff'
            or selected['plugin_runtime'] != 'gcr.io/distroless/static-debian13@sha256:'
               'e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3'
            or selected['postgresql_runtime'] != 'docker.io/library/postgres@sha256:'
               'd8703cd7fba306b9fec9268ecedfa8a966846c053036a60e3635791957eb2f66'
            or selected['sidecar_runtime'] != 'docker.io/library/python@sha256:'
               '7f2ec713f0436c59df2362d973b6a05af9e87ba78f0e94f5245c302984a7a1fd'
            or selected['python_version'] != '3.14.8' or selected['barman_version'] != '3.20.1'
            or selected['alpine_version'] != '3.24.2'
            or selected['runtime_packages'] != RUNTIME_PACKAGES
            or selected['sidecar_packages'] != SIDECAR_PACKAGES
            or selected['python_build_packages'] != BUILD_PACKAGES
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
    source = selected['sources'][source_key(component)]
    runtime_packages = ' '.join(name + '=' + version for name, version in selected['runtime_packages'].items())
    if component == 'postgresql':
        lines = [f"FROM {selected['postgresql_runtime']}", 'WORKDIR /', 'USER 0:0',
                 f'RUN apk add --no-cache {runtime_packages} && '
                 'adduser -D -H -u 26 -G tape -s /sbin/nologin cnpg',
                 'USER 26:26', 'ENTRYPOINT ["/usr/local/bin/postgres"]', 'CMD []']
        lines += ['LABEL org.opencontainers.image.source="https://github.com/hasanjodatshandi/HooshiX"',
                  f'LABEL io.hooshix.provenance.kind="{provenance(component)}"',
                  f'LABEL io.hooshix.source.revision="{source["revision"]}"',
                  f'LABEL io.hooshix.recipe.sha256="{hashlib.sha256(RECIPE.read_bytes()).hexdigest()}"']
        return '\n'.join(lines) + '\n'
    modules = ' '.join(name + '@' + version for name, version in selected['modules'].items())
    lines = [f"FROM {selected['builder']} AS build", 'WORKDIR /src',
        'ENV CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local GOWORK=off GOMAXPROCS=2',
        'ENV GOPROXY="https://proxy.golang.org|direct" GOSUMDB=sum.golang.org',
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
    if component == 'barman-sidecar':
        build_packages = ' '.join(name + '=' + version for name, version in selected['python_build_packages'].items())
        sidecar_packages = ' '.join(name + '=' + version for name, version in selected['sidecar_packages'].items())
        lines += [f"FROM {selected['sidecar_runtime']} AS pythonbuild", 'WORKDIR /build',
                  f'RUN apk add --no-cache {runtime_packages} {build_packages}',
                  'COPY --from=build /src/containers/sidecar-requirements.txt /build/requirements.txt',
                  'RUN python3 -c ' + shlex.quote(certifi_lock_patch()) + ' /build/requirements.txt',
                  'RUN python3 -m venv /venv && /venv/bin/pip install --disable-pip-version-check '
                  '--no-cache-dir --require-hashes -r /build/requirements.txt && /venv/bin/pip check && '
                  'sha256sum /build/requirements.txt > /venv/requirements.sha256',
                  f"FROM {selected['sidecar_runtime']}", 'WORKDIR /', 'USER 0:0',
                  f'RUN apk add --no-cache {runtime_packages} {sidecar_packages} && '
                  'adduser -D -H -u 26 -G tape -s /sbin/nologin cnpg',
                  'COPY --from=pythonbuild /venv /venv', 'ENV PATH="/venv/bin:$PATH"',
                  'ENV PYTHONDONTWRITEBYTECODE=1',
                  'RUN /venv/bin/python -c ' + shlex.quote('exec(' + repr(trust_material_patch()) + ')')
                  + ' && update-ca-certificates --fresh && /venv/bin/python -c '
                  + shlex.quote('exec(' + repr(trust_material_check()) + ')'),
                  'USER 26:26', 'ENTRYPOINT ["/manager"]', 'CMD []']
    base = selected['plugin_runtime'] if component == 'barman-plugin' else pins()['components']['operator']['image']
    if component != 'barman-sidecar':
        lines.append('FROM ' + base)
    if component == 'barman-plugin':
        lines += ['WORKDIR /', 'COPY --from=build /src/LICENSE /licenses/LICENSE',
                  'USER 65532:65532', 'ENTRYPOINT ["/manager"]']
    for destination in BINARIES[component].values():
        lines.append(f'COPY --from=build --chmod=0555 /out/{Path(destination).name} {destination}')
    lines += ['COPY --from=build /out/*.sha256 /out/*.json /out/*.buildinfo /usr/share/hooshix-source-build/',
        'LABEL org.opencontainers.image.source="https://github.com/hasanjodatshandi/HooshiX"',
        'LABEL io.hooshix.provenance.kind="patched-upstream-source-build"',
        f'LABEL io.hooshix.source.revision="{source["revision"]}"',
        f'LABEL io.hooshix.recipe.sha256="{hashlib.sha256(RECIPE.read_bytes()).hexdigest()}"']
    return '\n'.join(lines) + '\n'


def validate_inventory(directory, component, image_id, now, expected_config_digest=None):
    if component not in COMPONENTS:
        raise ValueError('known component required')
    if expected_config_digest is None:
        immutable = re.fullmatch(r'sha256:[a-f0-9]{64}', image_id)
    else:
        immutable = (re.fullmatch(r'[a-z0-9./_-]+@sha256:[a-f0-9]{64}', image_id)
                     and re.fullmatch(r'sha256:[a-f0-9]{64}', expected_config_digest))
    if not immutable:
        raise ValueError('known component and immutable identity required')
    selected = recipe()
    source = selected['sources'][source_key(component)]
    syft, cdx, report, db = (load(directory / name) for name in
        ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json'))
    require_complete_inventory(syft)
    metadata = syft['source']['metadata']
    if (syft['source']['type'] != 'image' or metadata['userInput'] != image_id
            or not re.fullmatch(r'sha256:[a-f0-9]{64}', metadata['imageID'])
            or metadata['os'] != 'linux' or metadata['architecture'] != 'amd64'
            or metadata['labels'].get('io.hooshix.provenance.kind') != provenance(component)
            or metadata['labels'].get('io.hooshix.source.revision') != source['revision']
            or metadata['labels'].get('io.hooshix.recipe.sha256') != hashlib.sha256(RECIPE.read_bytes()).hexdigest()
            or metadata['labels'].get('org.opencontainers.image.source') != 'https://github.com/hasanjodatshandi/HooshiX'
            or expected_config_digest is not None and metadata['imageID'] != expected_config_digest
            or report['source']['target']['imageID'] != metadata['imageID']
            or report['source']['target']['userInput'] != image_id
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
    if component in ('postgresql', 'barman-sidecar'):
        expected_packages = selected['runtime_packages'] | (
            selected['sidecar_packages'] if component == 'barman-sidecar' else {})
        packages = [item for item in syft['artifacts'] if item.get('type') == 'apk'
                    and item['name'] in expected_packages]
        if (syft['distro'].get('id') != 'alpine' or syft['distro'].get('versionID') != selected['alpine_version']
                or {item['name'] for item in packages} != set(expected_packages)
                or any(item['version'] != expected_packages[item['name']] for item in packages)):
            raise ValueError('exact fixed Alpine runtime inventory required')
        name, version = ('postgresql', '18.6') if component == 'postgresql' else ('barman', selected['barman_version'])
        application = [item for item in syft['artifacts'] if item['name'] == name]
        if not application or any(item['version'] != version for item in application):
            raise ValueError('exact complete operand/Barman inventory required')
        if component == 'barman-sidecar':
            certifi = [item for item in syft['artifacts'] if item['name'].lower() == 'certifi']
            if not certifi or any(item['version'] != CERTIFI_VERSION for item in certifi):
                raise ValueError('every catalogued certifi package must be fixed')
    standard = [item for item in syft['artifacts'] if item['name'] == 'stdlib']
    locations = {place['path'] for item in standard for place in item['locations']}
    net = [item for item in syft['artifacts'] if item['name'] == 'golang.org/x/net']
    if component != 'postgresql' and (not standard or any(item['version'] != 'go' + selected['go_version'] for item in standard)
            or not set(BINARIES[component].values()) <= locations or not net
            or any(item['version'] != MODULES['golang.org/x/net'] for item in net)):
        raise ValueError('every embedded Go executable and network dependency must be fixed')
    return {'schema_version': 1, 'component': component, 'scan': 'Passed',
        'image_config_digest': metadata['imageID'],
        ('local_engine_digest' if expected_config_digest is None else 'registry_image'): image_id,
        'provenance_kind': provenance(component),
        'recipe_sha256': hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
        'staging': 'Not verified', 'signature_provenance': 'Not verified',
        'deployment': 'Not verified', 'production_promotion': 'Not verified'}


def validate_local(directory, component, image_id, now):
    return validate_inventory(directory, component, image_id, now)


def local_endpoint():
    endpoint = json.loads(run(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']))
    if not endpoint.startswith('unix://') or not os.environ.get('DOCKER_HOST', 'unix://').startswith('unix://'):
        raise ValueError('local Unix-socket Docker required; no remote daemon')
    return endpoint


def native_entrypoint(component, image_id, endpoint):
    """Restricted smoke for the same immutable bytes built or pulled by the caller."""
    if (component not in COMPONENTS or not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id)
            or not endpoint.startswith('unix://')):
        raise ValueError('known component, immutable engine identity and local Docker required')
    runtime = ['docker', '--host', endpoint, 'run', '--rm', '--network', 'none', '--read-only',
               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--memory', '256m', '--cpus', '1', '--pids-limit', '64']
    run(runtime + [image_id, '--version' if component == 'postgresql' else '--help'], timeout=30)
    if component == 'barman-sidecar':
        version = run(runtime + ['--entrypoint', 'python3', image_id, '-c',
                                 'import platform; print(platform.python_version())'], timeout=30).strip()
        if version != recipe()['python_version']:
            raise ValueError('exact fixed Python interpreter required')
        run(runtime + ['--entrypoint', '/venv/bin/pip', image_id, 'check'], timeout=30)
        run(runtime + ['--entrypoint', '/venv/bin/python', image_id, '-c', trust_material_check()], timeout=30)
        for command in ('barman-cloud-backup', 'barman-cloud-wal-archive',
                        'barman-cloud-restore', 'barman-cloud-wal-restore'):
            run(runtime + ['--entrypoint', '/venv/bin/' + command, image_id, '--help'], timeout=30)


def build(component, directory, export_image=False, *, docker_host=None):
    if component not in COMPONENTS:
        raise ValueError('known component required')
    selected = recipe()
    endpoint = local_endpoint() if docker_host is None else docker_host
    if not endpoint.startswith('unix://'):
        raise ValueError('captured local Unix-socket Docker required')
    directory.mkdir(mode=0o700)  # Never reuse prior success or evidence.
    source = selected['sources'][source_key(component)]
    url = f"https://codeload.github.com/{source['repository']}/tar.gz/{source['revision']}"
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read(128 * 1024 * 1024 + 1)
    if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != source['archive_sha256']:
        raise ValueError('bounded official source integrity required')
    (directory / 'source.tar.gz').write_bytes(data)
    (directory / 'Dockerfile').write_text(dockerfile(component, selected))
    (directory / '.dockerignore').write_text('*\n!source.tar.gz\n!Dockerfile\n')
    tag = 'hooshix-postgresql-' + component + ':candidate'
    identity = directory / 'image-id.txt'
    run(['docker', '--host', endpoint, 'build', '--platform', 'linux/amd64', '--tag', tag,
         '--iidfile', str(identity), str(directory)], timeout=2700)
    image_id = identity.read_text().strip()
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id):
        raise ValueError('immutable local image configuration required')
    config = json.loads(run(['docker', '--host', endpoint, 'image', 'inspect', '--format', '{{json .Config}}', image_id]))
    expected_user = '26:26' if component in ('postgresql', 'barman-sidecar') else '65532:65532'
    entrypoint = ['/usr/local/bin/postgres'] if component == 'postgresql' else ['/manager']
    if (config.get('User') != expected_user or config.get('Entrypoint') != entrypoint
            or component in ('postgresql', 'barman-sidecar') and config.get('Cmd') not in (None, [])):
        raise ValueError('non-root identity and native manager entrypoint required')
    native_entrypoint(component, image_id, endpoint)
    # Entrypoint smoke is not Kubernetes reconciliation, backup or recovery evidence.
    scan_inventory(directory, image_id, 'docker', docker_host=endpoint)
    receipt = validate_local(directory, component, image_id, datetime.now(timezone.utc))
    receipt['native_entrypoint'] = 'Passed'
    if component == 'postgresql':
        from test_postgresql_isolation_native import exercise_candidate
        exercise_candidate(image_id, docker_host=endpoint)
        receipt['native_database_isolation'] = 'Passed'
        receipt['cnpg_reconciliation'] = 'Not verified'
    if component == 'barman-sidecar':
        receipt['python_runtime'] = 'Passed'
        receipt['physical_recovery'] = 'Not verified'
    if export_image:
        archive = directory / 'candidate-image.tar'
        run(['docker', '--host', endpoint, 'image', 'save', '--output', str(archive), image_id], timeout=180)
        size = archive.stat().st_size
        if not 0 < size <= 1024 * 1024 * 1024:
            archive.unlink()  # Failed exports must not enter the always-uploaded CI evidence.
            raise ValueError('bounded candidate image archive required')
        with archive.open('rb') as stream:
            receipt['image_archive_sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        receipt['image_archive_bytes'] = size
    (directory / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=COMPONENTS, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--export-image', action='store_true', help='Export only the scanned local image for isolated compatibility tests')
    args = parser.parse_args()
    try:
        receipt = build(args.component, args.directory, args.export_image)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'POSTGRESQL_SOURCE_CANDIDATE=Failed; state retained; no publication or installation\n')
    print(json.dumps(receipt, sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
