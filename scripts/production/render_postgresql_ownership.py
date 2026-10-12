#!/usr/bin/env python3
"""Render credential-free CNPG ownership candidates; never connect or install."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATABASES = ('authorization', 'conversation', 'identity', 'notification', 'web_bff')
NAMESPACE = 'platform-data'
CLUSTER = 'postgresql'


def isolation_sql(database: str) -> str:
    if database not in DATABASES:
        raise ValueError('reviewed service database required')
    migration, runtime = f'{database}_migration', f'{database}_runtime'
    others = ', '.join(f'{other}_{role}' for other in DATABASES if other != database
                       for role in ('migration', 'runtime'))
    return f'''BEGIN;
SET LOCAL search_path = pg_catalog;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '2s';
DO $boundary$
BEGIN
  IF current_database() <> '{database}' OR
     (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database())
       <> '{migration}' OR
     (SELECT count(*) FROM pg_roles WHERE rolname IN ('{migration}', '{runtime}')) <> 2 OR
     EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('{migration}', '{runtime}')
       AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)) OR
     EXISTS (SELECT 1 FROM pg_auth_members WHERE member IN
       (SELECT oid FROM pg_roles WHERE rolname IN ('{migration}', '{runtime}')) OR roleid IN
       (SELECT oid FROM pg_roles WHERE rolname IN ('{migration}', '{runtime}'))) OR
     EXISTS (SELECT 1 FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles
       WHERE rolname = '{runtime}')) OR
     EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = (SELECT oid FROM pg_roles
       WHERE rolname = '{runtime}')) OR
     EXISTS (SELECT 1 FROM pg_proc WHERE proowner = (SELECT oid FROM pg_roles
       WHERE rolname = '{runtime}')) THEN
    RAISE EXCEPTION 'SERVICE_DATABASE_BOUNDARY_CONFLICT';
  END IF;
END
$boundary$;
REVOKE ALL ON DATABASE "{database}" FROM PUBLIC, {others}, {runtime};
GRANT CONNECT ON DATABASE "{database}" TO {migration}, {runtime};
REVOKE ALL ON SCHEMA public FROM PUBLIC, {others}, {runtime};
GRANT USAGE, CREATE ON SCHEMA public TO {migration};
GRANT USAGE ON SCHEMA public TO {runtime};
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, {others}, {runtime};
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, {others}, {runtime};
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC, {others}, {runtime};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {runtime};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO {runtime};
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} REVOKE ALL ON FUNCTIONS FROM PUBLIC, {others}, {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} REVOKE ALL ON TABLES FROM PUBLIC, {others}, {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} REVOKE ALL ON SEQUENCES FROM PUBLIC, {others}, {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public
  REVOKE ALL ON FUNCTIONS FROM PUBLIC, {others}, {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public
  REVOKE ALL ON TABLES FROM PUBLIC, {others}, {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public
  REVOKE ALL ON SEQUENCES FROM PUBLIC, {others}, {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO {runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO {runtime};
DO $monitor$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cnpg_metrics_exporter') THEN
    GRANT CONNECT ON DATABASE "{database}" TO cnpg_metrics_exporter;
  END IF;
END
$monitor$;
COMMIT;
'''


def render() -> dict:
    policy = json.loads((ROOT / 'infrastructure/production/data/data-policy.json').read_text())
    selected = policy['postgresql']
    if (selected['cloudnativepg'] != '1.30.0' or selected['version'] != '18.4'
            or selected['instances'] != 1 or selected['physical_clusters'] != 1
            or selected['distinct_service_databases'] is not True
            or selected['runtime_migration_role_isolation'] is not True):
        raise ValueError('reviewed single-server PostgreSQL policy required')
    items = []
    for database in DATABASES:
        service = database.replace('_', '-')
        metadata = {'namespace': NAMESPACE, 'labels': {'app.kubernetes.io/part-of': 'hooshix',
                    'hooshix.io/database-owner': service}}
        for role in ('migration', 'runtime'):
            items.append({'apiVersion': 'postgresql.cnpg.io/v1', 'kind': 'DatabaseRole',
                'metadata': {**metadata, 'name': f'{service}-{role}'},
                'spec': {'cluster': {'name': CLUSTER}, 'name': f'{database}_{role}',
                         'ensure': 'present', 'login': True, 'superuser': False,
                         'createdb': False, 'createrole': False, 'inherit': False,
                         'replication': False, 'bypassrls': False, 'inRoles': [],
                         'databaseRoleReclaimPolicy': 'retain',
                         'passwordSecret': {'name': f'{service}-db-{role}'}}})
        items.append({'apiVersion': 'postgresql.cnpg.io/v1', 'kind': 'Database',
            'metadata': {**metadata, 'name': service},
            'spec': {'cluster': {'name': CLUSTER}, 'name': database,
                     'owner': f'{database}_migration', 'ensure': 'present',
                     'databaseReclaimPolicy': 'retain'}})
        items.append({'apiVersion': 'v1', 'kind': 'ConfigMap',
            'metadata': {**metadata, 'name': f'{service}-database-boundary'},
            'immutable': True, 'data': {'isolation.sql': isolation_sql(database)}})
    return {'apiVersion': 'v1', 'kind': 'List', 'items': items}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, action='store_true',
                        help='acknowledge preparation only, not a deployment approval')
    parser.parse_args()
    print(json.dumps(render(), indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
