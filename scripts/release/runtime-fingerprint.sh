#!/usr/bin/env bash
set -Eeuo pipefail

commit='HEAD'
if [[ $# -gt 0 && "$1" != --* ]]; then
  commit="$1"
  shift
fi
playwright_base=''
jre_base=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --playwright-base) playwright_base="${2:-}"; shift 2 ;;
    --jre-base) jre_base="${2:-}"; shift 2 ;;
    *)
      echo "Usage: $0 [commit] [--playwright-base IMAGE@sha256:DIGEST] [--jre-base IMAGE@sha256:DIGEST]" >&2
      exit 64
      ;;
  esac
done

playwright_version="$(git show "${commit}:pom.xml" | grep -A 2 '<groupId>com.microsoft.playwright</groupId>' | grep '<version>' | head -n 1 | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')"
[[ "$playwright_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo 'Unable to resolve the Playwright version from pom.xml.' >&2
  exit 1
}
if [[ -z "$playwright_base" ]]; then
  playwright_base="$(git show "${commit}:Dockerfile" | sed -n 's/^ARG PLAYWRIGHT_BASE_IMAGE=//p' | head -n 1)"
fi
if [[ -z "$jre_base" ]]; then
  jre_base="$(git show "${commit}:Dockerfile" | sed -n 's/^ARG JRE_BASE_IMAGE=//p' | head -n 1)"
fi
[[ -n "$playwright_base" && -n "$jre_base" ]] || {
  echo 'Unable to resolve the Playwright or JRE base image.' >&2
  exit 1
}

# 只纳入会改变容器运行契约的材料；整个 pom.xml 会把普通业务依赖改动误判为镜像更新。
# Release workflow supplies immutable base-image digests, preventing a mutable upstream tag from being classified as a JAR-only update.
runtime_material="$(git show "${commit}:Dockerfile"; git show "${commit}:Dockerfile.playwright-base"; git show "${commit}:docker/entrypoint.sh"; printf 'playwright=%s\nplaywright-base=%s\njre-base=%s\n' "$playwright_version" "$playwright_base" "$jre_base")"
runtime_hash="$(printf '%s' "$runtime_material" | sha256sum | awk '{print $1}')"
printf 'jre-21-playwright-%s-%s\n' "$playwright_version" "${runtime_hash:0:16}"
