#!/usr/bin/env bash
set -Eeuo pipefail

# 在已准备好 Compose、.env 和 update-agent.conf 的宿主机执行首次正式安装。
readonly agent_path='/usr/local/lib/xianyu2/xianyu2-update-agent'
readonly config_file='/etc/xianyu2/update-agent.conf'

usage() {
  echo "Usage: $0 vX.Y.Z" >&2
  exit 64
}

validate_release_tag() {
  [[ $# -eq 1 ]] || usage
  [[ "$1" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || {
    echo "Invalid release tag: $1" >&2
    return 64
  }
}

create_initial_request() {
  local release_tag="$1"
  : "${UPDATE_REQUEST_DIR:?UPDATE_REQUEST_DIR is required}"
  : "${APP_UID:?APP_UID is required}"
  : "${APP_GID:?APP_GID is required}"
  [[ "$APP_UID" =~ ^[0-9]+$ && "$APP_GID" =~ ^[0-9]+$ ]] || {
    echo 'APP_UID and APP_GID must be numeric.' >&2
    return 1
  }

  # 首次安装由 root 发起，但请求协议要求它与容器应用创建的请求具有
  # 相同的所有者和非可写权限，否则 agent 会把它当作不可信输入拒绝。
  install -d -m 0750 -o "$APP_UID" -g "$APP_GID" "$UPDATE_REQUEST_DIR"
  chown "$APP_UID:$APP_GID" "$UPDATE_REQUEST_DIR"
  chmod 0750 "$UPDATE_REQUEST_DIR"
  local task_id temporary
  task_id="$(cat /proc/sys/kernel/random/uuid)"
  temporary="$(mktemp "$UPDATE_REQUEST_DIR/.request.json.XXXXXX")"
  jq -n --arg taskId "$task_id" --arg releaseTag "$release_tag" --arg version "${release_tag#v}" \
    --arg requestedAt "$(date --iso-8601=seconds)" \
    '{schemaVersion: 1, taskId: $taskId, releaseTag: $releaseTag, version: $version, requestedAt: $requestedAt}' > "$temporary"
  chown "$APP_UID:$APP_GID" "$temporary"
  chmod 0640 "$temporary"
  mv -f "$temporary" "$UPDATE_REQUEST_DIR/request.json"
}

main() {
  validate_release_tag "$@" || return $?
  [[ "${EUID}" -eq 0 ]] || {
    echo 'Run this installer as root.' >&2
    return 1
  }
  [[ -x "$agent_path" && -r "$config_file" ]] || {
    echo 'Install and configure xianyu2-update-agent first.' >&2
    return 1
  }

  # shellcheck disable=SC1090
  source "$config_file"
  local release_tag="$1"

  # 先停用 Path unit，避免 request.json 刚出现就被后台服务抢占。
  systemctl stop xianyu2-update-agent.path xianyu2-update-agent.service 2>/dev/null || true
  restart_path_unit() {
    systemctl start xianyu2-update-agent.path
  }
  trap restart_path_unit EXIT

  create_initial_request "$release_tag"

  # 前台执行保证本命令返回时安装已完成。
  "$agent_path" --initial-install
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
