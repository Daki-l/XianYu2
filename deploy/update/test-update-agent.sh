#!/usr/bin/env bash
set -Eeuo pipefail

# 代理的黑盒回归测试。仅依赖 Linux CI 自带的 bash、jq、flock、coreutils；网络、Cosign 和 Docker 全部模拟。
readonly script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
readonly agent="$script_dir/xianyu2-update-agent"
readonly task_id='123e4567-e89b-12d3-a456-426614174000'
readonly target_tag='v2.0.8'
readonly target_version='2.0.8'
readonly target_digest='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
readonly current_digest='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
readonly fingerprint='jre-21-playwright-test'

for command_name in jq flock sha256sum stat mktemp setsid; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing test dependency: $command_name" >&2
    exit 1
  }
done

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

fail() {
  echo "FAILED: $*" >&2
  exit 1
}

assert_file_contains() {
  grep -Fqx "$2" "$1" >/dev/null || fail "Expected $1 to contain: $2"
}

assert_status() {
  local expected="$1"
  [[ "$(jq -r '.schemaVersion' "$status_dir/status.json")" == '1' ]] \
    || fail "Update status does not declare schema version 1"
  [[ "$(jq -r '.status' "$status_dir/status.json")" == "$expected" ]] \
    || fail "Expected status $expected, got $(cat "$status_dir/status.json")"
}

make_mocks() {
  mock_bin="$case_root/mock-bin"
  mkdir -p "$mock_bin"
  cat > "$mock_bin/cosign" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" != *"--certificate-identity ${AGENT_TEST_COSIGN_IDENTITY}"* ]]; then
  echo "Cosign did not receive the expected exact release identity." >&2
  exit 1
fi
if [[ "${AGENT_TEST_COSIGN_FAIL:-false}" == 'true' ]]; then
  exit 1
fi
exit 0
EOF
  cat > "$mock_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$AGENT_TEST_DOCKER_LOG"
arguments=" $* "
if [[ "${AGENT_TEST_DOCKER_PULL_FAIL:-false}" == 'true' && "$arguments" == *' pull app '* ]]; then
  exit 1
fi
  if [[ "${AGENT_TEST_DOCKER_DIRECT_PULL_FAIL:-false}" == 'true' && "$arguments" == *' pull ghcr.io/daki-l/xianyu2@sha256:'* ]]; then
    exit 1
  fi
if [[ "$arguments" == *' config --services '* ]]; then
  printf 'mysql\n'
elif [[ "$arguments" == *' ps -q app '* ]]; then
  printf 'test-app\n'
elif [[ "${1:-}" == 'inspect' ]]; then
    if [[ "${AGENT_TEST_HEALTH_FAIL:-false}" == 'true' ]]; then
      printf 'unhealthy\n'
    else
      printf 'healthy\n'
    fi
fi
exit 0
EOF
  cat > "$mock_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
destination=''
write_effective=false
args=("$@")
for ((index = 0; index < ${#args[@]}; index++)); do
  case "${args[$index]}" in
    -o|--output)
      destination="${args[$((index + 1))]}"
      ;;
    --write-out)
      write_effective=true
      ;;
  esac
done
url="${args[$(( ${#args[@]} - 1 ))]}"
if [[ "${AGENT_TEST_CURL_FAIL:-false}" == 'true' ]]; then
  exit 28
fi
case "$url" in
  */releases/tags/*) cp "$AGENT_TEST_FIXTURE/release.json" "$destination" ;;
  */git/ref/tags/*) cp "$AGENT_TEST_FIXTURE/tag-ref.json" "$destination" ;;
  */releases/assets/1) cp "$AGENT_TEST_FIXTURE/release-manifest.json" "$destination" ;;
  */releases/assets/2) cp "$AGENT_TEST_FIXTURE/release-manifest.json.bundle" "$destination" ;;
  */releases/assets/3) cp "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar" "$destination" ;;
  */releases/assets/4) cp "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar.bundle" "$destination" ;;
  *) echo "Unexpected curl URL: $url" >&2; exit 1 ;;
esac
if [[ "$write_effective" == true ]]; then
  printf 'https://%s/xianyu2-test\n' "${AGENT_TEST_EFFECTIVE_HOST:-release-assets.githubusercontent.com}"
fi
EOF
  chmod +x "$mock_bin/cosign" "$mock_bin/docker" "$mock_bin/curl"
}

write_fixture() {
  local update_type="$1"
  local declared_sha="$2"
  local requires_backup="${3:-false}"
  local minimum_agent_version="${4:-1}"
  fixture_dir="$case_root/fixture"
  mkdir -p "$fixture_dir"
  printf 'new-release-jar\n' > "$fixture_dir/xianyu2-v2.0.8.jar"
  : > "$fixture_dir/release-manifest.json.bundle"
  : > "$fixture_dir/xianyu2-v2.0.8.jar.bundle"
  local jar_size
  jar_size="$(stat -c '%s' "$fixture_dir/xianyu2-v2.0.8.jar")"
  jq -n --arg tag "$target_tag" --arg version "$target_version" \
    --arg commit '1111111111111111111111111111111111111111' --arg fingerprint "$fingerprint" \
    --arg type "$update_type" --arg sha "$declared_sha" --arg digest "$target_digest" \
    --argjson size "$jar_size" --argjson backup "$requires_backup" --argjson minimumAgentVersion "$minimum_agent_version" \
    '{schemaVersion: 1, releaseTag: $tag, version: $version, commitSha: $commit,
      platform: "linux/amd64", minimumAgentVersion: $minimumAgentVersion, runtimeFingerprint: $fingerprint,
      updateType: $type, jar: {name: "xianyu2-v2.0.8.jar", sha256: $sha, size: $size},
      hostPackage: {name: "xianyu2-host-package-v2.0.8.tar.gz",
        sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", size: 1},
      image: {repository: "ghcr.io/daki-l/xianyu2", digest: $digest},
      database: {requiresBackup: $backup},
      manual: {reason: "test host contract", instructions: "complete the test host change first"}}' > "$fixture_dir/release-manifest.json"
  jq -n --arg tag "$target_tag" \
    '{tag_name: $tag, draft: false, prerelease: false, assets: [
      {name: "release-manifest.json", id: 1}, {name: "release-manifest.json.bundle", id: 2},
      {name: "xianyu2-v2.0.8.jar", id: 3}, {name: "xianyu2-v2.0.8.jar.bundle", id: 4},
      {name: "xianyu2-host-package-v2.0.8.tar.gz", id: 5},
      {name: "xianyu2-host-package-v2.0.8.tar.gz.bundle", id: 6}
    ]}' > "$fixture_dir/release.json"
  jq -n '{object: {type: "commit", sha: "1111111111111111111111111111111111111111"}}' > "$fixture_dir/tag-ref.json"
}

prepare_case() {
  local name="$1"
  case_root="$test_root/$name"
  request_dir="$case_root/update/request"
  status_dir="$case_root/update/status"
  work_dir="$case_root/update/private"
  runtime_dir="$case_root/runtime"
  project_dir="$case_root/project"
  config_file="$case_root/update-agent.conf"
  mkdir -p "$request_dir" "$status_dir" "$work_dir" "$runtime_dir" "$project_dir"
  : > "$project_dir/compose.yaml"
  : > "$project_dir/.env"
  printf 'APP_IMAGE=ghcr.io/daki-l/xianyu2@%s\nAPP_IMAGE_DIGEST=%s\n' "$current_digest" "$current_digest" > "$project_dir/release.env"
  cat > "$config_file" <<EOF
PROJECT_DIR=$project_dir
COMPOSE_FILE=$project_dir/compose.yaml
ENV_FILE=$project_dir/.env
RELEASE_ENV_FILE=$project_dir/release.env
UPDATE_REQUEST_DIR=$request_dir
UPDATE_STATUS_DIR=$status_dir
UPDATE_WORK_DIR=$work_dir
RUNTIME_DIR=$runtime_dir
APP_UID=$(id -u)
APP_GID=$(id -g)
RELEASE_REPOSITORY=ghcr.io/daki-l/xianyu2
COSIGN_OIDC_ISSUER=https://token.actions.githubusercontent.com
HEALTH_TIMEOUT_SECONDS=1
EOF
  docker_log="$case_root/docker.log"
  : > "$docker_log"
  make_mocks
}

write_request() {
  jq -n --arg taskId "$task_id" --arg tag "$target_tag" --arg version "$target_version" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $tag, version: $version}' > "$request_dir/request.json"
  chmod 0640 "$request_dir/request.json"
}

write_installed_state() {
  jq -n --arg digest "$current_digest" --arg fingerprint "$fingerprint" \
    '{schemaVersion: 1, imageDigest: $digest, runtimeFingerprint: $fingerprint}' > "$runtime_dir/installed.json"
}

run_agent() {
  AGENT_TEST_FIXTURE="$fixture_dir" AGENT_TEST_DOCKER_LOG="$docker_log" \
    AGENT_TEST_COSIGN_IDENTITY="https://github.com/Daki-l/XianYu2/.github/workflows/release.yml@refs/tags/${target_tag}" \
    PATH="$mock_bin:$PATH" XIANYU2_UPDATE_AGENT_CONFIG="$config_file" bash "$agent" "$@"
}

run_agent_until_heartbeat_wait() {
  local agent_log="$case_root/recovery-wait.log"
  setsid env \
    AGENT_TEST_FIXTURE="$fixture_dir" AGENT_TEST_DOCKER_LOG="$docker_log" \
    AGENT_TEST_COSIGN_IDENTITY="https://github.com/Daki-l/XianYu2/.github/workflows/release.yml@refs/tags/${target_tag}" \
    PATH="$mock_bin:$PATH" XIANYU2_UPDATE_AGENT_CONFIG="$config_file" bash "$agent" "$@" > "$agent_log" 2>&1 &
  local agent_pid=$!
  local attempt
  for attempt in $(seq 1 20); do
    if grep -Fq 'still has a valid heartbeat; waiting' "$agent_log" 2>/dev/null; then
      # The test process owns a separate session, so this also terminates its
      # child sleep and does not leave a 120-second orphan in the CI runner.
      kill -KILL -- "-${agent_pid}" 2>/dev/null || true
      wait "$agent_pid" 2>/dev/null || true
      return 0
    fi
    sleep 0.1
  done
  kill -KILL -- "-${agent_pid}" 2>/dev/null || true
  wait "$agent_pid" 2>/dev/null || true
  return 1
}

test_hash_mismatch_preserves_runtime_jar() {
  prepare_case hash-mismatch
  write_fixture jar '0000000000000000000000000000000000000000000000000000000000000000'
  write_request
  write_installed_state
  printf 'old-runtime-jar\n' > "$runtime_dir/app.jar"
  if run_agent; then
    fail 'Hash mismatch unexpectedly succeeded'
  fi
  assert_status FAILED
  assert_file_contains "$runtime_dir/app.jar" 'old-runtime-jar'
}

test_unknown_state_falls_back_to_image_update() {
  prepare_case unknown-state
  local sha
  write_fixture jar placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture jar "$sha"
  write_request
  run_agent
  assert_status SUCCESS
  [[ ! -e "$runtime_dir/app.jar" ]] || fail 'Unknown state wrote runtime JAR instead of using the image baseline'
  [[ "$(jq -r '.source' "$runtime_dir/installed.json")" == baseline ]] || fail 'Unknown state did not record baseline source'
}

test_image_update_clears_runtime_jar() {
  prepare_case image-update
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  printf 'old-runtime-jar\n' > "$runtime_dir/app.jar"
  run_agent
  assert_status SUCCESS
  [[ ! -e "$runtime_dir/app.jar" ]] || fail 'Image update retained runtime JAR'
  [[ "$(jq -r '.source' "$runtime_dir/installed.json")" == baseline ]] || fail 'Image update did not record baseline source'
  grep -Fq "APP_IMAGE_DIGEST=$target_digest" "$project_dir/release.env" || fail 'Image update did not write release.env'
  grep -Fq 'pull app' "$docker_log" || fail 'Image update did not pull the app image'
}

test_downgrade_is_rejected() {
  prepare_case downgrade
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  write_installed_state
  jq '.version = "2.0.9"' "$runtime_dir/installed.json" > "$runtime_dir/installed.json.tmp"
  mv "$runtime_dir/installed.json.tmp" "$runtime_dir/installed.json"
  if run_agent; then
    fail 'Agent accepted a downgrade request'
  fi
  assert_status FAILED
  [[ ! -s "$docker_log" ]] || fail 'Downgrade request reached Docker Compose'
}

test_image_pull_failure_preserves_runtime_jar() {
  prepare_case image-pull-failure
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  printf 'old-runtime-jar\n' > "$runtime_dir/app.jar"
  export AGENT_TEST_DOCKER_PULL_FAIL=true
  if run_agent; then
    unset AGENT_TEST_DOCKER_PULL_FAIL
    fail 'Image pull failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_DOCKER_PULL_FAIL
  assert_status FAILED
  assert_file_contains "$runtime_dir/app.jar" 'old-runtime-jar'
  grep -Fq "APP_IMAGE_DIGEST=$current_digest" "$project_dir/release.env" || fail 'Image pull failure did not restore release.env'
}

test_candidate_image_pull_failure_keeps_release_inputs() {
  prepare_case candidate-image-pull-failure
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  printf 'old-runtime-jar\n' > "$runtime_dir/app.jar"
  export AGENT_TEST_DOCKER_DIRECT_PULL_FAIL=true
  if run_agent; then
    unset AGENT_TEST_DOCKER_DIRECT_PULL_FAIL
    fail 'Candidate image pull failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_DOCKER_DIRECT_PULL_FAIL
  assert_status FAILED
  assert_file_contains "$runtime_dir/app.jar" 'old-runtime-jar'
  grep -Fq "APP_IMAGE_DIGEST=$current_digest" "$project_dir/release.env" \
    || fail 'Candidate image pull failure changed release.env'
}

test_health_failure_requires_manual_recovery() {
  prepare_case health-failure
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  write_installed_state
  export AGENT_TEST_HEALTH_FAIL=true
  if run_agent; then
    unset AGENT_TEST_HEALTH_FAIL
    fail 'Health check failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_HEALTH_FAIL
  assert_status FAILED
  grep -Fq 'Flyway 可能已执行' "$status_dir/status.json" \
    || fail 'Health failure did not require manual recovery after container recreation'
  grep -Fq "APP_IMAGE_DIGEST=$target_digest" "$project_dir/release.env" \
    || fail 'Health failure incorrectly claimed to restore the previous release input'
}

test_backup_failure_stops_before_compose() {
  prepare_case backup-failure
  local sha hook
  write_fixture image placeholder true
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha" true
  hook="$case_root/failing-backup"
  printf '#!/usr/bin/env bash\nexit 1\n' > "$hook"
  chmod +x "$hook"
  printf 'BACKUP_HOOK=%q\n' "$hook" >> "$config_file"
  write_request
  if run_agent; then
    fail 'Backup failure unexpectedly succeeded'
  fi
  assert_status FAILED
  grep -F ' up -d ' "$docker_log" >/dev/null && fail 'Backup failure started an application container'
  grep -Fq "APP_IMAGE_DIGEST=$current_digest" "$project_dir/release.env" \
    || fail 'Backup failure did not restore release.env'
}

test_signature_failure_stops_before_runtime_change() {
  prepare_case signature-failure
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  export AGENT_TEST_COSIGN_FAIL=true
  if run_agent; then
    unset AGENT_TEST_COSIGN_FAIL
    fail 'Signature failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_COSIGN_FAIL
  assert_status FAILED
  grep -Fq "APP_IMAGE_DIGEST=$current_digest" "$project_dir/release.env" || fail 'Signature failure changed release.env'
}

test_untrusted_redirect_is_rejected() {
  prepare_case untrusted-redirect
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  export AGENT_TEST_EFFECTIVE_HOST=untrusted.example
  if run_agent; then
    unset AGENT_TEST_EFFECTIVE_HOST
    fail 'Untrusted redirect unexpectedly succeeded'
  fi
  unset AGENT_TEST_EFFECTIVE_HOST
  assert_status FAILED
}

test_download_failure_is_terminal() {
  prepare_case download-failure
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  export AGENT_TEST_CURL_FAIL=true
  if run_agent; then
    unset AGENT_TEST_CURL_FAIL
    fail 'Download failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_CURL_FAIL
  assert_status FAILED
}

test_manual_release_requires_host_confirmation() {
  prepare_case manual-release
  local sha
  write_fixture host-package-manual-required placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture host-package-manual-required "$sha"
  write_request
  run_agent
  assert_status MANUAL_REQUIRED
  [[ ! -s "$docker_log" ]] || fail 'Manual-required release reached Docker Compose without host confirmation'

  run_agent --apply-manual-release "$target_tag"
  assert_status SUCCESS
  grep -Fq "APP_IMAGE_DIGEST=$target_digest" "$project_dir/release.env" || fail 'Manual application did not update release.env'
}

test_initial_install_applies_verified_manual_release() {
  prepare_case initial-manual-release
  local sha
  write_fixture host-package-manual-required placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture host-package-manual-required "$sha"
  write_request
  run_agent --initial-install
  assert_status SUCCESS
  [[ "$(jq -r '.source' "$runtime_dir/installed.json")" == baseline ]] || fail 'Initial manual release did not install the image baseline'
}

test_newer_agent_is_required_for_manual_application() {
  prepare_case minimum-agent-version
  local sha
  write_fixture host-package-manual-required placeholder false 2
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture host-package-manual-required "$sha" false 2
  write_request
  run_agent
  assert_status MANUAL_REQUIRED

  run_agent --apply-manual-release "$target_tag"
  assert_status MANUAL_REQUIRED
  [[ ! -s "$docker_log" ]] || fail 'Outdated update agent reached Docker Compose'
}

test_newer_agent_blocks_regular_update_with_manual_status() {
  prepare_case minimum-agent-version-regular
  local sha
  write_fixture image placeholder false 2
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha" false 2
  write_request
  run_agent
  assert_status MANUAL_REQUIRED
  [[ ! -s "$docker_log" ]] || fail 'Outdated agent reached Docker for a regular release'
}

test_unknown_manifest_schema_requires_manual_recovery() {
  prepare_case unknown-manifest-schema
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  jq '.schemaVersion = 2' "$fixture_dir/release-manifest.json" > "$fixture_dir/release-manifest.json.tmp"
  mv "$fixture_dir/release-manifest.json.tmp" "$fixture_dir/release-manifest.json"
  write_request
  run_agent
  assert_status MANUAL_REQUIRED
  [[ ! -s "$docker_log" ]] || fail 'Unknown manifest schema reached Docker'
}

test_missing_host_package_asset_is_rejected() {
  prepare_case missing-host-package
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  jq 'del(.assets[] | select(.name | startswith("xianyu2-host-package")))' \
    "$fixture_dir/release.json" > "$fixture_dir/release.json.tmp"
  mv "$fixture_dir/release.json.tmp" "$fixture_dir/release.json"
  write_request
  if run_agent; then
    fail 'Release with a missing host package asset unexpectedly succeeded'
  fi
  assert_status FAILED
  [[ ! -s "$docker_log" ]] || fail 'Missing host package asset reached Docker Compose'
}

test_lock_keeps_request_unclaimed() {
  prepare_case lock
  local sha lock_holder
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  mkdir -p "$work_dir"
  (
    exec 9>"$work_dir/agent.lock"
    flock -n 9
    sleep 3
  ) &
  lock_holder=$!
  sleep 0.1
  run_agent
  wait "$lock_holder"
  [[ -f "$request_dir/request.json" ]] || fail 'Locked agent claimed the request'
}

test_symbolic_link_request_is_rejected() {
  prepare_case symbolic-link-request
  write_fixture image 'placeholder'
  printf '%s\n' '{"schemaVersion":1}' > "$case_root/untrusted-request.json"
  ln -s "$case_root/untrusted-request.json" "$request_dir/request.json"
  run_agent
  [[ ! -e "$request_dir/request.json" && ! -L "$request_dir/request.json" ]] \
    || fail 'Agent retained a symbolic-link request'
  [[ ! -s "$docker_log" ]] || fail 'Symbolic-link request reached Docker'
}

test_hard_link_request_is_rejected() {
  prepare_case hard-link-request
  write_fixture image 'placeholder'
  write_request
  ln "$request_dir/request.json" "$case_root/request-link.json"
  run_agent
  [[ ! -e "$request_dir/request.json" ]] || fail 'Agent retained a hard-linked request'
  [[ -e "$case_root/request-link.json" ]] || fail 'Hard-link fixture unexpectedly disappeared'
  [[ ! -s "$docker_log" ]] || fail 'Hard-linked request reached Docker'
}

test_world_writable_request_is_rejected() {
  prepare_case writable-request
  write_fixture image 'placeholder'
  write_request
  chmod 0666 "$request_dir/request.json"
  run_agent
  [[ ! -e "$request_dir/request.json" ]] || fail 'Agent retained a world-writable request'
  [[ ! -s "$docker_log" ]] || fail 'World-writable request reached Docker'
}

test_world_readable_request_is_rejected() {
  prepare_case readable-request
  write_fixture image 'placeholder'
  write_request
  chmod 0644 "$request_dir/request.json"
  run_agent
  [[ ! -e "$request_dir/request.json" ]] || fail 'Agent retained a world-readable request'
  [[ ! -s "$docker_log" ]] || fail 'World-readable request reached Docker'
}

test_claimed_task_is_archived_as_failed_after_restart() {
  prepare_case claimed-task
  write_fixture image 'placeholder'
  mkdir -p "$work_dir/in-progress"
  jq -n --arg taskId "$task_id" --arg tag "$target_tag" --arg version "$target_version" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $tag, version: $version}' > "$work_dir/in-progress/${task_id}.json"
  run_agent
  assert_status FAILED
  [[ ! -e "$work_dir/in-progress/${task_id}.json" ]] || fail 'Claimed task was not archived after restart'
  find "$work_dir/archive" -type f -name "${task_id}-*.json" -print -quit | grep -q . \
    || fail 'Claimed task archive is missing'
}

test_recent_heartbeat_is_not_failed_after_restart() {
  prepare_case recent-heartbeat
  write_fixture image 'placeholder'
  mkdir -p "$work_dir/in-progress"
  jq -n --arg taskId "$task_id" --arg tag "$target_tag" --arg version "$target_version" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $tag, version: $version}' > "$work_dir/in-progress/${task_id}.json"
  jq -n --arg taskId "$task_id" --arg now "$(date --iso-8601=seconds)" \
    '{taskId: $taskId, status: "DOWNLOADING", updatedAt: $now, timeoutSeconds: 120}' > "$status_dir/status.json"
  run_agent_until_heartbeat_wait || fail 'Agent did not wait for a recently heartbeating task'
  [[ -e "$work_dir/in-progress/${task_id}.json" ]] || fail 'Recent task was incorrectly archived'
  [[ "$(jq -r '.status' "$status_dir/status.json")" == DOWNLOADING ]] || fail 'Recent task status was overwritten'
}

test_total_timeout_wins_over_a_recent_heartbeat_after_restart() {
  prepare_case total-timeout-recovery
  write_fixture image 'placeholder'
  mkdir -p "$work_dir/in-progress"
  jq -n --arg taskId "$task_id" --arg tag "$target_tag" --arg version "$target_version" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $tag, version: $version}' > "$work_dir/in-progress/${task_id}.json"
  jq -n --arg taskId "$task_id" --arg started "$(date --date='-1 hour' --iso-8601=seconds)" \
    --arg now "$(date --iso-8601=seconds)" \
    '{schemaVersion: 1, taskId: $taskId, version: "2.0.8", status: "DOWNLOADING",
      taskStartedAt: $started, updatedAt: $now, timeoutSeconds: 120}' > "$status_dir/status.json"

  run_agent

  assert_status FAILED
  [[ ! -e "$work_dir/in-progress/${task_id}.json" ]] || fail 'Total-timeout task was not archived'
  grep -Fq '超过总时限' "$status_dir/status.json" || fail 'Total-timeout recovery did not report its cause'
}

test_install_release_requires_explicit_tag() {
  local installer="$script_dir/install-release.sh"
  if bash -c 'source "$1"; validate_release_tag' bash "$installer" 2>/dev/null; then
    fail 'Installer accepted a missing release tag'
  fi
  if bash -c 'source "$1"; validate_release_tag latest' bash "$installer" 2>/dev/null; then
    fail 'Installer accepted latest instead of a version tag'
  fi
  bash -c 'source "$1"; validate_release_tag v2.0.8' bash "$installer" \
    || fail 'Installer rejected a valid release tag'
}

test_initial_install_request_uses_application_identity() {
  prepare_case initial-request-owner
  local installer="$script_dir/install-release.sh"
  UPDATE_REQUEST_DIR="$request_dir" APP_UID="$(id -u)" APP_GID="$(id -g)" \
    bash -c 'source "$1"; create_initial_request v2.0.8' bash "$installer"
  [[ "$(stat -c '%u:%g' "$request_dir/request.json")" == "$(id -u):$(id -g)" ]] \
    || fail 'Initial installation request does not use the application identity'
  [[ "$(stat -c '%a' "$request_dir/request.json")" == '640' ]] \
    || fail 'Initial installation request does not use protected permissions'
  [[ "$(jq -r '.releaseTag' "$request_dir/request.json")" == "$target_tag" ]] \
    || fail 'Initial installation request has the wrong release tag'
}

test_total_timeout_requires_systemd_headroom() {
  prepare_case total-timeout-headroom
  printf '%s\n' 'TASK_TOTAL_TIMEOUT_SECONDS=2671' >> "$config_file"
  if run_agent --check; then
    fail 'Agent accepted a total timeout with no systemd shutdown headroom'
  fi
}

test_systemd_start_limit_is_explicit() {
  local service_file="$script_dir/xianyu2-update-agent.service"
  grep -Fx 'StartLimitIntervalSec=1h' "$service_file" >/dev/null \
    || fail 'Update agent unit does not set StartLimitIntervalSec'
  grep -Fx 'StartLimitBurst=4' "$service_file" >/dev/null \
    || fail 'Update agent unit does not set StartLimitBurst'
  grep -Fx 'WantedBy=multi-user.target' "$service_file" >/dev/null \
    || fail 'Update agent service is not enabled for boot recovery'
}

test_installer_enables_boot_recovery_service() {
  local installer="$script_dir/install-update-agent.sh"
  grep -Fx 'systemctl enable xianyu2-update-agent.service xianyu2-update-agent.path' "$installer" >/dev/null \
    || fail 'Update agent installer does not enable the boot recovery service'
  grep -Fx 'systemctl start xianyu2-update-agent.service' "$installer" >/dev/null \
    || fail 'Update agent installer does not start the recovery service'
}

test_hash_mismatch_preserves_runtime_jar
test_unknown_state_falls_back_to_image_update
test_image_update_clears_runtime_jar
test_downgrade_is_rejected
test_image_pull_failure_preserves_runtime_jar
test_candidate_image_pull_failure_keeps_release_inputs
test_health_failure_requires_manual_recovery
test_backup_failure_stops_before_compose
test_signature_failure_stops_before_runtime_change
test_untrusted_redirect_is_rejected
test_download_failure_is_terminal
test_manual_release_requires_host_confirmation
test_initial_install_applies_verified_manual_release
test_newer_agent_is_required_for_manual_application
test_newer_agent_blocks_regular_update_with_manual_status
test_unknown_manifest_schema_requires_manual_recovery
test_missing_host_package_asset_is_rejected
test_lock_keeps_request_unclaimed
test_symbolic_link_request_is_rejected
test_hard_link_request_is_rejected
test_world_writable_request_is_rejected
test_world_readable_request_is_rejected
test_claimed_task_is_archived_as_failed_after_restart
test_recent_heartbeat_is_not_failed_after_restart
test_total_timeout_wins_over_a_recent_heartbeat_after_restart
test_install_release_requires_explicit_tag
test_initial_install_request_uses_application_identity
test_total_timeout_requires_systemd_headroom
test_systemd_start_limit_is_explicit
test_installer_enables_boot_recovery_service
printf 'Update agent tests passed.\n'
