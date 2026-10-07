#!/bin/sh
set -eu

baseline_jar='/opt/xianyu2/app.jar'
runtime_dir='/app/runtime'
runtime_jar="${runtime_dir}/app.jar"
installed_file="${runtime_dir}/installed.json"
selected_jar="$baseline_jar"

if [ -f "$runtime_jar" ] && [ -f "$installed_file" ] && command -v jq >/dev/null 2>&1; then
  schema_version="$(jq -r '.schemaVersion // empty' "$installed_file" 2>/dev/null || true)"
  expected_sha="$(jq -r '.jarSha256 // empty' "$installed_file" 2>/dev/null || true)"
  expected_fingerprint="$(jq -r '.runtimeFingerprint // empty' "$installed_file" 2>/dev/null || true)"
  expected_digest="$(jq -r '.imageDigest // empty' "$installed_file" 2>/dev/null || true)"
  actual_sha="$(sha256sum "$runtime_jar" | awk '{print $1}')"

  if [ "$schema_version" = '1' ] \
    && [ -n "$expected_sha" ] \
    && [ "$actual_sha" = "$expected_sha" ] \
    && [ "$expected_fingerprint" = "${RUNTIME_FINGERPRINT:-}" ] \
    && [ "$expected_digest" = "${APP_IMAGE_DIGEST:-}" ]; then
    selected_jar="$runtime_jar"
  fi
fi

exec java ${JAVA_OPTS:-} -Dserver.port="${SERVER_PORT:-12400}" -jar "$selected_jar"
