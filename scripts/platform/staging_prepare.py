#!/usr/bin/env python3
from __future__ import annotations
import base64, json, os, re, secrets, subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
STATE=ROOT/'.platform-runtime'/'staging'
FILES=STATE/'files'
META=STATE/'metadata.json'
TOKEN_RE=re.compile(r'^[A-Za-z0-9_-]{43}$')

def token(): return secrets.token_urlsafe(32)

def validate_metadata(data: object, names: list[str]) -> dict[str,str]:
    if not isinstance(data, dict): raise SystemExit('staging metadata must be a JSON object')
    extra=set(data)-set(names)
    if extra: raise SystemExit('staging metadata contains unexpected keys')
    for n,v in data.items():
        if not isinstance(v,str) or TOKEN_RE.fullmatch(v) is None:
            raise SystemExit(f'staging metadata token is invalid: {n}')
    return data
def write(rel: str, value: str, newline: bool=True) -> Path:
    p=FILES/rel; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(value+('\n' if newline else ''),encoding='utf-8'); p.chmod(0o600); return p

def ring(rel: str):
    p=FILES/rel
    if not p.exists():
        val=base64.b64encode(secrets.token_bytes(32)).decode('ascii')
        write(rel,f'active_key_id=staging-k1\nkey.staging-k1={val}')

def main():
    os.umask(0o077)
    FILES.mkdir(parents=True,exist_ok=True)
    names=['postgres_admin','authorization_migration','authorization_runtime','identity_migration','identity_runtime','notification_migration','notification_runtime','redis_health','redis_verify','grafana_admin','redis_authorization','redis_identity','redis_webbff']
    if META.exists():
        try: data=json.loads(META.read_text(encoding='utf-8'))
        except (OSError,json.JSONDecodeError) as exc: raise SystemExit('staging metadata is unreadable or invalid JSON') from exc
        data=validate_metadata(data,names)
    else: data={}
    for n in names: data.setdefault(n,token())
    data=validate_metadata(data,names)
    META.write_text(json.dumps(data,sort_keys=True)+'\n',encoding='utf-8'); META.chmod(0o600)
    write('postgres-admin/password',data['postgres_admin'],False)
    for service in ('authorization','identity','notification'):
        for role in ('migration','runtime'):
            d=f'{service}-db-{role}'
            write(f'{d}/spring.datasource.username',f'{service}_{role}',False)
            write(f'{d}/spring.datasource.password',data[f'{service}_{role}'],False)
    redis_host='security-redis.platform-data.svc.cluster.local:6379'
    write('authorization-quota-redis/AUTHORIZATION_QUOTA_REDIS_URI',f"redis://authorization:{data['redis_authorization']}@{redis_host}",False)
    write('identity-quota-redis/quota_redis_uri',f"redis://identity:{data['redis_identity']}@{redis_host}",False)
    write('web-bff-redis/WEB_BFF_REDIS_URI',f"redis://webbff:{data['redis_webbff']}@{redis_host}",False)
    write('redis-health/password',data['redis_health'],False)
    write('grafana-admin/password',data['grafana_admin'],False)
    write('redis-verify/password',data['redis_verify'],False)
    acl='\n'.join([
        'user default off',
        f"user health on >{data['redis_health']} ~* +ping",
        f"user verify on >{data['redis_verify']} ~* +ping +config|get",
        f"user authorization on >{data['redis_authorization']} ~* +@all",
        f"user identity on >{data['redis_identity']} ~* +@all",
        f"user webbff on >{data['redis_webbff']} ~* +@all",
    ])
    write('redis-acl/users.acl',acl)
    for rel in ['authorization-fingerprint/fingerprint.properties','authorization-quota-key/quota.properties','identity-fingerprint/fingerprint.properties','identity-challenge/challenge.properties','identity-handoff/handoff.properties','identity-mfa/mfa.properties','identity-quota/quota.properties','identity-refresh/refresh.properties','notification-fingerprint/fingerprint.properties','notification-delivery/delivery.properties','web-bff-locator/locator.properties','web-bff-csrf/csrf.properties','web-bff-refresh/refresh.properties','web-bff-quota/quota.properties']:
        ring(rel)
    priv=FILES/'identity-jwt-private'/'signing.properties'; pub=FILES/'identity-jwt-public'/'verifier.properties'
    if priv.exists() != pub.exists(): raise SystemExit('incomplete staging JWT material; remove .platform-runtime/staging/files/identity-jwt-*')
    if not priv.exists():
        tmp=STATE/'jwt'; tmp.mkdir(parents=True,exist_ok=True)
        pem=tmp/'key.pem'; der=tmp/'private.der'; pder=tmp/'public.der'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:3072','-out',str(pem)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['openssl','pkcs8','-topk8','-nocrypt','-in',str(pem),'-outform','DER','-out',str(der)],check=True)
        subprocess.run(['openssl','pkey','-in',str(pem),'-pubout','-outform','DER','-out',str(pder)],check=True)
        write('identity-jwt-private/signing.properties',f"active_key_id=staging-jwt-k1\nkey.staging-jwt-k1={base64.b64encode(der.read_bytes()).decode()}")
        write('identity-jwt-public/verifier.properties',f"current_key_id=staging-jwt-k1\nkey.staging-jwt-k1={base64.b64encode(pder.read_bytes()).decode()}")
        for p in (pem,der,pder): p.unlink(missing_ok=True)
        tmp.rmdir()
    print(STATE)
if __name__=='__main__': main()
