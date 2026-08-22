#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
service=${1:?service name required}
case "$service" in authorization-service|compromised-password-service|identity-service|notification-service|web-bff) ;; *) fail "unsupported service: $service";; esac
svc="$ROOT/services/$service"
state="$ROOT/.platform-runtime/staging/images.env"
[[ -f "$state" ]] || fail "staging image provenance state is missing; run staging-build"
source "$state"
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$BUILD_GIT_REVISION" --source-state "$BUILD_SOURCE_STATE" --worktree-sha256 "$BUILD_WORKTREE_SHA256" >/dev/null
pins="$svc/container/pins.env"
load_env "$pins"
java_home=$(readlink -f "$HOME/.sdkman/candidates/java/current")
grep -q 'IMPLEMENTOR="Eclipse Adoptium"' "$java_home/release" || fail "installed JDK is not Eclipse Adoptium"
grep -q 'IMPLEMENTOR_VERSION="Temurin-25.0.4+7"' "$java_home/release" || fail "installed JDK is not Temurin 25.0.4+7"
(cd "$svc" && ./gradlew --no-daemon bootJar)
rm -rf "$svc/.runtime-jdk"
cp -al "$java_home" "$svc/.runtime-jdk"
trap 'rm -rf "$svc/.runtime-jdk"' EXIT
tag="staging-${BUILD_GIT_REVISION:0:12}-${BUILD_SOURCE_STATE}-${BUILD_WORKTREE_SHA256:0:12}"
repo="localhost:5001/hooshix/$service"
docker build --pull=false   --label "org.opencontainers.image.revision=$BUILD_GIT_REVISION"   --label "com.sajtech.hooshix.worktree-sha256=$BUILD_WORKTREE_SHA256"   --build-arg "RUNTIME_BASE_IMAGE=$RUNTIME_BASE_IMAGE"   -t "$repo:$tag" "$svc"
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$BUILD_GIT_REVISION" --source-state "$BUILD_SOURCE_STATE" --worktree-sha256 "$BUILD_WORKTREE_SHA256" >/dev/null
[[ "$(docker inspect "$repo:$tag" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" == "$BUILD_GIT_REVISION" ]] || fail "$service image revision label mismatch"
[[ "$(docker inspect "$repo:$tag" --format '{{index .Config.Labels "com.sajtech.hooshix.worktree-sha256"}}')" == "$BUILD_WORKTREE_SHA256" ]] || fail "$service image worktree label mismatch"
docker push "$repo:$tag" >/tmp/hooshix-${service}-push.log
ref=$(docker inspect "$repo:$tag" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep "^${repo}@" | head -1)
[[ -n "$ref" ]] || fail "$service pushed image digest is missing"
digest=${ref##*@}
python3 "$ROOT/scripts/platform/git_provenance.py" --root "$ROOT" verify --revision "$BUILD_GIT_REVISION" --source-state "$BUILD_SOURCE_STATE" --worktree-sha256 "$BUILD_WORKTREE_SHA256" >/dev/null
file="$state"
touch "$file"; chmod 600 "$file"
key=$(echo "$service" | tr '[:lower:]-' '[:upper:]_')
grep -v "^${key}_" "$file" > "$file.tmp" || true
printf '%s_REPOSITORY=%s\n%s_DIGEST=%s\n' "$key" "$repo" "$key" "$digest" >> "$file.tmp"
mv "$file.tmp" "$file"; chmod 600 "$file"
echo "$service image published at $repo@$digest"
