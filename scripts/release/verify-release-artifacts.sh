#!/usr/bin/env bash
set -Eeuo pipefail

image_ref="${1:?Usage: $0 <image@sha256:digest> <jar> <version> <runtime-fingerprint>}"
jar_path="${2:?Usage: $0 <image@sha256:digest> <jar> <version> <runtime-fingerprint>}"
version="${3:?Usage: $0 <image@sha256:digest> <jar> <version> <runtime-fingerprint>}"
runtime_fingerprint="${4:?Usage: $0 <image@sha256:digest> <jar> <version> <runtime-fingerprint>}"
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || {
  echo "Release version must use X.Y.Z: $version" >&2
  exit 64
}
for required_command in docker jar unzip sha256sum; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "Missing required command: $required_command" >&2
    exit 1
  }
done
expected_sha="$(sha256sum "$jar_path" | awk '{print $1}')"
container="xianyu2-release-verify-$$"
temporary="$(mktemp)"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -f "$temporary"
}
trap cleanup EXIT

build_info="$(unzip -p "$jar_path" META-INF/build-info.properties 2>/dev/null || true)"
grep -Fx "build.version=$version" <<< "$build_info" >/dev/null || {
  echo "Release JAR build-info version does not match $version." >&2
  exit 1
}

frontend_assets="$(jar tf "$jar_path" | grep -E '^BOOT-INF/classes/static/assets/.*\.js$' || true)"
[[ -n "$frontend_assets" ]] || {
  echo 'Release JAR has no frontend JavaScript assets.' >&2
  exit 1
}
frontend_version_found=false
while IFS= read -r asset; do
  if unzip -p "$jar_path" "$asset" | grep -Fq "\"$version\""; then
    frontend_version_found=true
    break
  fi
done <<< "$frontend_assets"
[[ "$frontend_version_found" == 'true' ]] || {
  echo "Release frontend assets do not contain version $version." >&2
  exit 1
}

image_version="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$image_ref")"
[[ "$image_version" == "$version" ]] || {
  echo "Image OCI version label does not match $version: $image_version" >&2
  exit 1
}

image_runtime_fingerprint="$(docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$image_ref" | sed -n 's/^RUNTIME_FINGERPRINT=//p' | head -n 1)"
[[ "$image_runtime_fingerprint" == "$runtime_fingerprint" ]] || {
  echo "Image runtime fingerprint does not match $runtime_fingerprint: $image_runtime_fingerprint" >&2
  exit 1
}

docker create --name "$container" "$image_ref" >/dev/null
docker cp "$container:/opt/xianyu2/app.jar" "$temporary"
actual_sha="$(sha256sum "$temporary" | awk '{print $1}')"
[[ "$actual_sha" == "$expected_sha" ]] || {
  echo 'Release JAR does not match the JAR embedded in the image.' >&2
  exit 1
}
printf 'Release JAR and image JAR SHA-256 match: %s\n' "$actual_sha"
