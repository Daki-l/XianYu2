#!/usr/bin/env bash
set -Eeuo pipefail

# 在 Linux 宿主机的仓库根目录执行：sudo deploy/update/install-update-agent.sh
readonly script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
readonly install_dir='/usr/local/lib/xianyu2'
readonly config_dir='/etc/xianyu2'
readonly state_dir='/var/lib/xianyu2'
readonly systemd_dir='/etc/systemd/system'
readonly systemd_override_dir='/etc/systemd/system/xianyu2-update-agent.service.d'

install_file_atomically() {
  local mode="$1"
  local source="$2"
  local destination="$3"
  local destination_dir temporary
  destination_dir="$(dirname "$destination")"
  install -d -m 0755 "$destination_dir"
  temporary="$(mktemp "$destination_dir/.${destination##*/}.tmp.XXXXXX")"
  install -o root -g root -m "$mode" "$source" "$temporary"
  mv -f -- "$temporary" "$destination"
}

require_protected_config() {
  local config="$1"
  [[ -f "$config" && ! -L "$config" ]] || {
    echo "Update agent config must be a regular file: $config" >&2
    return 1
  }
  [[ "$(stat -c '%u' "$config")" == 0 ]] || {
    echo "Update agent config must be owned by root: $config" >&2
    return 1
  }
  local mode
  mode=$((8#$(stat -c '%a' "$config")))
  (( (mode & 8#22) == 0 )) || {
    echo "Update agent config must not be group- or world-writable: $config" >&2
    return 1
  }
  local directory
  directory="$(dirname "$config")"
  while :; do
    [[ -d "$directory" && ! -L "$directory" ]] || {
      echo "Update agent config parent must be a real directory: $directory" >&2
      return 1
    }
    [[ "$(stat -c '%u' "$directory")" == 0 ]] || {
      echo "Update agent config parent must be owned by root: $directory" >&2
      return 1
    }
    mode=$((8#$(stat -c '%a' "$directory")))
    (( (mode & 8#22) == 0 )) || {
      echo "Update agent config parent must not be group- or world-writable: $directory" >&2
      return 1
    }
    [[ "$directory" == '/' ]] && break
    directory="$(dirname "$directory")"
  done
}

install_project_write_path_override() {
  local config="$1"
  local project_dir temporary
  require_protected_config "$config"
  # shellcheck disable=SC1090
  source "$config"
  : "${PROJECT_DIR:?PROJECT_DIR is required}"
  [[ "$PROJECT_DIR" == /* && "$PROJECT_DIR" != '/' && "$PROJECT_DIR" != *[[:space:]]* ]] || {
    echo 'PROJECT_DIR must be an absolute path without whitespace for the systemd write-path override.' >&2
    return 1
  }
  project_dir="$PROJECT_DIR"
  [[ -d "$project_dir" && ! -L "$project_dir" ]] || {
    echo "PROJECT_DIR must be an existing real directory: $project_dir" >&2
    return 1
  }

  install -d -m 0755 "$systemd_override_dir"
  temporary="$(mktemp "$systemd_override_dir/.project-dir.conf.tmp.XXXXXX")"
  printf '[Service]\nReadWritePaths=\nReadWritePaths=/var/lib/xianyu2 /etc/xianyu2 /usr/local/lib/xianyu2 /etc/systemd/system %s\n' \
    "$project_dir" > "$temporary"
  chown root:root "$temporary"
  chmod 0644 "$temporary"
  mv -f -- "$temporary" "$systemd_override_dir/project-dir.conf"
}

migrate_legacy_timeout_default() {
  local config="$1"
  # Only migrate the historical packaged default. A deliberately customized
  # timeout remains the administrator's explicit choice.
  grep -Fxq 'TASK_TOTAL_TIMEOUT_SECONDS=2400' "$config" || return 0
  local temporary
  temporary="$(mktemp "${config}.tmp.XXXXXX")"
  chmod --reference="$config" "$temporary"
  chown --reference="$config" "$temporary"
  sed 's/^TASK_TOTAL_TIMEOUT_SECONDS=2400$/TASK_TOTAL_TIMEOUT_SECONDS=17100/' "$config" > "$temporary"
  mv -f "$temporary" "$config"
  echo "Migrated the legacy TASK_TOTAL_TIMEOUT_SECONDS default in $config for slow transfer support." >&2
}

[[ "${EUID}" -eq 0 ]] || {
  echo 'Run this installer as root.' >&2
  exit 1
}

for required_command in curl jq cosign docker flock sha256sum stat timeout mkfifo tar find; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "Missing required command: $required_command" >&2
    exit 1
  }
done
docker compose version >/dev/null 2>&1 || {
  echo 'Docker Compose v2 is required.' >&2
  exit 1
}

install -d -m 0755 "$install_dir" "$config_dir"
install -d -m 0755 "$state_dir" "$state_dir/update"
install_file_atomically 0755 "$script_dir/xianyu2-update-agent" "$install_dir/xianyu2-update-agent"
install_file_atomically 0644 "$script_dir/agent-version" "$install_dir/agent-version"
install_file_atomically 0755 "$script_dir/backup-mysql" "$install_dir/backup-mysql"

if [[ ! -f "$config_dir/update-agent.conf" ]]; then
  install_file_atomically 0640 "$script_dir/update-agent.conf.example" "$config_dir/update-agent.conf"
  echo "Created $config_dir/update-agent.conf; review it, then run this installer again to enable the agent." >&2
  config_created=true
else
  config_created=false
  require_protected_config "$config_dir/update-agent.conf"
  migrate_legacy_timeout_default "$config_dir/update-agent.conf"
fi

install_file_atomically 0644 "$script_dir/xianyu2-update-agent.service" "$systemd_dir/xianyu2-update-agent.service"
install_file_atomically 0644 "$script_dir/xianyu2-update-agent.path" "$systemd_dir/xianyu2-update-agent.path"

if [[ "$config_created" == 'true' ]]; then
  systemctl daemon-reload
  exit 0
fi
install_project_write_path_override "$config_dir/update-agent.conf"
systemctl daemon-reload
"$install_dir/xianyu2-update-agent" --check
systemctl enable xianyu2-update-agent.service xianyu2-update-agent.path
systemctl start xianyu2-update-agent.path
# Path 只会观察应用可写的 request 目录。开机启动一次 service 才能处理已被
# 认领到代理私有 in-progress 目录、但尚未达到阶段超时的任务。
if [[ "${XIANYU2_UPDATE_AGENT_SELF_UPDATE:-false}" != 'true' ]]; then
  systemctl start xianyu2-update-agent.service
fi
echo 'XianYu2 update agent is ready.'
