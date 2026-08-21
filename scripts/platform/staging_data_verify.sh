#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
for d in postgresql security-redis; do k rollout status deployment/$d -n platform-data --timeout=30s >/dev/null; done
pg_image=$(k get deploy postgresql -n platform-data -o jsonpath='{.spec.template.spec.containers[0].image}')
[[ "$pg_image" == 'postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296' ]] || fail "PostgreSQL image mismatch"
redis_image=$(k get deploy security-redis -n platform-data -o jsonpath='{.spec.template.spec.containers[0].image}')
[[ "$redis_image" == 'redis:8.2.8-bookworm@sha256:2f7462b9e93e0a7ae2edf3a0a0babc8a4d29f8bfc50849b906b7caaef925edc1' ]] || fail "Redis image mismatch"
redis_pod=$(k get pod -n platform-data -l app.kubernetes.io/name=security-redis -o jsonpath='{.items[0].metadata.name}')
config=$(k exec -n platform-data "$redis_pod" -- sh -c 'redis-cli --user verify --pass "$REDIS_VERIFY_PASSWORD" --no-auth-warning CONFIG GET maxmemory maxmemory-policy appendonly appendfsync')
[[ "$config" == *$'maxmemory\n268435456'* && "$config" == *$'maxmemory-policy\nnoeviction'* && "$config" == *$'appendonly\nyes'* && "$config" == *$'appendfsync\neverysec'* ]] || fail "Redis runtime policy mismatch: $config"
pg_pod=$(k get pod -n platform-data -l app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].metadata.name}')
roles=$(k exec -n platform-data "$pg_pod" -- psql -U postgres -d postgres -Atc "select rolname||':'||rolsuper||':'||rolcreatedb||':'||rolcreaterole||':'||rolbypassrls from pg_roles where rolname in ('authorization_runtime','identity_runtime','notification_runtime') order by rolname")
while IFS= read -r line; do [[ "$line" == *':false:false:false:false' ]] || fail "unsafe runtime role: $line"; done <<<"$roles"
matrix=$(k exec -n platform-data "$pg_pod" -- psql -U postgres -d postgres -Atc "select rolname||':'||has_database_privilege(rolname,'authorization','CONNECT')||':'||has_database_privilege(rolname,'identity','CONNECT')||':'||has_database_privilege(rolname,'notification','CONNECT') from pg_roles where rolname in ('authorization_runtime','identity_runtime','notification_runtime') order by rolname")
expected_matrix=$'authorization_runtime:true:false:false\nidentity_runtime:false:true:false\nnotification_runtime:false:false:true'
[[ "$matrix" == "$expected_matrix" ]] || fail "runtime database CONNECT isolation mismatch"
echo "staging PostgreSQL/Redis verification PASSED"
