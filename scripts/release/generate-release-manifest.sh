#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: generate-release-manifest.sh --tag vX.Y.Z --commit SHA --jar PATH --host-package PATH \
  --metadata PATH --image-repository REPOSITORY --image-digest sha256:DIGEST --output PATH \
  [--runtime-fingerprint FINGERPRINT] [--previous-runtime-fingerprint FINGERPRINT]
USAGE
  exit 64
}

tag=''
commit=''
jar=''
image_repository=''
image_digest=''
output=''
metadata=''
host_package=''
runtime_fingerprint=''
previous_runtime_fingerprint=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) tag="${2:-}"; shift 2 ;;
    --commit) commit="${2:-}"; shift 2 ;;
    --jar) jar="${2:-}"; shift 2 ;;
    --image-repository) image_repository="${2:-}"; shift 2 ;;
    --image-digest) image_digest="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --metadata) metadata="${2:-}"; shift 2 ;;
    --host-package) host_package="${2:-}"; shift 2 ;;
    --runtime-fingerprint) runtime_fingerprint="${2:-}"; shift 2 ;;
    --previous-runtime-fingerprint) previous_runtime_fingerprint="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || usage
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || usage
[[ -f "$jar" && -f "$host_package" && -f "$metadata" && -n "$image_repository" && "$image_digest" =~ ^sha256:[0-9a-f]{64}$ && -n "$output" ]] || usage
command -v jq >/dev/null
agent_version_at_commit="$(git show "${commit}:deploy/update/agent-version" | tr -d '[:space:]')"

previous_tag="$(git tag --merged "$commit" --list 'v*' --sort=-v:refname \
  | grep -E '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
  | grep -Fxv "$tag" | head -n 1 || true)"
metadata_validation_args=(--tag "$tag" --metadata "$metadata" --agent-version "$agent_version_at_commit")
if [[ -z "$previous_tag" ]]; then
  metadata_validation_args+=(--first-release)
else
  previous_agent_version="$(git show "${previous_tag}:deploy/update/agent-version" | tr -d '[:space:]')"
  metadata_validation_args+=(--previous-agent-version "$previous_agent_version")
fi
bash "$(dirname "$0")/validate-release-metadata.sh" "${metadata_validation_args[@]}"

metadata_tag="$(jq -er 'if (.releaseTag | type) == "string" then .releaseTag else error("releaseTag must be a string") end' "$metadata")"
jq -e '(.hostContractRequired | type) == "boolean"' "$metadata" >/dev/null || {
  echo 'hostContractRequired must be a boolean.' >&2
  exit 1
}
host_contract_required="$(jq -r '.hostContractRequired' "$metadata")"
minimum_agent_version="$(jq -er 'if (.minimumAgentVersion | type) == "number" and .minimumAgentVersion >= 1 and .minimumAgentVersion == floor then .minimumAgentVersion else error("minimumAgentVersion must be a positive integer") end' "$metadata")"
[[ "$metadata_tag" == "$tag" ]] || {
  echo "Release metadata tag does not match requested tag: $metadata_tag" >&2
  exit 1
}

manual_reason=''
manual_instructions=''
if [[ "$host_contract_required" == 'true' ]]; then
  manual_reason="$(jq -er 'if (.manual.reason | type) == "string" and (.manual.reason | length) > 0 then .manual.reason else error("manual.reason is required") end' "$metadata")"
  manual_instructions="$(jq -er 'if (.manual.instructions | type) == "string" and (.manual.instructions | length) > 0 then .manual.instructions else error("manual.instructions is required") end' "$metadata")"
fi

requires_backup=true
migration_range='unknown'
host_contract_changes=''
if [[ -n "$previous_tag" ]]; then
  mapfile -t migrations < <(git diff --name-only "$previous_tag" "$commit" -- src/main/resources/db/migration | sort)
  if [[ ${#migrations[@]} -eq 0 ]]; then
    requires_backup=false
    migration_range='none'
  else
    migration_range="$(printf '%s,' "${migrations[@]}" | sed 's/,$//')"
  fi
  host_contract_changes="$(bash "$(dirname "$0")/detect-host-contract-change.sh" \
    --from "$previous_tag" --to "$commit" | tr '\n' ',' | sed 's/,$//')"
fi

if [[ -z "$runtime_fingerprint" ]]; then
  runtime_fingerprint="$(bash "$(dirname "$0")/runtime-fingerprint.sh" "$commit")"
fi
[[ "$runtime_fingerprint" =~ ^[A-Za-z0-9._:-]+$ ]] || {
  echo 'Invalid runtime fingerprint.' >&2
  exit 64
}
[[ -z "$previous_runtime_fingerprint" || "$previous_runtime_fingerprint" =~ ^[A-Za-z0-9._:-]+$ ]] || {
  echo 'Invalid previous runtime fingerprint.' >&2
  exit 64
}

if [[ -n "$host_contract_changes" && "$host_contract_required" != 'true' ]]; then
  echo "Host contract changed but release metadata does not require a manual host update: $host_contract_changes" >&2
  exit 1
fi

if [[ "$host_contract_required" == 'true' ]]; then
  update_type='host-package-manual-required'
elif [[ -z "$previous_tag" || -z "$previous_runtime_fingerprint" \
  || "$runtime_fingerprint" != "$previous_runtime_fingerprint" ]]; then
  update_type='image'
else
  update_type='jar'
fi

jar_name="$(basename "$jar")"
jar_sha="$(sha256sum "$jar" | awk '{print $1}')"
jar_size="$(stat -c '%s' "$jar")"
host_package_name="$(basename "$host_package")"
host_package_sha="$(sha256sum "$host_package" | awk '{print $1}')"
host_package_size="$(stat -c '%s' "$host_package")"

jq -n \
  --arg schemaVersion '1' \
  --arg releaseTag "$tag" \
  --arg version "${tag#v}" \
  --arg commitSha "$commit" \
  --arg publishedAt "$(date --iso-8601=seconds)" \
  --arg platform 'linux/amd64' \
  --arg runtimeFingerprint "$runtime_fingerprint" \
  --arg updateType "$update_type" \
  --argjson minimumAgentVersion "$minimum_agent_version" \
  --arg jarName "$jar_name" \
  --arg jarSha "$jar_sha" \
  --argjson jarSize "$jar_size" \
  --arg hostPackageName "$host_package_name" \
  --arg hostPackageSha "$host_package_sha" \
  --argjson hostPackageSize "$host_package_size" \
  --arg imageRepository "$image_repository" \
  --arg imageDigest "$image_digest" \
  --argjson requiresBackup "$requires_backup" \
  --arg migrationRange "$migration_range" \
  --arg backupReason "Flyway 迁移差异：$migration_range" \
  --arg manualReason "$manual_reason" \
  --arg manualInstructions "$manual_instructions" \
  '{schemaVersion: ($schemaVersion | tonumber), releaseTag: $releaseTag, version: $version,
    commitSha: $commitSha, publishedAt: $publishedAt, platform: $platform,
    minimumAgentVersion: $minimumAgentVersion, runtimeFingerprint: $runtimeFingerprint,
    updateType: $updateType, jar: {name: $jarName, sha256: $jarSha, size: $jarSize},
    hostPackage: {name: $hostPackageName, sha256: $hostPackageSha, size: $hostPackageSize},
    image: {repository: $imageRepository, digest: $imageDigest},
    database: {requiresBackup: $requiresBackup, migrationRange: $migrationRange,
      backupRequiredReason: $backupReason},
    manual: {reason: $manualReason, instructions: $manualInstructions},
    manualInstructions: $manualInstructions}' > "$output"
