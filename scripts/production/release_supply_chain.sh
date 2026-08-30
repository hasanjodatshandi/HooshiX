#!/usr/bin/env bash
set -euo pipefail

: "${COMPONENT_NAME:?COMPONENT_NAME is required}"
: "${IMAGE_REF:?IMAGE_REF is required}"
: "${GIT_REVISION:?GIT_REVISION is required}"
: "${COSIGN_IDENTITY:?COSIGN_IDENTITY is required}"
: "${COSIGN_ISSUER:?COSIGN_ISSUER is required}"
: "${PROVENANCE_FILE:?PROVENANCE_FILE is required}"
: "${EVIDENCE_DIR:?EVIDENCE_DIR is required}"

case "$COMPONENT_NAME" in
  authorization-service|compromised-password-service|identity-service|notification-service|web-bff|web-frontend) ;;
  *) echo 'unsupported production application component' >&2; exit 2 ;;
esac
[[ "$IMAGE_REF" =~ ^[a-z0-9][a-z0-9._:-]*(/[a-z0-9][a-z0-9._-]*)+@sha256:[0-9a-f]{64}$ ]] || { echo 'invalid immutable image reference' >&2; exit 2; }
[[ "$GIT_REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo 'invalid Git revision' >&2; exit 2; }
[[ "$COSIGN_IDENTITY" == 'https://github.com/hasanjodatshandi/HooshiX/.github/workflows/production-release.yml@refs/heads/main' ]] || { echo 'unexpected Cosign identity' >&2; exit 2; }
[[ "$COSIGN_ISSUER" == 'https://token.actions.githubusercontent.com' ]] || { echo 'unexpected Cosign issuer' >&2; exit 2; }
for command in syft grype cosign python3 sha256sum; do command -v "$command" >/dev/null || { echo "missing command: $command" >&2; exit 2; }; done
mkdir -p "$EVIDENCE_DIR"
chmod 0700 "$EVIDENCE_DIR"
name=$(printf '%s' "$IMAGE_REF" | sha256sum | awk '{print $1}')
sbom="$EVIDENCE_DIR/${name}.cyclonedx.json"
scan="$EVIDENCE_DIR/${name}.grype.json"
db_status="$EVIDENCE_DIR/${name}.grype-db-status.json"
receipt="$EVIDENCE_DIR/${name}.receipt"
python3 - "$PROVENANCE_FILE" "$GIT_REVISION" "$IMAGE_REF" <<'PY'
import json,sys
predicate=json.load(open(sys.argv[1],encoding='utf-8'))
assert predicate['buildDefinition']['externalParameters']['gitRevision']==sys.argv[2]
assert predicate['buildDefinition']['externalParameters']['image']==sys.argv[3]
assert predicate['runDetails']['builder']['id']=='https://github.com/hasanjodatshandi/HooshiX/.github/workflows/production-release.yml@refs/heads/main'
PY
grype db status -o json > "$db_status"
syft scan "$IMAGE_REF" -o "cyclonedx-json=$sbom"
python3 - "$sbom" <<'PY'
import json,sys
sbom=json.load(open(sys.argv[1],encoding='utf-8'))
assert sbom.get('bomFormat')=='CycloneDX'
PY
grype "sbom:$sbom" --fail-on high -o json > "$scan"
cosign sign --yes "$IMAGE_REF"
cosign attest --yes --predicate "$PROVENANCE_FILE" --type slsaprovenance1 "$IMAGE_REF"
cosign attest --yes --predicate "$sbom" --type cyclonedx "$IMAGE_REF"
cosign verify --certificate-identity "$COSIGN_IDENTITY" --certificate-oidc-issuer "$COSIGN_ISSUER" "$IMAGE_REF" >/dev/null
cosign verify-attestation --type slsaprovenance1 --certificate-identity "$COSIGN_IDENTITY" --certificate-oidc-issuer "$COSIGN_ISSUER" "$IMAGE_REF" >/dev/null
cosign verify-attestation --type cyclonedx --certificate-identity "$COSIGN_IDENTITY" --certificate-oidc-issuer "$COSIGN_ISSUER" "$IMAGE_REF" >/dev/null
{
  printf 'component=%s\n' "$COMPONENT_NAME"
  printf 'owner=application-component:%s\n' "$COMPONENT_NAME"
  printf 'environment=production\n'
  printf 'image=%s\n' "$IMAGE_REF"
  printf 'git_revision=%s\n' "$GIT_REVISION"
  printf 'build_workflow_identity=%s\n' "$COSIGN_IDENTITY"
  printf 'approved_exceptions=none\n'
  printf 'scan_observed_at='; date -u +'%Y-%m-%dT%H:%M:%SZ'
  printf 'sbom_sha256='; sha256sum "$sbom" | awk '{print $1}'
  printf 'grype_scan_sha256='; sha256sum "$scan" | awk '{print $1}'
  printf 'grype_db_status_sha256='; sha256sum "$db_status" | awk '{print $1}'
  printf 'syft_version='; syft version 2>/dev/null | awk '/Version:/ {print $2; exit}'
  printf 'grype_version='; grype version 2>/dev/null | awk '/Version:/ {print $2; exit}'
  printf 'cosign_version='; cosign version 2>/dev/null | awk '/GitVersion:/ {print $2; exit}'
} > "$receipt"
