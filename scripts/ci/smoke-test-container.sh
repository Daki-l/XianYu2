#!/usr/bin/env bash
set -Eeuo pipefail

readonly image_ref="${1:?Usage: $0 <image@sha256:digest>}"
readonly prefix="xianyu2-smoke-${GITHUB_RUN_ID:-local}-$$"
readonly network_name="${prefix}-network"
readonly mysql_container="${prefix}-mysql"
readonly app_container="${prefix}-app"
readonly mysql_password="smoke-db-password-${RANDOM}${RANDOM}"
readonly root_password="smoke-root-password-${RANDOM}${RANDOM}"
readonly health_file="$(mktemp)"

app_port=''

classify_failure() {
  local logs="$1"
  local container_state="$2"
  local health_response="$3"

  if grep -Eqi 'Flyway|Migration checksum mismatch|Validate failed|migration failed' <<< "$logs"; then
    printf 'FLYWAY_MIGRATION_FAILURE\n'
  elif grep -Eqi 'BeanCreationException|UnsatisfiedDependencyException|No default constructor|NoSuchMethodException|Error creating bean' <<< "$logs"; then
    printf 'SPRING_BEAN_CREATION_FAILURE\n'
  elif grep -Eqi 'Communications link failure|Access denied|Connection refused|Could not create connection|JDBCConnectionException|SQLNonTransientConnectionException' <<< "$logs"; then
    printf 'DATABASE_CONNECTIVITY_FAILURE\n'
  elif [[ "$container_state" != 'healthy' ]] || ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$health_response"; then
    printf 'APPLICATION_HEALTHCHECK_FAILURE\n'
  else
    printf 'UNKNOWN_DEPLOY_FAILURE\n'
  fi
}

report_diagnostics() {
  local state='unavailable'
  local logs=''
  local health_response=''

  state="$(docker inspect "$app_container" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
  logs="$(docker logs --tail 200 "$app_container" 2>&1 || true)"
  health_response="$(cat "$health_file" 2>/dev/null || true)"

  echo "SMOKE_TEST_FAILURE_CATEGORY=$(classify_failure "$logs" "${state:-unavailable}" "$health_response")" >&2
  echo 'Smoke test application container status:' >&2
  docker inspect "$app_container" \
    --format 'status={{.State.Status}} exit_code={{.State.ExitCode}} error={{.State.Error}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    2>&1 || true
  echo 'Smoke test actuator health response:' >&2
  cat "$health_file" 2>/dev/null || true
  echo 'Smoke test application logs (last 200 lines):' >&2
  printf '%s\n' "$logs" >&2
  echo 'Smoke test MySQL logs (last 100 lines):' >&2
  docker logs --tail 100 "$mysql_container" 2>&1 || true
}

cleanup() {
  local exit_code=$?
  if [[ "$exit_code" -ne 0 ]]; then
    report_diagnostics
  fi
  docker rm -f "$app_container" "$mysql_container" >/dev/null 2>&1 || true
  docker network rm "$network_name" >/dev/null 2>&1 || true
  rm -f "$health_file"
  return "$exit_code"
}
trap cleanup EXIT

docker network create "$network_name" >/dev/null

docker run -d --name "$mysql_container" --network "$network_name" \
  -e MYSQL_DATABASE=xianyu2 \
  -e MYSQL_USER=xianyu2 \
  -e MYSQL_PASSWORD="$mysql_password" \
  -e MYSQL_ROOT_PASSWORD="$root_password" \
  mysql:8.4 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_0900_ai_ci \
  --default-time-zone=+08:00 \
  --log-bin-trust-function-creators=1 >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$mysql_container" mysqladmin ping -h 127.0.0.1 -uroot "-p${root_password}" --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$mysql_container" mysqladmin ping -h 127.0.0.1 -uroot "-p${root_password}" --silent >/dev/null

docker run -d --name "$app_container" --network "$network_name" -p 127.0.0.1::12400 \
  -e "DB_URL=jdbc:mysql://${mysql_container}:3306/xianyu2?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true" \
  -e DB_USERNAME=xianyu2 \
  -e DB_PASSWORD="$mysql_password" \
  -e JWT_SECRET='smoke-test-jwt-secret-at-least-thirty-two-characters' \
  -e ALLOWED_ORIGINS=http://localhost:12400 \
  -e AI_ENABLED=false \
  "$image_ref" >/dev/null

app_port="$(docker port "$app_container" 12400/tcp | sed -n 's/.*:\([0-9][0-9]*\)$/\1/p' | head -n 1)"
[[ -n "$app_port" ]] || {
  echo 'Unable to determine the smoke test application port.' >&2
  exit 1
}

for _ in $(seq 1 90); do
  if curl --fail --silent --show-error "http://127.0.0.1:${app_port}/actuator/health" > "$health_file" 2>/dev/null \
    && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$health_file"; then
    echo 'Containerized Spring smoke test passed: /actuator/health is UP.'
    exit 0
  fi
  sleep 2
done

echo 'Timed out waiting for /actuator/health to report UP.' >&2
exit 1
