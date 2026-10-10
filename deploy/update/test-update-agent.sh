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
readonly current_agent_version="$(tr -d '[:space:]' < "$script_dir/agent-version")"
readonly skip_jar_download_live_progress_test="${XIANYU2_SKIP_JAR_DOWNLOAD_LIVE_PROGRESS_TEST:-false}"
[[ "$current_agent_version" =~ ^[1-9][0-9]*$ ]] || {
  echo "Invalid test agent version: $current_agent_version" >&2
  exit 1
}
[[ "$skip_jar_download_live_progress_test" == 'true' || "$skip_jar_download_live_progress_test" == 'false' ]] || {
  echo 'XIANYU2_SKIP_JAR_DOWNLOAD_LIVE_PROGRESS_TEST must be true or false.' >&2
  exit 1
}
readonly next_agent_version=$((current_agent_version + 1))
# Live-transfer tests start while the agent is still fetching and verifying
# small Release metadata assets. The mocked JAR/image body then deliberately
# takes several seconds to stream, so the observation window must cover both.
readonly live_transfer_wait_attempts=200

for command_name in jq flock sha256sum stat mktemp mkfifo setsid tar; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing test dependency: $command_name" >&2
    exit 1
  }
done

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
current_test_case=''

report_github_actions_error() {
  local message="$1"
  [[ "${GITHUB_ACTIONS:-false}" == 'true' ]] || return 0
  message="${message//$'\r'/ }"
  message="${message//$'\n'/ }"
  message="${message//%/%25}"
  printf '::error title=Update agent regression::%s\n' "$message"
}

fail() {
  report_github_actions_error "$*"
  echo "FAILED: $*" >&2
  exit 1
}

on_unexpected_error() {
  local exit_code=$?
  local test_context=''
  if [[ -n "$current_test_case" ]]; then
    test_context=" while running ${current_test_case}"
  fi
  report_github_actions_error "Unexpected test command failure${test_context} at line ${BASH_LINENO[0]} (exit ${exit_code})."
  exit "$exit_code"
}

trap on_unexpected_error ERR

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
printf 'cosign|HTTP_PROXY=%s|HTTPS_PROXY=%s|NO_PROXY=%s\n' \
  "${HTTP_PROXY-}" "${HTTPS_PROXY-}" "${NO_PROXY-}" >> "$AGENT_TEST_PROXY_LOG"
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
printf 'docker|HTTP_PROXY=%s|HTTPS_PROXY=%s|NO_PROXY=%s\n' \
  "${HTTP_PROXY-}" "${HTTPS_PROXY-}" "${NO_PROXY-}" >> "$AGENT_TEST_PROXY_LOG"
printf '%s\n' "$*" >> "$AGENT_TEST_DOCKER_LOG"
arguments=" $* "
  if [[ "${AGENT_TEST_DOCKER_PULL_FAIL:-false}" == 'true' && "$arguments" == *' pull app '* ]]; then
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
printf 'curl|HTTP_PROXY=%s|HTTPS_PROXY=%s|NO_PROXY=%s\n' \
  "${HTTP_PROXY-}" "${HTTPS_PROXY-}" "${NO_PROXY-}" >> "$AGENT_TEST_PROXY_LOG"
destination=''
headers=''
write_status=false
args=("$@")
for ((index = 0; index < ${#args[@]}; index++)); do
  case "${args[$index]}" in
    -o|--output)
      destination="${args[$((index + 1))]}"
      ;;
    -D|--dump-header)
      headers="${args[$((index + 1))]}"
      ;;
    --write-out)
      write_status=true
      ;;
  esac
done
  url="${args[$(( ${#args[@]} - 1 ))]}"
  printf '%s\n' "$url" >> "$AGENT_TEST_CURL_LOG"
  if [[ "${AGENT_TEST_CURL_FAIL:-false}" == 'true' ]]; then
    exit 28
  fi

  if [[ "$url" == 'http://localhost/images/create' ]]; then
    if [[ "${AGENT_TEST_DOCKER_ENGINE_CURL_FAIL:-false}" == 'true' ]]; then
      exit 28
    fi
    if [[ "${AGENT_TEST_DOCKER_ENGINE_PULL_FAIL:-false}" == 'true' ]]; then
      printf '%s\n' '{"error":"simulated registry failure"}'
      exit 0
    fi
    printf '%s\n' '{"status":"Pulling fs layer","id":"layer-one"}'
    printf '%s\n' '{"status":"Pulling fs layer","id":"layer-two"}'
    printf '%s\n' '{"status":"Already exists","id":"layer-two"}'
    printf '%s\n' '{"status":"Downloading","id":"layer-one","progressDetail":{"current":25,"total":100}}'
    if [[ -n "${AGENT_TEST_DOCKER_ENGINE_DELAY:-}" ]]; then
      sleep "$AGENT_TEST_DOCKER_ENGINE_DELAY"
    fi
    printf '%s\n' '{"status":"Downloading","id":"layer-one","progressDetail":{"current":50,"total":100}}'
    if [[ -n "${AGENT_TEST_DOCKER_ENGINE_DELAY:-}" ]]; then
      sleep "$AGENT_TEST_DOCKER_ENGINE_DELAY"
    fi
    printf '%s\n' '{"status":"Downloading","id":"layer-one","progressDetail":{"current":100,"total":100}}'
    printf '%s\n' '{"status":"Download complete","id":"layer-one","progressDetail":{"current":100,"total":100}}'
    printf '%s\n' '{"status":"Pull complete","id":"layer-one","progressDetail":{"current":100,"total":100}}'
    exit 0
  fi

if [[ "$url" == https://api.github.com/repos/Daki-l/XianYu2/releases/assets/* && -n "$headers" ]]; then
  asset_id="${url##*/}"
  printf 'HTTP/1.1 302 Found\r\nLocation: https://%s/assets/%s\r\n\r\n' \
    "${AGENT_TEST_EFFECTIVE_HOST:-release-assets.githubusercontent.com}" "$asset_id" > "$headers"
  if [[ "$write_status" == true ]]; then
    printf '302'
  fi
  exit 0
fi

if [[ -n "$headers" ]]; then
  printf 'HTTP/1.1 200 OK\r\n\r\n' > "$headers"
fi
case "$url" in
  */releases/tags/*) cp "$AGENT_TEST_FIXTURE/release.json" "$destination" ;;
  */git/ref/tags/*) cp "$AGENT_TEST_FIXTURE/tag-ref.json" "$destination" ;;
  */assets/1) cp "$AGENT_TEST_FIXTURE/release-manifest.json" "$destination" ;;
  */assets/2) cp "$AGENT_TEST_FIXTURE/release-manifest.json.bundle" "$destination" ;;
  */assets/3)
    if [[ "${AGENT_TEST_JAR_PROGRESS:-false}" == 'true' ]]; then
      head -c 4 "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar" > "$destination"
      sleep "${AGENT_TEST_JAR_PROGRESS_DELAY:-2}"
      head -c 8 "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar" | tail -c 4 >> "$destination"
      sleep "${AGENT_TEST_JAR_PROGRESS_DELAY:-2}"
      tail -c +9 "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar" >> "$destination"
    else
      cp "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar" "$destination"
    fi
    ;;
  */assets/4) cp "$AGENT_TEST_FIXTURE/xianyu2-v2.0.8.jar.bundle" "$destination" ;;
  */assets/5) cp "$AGENT_TEST_FIXTURE/xianyu2-host-package-v2.0.8.tar.gz" "$destination" ;;
  */assets/6) cp "$AGENT_TEST_FIXTURE/xianyu2-host-package-v2.0.8.tar.gz.bundle" "$destination" ;;
  *) echo "Unexpected curl URL: $url" >&2; exit 1 ;;
esac
if [[ "$write_status" == true ]]; then
  printf '200'
fi
EOF
  chmod +x "$mock_bin/cosign" "$mock_bin/docker" "$mock_bin/curl"
}

write_host_package_fixture() {
  local package_agent_version="$1"
  local package_root="$fixture_dir/xianyu2-host-package-v2.0.8"
  rm -rf "$package_root" "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz"
  mkdir -p "$package_root/deploy/nginx" "$package_root/deploy/server" "$package_root/deploy/update"
  printf 'new environment example\n' > "$package_root/.env.example"
  printf 'new-compose\n' > "$package_root/compose.yaml"
  printf 'server config\n' > "$package_root/deploy/nginx/default.conf"
  printf 'server compose\n' > "$package_root/deploy/server/compose-existing-mysql.yaml"
  printf 'server nginx\n' > "$package_root/deploy/server/xianyu2.nginx.conf"
  cp "$agent" "$package_root/deploy/update/xianyu2-update-agent"
  printf '%s\n' "$package_agent_version" > "$package_root/deploy/update/agent-version"
  cp "$script_dir/xianyu2-update-agent.service" "$package_root/deploy/update/xianyu2-update-agent.service"
  cp "$script_dir/xianyu2-update-agent.path" "$package_root/deploy/update/xianyu2-update-agent.path"
  printf 'config example\n' > "$package_root/deploy/update/update-agent.conf.example"
  printf 'backup hook\n' > "$package_root/deploy/update/backup-mysql"
  printf 'release installer\n' > "$package_root/deploy/update/install-release.sh"
cat > "$package_root/deploy/update/install-update-agent.sh" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'host package installer executed\n' >> "$AGENT_TEST_HOST_INSTALL_LOG"
[[ "${AGENT_TEST_HOST_INSTALL_FAIL:-false}" != 'true' ]]
EOF
  tar -C "$fixture_dir" -czf "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz" "$(basename "$package_root")"
  : > "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz.bundle"
}

write_fixture() {
  local update_type="$1"
  local declared_sha="$2"
  local requires_backup="${3:-false}"
  local minimum_agent_version="${4:-1}"
  local package_agent_version="${5:-$current_agent_version}"
  fixture_dir="$case_root/fixture"
  mkdir -p "$fixture_dir"
  printf 'new-release-jar\n' > "$fixture_dir/xianyu2-v2.0.8.jar"
  : > "$fixture_dir/release-manifest.json.bundle"
  : > "$fixture_dir/xianyu2-v2.0.8.jar.bundle"
  write_host_package_fixture "$package_agent_version"
  local jar_size host_package_size host_package_sha
  jar_size="$(stat -c '%s' "$fixture_dir/xianyu2-v2.0.8.jar")"
  host_package_size="$(stat -c '%s' "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz")"
  host_package_sha="$(sha256sum "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz" | awk '{print $1}')"
  jq -n --arg tag "$target_tag" --arg version "$target_version" \
    --arg commit '1111111111111111111111111111111111111111' --arg fingerprint "$fingerprint" \
    --arg type "$update_type" --arg sha "$declared_sha" --arg digest "$target_digest" --arg hostSha "$host_package_sha" \
    --argjson size "$jar_size" --argjson hostSize "$host_package_size" --argjson backup "$requires_backup" --argjson minimumAgentVersion "$minimum_agent_version" \
    '{schemaVersion: 1, releaseTag: $tag, version: $version, commitSha: $commit,
      platform: "linux/amd64", minimumAgentVersion: $minimumAgentVersion, runtimeFingerprint: $fingerprint,
      updateType: $type, jar: {name: "xianyu2-v2.0.8.jar", sha256: $sha, size: $size},
      hostPackage: {name: "xianyu2-host-package-v2.0.8.tar.gz",
        sha256: $hostSha, size: $hostSize},
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
  mkdir -p "$request_dir" "$status_dir" "$work_dir" "$runtime_dir" "$project_dir/deploy/update"
  printf 'old environment example\n' > "$project_dir/.env.example"
  printf 'old-compose\n' > "$project_dir/compose.yaml"
  : > "$project_dir/.env"
  printf 'old deploy file\n' > "$project_dir/deploy/update/old-file"
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
  curl_log="$case_root/curl.log"
  : > "$curl_log"
  proxy_log="$case_root/proxy.log"
  : > "$proxy_log"
  export AGENT_TEST_CURL_LOG="$curl_log"
  export AGENT_TEST_PROXY_LOG="$proxy_log"
  make_mocks
}

write_request() {
  jq -n --arg taskId "$task_id" --arg tag "$target_tag" --arg version "$target_version" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $tag, version: $version}' > "$request_dir/request.json"
  chmod 0640 "$request_dir/request.json"
}

write_cancel_request() {
  jq -n --arg taskId "$task_id" --arg requestedAt "$(date --iso-8601=seconds)" \
    '{schemaVersion: 1, taskId: $taskId, requestedAt: $requestedAt}' > "$request_dir/cancel.json"
  chmod 0640 "$request_dir/cancel.json"
}

write_installed_state() {
  jq -n --arg digest "$current_digest" --arg fingerprint "$fingerprint" \
    '{schemaVersion: 1, imageDigest: $digest, runtimeFingerprint: $fingerprint}' > "$runtime_dir/installed.json"
}

run_agent() {
  printf 'Running update agent command: %s\n' "${*:-<request>}"
  AGENT_TEST_FIXTURE="$fixture_dir" AGENT_TEST_DOCKER_LOG="$docker_log" \
    AGENT_TEST_COSIGN_IDENTITY="https://github.com/Daki-l/XianYu2/.github/workflows/release.yml@refs/tags/${target_tag}" \
    PATH="$mock_bin:$PATH" XIANYU2_UPDATE_AGENT_CONFIG="$config_file" bash "$agent" "$@"
}

run_agent_in_background() {
  agent_log="$case_root/agent.log"
  printf 'Running update agent in background: %s\n' "${*:-<request>}"
  setsid env \
    AGENT_TEST_FIXTURE="$fixture_dir" AGENT_TEST_DOCKER_LOG="$docker_log" \
    AGENT_TEST_COSIGN_IDENTITY="https://github.com/Daki-l/XianYu2/.github/workflows/release.yml@refs/tags/${target_tag}" \
    PATH="$mock_bin:$PATH" XIANYU2_UPDATE_AGENT_CONFIG="$config_file" bash "$agent" "$@" > "$agent_log" 2>&1 &
  agent_pid=$!
}

wait_for_live_transfer() {
  local phase="$1"
  local attempt status_snapshot='status.json is unavailable' last_transfer_status='no downloading transfer status was observed'
  for attempt in $(seq 1 "$live_transfer_wait_attempts"); do
    if [[ -f "$status_dir/status.json" ]]; then
      if jq -e --arg phase "$phase" \
        '.status == "DOWNLOADING" and .transfer.phase == $phase' "$status_dir/status.json" >/dev/null 2>&1; then
        last_transfer_status="$(cat "$status_dir/status.json")"
      fi
      if jq -e --arg phase "$phase" '
        .status == "DOWNLOADING" and .transfer.phase == $phase
        and (.transfer.downloadedBytes > 0)
        and (.transfer.totalBytes > .transfer.downloadedBytes)
        and (.transfer.speedBytesPerSecond > 0)
      ' "$status_dir/status.json" >/dev/null 2>&1; then
        return 0
      fi
    fi
    sleep 0.1
  done
  kill -KILL -- "-${agent_pid}" 2>/dev/null || true
  wait "$agent_pid" 2>/dev/null || true
  if [[ -f "$status_dir/status.json" ]]; then
    status_snapshot="$(cat "$status_dir/status.json")"
  fi
  fail "Did not observe live ${phase} transfer progress; last transfer status: ${last_transfer_status}; final status: ${status_snapshot}; agent log: $(cat "$agent_log" 2>/dev/null || true)"
}

wait_for_background_agent() {
  if wait "$agent_pid"; then
    return 0
  fi
  fail "Background update agent failed: $(cat "$agent_log" 2>/dev/null || true)"
}

wait_for_background_agent_failure() {
  if wait "$agent_pid"; then
    fail 'Background update agent unexpectedly succeeded'
  fi
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
  ! grep -Fq '/assets/3' "$curl_log" || fail 'Unknown state downloaded a JAR before selecting the image baseline'
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
  grep -Fqx 'http://localhost/images/create' "$curl_log" || fail 'Image update did not use the Docker Engine progress endpoint'
  ! grep -Fq '/assets/3' "$curl_log" || fail 'Image update downloaded an unused JAR'
}

test_jar_download_reports_live_progress() {
  prepare_case jar-live-progress
  local sha
  write_fixture jar placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture jar "$sha"
  write_request
  write_installed_state
  export AGENT_TEST_JAR_PROGRESS=true
  export AGENT_TEST_JAR_PROGRESS_DELAY=2
  run_agent_in_background
  wait_for_live_transfer JAR_DOWNLOAD
  jq -e '
    .transfer.phase == "JAR_DOWNLOAD"
    and .transfer.downloadedBytes > 0
    and .transfer.downloadedBytes < .transfer.totalBytes
    and .transfer.speedBytesPerSecond > 0
    and (.transfer.etaSeconds | type) == "number"
  ' "$status_dir/status.json" >/dev/null || fail 'JAR transfer status lacks live byte, speed, or ETA telemetry'
  wait_for_background_agent
  unset AGENT_TEST_JAR_PROGRESS AGENT_TEST_JAR_PROGRESS_DELAY
  assert_status SUCCESS
}

test_image_pull_reports_live_progress() {
  prepare_case image-live-progress
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  export AGENT_TEST_DOCKER_ENGINE_DELAY=2
  run_agent_in_background
  wait_for_live_transfer IMAGE_PULL
  jq -e '
    .transfer.phase == "IMAGE_PULL"
    and .transfer.downloadedBytes > 0
    and .transfer.downloadedBytes < .transfer.totalBytes
    and .transfer.speedBytesPerSecond > 0
    and .transfer.currentLayer == "layer-one"
    and .transfer.completedLayers == 1
    and .transfer.totalLayers == 2
    and (.transfer.etaSeconds | type) == "number"
  ' "$status_dir/status.json" >/dev/null || fail 'Image pull status lacks live byte, speed, layer, or ETA telemetry'
  wait_for_background_agent
  unset AGENT_TEST_DOCKER_ENGINE_DELAY
  assert_status SUCCESS
}

test_transfer_deadline_extends_from_observed_speed() {
  prepare_case dynamic-transfer-deadline
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  cat >> "$config_file" <<'EOF'
DOWNLOAD_TIMEOUT_SECONDS=2
DOWNLOAD_MAX_TIMEOUT_SECONDS=60
DOWNLOAD_IDLE_TIMEOUT_SECONDS=3
EOF
  export AGENT_TEST_DOCKER_ENGINE_DELAY=3
  run_agent_in_background
  wait_for_live_transfer IMAGE_PULL
  jq -e '
    .transfer.phase == "IMAGE_PULL"
    and .transfer.speedBytesPerSecond > 0
    and (.timeoutSeconds >= 3 and .timeoutSeconds < 20)
  ' "$status_dir/status.json" >/dev/null || fail 'Transfer deadline was not extended from observed speed instead of reporting the fixed maximum'
  wait_for_background_agent
  unset AGENT_TEST_DOCKER_ENGINE_DELAY
  assert_status SUCCESS
}

test_cancelled_image_transfer_is_retryable() {
  prepare_case cancel-image-transfer
  local sha
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  printf 'old-runtime-jar\n' > "$runtime_dir/app.jar"
  export AGENT_TEST_DOCKER_ENGINE_DELAY=3
  run_agent_in_background
  wait_for_live_transfer IMAGE_PULL
  write_cancel_request
  wait_for_background_agent_failure
  unset AGENT_TEST_DOCKER_ENGINE_DELAY
  assert_status FAILED
  grep -Fq '更新已取消' "$status_dir/status.json" || fail 'Cancellation was not reported as a retryable update failure'
  jq -e 'has("transfer") | not' "$status_dir/status.json" >/dev/null \
    || fail 'Cancelled update retained an active transfer in its terminal status'
  assert_file_contains "$runtime_dir/app.jar" 'old-runtime-jar'
  grep -Fq "APP_IMAGE_DIGEST=$current_digest" "$project_dir/release.env" || fail 'Cancellation changed the release input'
  [[ ! -e "$request_dir/cancel.json" ]] || fail 'Agent did not consume the cancellation request'

  write_request
  run_agent
  assert_status SUCCESS
}

test_verified_jar_cache_skips_repeated_download() {
  prepare_case jar-cache
  local sha cache_dir cache_jar cache_metadata certificate_identity
  write_fixture jar placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture jar "$sha"
  write_request
  write_installed_state
  cache_dir="$work_dir/cache"
  cache_jar="$cache_dir/app.jar"
  cache_metadata="$cache_dir/app.jar.json"
  certificate_identity="https://github.com/Daki-l/XianYu2/.github/workflows/release.yml@refs/tags/${target_tag}"
  mkdir -p "$cache_dir"
  cp "$fixture_dir/xianyu2-v2.0.8.jar" "$cache_jar"
  jq -n --arg version "$target_version" --arg name 'xianyu2-v2.0.8.jar' --arg sha "$sha" \
    --arg identity "$certificate_identity" --argjson size "$(stat -c '%s' "$cache_jar")" \
    '{schemaVersion: 1, version: $version, name: $name, sha256: $sha, size: $size,
      certificateIdentity: $identity}' > "$cache_metadata"
  run_agent
  assert_status SUCCESS
  ! grep -Fq '/assets/3' "$curl_log" || fail 'Verified JAR cache did not skip the JAR asset download'
  ! grep -Fq '/assets/4' "$curl_log" || fail 'Verified JAR cache did not skip the JAR bundle download'
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
  export AGENT_TEST_DOCKER_ENGINE_PULL_FAIL=true
  if run_agent; then
    unset AGENT_TEST_DOCKER_ENGINE_PULL_FAIL
    fail 'Image pull failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_DOCKER_ENGINE_PULL_FAIL
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
  export AGENT_TEST_DOCKER_ENGINE_CURL_FAIL=true
  if run_agent; then
    unset AGENT_TEST_DOCKER_ENGINE_CURL_FAIL
    fail 'Candidate image pull failure unexpectedly succeeded'
  fi
  unset AGENT_TEST_DOCKER_ENGINE_CURL_FAIL
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
  ! grep -Fq 'https://untrusted.example/' "$curl_log" \
    || fail 'Agent contacted an untrusted redirect target'
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

test_configured_update_proxy_is_scoped_to_download_clients() {
  prepare_case configured-update-proxy
  local sha proxy_url no_proxy
  proxy_url='http://user:example-secret@proxy.example:7890'
  no_proxy='localhost,127.0.0.1,::1,mysql'
  printf "UPDATE_HTTP_PROXY='%s'\nUPDATE_NO_PROXY=%s\n" "$proxy_url" "$no_proxy" >> "$config_file"
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  HTTP_PROXY= HTTPS_PROXY= ALL_PROXY= NO_PROXY= http_proxy= https_proxy= all_proxy= no_proxy= run_agent
  assert_status SUCCESS
  grep -Fqx "curl|HTTP_PROXY=$proxy_url|HTTPS_PROXY=$proxy_url|NO_PROXY=$no_proxy" "$proxy_log" \
    || fail 'Configured update proxy was not passed to curl'
  grep -Fqx "cosign|HTTP_PROXY=$proxy_url|HTTPS_PROXY=$proxy_url|NO_PROXY=$no_proxy" "$proxy_log" \
    || fail 'Configured update proxy was not passed to Cosign'
  grep -Fqx 'curl|HTTP_PROXY=|HTTPS_PROXY=|NO_PROXY=' "$proxy_log" \
    || fail 'Docker Engine progress client inherited the release-download proxy'
  grep -Fqx 'docker|HTTP_PROXY=|HTTPS_PROXY=|NO_PROXY=' "$proxy_log" \
    || fail 'Update proxy leaked into the Docker client'
  ! grep -Fq 'example-secret' "$status_dir/status.json" \
    || fail 'Update proxy credential leaked into agent status'
}

test_update_clients_ignore_inherited_proxy_without_configuration() {
  prepare_case direct-update-clients
  local sha inherited_proxy
  inherited_proxy='http://inherited.example:7890'
  write_fixture image placeholder
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha"
  write_request
  HTTP_PROXY="$inherited_proxy" HTTPS_PROXY="$inherited_proxy" ALL_PROXY="$inherited_proxy" \
    NO_PROXY='inherited.example' run_agent
  assert_status SUCCESS
  if grep -E '^(curl|cosign)\|.*inherited\.example' "$proxy_log" >/dev/null; then
    fail 'Unconfigured update clients inherited a process-wide proxy'
  fi
}

test_invalid_update_proxy_is_rejected_before_network_calls() {
  prepare_case invalid-update-proxy
  write_fixture image placeholder
  printf 'UPDATE_HTTP_PROXY=socks5://proxy.example:1080\n' >> "$config_file"
  if run_agent --check; then
    fail 'Agent accepted an unsupported update proxy URL'
  fi
  [[ ! -s "$curl_log" ]] || fail 'Invalid update proxy reached curl'
  [[ ! -s "$docker_log" ]] || fail 'Invalid update proxy reached Docker'
  [[ ! -s "$proxy_log" ]] || fail 'Invalid update proxy started a network client'
}

test_verified_host_package_is_auto_applied_and_resumed() {
  prepare_case auto-host-package
  local sha
  write_fixture host-package-manual-required placeholder false "$current_agent_version" "$current_agent_version"
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture host-package-manual-required "$sha" false "$current_agent_version" "$current_agent_version"
  write_request
  printf 'application-secret-must-survive\n' > "$project_dir/.env"
  host_install_log="$case_root/host-installer.log"
  : > "$host_install_log"
  export AGENT_TEST_HOST_INSTALL_LOG="$host_install_log"
  export XIANYU2_UPDATE_AGENT_TEST_MODE=true
  run_agent
  unset AGENT_TEST_HOST_INSTALL_LOG XIANYU2_UPDATE_AGENT_TEST_MODE

  assert_status SUCCESS
  [[ "$(jq -r '.taskId' "$status_dir/status.json")" == "$task_id" ]] \
    || fail 'Self-updated agent did not preserve the original web update task identifier'
  assert_file_contains "$host_install_log" 'host package installer executed'
  assert_file_contains "$project_dir/compose.yaml" 'new-compose'
  assert_file_contains "$project_dir/.env" 'application-secret-must-survive'
  [[ "$(tr -d '[:space:]' < "$project_dir/deploy/update/agent-version")" == "$current_agent_version" ]] \
    || fail 'Auto-applied host package did not replace the project update agent'
  assert_file_contains "$work_dir/archive/host-package-${task_id}/compose.yaml" 'old-compose'
  grep -Fqx 'http://localhost/images/create' "$curl_log" \
    || fail 'Updated agent did not resume the image deployment after host package installation'
}

test_unsafe_host_package_is_rejected_before_project_replacement() {
  prepare_case unsafe-host-package
  local sha package_root package_sha package_size
  write_fixture host-package-manual-required placeholder false "$current_agent_version" "$current_agent_version"
  package_root="$fixture_dir/xianyu2-host-package-v2.0.8"
  ln -s /etc/passwd "$package_root/deploy/update/untrusted-link"
  tar -C "$fixture_dir" -czf "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz" "$(basename "$package_root")"
  package_sha="$(sha256sum "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz" | awk '{print $1}')"
  package_size="$(stat -c '%s' "$fixture_dir/xianyu2-host-package-v2.0.8.tar.gz")"
  jq --arg sha "$package_sha" --argjson size "$package_size" \
    '.hostPackage.sha256 = $sha | .hostPackage.size = $size' "$fixture_dir/release-manifest.json" \
    > "$fixture_dir/release-manifest.json.tmp"
  mv "$fixture_dir/release-manifest.json.tmp" "$fixture_dir/release-manifest.json"
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  jq --arg sha "$sha" '.jar.sha256 = $sha' "$fixture_dir/release-manifest.json" \
    > "$fixture_dir/release-manifest.json.tmp"
  mv "$fixture_dir/release-manifest.json.tmp" "$fixture_dir/release-manifest.json"
  write_request
  export XIANYU2_UPDATE_AGENT_TEST_MODE=true
  if run_agent; then
    unset XIANYU2_UPDATE_AGENT_TEST_MODE
    fail 'Unsafe host package unexpectedly succeeded'
  fi
  unset XIANYU2_UPDATE_AGENT_TEST_MODE

  assert_status FAILED
  assert_file_contains "$project_dir/compose.yaml" 'old-compose'
  [[ -f "$project_dir/deploy/update/old-file" ]] \
    || fail 'Unsafe host package changed the existing project deploy directory'
}

test_failed_host_package_install_restores_project_files() {
  prepare_case failed-host-package-install
  local sha
  write_fixture host-package-manual-required placeholder false "$current_agent_version" "$current_agent_version"
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture host-package-manual-required "$sha" false "$current_agent_version" "$current_agent_version"
  write_request
  printf 'application-secret-must-survive\n' > "$project_dir/.env"
  host_install_log="$case_root/host-installer.log"
  : > "$host_install_log"
  export AGENT_TEST_HOST_INSTALL_LOG="$host_install_log"
  export AGENT_TEST_HOST_INSTALL_FAIL=true
  export XIANYU2_UPDATE_AGENT_TEST_MODE=true
  if run_agent; then
    unset AGENT_TEST_HOST_INSTALL_LOG AGENT_TEST_HOST_INSTALL_FAIL XIANYU2_UPDATE_AGENT_TEST_MODE
    fail 'Failed host package installer unexpectedly succeeded'
  fi
  unset AGENT_TEST_HOST_INSTALL_LOG AGENT_TEST_HOST_INSTALL_FAIL XIANYU2_UPDATE_AGENT_TEST_MODE

  assert_status FAILED
  assert_file_contains "$host_install_log" 'host package installer executed'
  assert_file_contains "$project_dir/compose.yaml" 'old-compose'
  assert_file_contains "$project_dir/.env" 'application-secret-must-survive'
  [[ -f "$project_dir/deploy/update/old-file" ]] \
    || fail 'Failed host package installer did not restore the previous deploy directory'
}

test_manual_release_requires_host_confirmation() {
  prepare_case manual-release
  printf '%s\n' 'AUTO_APPLY_HOST_PACKAGE_UPDATES=false' >> "$config_file"
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
  printf '%s\n' 'AUTO_APPLY_HOST_PACKAGE_UPDATES=false' >> "$config_file"
  local sha
  write_fixture host-package-manual-required placeholder false "$next_agent_version"
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture host-package-manual-required "$sha" false "$next_agent_version"
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
  write_fixture image placeholder false "$next_agent_version"
  sha="$(sha256sum "$fixture_dir/xianyu2-v2.0.8.jar" | awk '{print $1}')"
  write_fixture image "$sha" false "$next_agent_version"
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

test_transfer_recovery_uses_idle_timeout_after_restart() {
  prepare_case transfer-recovery-idle-timeout
  write_fixture image 'placeholder'
  printf '%s\n' 'DOWNLOAD_IDLE_TIMEOUT_SECONDS=1' >> "$config_file"
  mkdir -p "$work_dir/in-progress"
  jq -n --arg taskId "$task_id" --arg tag "$target_tag" --arg version "$target_version" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $tag, version: $version}' > "$work_dir/in-progress/${task_id}.json"
  jq -n --arg taskId "$task_id" --arg now "$(date --iso-8601=seconds)" \
    '{schemaVersion: 1, taskId: $taskId, version: "2.0.8", status: "DOWNLOADING",
      taskStartedAt: $now, updatedAt: $now, timeoutSeconds: 3600,
      transfer: {phase: "IMAGE_PULL", downloadedBytes: 50, totalBytes: 100}}' > "$status_dir/status.json"

  run_agent

  assert_status FAILED
  [[ ! -e "$work_dir/in-progress/${task_id}.json" ]] || fail 'Interrupted transfer task was not archived after its idle window'
}

test_total_timeout_wins_over_a_recent_heartbeat_after_restart() {
  prepare_case total-timeout-recovery
  write_fixture image 'placeholder'
  printf '%s\n' 'TASK_TOTAL_TIMEOUT_SECONDS=1' >> "$config_file"
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
  printf '%s\n' 'TASK_TOTAL_TIMEOUT_SECONDS=17971' >> "$config_file"
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
  grep -Fx 'Environment=HOME=/var/lib/xianyu2/update/private/cosign' "$service_file" >/dev/null \
    || fail 'Update agent unit does not provide a writable private HOME for Cosign metadata'
  grep -Fx 'TimeoutStartSec=5h' "$service_file" >/dev/null \
    || fail 'Update agent unit does not provide the slow-transfer task headroom'
  grep -Fx 'WantedBy=multi-user.target' "$service_file" >/dev/null \
    || fail 'Update agent service is not enabled for boot recovery'
}

test_installer_enables_boot_recovery_service() {
  local installer="$script_dir/install-update-agent.sh"
  grep -Fq 'timeout mkfifo tar find' "$installer" \
    || fail 'Update agent installer does not verify host-package dependencies'
  grep -Fq 'TASK_TOTAL_TIMEOUT_SECONDS=2400' "$installer" \
    || fail 'Update agent installer does not migrate the legacy total-timeout default'
  grep -Fq 'TASK_TOTAL_TIMEOUT_SECONDS=17100' "$installer" \
    || fail 'Update agent installer does not install the slow-transfer total-timeout default'
  grep -Fx 'systemctl enable xianyu2-update-agent.service xianyu2-update-agent.path' "$installer" >/dev/null \
    || fail 'Update agent installer does not enable the boot recovery service'
  grep -Fx 'systemctl start xianyu2-update-agent.service' "$installer" >/dev/null \
    || fail 'Update agent installer does not start the recovery service'
  grep -Fq 'XIANYU2_UPDATE_AGENT_SELF_UPDATE' "$installer" \
    || fail 'Update agent installer can deadlock by starting its own active service'
  grep -Fq 'install_project_write_path_override' "$installer" \
    || fail 'Update agent installer does not create the project write-path override'
}

run_case() {
  current_test_case="$1"
  printf 'Running update agent test: %s\n' "$current_test_case"
  "$current_test_case"
  current_test_case=''
}

run_jar_download_live_progress_test() {
  if [[ "$skip_jar_download_live_progress_test" == 'true' ]]; then
    printf 'Skipping update agent test: test_jar_download_reports_live_progress (explicit release waiver)\n'
    return 0
  fi
  run_case test_jar_download_reports_live_progress
}

for test_case in \
  test_hash_mismatch_preserves_runtime_jar \
  test_unknown_state_falls_back_to_image_update \
  test_image_update_clears_runtime_jar \
  run_jar_download_live_progress_test \
  test_image_pull_reports_live_progress \
  test_transfer_deadline_extends_from_observed_speed \
  test_cancelled_image_transfer_is_retryable \
  test_verified_jar_cache_skips_repeated_download \
  test_downgrade_is_rejected \
  test_image_pull_failure_preserves_runtime_jar \
  test_candidate_image_pull_failure_keeps_release_inputs \
  test_health_failure_requires_manual_recovery \
  test_backup_failure_stops_before_compose \
  test_signature_failure_stops_before_runtime_change \
  test_untrusted_redirect_is_rejected \
  test_download_failure_is_terminal \
  test_configured_update_proxy_is_scoped_to_download_clients \
  test_update_clients_ignore_inherited_proxy_without_configuration \
  test_invalid_update_proxy_is_rejected_before_network_calls \
  test_verified_host_package_is_auto_applied_and_resumed \
  test_unsafe_host_package_is_rejected_before_project_replacement \
  test_failed_host_package_install_restores_project_files \
  test_manual_release_requires_host_confirmation \
  test_initial_install_applies_verified_manual_release \
  test_newer_agent_is_required_for_manual_application \
  test_newer_agent_blocks_regular_update_with_manual_status \
  test_unknown_manifest_schema_requires_manual_recovery \
  test_missing_host_package_asset_is_rejected \
  test_lock_keeps_request_unclaimed \
  test_symbolic_link_request_is_rejected \
  test_hard_link_request_is_rejected \
  test_world_writable_request_is_rejected \
  test_world_readable_request_is_rejected \
  test_claimed_task_is_archived_as_failed_after_restart \
  test_recent_heartbeat_is_not_failed_after_restart \
  test_transfer_recovery_uses_idle_timeout_after_restart \
  test_total_timeout_wins_over_a_recent_heartbeat_after_restart \
  test_install_release_requires_explicit_tag \
  test_initial_install_request_uses_application_identity \
  test_total_timeout_requires_systemd_headroom \
  test_systemd_start_limit_is_explicit \
  test_installer_enables_boot_recovery_service; do
  run_case "$test_case"
done
printf 'Update agent tests passed.\n'
