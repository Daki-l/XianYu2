#!/usr/bin/env bash
set -Eeuo pipefail

# Verify the production Compose contract and the container-side mount boundary.
# The update agent runs on the host; the application may only create requests.
readonly image_ref="${1:?Usage: $0 <image> [compose-file]}"
readonly compose_file="${2:-compose.yaml}"
readonly repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

for command_name in docker jq mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 1
  }
done
docker compose version >/dev/null 2>&1 || {
  echo 'Docker Compose v2 is required.' >&2
  exit 1
}

host_uid="$(id -u)"
host_gid="$(id -g)"
test_root="$(mktemp -d)"
cleanup() {
  # The test deliberately assigns this directory to the unprivileged app user.
  # Restore CI-runner ownership so the trap can always remove its temporary data.
  docker run --rm --user root --entrypoint /bin/sh \
    -v "$test_root:/cleanup" "$image_ref" \
    -ec "chown -R ${host_uid}:${host_gid} /cleanup && chmod -R u+rwX /cleanup" \
    >/dev/null 2>&1 || true
  rm -rf "$test_root"
}
trap cleanup EXIT

runtime_dir="$test_root/runtime"
request_dir="$test_root/update/request"
status_dir="$test_root/update/status"
environment_file="$test_root/compose.env"
mkdir -p "$runtime_dir" "$request_dir" "$status_dir"
printf 'ready\n' > "$status_dir/agent.ready"

cat > "$environment_file" <<EOF
APP_IMAGE=$image_ref
APP_IMAGE_DIGEST=development
DB_PASSWORD=test-database-password
DB_ROOT_PASSWORD=test-root-password
JWT_SECRET=test-jwt-secret-at-least-32-bytes-long
DB_URL=jdbc:mysql://host.docker.internal:3306/xianyu2?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
ALLOWED_ORIGINS=http://localhost:12400
RUNTIME_HOST_DIR=$runtime_dir
UPDATE_REQUEST_HOST_DIR=$request_dir
UPDATE_STATUS_HOST_DIR=$status_dir
EOF

compose_config="$test_root/compose.json"
docker compose --project-directory "$repository_root" --env-file "$environment_file" \
  -f "$repository_root/$compose_file" config --format json > "$compose_config"

jq -e '
  .services.app.volumes as $volumes
  | ([ $volumes[] | select(.target == "/app/runtime" and .read_only == true) ] | length == 1)
  and ([ $volumes[] | select(.target == "/app/update/status" and .read_only == true) ] | length == 1)
  and ([ $volumes[] | select(.target == "/app/update/request" and (.read_only | not)) ] | length == 1)
  and ([ $volumes[] | select(.target == "/app/update/private") ] | length == 0)
' "$compose_config" >/dev/null || {
  echo 'Compose does not preserve the required update mount permissions.' >&2
  exit 1
}

# Prepare a request directory with the same owner/mode as the production
# application user without relying on host sudo availability.
docker run --rm --user root --entrypoint /bin/sh \
  -v "$request_dir:/request" "$image_ref" \
  -ec 'chown 10001:10001 /request && chmod 0750 /request'

docker run --rm --user 10001:10001 --entrypoint /bin/sh \
  -v "$runtime_dir:/app/runtime:ro" \
  -v "$request_dir:/app/update/request" \
  -v "$status_dir:/app/update/status:ro" \
  "$image_ref" -ec '
    touch /app/update/request/request.json
    test -f /app/update/request/request.json
    if touch /app/runtime/forbidden; then
      echo "application container wrote runtime mount" >&2
      exit 1
    fi
    if touch /app/update/status/forbidden; then
      echo "application container wrote status mount" >&2
      exit 1
    fi
    test ! -e /app/update/private
  '

echo 'Update mount permission test passed.'
