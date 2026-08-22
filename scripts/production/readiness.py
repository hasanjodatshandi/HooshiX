#!/usr/bin/env python3
from __future__ import annotations
import argparse,json,re,subprocess,sys
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PROFILE=ROOT/'infrastructure/production/profile.json'
GATES={'artifact_integrity','host_k3s_network','wireguard_fido_jit_audit','gitops_argocd','postgresql_backup_restore','redis_recovery_capacity_clock','kafka_transport_recovery','openbao_external_secrets','ambient_kyverno_admission','edge_client_address_waf','observability_privacy_faults','external_host_down_monitor','supply_chain_release','complete_stack_capacity','cold_dr','five_service_production_runtime'}
EVIDENCE_ID=re.compile(r'^[A-Za-z0-9][A-Za-z0-9._:/@+-]{2,255}$')
PLACEHOLDER=re.compile(r'(?i)(^|[^a-z])(tbd|todo|unknown|placeholder|example|not verified|none)([^a-z]|$)')
def current_revision(): return subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
def load(path):
    data=json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data,dict): raise ValueError('evidence root must be an object')
    return data
def validate(data,expected_revision,now=None):
    errors=[]; now=now or datetime.now(timezone.utc)
    required=set(json.loads(PROFILE.read_text(encoding='utf-8'))['required_external_inputs'])
    if data.get('schema_version')!=1: errors.append('schema_version must be 1')
    if data.get('profile')!='production-single-server': errors.append('profile must be production-single-server')
    if data.get('git_revision')!=expected_revision: errors.append('git_revision must equal the exact promoted repository revision')
    external=data.get('external_inputs')
    if not isinstance(external,dict) or set(external)!=required: errors.append('external_inputs must contain exactly the profile-required keys')
    else:
        for key,value in external.items():
            if not isinstance(value,str) or not EVIDENCE_ID.fullmatch(value) or PLACEHOLDER.search(value): errors.append(f'external input {key} must be a non-secret, non-placeholder evidence/reference identifier')
    gates=data.get('gates')
    if not isinstance(gates,dict) or set(gates)!=GATES: errors.append('gates must contain exactly the mandatory production readiness gates')
    else:
        for name,gate in gates.items():
            if not isinstance(gate,dict): errors.append(f'gate {name} must be an object'); continue
            if gate.get('status')!='PASS': errors.append(f'gate {name} is not PASS')
            ref=gate.get('evidence_id')
            if not isinstance(ref,str) or not EVIDENCE_ID.fullmatch(ref) or PLACEHOLDER.search(ref): errors.append(f'gate {name} needs a bounded non-secret, non-placeholder evidence_id')
            observed=gate.get('observed_at')
            try:
                when=datetime.fromisoformat(observed.replace('Z','+00:00')) if isinstance(observed,str) else None
                if when is None or when.tzinfo is None: raise ValueError
                if when>now: errors.append(f'gate {name} observed_at is in the future')
            except ValueError: errors.append(f'gate {name} observed_at must be timezone-aware ISO-8601')
    if data.get('go_live_approved') is not True: errors.append('go_live_approved must be true only after all external review/approval gates pass')
    return errors
def main():
    p=argparse.ArgumentParser(); p.add_argument('evidence',type=Path); p.add_argument('--expected-revision'); a=p.parse_args()
    try: errors=validate(load(a.evidence),a.expected_revision or current_revision())
    except (OSError,json.JSONDecodeError,ValueError) as exc: errors=[f'cannot load readiness evidence: {exc}']
    if errors:
        print('Production readiness verification FAILED:'); [print(f'- {e}') for e in errors]; return 1
    print('Production readiness evidence verification PASSED.'); return 0
if __name__=='__main__': sys.exit(main())
