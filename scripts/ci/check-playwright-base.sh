#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "Usage: $0 --version <ref> | --changed <base-ref> <head-ref>" >&2
  exit 64
}

file_at() {
  git show "$1:$2"
}

playwright_version_from_pom() {
  grep -A 2 "<groupId>com.microsoft.playwright</groupId>" \
    | grep "<version>" \
    | head -n 1 \
    | cut -d ">" -f2 \
    | cut -d "<" -f1
}

version_at() {
  local ref="$1"
  local pom_version base_version docker_version

  pom_version="$(file_at "$ref" pom.xml | playwright_version_from_pom)"
  base_version="$(file_at "$ref" Dockerfile.playwright-base | grep "^ARG PLAYWRIGHT_VERSION=" | head -n 1 | cut -d= -f2)"
  docker_version="$(file_at "$ref" Dockerfile | grep "^ARG PLAYWRIGHT_BASE_IMAGE=" | head -n 1 | cut -dv -f2)"

  if [[ -z "$pom_version" || -z "$base_version" || -z "$docker_version" ]]; then
    echo "Unable to read the Playwright version from ${ref}." >&2
    return 1
  fi
  if [[ "$pom_version" != "$base_version" || "$pom_version" != "$docker_version" ]]; then
    echo "Playwright version mismatch in ${ref}: pom.xml=${pom_version}, Dockerfile.playwright-base=${base_version}, Dockerfile=${docker_version}." >&2
    return 1
  fi
  printf '%s\n' "$pom_version"
}

case "${1:-}" in
  --version)
    [[ $# -eq 2 ]] || usage
    git rev-parse --verify --quiet "${2}^{commit}" >/dev/null || {
      echo "Revision does not exist: ${2}" >&2
      exit 2
    }
    version_at "$2"
    ;;
  --changed)
    [[ $# -eq 3 ]] || usage
    git rev-parse --verify --quiet "${2}^{commit}" >/dev/null || {
      echo "Base revision does not exist: ${2}" >&2
      exit 2
    }
    git rev-parse --verify --quiet "${3}^{commit}" >/dev/null || {
      echo "Head revision does not exist: ${3}" >&2
      exit 2
    }
    temp_file="${TMPDIR:-/tmp}/xianyu2-playwright-change-${BASHPID}-${RANDOM}"
    version_at "$3" > "$temp_file"
    IFS= read -r head_version < "$temp_file"
    if ! git cat-file -e "${2}:Dockerfile.playwright-base" 2>/dev/null; then
      rm -f "$temp_file"
      printf 'true\n'
      exit 0
    fi
    version_at "$2" > "$temp_file"
    IFS= read -r base_version < "$temp_file"
    rm -f "$temp_file"
    if [[ "$base_version" != "$head_version" ]] \
      || ! git diff --quiet "$2" "$3" -- Dockerfile Dockerfile.playwright-base; then
      printf 'true\n'
    else
      printf 'false\n'
    fi
    ;;
  *) usage ;;
esac
