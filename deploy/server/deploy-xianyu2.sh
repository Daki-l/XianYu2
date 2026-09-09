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

wait_for_healthy() {
  local state
  for _ in $(seq 1 60); do
    state="$(docker inspect "$container_name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
    case "$state" in
      healthy)
        curl --fail --silent --show-error http://127.0.0.1:12400/actuator/health >/dev/null
        return 0
        ;;
      unhealthy|exited|dead)
        return 1
        ;;
    esac
    sleep 3
  done
  return 1
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

compose pull app
if compose up -d --no-build --force-recreate app && wait_for_healthy; then
  actual_image="$(docker inspect "$container_name" --format '{{.Config.Image}}')"
  [[ "$actual_image" == "$image_ref" ]] || {
    echo 'Running image does not match requested digest.' >&2
    exit 1
  }
  printf 'deployment=ok\ncommit=%s\nimage=%s\n' "$(git rev-parse --short HEAD)" "$actual_image"
  exit 0
fi

if [[ -n "$previous_image" ]]; then
  echo 'Deployment failed; restoring the previous image.' >&2
  update_app_image "$previous_image"
  compose up -d --no-build --force-recreate app
  wait_for_healthy || echo 'Rollback health check failed.' >&2
fi

exit 1
