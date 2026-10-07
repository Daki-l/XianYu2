#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: detect-host-contract-change.sh --from GIT_REF --to GIT_REF

Prints changed production host-contract paths, one path per line. An empty result
means that the inspected commits do not change the host installation contract.
USAGE
  exit 64
}

from=''
to=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) from="${2:-}"; shift 2 ;;
    --to) to="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -n "$from" && -n "$to" ]] || usage
git rev-parse --verify "${from}^{commit}" >/dev/null
git rev-parse --verify "${to}^{commit}" >/dev/null

# Every listed path participates in installation, bind-mount, port, systemd,
# proxy, environment, or update-agent behavior. Treat all changes
# conservatively: the matching release must require a verified host package.
# Dockerfile is intentionally not listed here: JRE, Playwright, system-library
# and entrypoint changes are runtime inputs and must remain eligible for an
# automatic immutable-image update. Container identity is the exception because
# it controls ownership of the host request directory.
readonly host_contract_paths=(
  .env.example
  compose.yaml
  deploy/nginx
  deploy/server
  deploy/update
  install.sh
)

git diff --name-only "$from" "$to" -- "${host_contract_paths[@]}" | LC_ALL=C sort -u

dockerfile_contract_value() {
  local ref="$1"
  local key="$2"
  git show "${ref}:Dockerfile" 2>/dev/null \
    | sed -n -E "s/^${key}[[:space:]]*=?[[:space:]]*(.*)$/\\1/p" \
    | tail -n 1
}

for dockerfile_key in 'ARG APP_UID' 'ARG APP_GID' 'USER'; do
  from_value="$(dockerfile_contract_value "$from" "$dockerfile_key")"
  to_value="$(dockerfile_contract_value "$to" "$dockerfile_key")"
  if [[ "$from_value" != "$to_value" ]]; then
    printf 'Dockerfile:%s\n' "${dockerfile_key// /_}"
  fi
done
