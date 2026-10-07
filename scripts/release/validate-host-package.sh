#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: validate-host-package.sh --archive PATH --version X.Y.Z
USAGE
  exit 64
}

archive=''
version=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --archive) archive="${2:-}"; shift 2 ;;
    --version) version="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -f "$archive" && "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || usage
command -v tar >/dev/null

prefix="xianyu2-host-package-v${version}/"
list_file="$(mktemp)"
verbose_file="$(mktemp)"
cleanup() {
  rm -f "$list_file" "$verbose_file"
}
trap cleanup EXIT

tar -tzf "$archive" > "$list_file"
tar -tvzf "$archive" > "$verbose_file"

fail() {
  echo "Invalid host package: $*" >&2
  exit 1
}

while IFS= read -r entry; do
  [[ "$entry" == "${prefix}"* ]] || fail "unexpected path: $entry"
  relative="${entry#"$prefix"}"
  [[ -n "$relative" ]] || continue
  # tar 为目录保留末尾 /；安全检查中先去掉这一处合法分隔符，避免把 deploy/
  # 误判为重复斜杠，同时仍拒绝 deploy// 之类的异常路径。
  normalized_relative="${relative%/}"
  case "/${normalized_relative}/" in
    *'/../'*|*'//'*) fail "unsafe path: $entry" ;;
  esac
  case "$relative" in
    .env.example|compose.yaml|deploy/|deploy/nginx/|deploy/server/|deploy/update/|\
    deploy/nginx/*|deploy/server/*|deploy/update/*) ;;
    *) fail "path is outside the package allowlist: $entry" ;;
  esac
done < "$list_file"

while IFS= read -r record; do
  case "${record:0:1}" in
    -|d) ;;
    *) fail "non-regular archive member: $record" ;;
  esac
done < "$verbose_file"

for required_path in \
  '.env.example' \
  'compose.yaml' \
  'deploy/update/agent-version' \
  'deploy/update/xianyu2-update-agent' \
  'deploy/update/xianyu2-update-agent.service' \
  'deploy/update/xianyu2-update-agent.path' \
  'deploy/update/update-agent.conf.example' \
  'deploy/update/backup-mysql' \
  'deploy/update/install-update-agent.sh' \
  'deploy/update/install-release.sh'; do
  grep -Fx "${prefix}${required_path}" "$list_file" >/dev/null \
    || fail "required path is missing: $required_path"
done
