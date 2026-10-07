#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: build-host-package.sh --version X.Y.Z --commit SHA --output PATH
USAGE
  exit 64
}

version=''
commit=''
output=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="${2:-}"; shift 2 ;;
    --commit) commit="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || usage
[[ "$commit" =~ ^[0-9a-f]{40}$ && -n "$output" ]] || usage
git cat-file -e "${commit}:deploy/update/xianyu2-update-agent"
git cat-file -e "${commit}:deploy/update/agent-version"
git cat-file -e "${commit}:compose.yaml"
git cat-file -e "${commit}:.env.example"

output_directory="$(dirname "$output")"
mkdir -p "$output_directory"
temporary="$(mktemp "${output_directory}/.xianyu2-host-package.XXXXXX")"
cleanup() {
  rm -f "$temporary"
}
trap cleanup EXIT

LC_ALL=C git archive --format=tar --prefix="xianyu2-host-package-v${version}/" "$commit" \
  .env.example compose.yaml deploy/nginx deploy/server deploy/update \
  | gzip -n > "$temporary"
bash "$(dirname "$0")/validate-host-package.sh" --archive "$temporary" --version "$version"
mv -f "$temporary" "$output"
trap - EXIT
