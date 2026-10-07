#!/usr/bin/env bash
set -Eeuo pipefail

readonly repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

for required_command in git gzip jq sha256sum tar; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "Missing test dependency: $required_command" >&2
    exit 1
  }
done

test_root="$(mktemp -d)"
cleanup() {
  rm -rf "$test_root"
}
trap cleanup EXIT

fixture="$test_root/repository"
mkdir -p "$fixture"
git -C "$fixture" init -q
git -C "$fixture" config user.email 'release-test@example.invalid'
git -C "$fixture" config user.name 'release-test'

write_runtime_files() {
  mkdir -p "$fixture/docker" "$fixture/deploy/nginx" "$fixture/deploy/server" \
    "$fixture/deploy/update" "$fixture/src/main/resources/db/migration"
  printf '%s\n' 'DB_PASSWORD=change-me' > "$fixture/.env.example"
  printf '%s\n' 'name: xianyu2' > "$fixture/compose.yaml"
  cat > "$fixture/pom.xml" <<'EOF'
<project><dependencies><dependency><groupId>com.microsoft.playwright</groupId><artifactId>playwright</artifactId><version>1.61.0</version></dependency></dependencies></project>
EOF
  cat > "$fixture/Dockerfile" <<'EOF'
ARG PLAYWRIGHT_BASE_IMAGE=ghcr.io/daki-l/xianyu2-playwright:v1.61.0
ARG JRE_BASE_IMAGE=eclipse-temurin:21-jre-jammy
ARG APP_UID=10001
ARG APP_GID=10001
USER xianyu2
EOF
  cat > "$fixture/Dockerfile.playwright-base" <<'EOF'
ARG PLAYWRIGHT_VERSION=1.61.0
EOF
  printf '%s\n' '#!/bin/sh' > "$fixture/docker/entrypoint.sh"
  printf '%s\n' 'server {}' > "$fixture/deploy/nginx/default.conf"
  printf '%s\n' 'services: {}' > "$fixture/deploy/server/compose-existing-mysql.yaml"
  printf '%s\n' '1' > "$fixture/deploy/update/agent-version"
  printf '%s\n' '#!/bin/sh' > "$fixture/deploy/update/xianyu2-update-agent"
  printf '%s\n' '[Service]' > "$fixture/deploy/update/xianyu2-update-agent.service"
  printf '%s\n' '[Path]' > "$fixture/deploy/update/xianyu2-update-agent.path"
  printf '%s\n' 'PROJECT_DIR=/opt/xianyu2' > "$fixture/deploy/update/update-agent.conf.example"
  printf '%s\n' '#!/bin/sh' > "$fixture/deploy/update/backup-mysql"
  printf '%s\n' '#!/bin/sh' > "$fixture/deploy/update/install-update-agent.sh"
  printf '%s\n' '#!/bin/sh' > "$fixture/deploy/update/install-release.sh"
  printf '%s\n' 'placeholder' > "$fixture/src/main/resources/db/migration/V1__initial.sql"
}

write_metadata() {
  local tag="$1"
  local host_contract_required="$2"
  local minimum_agent_version="${3:-1}"
  mkdir -p "$fixture/release-metadata"
  if [[ "$host_contract_required" == 'true' ]]; then
    cat > "$fixture/release-metadata/${tag}.json" <<EOF
{"releaseTag":"${tag}","hostContractRequired":true,"minimumAgentVersion":${minimum_agent_version},"manual":{"reason":"test host contract","instructions":"apply the verified host package"}}
EOF
  else
    cat > "$fixture/release-metadata/${tag}.json" <<EOF
{"releaseTag":"${tag}","hostContractRequired":false,"minimumAgentVersion":${minimum_agent_version}}
EOF
  fi
}

write_runtime_files
git -C "$fixture" add .
git -C "$fixture" commit -qm 'baseline'
git -C "$fixture" tag v2.0.7

write_metadata v2.0.8 false
printf '%s\n' 'business-only-change' > "$fixture/application.txt"
git -C "$fixture" add .
git -C "$fixture" commit -qm 'release metadata'
release_commit="$(git -C "$fixture" rev-parse HEAD)"
runtime_fingerprint="$(
  cd "$fixture"
  bash "$repository_root/scripts/release/runtime-fingerprint.sh" "$release_commit"
)"
[[ -z "$(cd "$fixture" && bash "$repository_root/scripts/release/detect-host-contract-change.sh" --from v2.0.7 --to "$release_commit")" ]]

jar="$test_root/xianyu2-v2.0.8.jar"
printf '%s\n' 'test jar' > "$jar"
host_package="$test_root/xianyu2-host-package-v2.0.8.tar.gz"
(
  cd "$fixture"
  bash "$repository_root/scripts/release/build-host-package.sh" \
    --version 2.0.8 --commit "$release_commit" --output "$host_package"
)
tar -tzf "$host_package" | grep -Fx 'xianyu2-host-package-v2.0.8/deploy/update/agent-version' >/dev/null
bash "$repository_root/scripts/release/validate-host-package.sh" --archive "$host_package" --version 2.0.8

malicious_package="$test_root/malicious-host-package.tar.gz"
malicious_directory="$test_root/malicious-host-package-v2.0.8"
mkdir -p "$malicious_directory/deploy/update"
printf '%s\n' '1' > "$malicious_directory/deploy/update/agent-version"
ln -s /etc/passwd "$malicious_directory/deploy/update/untrusted-link"
tar -C "$test_root" -czf "$malicious_package" "$(basename "$malicious_directory")"
if bash "$repository_root/scripts/release/validate-host-package.sh" --archive "$malicious_package" --version 2.0.8; then
  echo 'Host package validator accepted a symbolic link.' >&2
  exit 1
fi

manifest="$test_root/release-manifest.json"
(
  cd "$fixture"
  bash "$repository_root/scripts/release/generate-release-manifest.sh" \
    --tag v2.0.8 --commit "$release_commit" --jar "$jar" --host-package "$host_package" \
    --metadata "release-metadata/v2.0.8.json" \
    --runtime-fingerprint "$runtime_fingerprint" \
    --previous-runtime-fingerprint "$runtime_fingerprint" \
    --image-repository ghcr.io/daki-l/xianyu2 \
    --image-digest 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    --output "$manifest"
)
[[ "$(jq -r '.updateType' "$manifest")" == 'jar' ]]
[[ "$(jq -r '.hostPackage.name' "$manifest")" == 'xianyu2-host-package-v2.0.8.tar.gz' ]]
[[ "$(jq -r '.minimumAgentVersion' "$manifest")" == '1' ]]

write_metadata v2.0.9 false
printf '%s\n' 'runtime-fingerprint-mismatch' > "$fixture/application-2.txt"
git -C "$fixture" add .
git -C "$fixture" commit -qm 'runtime fingerprint mismatch fixture'
image_commit="$(git -C "$fixture" rev-parse HEAD)"
(
  cd "$fixture"
  bash "$repository_root/scripts/release/generate-release-manifest.sh" \
    --tag v2.0.9 --commit "$image_commit" --jar "$jar" --host-package "$host_package" \
    --metadata "release-metadata/v2.0.9.json" \
    --runtime-fingerprint "$runtime_fingerprint" \
    --previous-runtime-fingerprint 'jre-21-playwright-other-runtime' \
    --image-repository ghcr.io/daki-l/xianyu2 \
    --image-digest 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    --output "$test_root/image-manifest.json"
)
[[ "$(jq -r '.updateType' "$test_root/image-manifest.json")" == 'image' ]]

write_metadata v2.0.10 true
git -C "$fixture" add .
git -C "$fixture" commit -qm 'forced manual release metadata'
manual_commit="$(git -C "$fixture" rev-parse HEAD)"
(
  cd "$fixture"
  bash "$repository_root/scripts/release/generate-release-manifest.sh" \
    --tag v2.0.10 --commit "$manual_commit" --jar "$jar" --host-package "$host_package" \
    --metadata "release-metadata/v2.0.10.json" \
    --runtime-fingerprint "$runtime_fingerprint" \
    --previous-runtime-fingerprint "$runtime_fingerprint" \
    --image-repository ghcr.io/daki-l/xianyu2 \
    --image-digest 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    --output "$test_root/manual-manifest.json"
)
[[ "$(jq -r '.updateType' "$test_root/manual-manifest.json")" == 'host-package-manual-required' ]]

write_metadata v2.0.11 false 2
if bash "$repository_root/scripts/release/validate-release-metadata.sh" \
  --tag v2.0.11 --metadata "$fixture/release-metadata/v2.0.11.json" \
  --agent-version 2 --previous-agent-version 1; then
  echo 'Increasing minimumAgentVersion without a manual host release unexpectedly passed.' >&2
  exit 1
fi

write_metadata v2.0.12 false
if bash "$repository_root/scripts/release/validate-release-metadata.sh" \
  --tag v2.0.12 --metadata "$fixture/release-metadata/v2.0.12.json" \
  --agent-version 1 --first-release; then
  echo 'The first formal release unexpectedly skipped the manual host-package requirement.' >&2
  exit 1
fi

write_metadata v2.0.13 false
printf '%s\n' '# host contract change' >> "$fixture/deploy/nginx/default.conf"
git -C "$fixture" add .
git -C "$fixture" commit -qm 'host contract without manual metadata'
invalid_commit="$(git -C "$fixture" rev-parse HEAD)"
changed_contract_paths="$(cd "$fixture" && bash "$repository_root/scripts/release/detect-host-contract-change.sh" --from v2.0.7 --to "$invalid_commit")"
grep -Fx 'deploy/nginx/default.conf' <<< "$changed_contract_paths" >/dev/null
if (
  cd "$fixture"
  bash "$repository_root/scripts/release/generate-release-manifest.sh" \
    --tag v2.0.13 --commit "$invalid_commit" --jar "$jar" --host-package "$host_package" \
    --metadata "release-metadata/v2.0.13.json" \
    --runtime-fingerprint "$runtime_fingerprint" \
    --previous-runtime-fingerprint "$runtime_fingerprint" \
    --image-repository ghcr.io/daki-l/xianyu2 \
    --image-digest 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    --output "$test_root/invalid-manifest.json"
); then
  echo 'Host contract change without manual metadata unexpectedly passed.' >&2
  exit 1
fi

write_metadata v2.0.14 false
sed -i 's|^ARG JRE_BASE_IMAGE=.*|ARG JRE_BASE_IMAGE=eclipse-temurin:21-jre-noble|' "$fixture/Dockerfile"
git -C "$fixture" add .
git -C "$fixture" commit -qm 'runtime-only Dockerfile change'
runtime_only_commit="$(git -C "$fixture" rev-parse HEAD)"
runtime_only_contract_changes="$(cd "$fixture" && bash "$repository_root/scripts/release/detect-host-contract-change.sh" --from "$invalid_commit" --to "$runtime_only_commit")"
[[ -z "$runtime_only_contract_changes" ]] || {
  echo "Runtime-only Dockerfile change unexpectedly requires a host package: $runtime_only_contract_changes" >&2
  exit 1
}

sed -i 's|^ARG APP_UID=.*|ARG APP_UID=10002|' "$fixture/Dockerfile"
git -C "$fixture" add Dockerfile
git -C "$fixture" commit -qm 'container identity change'
identity_change_commit="$(git -C "$fixture" rev-parse HEAD)"
identity_contract_changes="$(cd "$fixture" && bash "$repository_root/scripts/release/detect-host-contract-change.sh" --from "$runtime_only_commit" --to "$identity_change_commit")"
grep -Fx 'Dockerfile:ARG_APP_UID' <<< "$identity_contract_changes" >/dev/null

printf '%s\n' 'Release packaging tests passed.'
