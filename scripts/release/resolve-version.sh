#!/usr/bin/env bash
set -Eeuo pipefail

tag="${1:?Usage: $0 <vX.Y.Z tag>}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Release tag must use vX.Y.Z: $tag" >&2
  exit 64
fi
printf '%s\n' "${tag#v}"
