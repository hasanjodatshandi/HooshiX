"""Supervised existing-custody projection, without initialization or credential replacement."""
import re
from datetime import datetime, timezone

import bootstrap_postgresql_secrets as pg
import application_postgresql_delivery as contract
import install_application_postgresql_delivery as installer


def token_request(namespace, account, audiences):
    pg.host.require((namespace == contract.NAMESPACE and account in {
        e['account'] for e in contract.ENTRIES.values()} or (namespace, account) == ('default', 'default'))
        and isinstance(audiences, list) and 1 <= len(audiences) <= 5
        and all(isinstance(a, str) and re.fullmatch(r'[a-zA-Z0-9:/_.-]{1,256}', a) for a in audiences),
        'PG_APP_TOKENREQUEST_BOUNDARY')
    return pg.kube('-n', namespace, 'create', 'token', account, '--duration=10m',
                   *['--audience=' + a for a in audiences]).decode().strip()


def commission(request, revision):
    plan = pg.decode_plan(request['plan'], request['plan_sha256'])
    observed = datetime.fromisoformat(plan['staging']['observed_at'])
    pg.host.require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds()
                    <= 5 * 86400, 'PG_APP_FRESH_NATIVE_PROOF_REQUIRED')
    return pg.commission(request, revision, execute=installer.execute, token_requester=token_request,
                         audit_label='PostgreSQL', preserve=installer.snapshot)


def main(revision):
    return pg.main(revision, commissioner=commission)
