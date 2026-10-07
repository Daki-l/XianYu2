#!/usr/bin/env bash
set -Eeuo pipefail

# 在 Linux 宿主机的仓库根目录执行：sudo deploy/update/install-update-agent.sh
readonly script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
readonly install_dir='/usr/local/lib/xianyu2'
readonly config_dir='/etc/xianyu2'
readonly state_dir='/var/lib/xianyu2'

[[ "${EUID}" -eq 0 ]] || {
  echo 'Run this installer as root.' >&2
  exit 1
}

for required_command in curl jq cosign docker flock sha256sum stat timeout; do
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
install -m 0755 "$script_dir/xianyu2-update-agent" "$install_dir/xianyu2-update-agent"
install -m 0644 "$script_dir/agent-version" "$install_dir/agent-version"
install -m 0755 "$script_dir/backup-mysql" "$install_dir/backup-mysql"

if [[ ! -f "$config_dir/update-agent.conf" ]]; then
  install -m 0640 "$script_dir/update-agent.conf.example" "$config_dir/update-agent.conf"
  echo "Created $config_dir/update-agent.conf; review it, then run this installer again to enable the agent." >&2
  config_created=true
else
  config_created=false
fi

install -m 0644 "$script_dir/xianyu2-update-agent.service" /etc/systemd/system/xianyu2-update-agent.service
install -m 0644 "$script_dir/xianyu2-update-agent.path" /etc/systemd/system/xianyu2-update-agent.path

systemctl daemon-reload
if [[ "$config_created" == 'true' ]]; then
  exit 0
fi
"$install_dir/xianyu2-update-agent" --check
systemctl enable xianyu2-update-agent.service xianyu2-update-agent.path
systemctl start xianyu2-update-agent.path
# Path 只会观察应用可写的 request 目录。开机启动一次 service 才能处理已被
# 认领到代理私有 in-progress 目录、但尚未达到阶段超时的任务。
systemctl start xianyu2-update-agent.service
echo 'XianYu2 update agent is ready.'
