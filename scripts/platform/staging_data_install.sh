#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/staging_secrets_apply.sh"
k apply -f "$ROOT/infrastructure/staging/networkpolicy.yaml"
k apply -f "$ROOT/infrastructure/staging/authorizationpolicy.yaml"
k apply -f "$ROOT/infrastructure/staging/data.yaml"
k rollout status deployment/postgresql -n platform-data --timeout=70s
k rollout status deployment/security-redis -n platform-data --timeout=70s
k rollout status deployment/kafka -n platform-data --timeout=120s
kafka_pod=$(k get pod -n platform-data -l app.kubernetes.io/name=kafka -o jsonpath='{.items[0].metadata.name}')
for spec in \
  'hooshix.identity.erasure.command.v1 3024000000' \
  'hooshix.identity.erasure.receipt.v1 3024000000' \
  'hooshix.identity.erasure.command.v1.DLT 1209600000' \
  'hooshix.identity.erasure.receipt.v1.DLT 1209600000'; do
  read -r topic retention <<<"$spec"
  k exec -n platform-data "$kafka_pod" -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9093 --create --if-not-exists --topic "$topic" \
    --partitions 1 --replication-factor 1 \
    --config "retention.ms=$retention" --config cleanup.policy=delete >/dev/null
done
k delete job postgres-bootstrap -n platform-data --ignore-not-found >/dev/null
k apply -f "$ROOT/infrastructure/staging/postgres-bootstrap.yaml"
k wait --for=condition=complete job/postgres-bootstrap -n platform-data --timeout=70s
"$ROOT/scripts/platform/staging_data_verify.sh"
