#!/usr/bin/env bash
set -Eeuo pipefail

readonly deploy_path=/opt/xianyu2
readonly project_name=xianyu2
readonly expected_image=ghcr.io/daki-l/xianyu2
readonly lock_file=/home/server/.cache/xianyu2-deploy.lock
readonly container_name=xianyu2-app-1

compose() {
  docker compose \
    --project-name "$project_name" \
    --project-directory "$deploy_path" \
    --env-file "$deploy_path/.env" \
    -f "$deploy_path/compose.yaml" \
    -f "$deploy_path/deploy/server/compose-production.yaml" \
    "$@"
}

update_app_image() {
  local image_ref="$1"
  if grep -q '^APP_IMAGE=' "$deploy_path/.env"; then
    sed -i "s|^APP_IMAGE=.*$|APP_IMAGE=$image_ref|" "$deploy_path/.env"
  else
    printf 'APP_IMAGE=%s\n' "$image_ref" >> "$deploy_path/.env"
  fi
}

restore_app_image() {
  local image_ref="$1"
  if [[ -n "$image_ref" ]]; then
    update_app_image "$image_ref"
  else
    sed -i '/^APP_IMAGE=/d' "$deploy_path/.env"
  fi
}

pull_app_image() {
  local max_attempts=3
  local retry_delay_seconds=15
  local attempt delay_seconds

  for attempt in $(seq 1 "$max_attempts"); do
    if compose pull app; then
      return 0
    fi

    if [[ "$attempt" -lt "$max_attempts" ]]; then
      delay_seconds=$((retry_delay_seconds * attempt))
      echo "Image pull failed (attempt ${attempt}/${max_attempts}); retrying in ${delay_seconds}s." >&2
      sleep "$delay_seconds"
    fi
  done

  echo 'DEPLOYMENT_FAILURE_CATEGORY=IMAGE_PULL_FAILURE' >&2
  echo "Unable to pull the application image after ${max_attempts} attempts." >&2
  return 1
}

wait_for_healthy() {
  local state='unknown'
  for _ in $(seq 1 60); do
    state="$(docker inspect "$container_name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
    case "$state" in
      healthy)
        if curl --fail --silent --show-error http://127.0.0.1:12400/actuator/health >/dev/null; then
          return 0
        fi
        echo 'Container is healthy, but the actuator health endpoint is unavailable.' >&2
        return 1
        ;;
      unhealthy|exited|dead)
        echo "Container entered an unhealthy state: ${state}" >&2
        return 1
        ;;
    esac
    sleep 3
  done
  echo "Timed out waiting for the container to become healthy; last state: ${state:-unknown}." >&2
  return 1
}

report_deployment_diagnostics() {
  local container_state='unavailable'
  local app_logs=''
  local health_response=''

  container_state="$(docker inspect "$container_name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
  app_logs="$(docker logs --tail 200 "$container_name" 2>&1 || true)"
  health_response="$(curl --silent --show-error http://127.0.0.1:12400/actuator/health 2>&1 || true)"

  echo "DEPLOYMENT_FAILURE_CATEGORY=$(classify_deployment_failure "$app_logs" "${container_state:-unavailable}" "$health_response")" >&2
  echo 'Deployment health check failed. Collecting diagnostics before rollback.' >&2
  if ! docker inspect "$container_name" \
    --format 'container status={{.State.Status}} exit_code={{.State.ExitCode}} error={{.State.Error}} started_at={{.State.StartedAt}} finished_at={{.State.FinishedAt}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    2>&1; then
    echo "Container ${container_name} is unavailable for inspection." >&2
  fi

  echo 'Container health details:' >&2
  docker inspect "$container_name" --format '{{json .State.Health}}' 2>&1 || true
  echo 'Actuator health response:' >&2
  printf '%s\n' "$health_response" >&2
  echo 'Recent application logs (last 200 lines):' >&2
  printf '%s\n' "$app_logs" >&2
}

classify_deployment_failure() {
  local app_logs="$1"
  local container_state="$2"
  local health_response="$3"

  if grep -Eqi 'Flyway|Migration checksum mismatch|Validate failed|migration failed' <<< "$app_logs"; then
    printf 'FLYWAY_MIGRATION_FAILURE\n'
  elif grep -Eqi 'BeanCreationException|UnsatisfiedDependencyException|No default constructor|NoSuchMethodException|Error creating bean' <<< "$app_logs"; then
    printf 'SPRING_BEAN_CREATION_FAILURE\n'
  elif grep -Eqi 'Communications link failure|Access denied|Connection refused|Could not create connection|JDBCConnectionException|SQLNonTransientConnectionException' <<< "$app_logs"; then
    printf 'DATABASE_CONNECTIVITY_FAILURE\n'
  elif [[ "$container_state" != 'healthy' ]] || ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$health_response"; then
    printf 'APPLICATION_HEALTHCHECK_FAILURE\n'
  else
    printf 'UNKNOWN_DEPLOY_FAILURE\n'
  fi
}

parse_command() {
  local extra
  read -r verb image digest extra <<< "${SSH_ORIGINAL_COMMAND:-}"
  if [[ "${verb:-}" != deploy || "${image:-}" != "$expected_image" || -n "${extra:-}" ]]; then
    echo 'Invalid deployment command.' >&2
    exit 64
  fi
  if [[ "${digest:-}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    return
  fi
  echo 'Invalid image digest.' >&2
  exit 64
}

parse_command
IFS= read -r ghcr_token || true
if [[ -z "${ghcr_token:-}" ]]; then
  echo 'Missing GHCR token.' >&2
  exit 65
fi

mkdir -p "$(dirname "$lock_file")"
exec 9>"$lock_file"
if ! flock -n 9; then
  echo 'Another XianYu2 deployment is already running.' >&2
  exit 75
fi

cd "$deploy_path"
git pull --ff-only origin main

previous_image="$(sed -n 's/^APP_IMAGE=//p' .env | head -n 1 || true)"
image_ref="$image@$digest"
update_app_image "$image_ref"

temp_root="$(mktemp -d)"
cleanup() {
  rm -rf "$temp_root"
}
trap cleanup EXIT

export DOCKER_CONFIG="$temp_root/docker-config"
install -d -m 700 "$DOCKER_CONFIG"
printf '%s\n' "$ghcr_token" | docker login ghcr.io -u Daki-l --password-stdin >/dev/null
unset ghcr_token

if ! pull_app_image; then
  echo 'Image pull failed before the application was recreated; restoring the previous image setting.' >&2
  restore_app_image "$previous_image"
  exit 1
fi

if compose up -d --no-build --force-recreate app && wait_for_healthy; then
  actual_image="$(docker inspect "$container_name" --format '{{.Config.Image}}')"
  [[ "$actual_image" == "$image_ref" ]] || {
    echo 'Running image does not match requested digest.' >&2
    exit 1
  }
  printf 'deployment=ok\ncommit=%s\nimage=%s\n' "$(git rev-parse --short HEAD)" "$actual_image"
  exit 0
fi

report_deployment_diagnostics

if [[ -n "$previous_image" ]]; then
  echo 'Deployment failed; restoring the previous image.' >&2
  restore_app_image "$previous_image"
  if compose up -d --no-build --force-recreate app && wait_for_healthy; then
    rollback_image="$(docker inspect "$container_name" --format '{{.Config.Image}}' 2>/dev/null || true)"
    printf 'ROLLBACK_RESULT=success\nROLLBACK_IMAGE=%s\n' "${rollback_image:-unavailable}" >&2
  else
    echo 'ROLLBACK_RESULT=failure' >&2
  fi
else
  echo 'ROLLBACK_RESULT=skipped_no_previous_image' >&2
fi

final_image="$(docker inspect "$container_name" --format '{{.Config.Image}}' 2>/dev/null || true)"
final_health="$(docker inspect "$container_name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
printf 'FINAL_RUNNING_IMAGE=%s\nFINAL_CONTAINER_HEALTH=%s\n' \
  "${final_image:-unavailable}" "${final_health:-unavailable}" >&2

exit 1
