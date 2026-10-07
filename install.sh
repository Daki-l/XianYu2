#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"
BOOTSTRAP_ADMIN_PASSWORD=""

if [[ "${1:-}" != '--development' || $# -ne 1 ]]; then
    echo 'This bootstrap script is for local development only.' >&2
    echo 'Use the GitHub Release installation instructions for Linux production.' >&2
    echo "Usage: $0 --development" >&2
    exit 64
fi

random_hex() {
    local bytes="$1"
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex "$bytes"
        return
    fi
    od -An -N "$bytes" -tx1 /dev/urandom | tr -d ' \n'
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "缺少命令: $1" >&2
        exit 1
    fi
}

require_command docker

if ! docker compose version >/dev/null 2>&1; then
    echo "需要 Docker Compose v2。" >&2
    exit 1
fi

if [ ! -f .env ]; then
    cp .env.example .env
    DB_PASSWORD="$(random_hex 24)"
    DB_ROOT_PASSWORD="$(random_hex 24)"
    JWT_SECRET="$(random_hex 48)"
    BOOTSTRAP_ADMIN_PASSWORD="$(random_hex 12)"

    # 首次安装生成独立密钥，避免示例凭据进入运行环境。
    sed -i "s/change-me-database-password/$DB_PASSWORD/" .env
    sed -i "s/change-me-root-password/$DB_ROOT_PASSWORD/" .env
    sed -i "s/change-me-to-at-least-32-random-bytes/$JWT_SECRET/" .env
    sed -i "s/change-me-bootstrap-admin-password/$BOOTSTRAP_ADMIN_PASSWORD/" .env
    chmod 600 .env
fi

docker compose --env-file compose.dev.env -f compose.yaml -f compose.dev.yaml up -d --build
docker compose --env-file compose.dev.env -f compose.yaml -f compose.dev.yaml ps

echo
echo "XianYu2 已启动: http://localhost:12400"
if [ -n "$BOOTSTRAP_ADMIN_PASSWORD" ]; then
    echo "初始管理员: admin"
    echo "初始密码: $BOOTSTRAP_ADMIN_PASSWORD"
fi
echo 'This development environment is not the production Release update path.'
