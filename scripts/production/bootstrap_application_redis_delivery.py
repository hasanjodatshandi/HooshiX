"""Supervised existing-custody Redis projection; no KV or workload mutations."""
from datetime import datetime, timezone

import bootstrap_postgresql_secrets as pg
import install_application_redis_delivery as installer


def commission(request, revision):
    plan = pg.decode_plan(request['plan'], request['plan_sha256'])
    observed = datetime.fromisoformat(plan['staging']['observed_at'])
    pg.host.require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds()
                    <= 5 * 86400, 'REDIS_APP_FRESH_NATIVE_PROOF_REQUIRED')
    return pg.commission(request, revision, execute=installer.execute, audit_label='Redis',
                         preserve=installer.pg.snapshot)


def main(revision):
    return pg.main(revision, commissioner=commission)
