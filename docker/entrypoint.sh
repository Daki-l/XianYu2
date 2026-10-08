#!/bin/sh
set -eu

baseline_jar='/opt/xianyu2/app.jar'
runtime_dir='/app/runtime'
runtime_jar="${runtime_dir}/app.jar"
installed_file="${runtime_dir}/installed.json"
selected_jar="$baseline_jar"

if [ -f "$runtime_jar" ] && [ -f "$installed_file" ] && command -v jq >/dev/null 2>&1; then
  schema_version="$(jq -r '.schemaVersion // empty' "$installed_file" 2>/dev/null || true)"
  expected_fingerprint="$(jq -r '.runtimeFingerprint // empty' "$installed_file" 2>/dev/null || true)"
  expected_digest="$(jq -r '.imageDigest // empty' "$installed_file" 2>/dev/null || true)"

  # 更新代理先原子替换已验签的 JAR，再重建容器，健康检查通过后才写回
  # installed.json。该短窗口内 jarSha256 仍是上一个版本，不能因此回退到
  # 镜像内 JAR。运行时目录对容器只读，JAR 的完整性由宿主机代理负责。
  if [ "$schema_version" = '1' ] \
    && [ "$expected_fingerprint" = "${RUNTIME_FINGERPRINT:-}" ] \
    && [ "$expected_digest" = "${APP_IMAGE_DIGEST:-}" ]; then
    selected_jar="$runtime_jar"
  fi
fi

exec java ${JAVA_OPTS:-} -Dserver.port="${SERVER_PORT:-12400}" -jar "$selected_jar"
