#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
service=${1:?service name required}
case "$service" in authorization-service|compromised-password-service|identity-service|notification-service|web-bff) ;; *) fail "unsupported service: $service";; esac
svc="$ROOT/services/$service"
pins="$svc/container/pins.env"
load_env "$pins"
java_home=$(readlink -f "$HOME/.sdkman/candidates/java/current")
grep -q 'IMPLEMENTOR="Eclipse Adoptium"' "$java_home/release" || fail "installed JDK is not Eclipse Adoptium"
grep -q 'IMPLEMENTOR_VERSION="Temurin-25.0.4+7"' "$java_home/release" || fail "installed JDK is not Temurin 25.0.4+7"
(cd "$svc" && ./gradlew --no-daemon bootJar)
rm -rf "$svc/.runtime-jdk"
cp -al "$java_home" "$svc/.runtime-jdk"
trap 'rm -rf "$svc/.runtime-jdk"' EXIT
tag="staging-$(git -C "$ROOT" rev-parse --short=12 HEAD)"
repo="localhost:5001/hooshix/$service"
docker build --pull=false --build-arg "RUNTIME_BASE_IMAGE=$RUNTIME_BASE_IMAGE" -t "$repo:$tag" "$svc"
docker push "$repo:$tag" >/tmp/hooshix-${service}-push.log
ref=$(docker inspect "$repo:$tag" --format '{{index .RepoDigests 0}}')
digest=${ref##*@}
mkdir -p "$ROOT/.platform-runtime/staging"
file="$ROOT/.platform-runtime/staging/images.env"
touch "$file"; chmod 600 "$file"
key=$(echo "$service" | tr '[:lower:]-' '[:upper:]_')
grep -v "^${key}_" "$file" > "$file.tmp" || true
printf '%s_REPOSITORY=%s\n%s_DIGEST=%s\n' "$key" "$repo" "$key" "$digest" >> "$file.tmp"
mv "$file.tmp" "$file"; chmod 600 "$file"
echo "$service image published at $repo@$digest"
