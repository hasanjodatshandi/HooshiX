"""Owner-approved empty CNPG quarantine; no application, provider or promotion access."""
import copy

import render_platform_admission as admission
from build_postgresql_candidates import recipe
from publish_postgresql_candidates import source_predicate
from render_mesh_security_upgrade import provenance_expression
from verify_published_postgresql import candidate

NAMESPACE = 'platform-data'
CLUSTER = 'postgresql'
MANAGER = 'hooshix-postgresql-quarantine'
LABELS = {'app.kubernetes.io/managed-by': MANAGER, 'hooshix.io/commissioning': 'quarantined'}
SELECTOR = {'matchLabels': {'cnpg.io/cluster': CLUSTER}}
RESOURCES = {'requests': {'cpu': '500m', 'memory': '512Mi', 'ephemeral-storage': '128Mi'},
             'limits': {'cpu': '2', 'memory': '2Gi', 'ephemeral-storage': '1Gi'}}
INIT = ['/controller/manager', 'instance', 'init', '--initdb-flags',
        '--encoding=UTF8 --lc-collate=C --lc-ctype=C', '--app-db-name', 'identity',
        '--app-user', 'identity_migration', '--log-level=info']
RUN = ['/controller/manager', 'instance', 'run', '--log-level=info']
BOOTSTRAP = ['/manager', 'bootstrap', '/controller/manager', '--log-level=info']
ENV = [{'name': k, 'value': v} for k, v in (
    ('PGDATA', '/var/lib/postgresql/data/pgdata'), ('POD_NAME', 'postgresql-1'),
    ('NAMESPACE', NAMESPACE), ('CLUSTER_NAME', CLUSTER),
    ('PSQL_HISTORY', '/controller/tmp/.psql_history'), ('PGPORT', '5432'),
    ('PGHOST', '/controller/run'), ('TMPDIR', '/controller/tmp'))]
MOUNTS = {'pgdata': ['/var/lib/postgresql/data'], 'scratch-data': ['/run', '/controller'], 'shm': ['/dev/shm']}


def resource(kind, name, api='v1', namespace=NAMESPACE, **body):
    return {'apiVersion': api, 'kind': kind, 'metadata': {
        'name': name, 'namespace': namespace, 'labels': LABELS.copy()}, **body}


def cluster(images):
    return resource('Cluster', CLUSTER, 'postgresql.cnpg.io/v1', spec={
        'instances': 1, 'imageName': images['postgresql'], 'imagePullPolicy': 'IfNotPresent',
        'postgresUID': 26, 'postgresGID': 26, 'enableSuperuserAccess': False, 'logLevel': 'info',
        'resources': copy.deepcopy(RESOURCES),
        'inheritedMetadata': {'labels': {'istio.io/dataplane-mode': 'ambient',
                                       'hooshix.io/commissioning': 'quarantined'}},
        'bootstrap': {'initdb': {'database': 'identity', 'owner': 'identity_migration',
            'secret': {'name': 'identity-db-migration'}, 'encoding': 'UTF8', 'localeCollate': 'C', 'localeCType': 'C'}},
        'storage': {'storageClass': 'hooshix-postgresql-local', 'size': '29Gi',
            'pvcTemplate': {'volumeName': 'hooshix-postgresql-local-29gib'}},
        'affinity': {'enablePodAntiAffinity': False, 'nodeSelector': {'kubernetes.io/hostname': 'hooshix-production-1'}},
        'ephemeralVolumesSizeLimit': {'shm': '128Mi', 'temporaryData': '512Mi'},
        'postgresql': {'parameters': {'max_connections': '100', 'shared_buffers': '256MB',
            'password_encryption': 'scram-sha-256', 'log_min_error_statement': 'panic',
            'log_error_verbosity': 'terse', 'log_parameter_max_length': '0',
            'log_parameter_max_length_on_error': '0'}, 'pg_hba': ['host all all all reject']}})


def boundary(previous, images):
    obj = copy.deepcopy(previous)
    # Preserve the existing ESO contract verbatim for every other service account.
    for check in obj['spec']['validations']:
        check['expression'] = 'object.spec.serviceAccountName == "postgresql" || (' + check['expression'] + ')'
    terms = [
        'has(object.metadata.labels) && "cnpg.io/cluster" in object.metadata.labels'
        ' && object.metadata.labels["cnpg.io/cluster"] == "postgresql"'
        ' && "istio.io/dataplane-mode" in object.metadata.labels'
        ' && object.metadata.labels["istio.io/dataplane-mode"] == "ambient"',
        'object.spec.containers.size() == 1 && has(object.spec.initContainers) && object.spec.initContainers.size() == 1',
        '!has(object.spec.ephemeralContainers)',
        '!has(object.spec.imagePullSecrets) || object.spec.imagePullSecrets.size() == 0',
        admission.scalar_tree('object.spec.securityContext', {'runAsNonRoot': True, 'runAsUser': 26,
            'runAsGroup': 26, 'fsGroup': 26, 'seccompProfile': {'type': 'RuntimeDefault'}}),
        '!has(object.spec.securityContext.sysctls) || object.spec.securityContext.sysctls.size() == 0',
        '!has(object.spec.securityContext.windowsOptions)',
        *['!has(object.spec.' + f + ') || !object.spec.' + f for f in ('hostNetwork', 'hostPID', 'hostIPC', 'shareProcessNamespace')],
        'object.spec.containers[0].image == ' + admission.literal(images['postgresql']),
        'has(object.metadata.ownerReferences) && ('
        '(object.spec.containers[0].name == "postgres" && object.metadata.name == "postgresql-1"'
        ' && object.metadata.ownerReferences.exists(r, r.kind == "Cluster" && r.name == "postgresql" && r.controller))'
        ' || (object.spec.containers[0].name == "initdb" && object.metadata.name.startsWith("postgresql-1-initdb-")'
        ' && object.metadata.ownerReferences.exists(r, r.kind == "Job" && r.name == "postgresql-1-initdb" && r.controller)))',
        '(object.spec.containers[0].name == "postgres" && (' + admission.scalar_tree('object.spec.containers[0].command', RUN) +
        ')) || (object.spec.containers[0].name == "initdb" && (' + admission.scalar_tree('object.spec.containers[0].command', INIT) + '))',
        'object.spec.initContainers[0].name == "bootstrap-controller" && object.spec.initContainers[0].image == ' +
            admission.literal(images['operator']) + ' && (' + admission.scalar_tree('object.spec.initContainers[0].command', BOOTSTRAP) + ')',
        admission.scalar_tree('object.spec.containers[0].env', ENV),
        '!has(object.spec.initContainers[0].env) || object.spec.initContainers[0].env.size() == 0',
        'has(object.spec.volumes) && object.spec.volumes.size() == 4 && object.spec.volumes.all(v,'
        ' (v.name == "pgdata" && has(v.persistentVolumeClaim) && v.persistentVolumeClaim.claimName == "postgresql-1")'
        ' || (v.name in ["scratch-data","shm"] && has(v.emptyDir))'
        ' || (v.name.startsWith("kube-api-access-") && has(v.projected) && v.projected.sources.size() == 3'
        ' && v.projected.sources.all(s, (has(s.serviceAccountToken) && s.serviceAccountToken.path == "token"'
        ' && !has(s.serviceAccountToken.audience) && s.serviceAccountToken.expirationSeconds <= 3607)'
        ' || (has(s.configMap) && s.configMap.name == "kube-root-ca.crt") || has(s.downwardAPI))))']
    context = {'allowPrivilegeEscalation': False, 'privileged': False, 'runAsNonRoot': True,
        'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}, 'seccompProfile': {'type': 'RuntimeDefault'}}
    mounts = ' || '.join('(m.name == ' + admission.literal(name) + ' && m.mountPath in ' + admission.literal(paths) + ')'
                        for name, paths in MOUNTS.items())
    for path in ('object.spec.containers[0]', 'object.spec.initContainers[0]'):
        terms += [admission.scalar_tree(path + '.securityContext', context),
            '(!has(' + path + '.securityContext.capabilities.add) || ' + path + '.securityContext.capabilities.add.size() == 0)',
            '!has(' + path + '.securityContext.procMount) || ' + path + '.securityContext.procMount == "Default"',
            '!has(' + path + '.securityContext.windowsOptions)',
            '!has(' + path + '.securityContext.runAsUser) || ' + path + '.securityContext.runAsUser == 26',
            '!has(' + path + '.securityContext.runAsGroup) || ' + path + '.securityContext.runAsGroup == 26',
            '!has(' + path + '.envFrom) || ' + path + '.envFrom.size() == 0',
            '!has(' + path + '.args) || ' + path + '.args.size() == 0',
            *['has(' + path + '.resources.' + group + ') && ' + path + '.resources.' + group
              + '.size() == 3 && ' + ' && '.join(admission.literal(key) + ' in ' + path + '.resources.' + group
                + ' && ' + path + '.resources.' + group + '[' + admission.literal(key) + '] == ' + admission.literal(value)
                for key, value in values.items()) for group, values in RESOURCES.items()],
            '!has(' + path + '.ports) || ' + path + '.ports.all(p, !has(p.hostPort) || p.hostPort == 0)',
            'has(' + path + '.volumeMounts) && ' + path + '.volumeMounts.size() == 5 && '
            + path + '.volumeMounts.all(m, (!has(m.subPath) || m.subPath == "") && !has(m.subPathExpr) && ('
            + mounts + ' || (m.name.startsWith("kube-api-access-") && m.mountPath == '
            '"/var/run/secrets/kubernetes.io/serviceaccount" && m.readOnly)))']
    obj['spec']['validations'].append(admission.validation('quarantined PostgreSQL contract rejected',
        'object.spec.serviceAccountName != "postgresql" || (' + ' && '.join('(' + t + ')' for t in terms) + ')'))
    return obj


def render(previous, addresses):
    from render_eso_candidate import network as validate_addresses
    validate_addresses(addresses)
    published = candidate()['publication']
    images = {k: published['images'][k] for k in ('operator', 'postgresql')}
    sources = []
    for key, image in images.items():
        obj = admission.image_policy(NAMESPACE, [image], published['repository_revision'])
        obj['metadata'].update(name='hooshix-pg-quarantine-' + key, labels=LABELS.copy())
        obj['spec'].pop('credentials', None)
        obj['spec']['validations'][2] = admission.validation('PostgreSQL source identity rejected', provenance_expression(
            source_predicate(key, recipe(), published['repository_revision'], published['invocation_id'])))
        sources += admission.split_image_policy(obj)
    cnpg = {'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'cnpg-system'}},
            'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}}}
    dns = {'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
           'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}
    mesh = {'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'istio-system'}},
            'podSelector': {'matchLabels': {'app': 'istiod'}}}
    def ports(*values): return [{'protocol': 'TCP', 'port': n} for n in values]
    networks = [resource('NetworkPolicy', 'hooshix-postgresql-quarantine', 'networking.k8s.io/v1', spec={
        'podSelector': SELECTOR, 'policyTypes': ['Ingress', 'Egress'],
        'ingress': [{'from': [cnpg], 'ports': ports(8000, 15008)}],
        'egress': [{'to': [{'ipBlock': {'cidr': a + '/32'}} for a in sorted(set(addresses))], 'ports': ports(443, 6443)},
            {'to': [dns], 'ports': [*ports(53), {'protocol': 'UDP', 'port': 53}]},
            {'to': [mesh], 'ports': ports(15012)}]}),
        resource('NetworkPolicy', 'hooshix-cnpg-postgresql-quarantine', 'networking.k8s.io/v1', namespace='cnpg-system',
            spec={'podSelector': cnpg['podSelector'], 'policyTypes': ['Egress'], 'egress': [
                {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': NAMESPACE}},
                          'podSelector': SELECTOR}], 'ports': ports(8000, 15008)}]})]
    authorization = resource('AuthorizationPolicy', 'hooshix-postgresql-control-only', 'security.istio.io/v1',
        spec={'selector': SELECTOR, 'action': 'ALLOW', 'rules': [{'from': [{'source': {'principals': [
            'prod.sajtech.internal/ns/cnpg-system/sa/cnpg-manager']}}], 'to': [{'operation': {'ports': ['8000']}}]}]})
    return {'schema_version': 1, 'installation_id': 'hooshix-production', 'profile': 'production-single-server',
        'approval': 'owner-approved-empty-quarantine-2026-10-16', 'cluster': cluster(images), 'images': images,
        'previous_boundary': previous, 'boundary': boundary(previous, images), 'source_policies': sources,
        'network': networks, 'authorization': authorization, 'application_ingress': 'CLOSED',
        'provider_backup': 'Not run', 'production_readiness': 'Not verified'}
