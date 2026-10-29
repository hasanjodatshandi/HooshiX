"""Exact installer partial-reentry proof inside the existing signed disposable closed broker."""
import json
from unittest.mock import patch

import install_kafka_quotas as installer
import kafka_quota_profile as profile


def rehearse(kube, plan):
    plan = {**plan, 'kafka_quotas': True,
            'kafka_quota_profile': json.loads(profile.PATH.read_bytes())}
    # Current staging runs the corrected ESO image; retained mode must reject it before writes.
    def read_only(*args, body=None, **options):
        if body is not None or any(arg in args for arg in ('create', 'patch', 'delete', 'exec')):
            raise ValueError('KAFKA_QUOTA_RETAINED_NEGATIVE_WRITE_REJECTED')
        return kube(*args, **options)
    try:
        retained = {**plan, 'retained_eso_admission': True,
            'kafka_runtime': {**plan['kafka_runtime'],
                'boundary': installer.contract.retained_boundary(plan['kafka_runtime']['boundary'])}}
        installer.execute(retained, kube=read_only, guard=lambda **_: None)
    except ValueError as error:
        if str(error) != 'KAFKA_QUOTA_RETAINED_ESO_REQUIRED':
            raise
    else:
        raise ValueError('KAFKA_QUOTA_CURRENT_ERA_RETAINED_MODE_REJECTED')
    original = installer.command
    written = 0

    def interrupt(kube, *args):
        nonlocal written
        if '--alter' in args:
            if written == 2:
                raise ValueError('SYNTHETIC_QUOTA_ACK_INTERRUPTION')
            written += 1
        return original(kube, *args)

    with patch.object(installer, 'command', side_effect=interrupt):
        try:
            installer.execute(plan, kube=kube, guard=lambda **_: None)
        except ValueError as error:
            if str(error) != 'SYNTHETIC_QUOTA_ACK_INTERRUPTION':
                raise
        else:
            raise ValueError('KAFKA_QUOTA_PARTIAL_PROOF_REQUIRED')
    if written != 2:
        raise ValueError('KAFKA_QUOTA_PARTIAL_COUNT_REQUIRED')
    first = installer.execute(plan, kube=kube, guard=lambda **_: None)

    def read_only(kube, *args):
        if '--alter' in args:
            raise ValueError('KAFKA_QUOTA_REENTRY_WRITE_REJECTED')
        return original(kube, *args)

    with patch.object(installer, 'command', side_effect=read_only):
        if installer.execute(plan, kube=kube, guard=lambda **_: None) != first:
            raise ValueError('KAFKA_QUOTA_REENTRY_STATE_CHANGED')
    return {**first, 'idempotent_reconciliation': 'Passed', 'partial_recovery': 'Passed',
            'retained_current_digest_denied': 'Passed'}
