#!/usr/bin/env bash
set -Eeuo pipefail

readonly workflow_directory="${1:-.github/workflows}"
[[ -d "$workflow_directory" ]] || {
  echo "Workflow directory does not exist: $workflow_directory" >&2
  exit 64
}

while IFS= read -r -d '' workflow; do
  while IFS= read -r use_line; do
    action_ref="${use_line#*@}"
    action_ref="${action_ref%%[[:space:]#]*}"
    if [[ ! "$action_ref" =~ ^[0-9a-f]{40}$ ]]; then
      echo "Workflow action is not pinned to a full commit SHA: ${workflow}: ${use_line}" >&2
      exit 1
    fi
  done < <(grep -E '^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]+' "$workflow" || true)
done < <(find "$workflow_directory" -type f \( -name '*.yml' -o -name '*.yaml' \) -print0)

readonly release_workflow="$workflow_directory/release.yml"
[[ -f "$release_workflow" ]] || exit 0
anonymous_verification="$(awk '
  /name: Verify published Release and registry anonymously/ { in_section = 1 }
  /name: Delete Release if anonymous verification failed/ { in_section = 0 }
  in_section { print }
' "$release_workflow")"
[[ -n "$anonymous_verification" ]] || {
  echo 'The Release workflow is missing the anonymous verification step.' >&2
  exit 1
}
if grep -Eq '(^|[[:space:]])(GH_TOKEN|GITHUB_TOKEN)[[:space:]]*:|Authorization:|docker[[:space:]]+login' <<< "$anonymous_verification"; then
  echo 'The anonymous Release verification step must not receive credentials.' >&2
  exit 1
fi

echo 'Workflow pinning checks passed.'
