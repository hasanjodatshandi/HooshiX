#!/usr/bin/env python3
"""Exercise generated ownership SQL on disposable PostgreSQL, never a VPS."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from pathlib import Path

from render_postgresql_ownership import DATABASES, isolation_sql, render

ROOT = Path(__file__).resolve().parents[2]


def docker(*argv: str, input_text: str | None = None, timeout: int = 20):
    return subprocess.run(['docker', *argv], input=input_text, capture_output=True,
                          text=True, timeout=timeout, check=False)


def exercise(container: str) -> None:
    def sql(database, statement, user='postgres', denied=None):
        result = docker('exec', '-i', container, 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1',
                        '-U', user, '-d', database, input_text=statement)
        if denied is not None:
            if result.returncode == 0 or denied not in result.stderr:
                raise RuntimeError('expected PostgreSQL boundary denial missing')
        elif result.returncode != 0:
            raise RuntimeError('synthetic PostgreSQL operation failed: ' + result.stderr[:500])
        return result.stdout.strip()

    sql('postgres', 'CREATE ROLE cnpg_metrics_exporter LOGIN;')
    for resource in render()['items']:
        spec = resource.get('spec', {})
        if resource['kind'] == 'DatabaseRole':
            sql('postgres', f"CREATE ROLE {spec['name']} LOGIN NOSUPERUSER NOCREATEDB "
                           'NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;')
        elif resource['kind'] == 'Database':
            sql('postgres', f'CREATE DATABASE "{spec["name"]}" OWNER {spec["owner"]};')

    for database in DATABASES:
        migration, runtime = f'{database}_migration', f'{database}_runtime'
        sql(database, isolation_sql(database))
        sql(database, '''
CREATE TABLE public.boundary_fixture (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id text NOT NULL, payload text NOT NULL);
ALTER TABLE public.boundary_fixture ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boundary_fixture FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON public.boundary_fixture
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE FUNCTION public.boundary_function() RETURNS integer LANGUAGE sql AS 'SELECT 7';
''', migration)
        sql(database, "INSERT INTO public.boundary_fixture (tenant_id,payload) VALUES ('a','first'),('b','second');")
        sql(database, f'GRANT ALL ON TABLE public.boundary_fixture TO {runtime}; '
                      f'ALTER DEFAULT PRIVILEGES FOR ROLE {migration} IN SCHEMA public '
                      'GRANT EXECUTE ON FUNCTIONS TO PUBLIC;')
        # Replay must preserve rows, owner and forced RLS after service migrations.
        sql(database, isolation_sql(database))
        sql(database, "CREATE FUNCTION public.later_boundary() RETURNS integer LANGUAGE sql AS 'SELECT 8';",
            migration)
        if sql(database, 'SELECT count(*) FROM public.boundary_fixture;') != '2':
            raise RuntimeError('ownership replay altered fixture data')
        if sql(database, f"SELECT has_table_privilege('{runtime}', 'public.boundary_fixture', 'TRUNCATE');") != 'f':
            raise RuntimeError('replay retained unsafe runtime privilege')
        observed = sql(database, '''
SELECT count(*) FROM public.boundary_fixture;
BEGIN;
SELECT set_config('app.tenant_id','a',true);
SELECT count(*) FROM public.boundary_fixture;
INSERT INTO public.boundary_fixture (tenant_id,payload) VALUES ('a','runtime');
SELECT public.boundary_function();
COMMIT;
SELECT count(*) FROM public.boundary_fixture;
BEGIN;
SELECT set_config('app.tenant_id','b',true);
SELECT count(*) FROM public.boundary_fixture;
ROLLBACK;
SELECT count(*) FROM public.boundary_fixture;
''', runtime).splitlines()
        if observed != ['0', 'a', '1', '7', '0', 'b', '1', '0']:
            raise RuntimeError('forced RLS or transaction-local context failed')
        sql(database, "BEGIN; SELECT set_config('app.tenant_id','a',true); "
                      "INSERT INTO public.boundary_fixture (tenant_id,payload) VALUES ('b','denied');",
            runtime, 'row-level security policy')
        sql(database, 'CREATE TABLE public.forbidden (id integer);', runtime, 'permission denied')
        sql(database, 'CREATE TEMP TABLE forbidden (id integer);', runtime, 'permission denied')
        sql(database, f'SET ROLE {migration};', runtime, 'permission denied')
        if sql(database, 'SELECT 1;', 'cnpg_metrics_exporter') != '1':
            raise RuntimeError('explicit monitoring connection grant missing')
        sql(database, 'SELECT count(*) FROM public.boundary_fixture;', 'cnpg_metrics_exporter',
            'permission denied')
        for other in DATABASES:
            if other != database:
                for role in ('migration', 'runtime'):
                    sql(database, 'SELECT 1;', f'{other}_{role}', 'permission denied for database')
                    grants = sql(database, f"SELECT has_table_privilege('{other}_{role}', "
                                 "'public.boundary_fixture','SELECT') OR "
                                 f"has_schema_privilege('{other}_{role}','public','USAGE') OR "
                                 f"has_function_privilege('{other}_{role}','public.later_boundary()','EXECUTE');")
                    if grants != 'f':
                        raise RuntimeError('cross-service object/default privilege retained')
    sql('identity', isolation_sql('notification'), denied='SERVICE_DATABASE_BOUNDARY_CONFLICT')
    sql('postgres', 'ALTER ROLE identity_runtime BYPASSRLS;')
    before = sql('identity', "SELECT datacl::text FROM pg_database WHERE datname='identity';")
    sql('identity', isolation_sql('identity'), denied='SERVICE_DATABASE_BOUNDARY_CONFLICT')
    if before != sql('identity', "SELECT datacl::text FROM pg_database WHERE datname='identity';"):
        raise RuntimeError('failed preflight changed database privileges')
    sql('postgres', 'ALTER ROLE identity_runtime NOBYPASSRLS; GRANT pg_read_all_data TO identity_runtime;')
    sql('identity', isolation_sql('identity'), denied='SERVICE_DATABASE_BOUNDARY_CONFLICT')
    sql('postgres', 'REVOKE pg_read_all_data FROM identity_runtime;')
    sql('identity', isolation_sql('identity'))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--disposable', action='store_true', required=True)
    parser.parse_args()
    endpoint = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
    if (endpoint.returncode != 0 or not json.loads(endpoint.stdout).startswith('unix://')
            or not os.environ.get('DOCKER_HOST', 'unix://').startswith('unix://')):
        raise RuntimeError('local Unix-socket Docker required; remote daemons are forbidden')
    # Reuse the repository's immutable PostgreSQL 18.4 fixture, not a production image selection.
    fixture = (ROOT / 'infrastructure/staging/postgres-bootstrap.yaml').read_text()
    image = re.search(r'image: (postgres:18\.4-bookworm@sha256:[a-f0-9]{64})', fixture).group(1)
    started = docker('run', '-d', '--network', 'none', '--memory', '512m', '--cpus', '1',
                     '--pids-limit', '128', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                     image, timeout=180)
    container = started.stdout.strip()
    if started.returncode != 0 or not re.fullmatch(r'[a-f0-9]{64}', container):
        raise RuntimeError('disposable PostgreSQL failed to start')
    try:
        deadline = time.monotonic() + 45
        while docker('exec', container, 'pg_isready', '-U', 'postgres').returncode != 0:
            if time.monotonic() >= deadline:
                raise RuntimeError('disposable PostgreSQL startup exceeded deadline')
            time.sleep(1)
        exercise(container)
    finally:
        removed = docker('rm', '-fv', container)
        if removed.returncode != 0:
            raise RuntimeError('disposable container/volume cleanup failed')
    print(json.dumps({'schema_version': 1, 'postgresql': '18.4', 'image': image,
                      'native_database_isolation': 'Passed', 'databases': len(DATABASES),
                      'cross_service_connection_denials': 40,
                      'scope': 'network-isolated disposable fixture; no production connection',
                      'cnpg_reconciliation': 'Not run', 'production_readiness': 'Not verified'}))


if __name__ == '__main__':
    main()
