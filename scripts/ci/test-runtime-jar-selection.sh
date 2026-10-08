#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <image> <verified-jar> <runtime-fingerprint> <image-digest>" >&2
  exit 64
fi

readonly image="$1"
readonly verified_jar="$2"
readonly runtime_fingerprint="$3"
readonly image_digest="$4"

[[ -f "$verified_jar" ]] || {
  echo "Verified runtime JAR does not exist: $verified_jar" >&2
  exit 1
}

test_root="$(mktemp -d)"
cleanup() {
  rm -rf "$test_root"
}
trap cleanup EXIT

runtime_dir="$test_root/runtime"
test_bin="$test_root/bin"
output_dir="$test_root/output"
mkdir -p "$runtime_dir" "$test_bin" "$output_dir"
cp "$verified_jar" "$runtime_dir/app.jar"

# Simulate the interval after the agent has atomically placed a verified JAR but
# before its post-health-check state write replaces the previous JAR checksum.
jq -n --arg fingerprint "$runtime_fingerprint" --arg digest "$image_digest" \
  '{schemaVersion: 1, jarSha256: "stale-checksum", runtimeFingerprint: $fingerprint, imageDigest: $digest}' \
  > "$runtime_dir/installed.json"

cat > "$test_bin/java" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" > /test-output/java-args
EOF
chmod 0755 "$test_bin/java"
chmod 0755 "$runtime_dir" "$test_bin"
chmod 0644 "$runtime_dir/app.jar" "$runtime_dir/installed.json"
chmod 0777 "$output_dir"

docker run --rm \
  --entrypoint /usr/local/bin/xianyu2-entrypoint \
  --env "PATH=/test-bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
  --env "RUNTIME_FINGERPRINT=$runtime_fingerprint" \
  --env "APP_IMAGE_DIGEST=$image_digest" \
  --volume "$runtime_dir:/app/runtime:ro" \
  --volume "$test_bin:/test-bin:ro" \
  --volume "$output_dir:/test-output" \
  "$image"

grep -Fqx '-Dserver.port=12400 -jar /app/runtime/app.jar' "$output_dir/java-args" || {
  echo 'Entrypoint did not select the pending verified runtime JAR.' >&2
  cat "$output_dir/java-args" >&2
  exit 1
}
