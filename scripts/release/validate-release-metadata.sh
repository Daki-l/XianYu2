#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: validate-release-metadata.sh --tag vX.Y.Z --metadata PATH (--agent-version-file PATH | --agent-version NUMBER) \
  [--previous-agent-version NUMBER | --first-release]
USAGE
  exit 64
}

tag=''
metadata=''
agent_version_file=''
agent_version=''
previous_agent_version=''
first_release=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) tag="${2:-}"; shift 2 ;;
    --metadata) metadata="${2:-}"; shift 2 ;;
    --agent-version-file) agent_version_file="${2:-}"; shift 2 ;;
    --agent-version) agent_version="${2:-}"; shift 2 ;;
    --previous-agent-version) previous_agent_version="${2:-}"; shift 2 ;;
    --first-release) first_release=true; shift ;;
    *) usage ;;
  esac
done

[[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || usage
[[ -f "$metadata" ]] || usage
if [[ -n "$agent_version_file" && -n "$agent_version" ]] || [[ -z "$agent_version_file" && -z "$agent_version" ]]; then
  usage
fi
if [[ "$first_release" == 'true' && -n "$previous_agent_version" ]]; then
  usage
fi
[[ -z "$agent_version_file" || -f "$agent_version_file" ]] || usage
command -v jq >/dev/null

metadata_tag="$(jq -er 'if (.releaseTag | type) == "string" then .releaseTag else error("releaseTag must be a string") end' "$metadata")"
jq -e '(.hostContractRequired | type) == "boolean"' "$metadata" >/dev/null || {
  echo 'hostContractRequired must be a boolean.' >&2
  exit 1
}
host_contract_required="$(jq -r '.hostContractRequired' "$metadata")"
minimum_agent_version="$(jq -er '
  .minimumAgentVersion as $minimum_agent_version
  | if ($minimum_agent_version | type) == "number"
      and $minimum_agent_version >= 1
      and $minimum_agent_version == ($minimum_agent_version | floor)
    then $minimum_agent_version
    else error("minimumAgentVersion must be a positive integer")
    end
' "$metadata")"
if [[ -n "$agent_version_file" ]]; then
  package_agent_version="$(tr -d '[:space:]' < "$agent_version_file")"
else
  package_agent_version="$agent_version"
fi

[[ "$metadata_tag" == "$tag" ]] || {
  echo "Release metadata tag does not match requested tag: $metadata_tag" >&2
  exit 1
}
[[ "$package_agent_version" =~ ^[1-9][0-9]*$ ]] || {
  echo "Invalid packaged agent version: $package_agent_version" >&2
  exit 1
}
(( package_agent_version >= minimum_agent_version )) || {
  echo "The packaged agent version ${package_agent_version} is below metadata minimum ${minimum_agent_version}." >&2
  exit 1
}

if [[ "$first_release" == 'true' && "$host_contract_required" != 'true' ]]; then
  echo 'The first formal release must require a manual host-package installation.' >&2
  exit 1
fi

if [[ -n "$previous_agent_version" ]]; then
  [[ "$previous_agent_version" =~ ^[1-9][0-9]*$ ]] || {
    echo "Invalid previous packaged agent version: $previous_agent_version" >&2
    exit 1
  }
  if (( minimum_agent_version > previous_agent_version )) && [[ "$host_contract_required" != 'true' ]]; then
    echo 'Raising minimumAgentVersion requires a manual host-package release.' >&2
    exit 1
  fi
fi

if [[ "$host_contract_required" == 'true' ]]; then
  jq -e '(.manual.reason | type) == "string" and (.manual.reason | length) > 0
    and (.manual.instructions | type) == "string" and (.manual.instructions | length) > 0' "$metadata" >/dev/null || {
      echo 'Manual host releases require non-empty manual.reason and manual.instructions.' >&2
      exit 1
    }
fi
