# XianYu2

[English](README.en.md) | **简体中文**

[![Stars](https://img.shields.io/github/stars/Daki-l/XianYu2?style=flat&color=2f6f5e)](https://github.com/Daki-l/XianYu2/stargazers)
[![Forks](https://img.shields.io/github/forks/Daki-l/XianYu2?style=flat&color=2f6f5e)](https://github.com/Daki-l/XianYu2/forks)
[![Star History](https://img.shields.io/badge/Star%20History-View%20Growth-2f6f5e)](#star-history)
[![Java 21](https://img.shields.io/badge/Java-21-2f6f5e)](https://www.oracle.com/java/technologies/downloads/#java21)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-2f6f5e)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3-2f6f5e)](https://vuejs.org/)
[![MySQL](https://img.shields.io/badge/MySQL-5.7%2B-2f6f5e)](https://www.mysql.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **让虚拟商品从下单、交付、答疑到评价尽量自动完成；正常订单无需盯守，异常订单集中处理。**

XianYu2 是一个面向多租户场景的闲鱼虚拟商品运营系统。买家下单后，平台可以按商品自动交付固定资源或卡密，通过发货凭证、私聊或两种通道完成触达；成交前后的常见咨询、收货引导和评价跟进也能按规则自动处理。商家只需关注低库存、账号掉线、发送失败和待复核等真正需要介入的事项。

它不只是在收到订单后发送一段文本，而是把 **订单发现、幂等入队、库存预占、双通道交付、失败重试和人工复核** 串成可恢复的完整链路。固定内容与卡密两种交付模式严格互斥，账号、商品、消息、订单、库存、任务和 AI 知识库按租户隔离。核心任务链路只依赖 MySQL，不强制引入 Redis 或消息队列，兼顾部署成本与后续扩展。

下一正式版本：2.0.8 · [查看更新日志](CHANGELOG.md)

[商家能得到什么](#商家能得到什么) · [技术亮点](#技术亮点) · [解决的问题](#解决的问题) · [能力范围](#能力范围) · [功能入口与使用顺序](#功能入口与使用顺序) · [业务流程](#业务流程) · [技术基线](#技术基线) · [镜像部署](#镜像部署) · [快速启动](#快速启动) · [配置说明](#配置说明) · [开发构建](#开发构建) · [构建与验证](#构建与验证) · [目录与职责](#目录与职责) · [日常运维](#日常运维) · [使用边界](#使用边界) · [许可证与免责声明](#许可证与免责声明) · [Star History](#star-history)

## 商家能得到什么

| 使用场景 | XianYu2 自动完成 | 直接效果 |
| --- | --- | --- |
| 出售网盘链接、教程或固定资源 | 复用固定内容模板，自动替换会员名称、订单号和发货内容 | 不需要为每笔订单重复复制粘贴 |
| 出售激活码、兑换码或会员卡 | 按订单数量预占卡密，交付成功后核销并记录去向 | 降低重复发卡、少发和库存对不上的概率 |
| 卡密由已有供货系统提供 | 按订单实时调用 HTTPS 接口，使用幂等键避免重复采购，不确定结果转人工核对 | 不需要提前搬运全部库存，也不会因超时盲目重复扣货 |
| 买家需要及时收到内容 | 发货凭证与私聊通道可以单独开启，也可以同时发送 | 买家更容易找到交付内容，减少重复询问 |
| 大量重复咨询占用时间 | 按关键词、商品专属规则或 AI 知识库回复，支持人工接管 | 常见问题自动处理，复杂会话仍可人工继续 |
| 买家确认收货后需要跟进 | 按顺序发送自定义收货话术，并根据配置执行评价 | 售后引导形成固定流程，不依赖人工记忆 |
| 多商品需要统一维护 | 固定模板、卡密仓库、评价文案池和批量规则集中复用 | 减少重复配置，修改一次即可用于多个商品 |
| 服务重启、网络抖动或接口失败 | 自动恢复持久化任务，按退避策略重试，结果不确定时转人工复核 | 异常不会静默丢失，也不会盲目重复发送 |
| 多商家共同使用同一平台 | 业务数据和 AI 知识库按租户隔离 | 每个商家只管理自己的账号、商品、订单和配置 |
| 需要识别老客或暂停风险买家自动化 | 自动沉淀买家互动和成交数据，可维护标签、备注与自动化暂停状态 | 客户关系更清晰，异常买家不会继续触发自动回复或自动发货 |
| 关键异常需要及时触达 | 账号离线、凭证失效、发货异常和低库存通过 Webhook 通知并保留发送记录 | 不必持续盯着后台，也能及时发现需要介入的事项 |

对买家而言，核心体验是 **下单后更快收到内容、交付入口更清晰、常见问题更快得到回复**；对商家而言，核心变化是从“逐单操作”转为“配置规则、处理异常、查看结果”。

## 技术亮点

| 工程设计 | 实现方式 | 带来的价值 |
| --- | --- | --- |
| 原子卡密交付 | MySQL 行级锁整单预占，发送成功后核销，失败释放或转人工复核 | 避免并发订单导致重复发卡、少发和超卖 |
| 可恢复任务队列 | 订单与回复任务持久化，使用 Worker 租约、超时回收和退避重试 | 进程退出或服务器重启后仍能继续处理 |
| 双入口幂等 | WebSocket 实时事件与订单接口补偿统一按订单号入队 | 实时性与完整性兼顾，同一订单不会重复交付 |
| 多租户数据边界 | `TenantContext`、租户字段、联合唯一索引与数据库迁移共同约束 | 账号、订单、库存、配置和知识库互不串数据 |
| 实时消息链路 | Java-WebSocket 接入，MessagePack 解码，消息持久化与业务处理异步解耦 | 长连接接收不被耗时业务阻塞 |
| AI 客服可隔离 | Spring AI、租户级动态客户端与独立向量库，支持关键词、商品规则和人工接管 | AI 能力可按租户配置，也能随时回到确定性规则 |
| 轻量运行边界 | 有界线程池、有限任务队列、批量领取和数据库连接池上限 | 不依赖重型中间件也能控制资源占用 |
| 策略化扩展 | 固定内容/卡密交付策略与关键词/AI 回复策略独立解析 | 新增交付或回复方式时无需改写主流程 |
| 外部供货幂等 | 订单级请求令牌、响应数量校验和不确定结果隔离 | 对接外部卡密系统时避免重复采购与错误交付 |
| 主动通知与诊断 | 多渠道通知、签名校验、SSRF 防护、统一异常视图与处理状态 | 关键事件可及时触达，已处理的历史异常不会持续干扰 |

这套实现适合研究 **可靠任务调度、事件驱动自动化、多租户数据隔离、虚拟库存一致性和 AI 客服编排**。仓库提供的是从前端工作台、后端状态机、数据库迁移到容器部署的完整闭环，而不是只能运行单一路径的代码片段。

> 如果这些工程问题也是关注重点，欢迎 Star 关注项目演进；需要定制交付或回复链路时，可以 Fork 后沿现有策略接口扩展。

## 解决的问题

| 经营问题 | 处理方式 |
| --- | --- |
| 卡密重复发送、少发或超卖 | MySQL 行级锁、整单预占、发送成功后核销、失败释放或转人工复核 |
| 服务重启后订单或回复丢失 | 发货任务与回复任务持久化，租约超时后自动恢复 |
| 多入口同时触发重复发货 | 订单号幂等入队，WebSocket 与接口发现统一进入任务队列 |
| 消息高峰占用失控 | 共用有界线程池、有限队列、批量领取和连接池上限 |
| 客服自动化误回复 | 人工接管状态持久化，关键词、商品回复和 AI 回复按结果统一判定 |
| 库存与失败情况发现太晚 | 首页集中显示可用卡密、低库存、待处理、需复核和失败任务 |
| 多商家数据相互影响 | 账号、商品、消息、订单、卡密、设置、任务和AI知识库按租户隔离 |
| 商品发布与分销链路割裂 | 货源采集、选品入库、素材管理、批量发布、删除和补偿任务统一编排 |
| 评价与擦亮依赖人工巡检 | 商品管理统一维护评价开关、触发方式和文案池，支持批量应用；订单页处理手动评价和双方评价，擦亮按自然日去重 |
| 公网访问边界不清晰 | 应用仅绑定本机端口，Nginx 提供 HTTPS、限流和反向代理 |
| 买家信息散落在消息与订单中 | 自动建立买家资料，汇总互动、订单、成交金额、标签和运营备注 |
| 外部供货接口超时后无法判断是否扣货 | 使用固定幂等键重试，网络结果不确定时锁定订单并进入人工核对 |

## 能力范围

| 经营自动化 | 可靠交付 | 多租户运维 |
| --- | --- | --- |
| 账号、Cookie、连接与掉线提醒 | 多数量卡密原子预占与幂等交付 | MySQL 自动建表和版本迁移 |
| 商品、SKU、发货规则与卡密仓库 | 发货任务重试、租约恢复与人工复核 | Docker Compose、健康检查与 HTTPS 代理 |
| 固定内容与卡密严格二选一，凭证与私聊通道可独立启用 | 人工接管与延迟回复恢复 | 业务数据备份与操作日志 |
| 关键词、商品配置与 AI 自动回复 | 消息、订单和发货结果全程留痕 | 默认排除 Cookie、API Key、邮箱密码等敏感值 |
| 收入、交付、回复、库存与异常工作台 | 失败任务集中待办 | 有界线程池、连接池与批量调度参数 |
| 素材、地址、货源、选品和发布规则 | 自动/手动评价、自定义文案、自动擦亮与订单状态跟踪 | 租户级AI客户端、配置与向量库 |
| 返佣账号、分销结算与补偿任务 | 发布ID回写、短链修复和卡券绑定 | 公告、反馈、风控事件与操作日志 |
| 买家标签、备注与风险自动化暂停 | 本地库存与外部接口卡密供货 | Webhook 通知、发送日志与系统诊断 |

## 功能入口与使用顺序

同一项自动化能力只在一个业务模块维护开关和模板，其他页面仅展示状态、执行结果或提供跳转，避免多处配置互相覆盖。

| 唯一配置入口 | 负责内容 |
| --- | --- |
| 连接管理 | Cookie 更新、连接状态、WebSocket 重连和账号健康检查 |
| 商品管理 | 商品同步与编辑、自动评价规则、评价文案池、批量应用和自动擦亮 |
| 固定内容模板 | 下载链接、使用说明等可复用固定发货内容及变量模板 |
| 卡密仓库 | 本地卡密库存、外部供货接口、批量导入、库存预警和使用记录 |
| 自动发货 | 为商品选择固定内容或卡密模式，设置总开关、凭证发送和私聊发送 |
| 自动回复 | 关键词、商品专属回复、AI 回复和人工接管设置 |
| 买家管理 | 查看买家互动、订单和成交数据，维护标签、备注与自动化暂停状态 |
| 订单与评价 | 查看履约状态、手动评价、双方评价和失败重试，不维护自动评价规则 |
| 通知与诊断 | 检查账号、发货、回复和库存异常，单条或批量标记已处理，配置通知渠道与查看发送记录 |
| 操作日志与系统设置 | 查询业务操作、异常原因和租户级系统参数 |

推荐配置顺序：

1. 在连接管理添加闲鱼账号，确认 Cookie 有效且 WebSocket 已连接。
2. 同步商品；固定资源先创建固定内容模板，卡密商品先建立卡密仓库并导入库存。
3. 在自动发货中为商品二选一配置发货模式，再按需开启凭证和私聊通道。
4. 在自动回复中配置关键词、商品回复或 AI 回复策略。
5. 在商品管理统一设置自动评价模式与文案，按需开启自动擦亮。
6. 在订单与评价、操作日志和首页待办中检查执行结果与异常任务。

## 业务流程

```mermaid
flowchart LR
    XY["闲鱼消息与订单"] --> WS["连接与消息路由"]
    WS --> SAVE["消息持久化"]
    WS --> REPLY["回复任务"]
    WS --> DISCOVER["订单发现"]
    API["订单接口补偿"] --> DISCOVER
    DISCOVER --> TASK["持久化发货任务"]
    TASK --> CLAIM["租约领取与有限并发"]
    CLAIM --> RULE["商品与 SKU 规则解析"]
    RULE -->|卡密模式| CARD["卡密整单预占"]
    RULE -->|固定内容模式| FIXED["固定内容模板"]
    CARD --> CHANNEL["凭证 / 私聊通道"]
    FIXED --> CHANNEL
    CHANNEL --> SEND["闲鱼消息发送"]
    SEND -->|成功| COMMIT["核销库存与记录交付"]
    SEND -->|可重试失败| RETRY["退避重试"]
    SEND -->|不可确认| REVIEW["人工复核"]
    RETRY --> TASK
    REPLY --> HUMAN["人工接管校验"]
    HUMAN --> RULES["关键词 / 商品 / AI"]
    RULES --> SEND
    COMMIT --> DASH["商家工作台"]
    REVIEW --> DASH
```

### 商品运营闭环

`商品采集 -> 选品规则 -> 素材库 -> 单品或批量发布 -> 商品ID回写 -> 自动擦亮/评价 -> 分销结算`

- 发布规则和删除规则按租户定时生成持久化任务，失败后按退避时间重试。
- 补偿任务统一处理发布商品 ID 回写、站内短链修复和卡券仓库绑定。
- 公告、反馈、风控事件与任务结果统一进入运营中心，便于按租户追踪。
- 首页提供快速上手与核心功能直达入口；运营中心提供使用向导、模块说明、必填校验和下一步提示；自动评价规则统一在商品管理维护，订单页仅处理手动评价和双方评价结果。

### 发货状态

`PENDING -> PROCESSING -> SUCCESS`

- 临时网络或接口失败：`PROCESSING -> RETRY -> PENDING`
- 超过重试上限或发送结果不确定：`PROCESSING -> REVIEW_REQUIRED`
- 进程异常退出：租约过期后重新领取

### 卡密状态

`AVAILABLE -> RESERVED -> USED`

- 只有库存数量完整满足订单数量时才会预占。
- 消息确认发送成功后才会核销为 `USED`。
- 明确发送失败时释放为 `AVAILABLE`。
- 发送结果无法确认时保留关联并进入人工复核，避免重复发送。

## 技术基线

- Java 21
- Spring Boot 3.5
- MySQL 5.7+
- Flyway
- MyBatis-Plus
- Vue 3、TypeScript、Vite
- Docker Compose
- Nginx

## 镜像部署

每个正式 `vX.Y.Z` Release 会发布 `linux/amd64` 镜像、同一构建产出的 JAR、签名的 host package、`SHA256SUMS.txt`、`release-manifest.json` 与 Cosign 签名。生产环境只使用 manifest 指定的不可变 digest，项目不发布或使用 `latest`。

`main` 与 PR 仅执行迁移校验、测试和容器烟测，不会连接或部署任何服务器。正式发布在测试、JAR 构建、镜像 digest 烟测和签名验证均成功后才创建 GitHub Release。

### Playwright 基础镜像

Playwright Chromium 使用版本锁定的 `ghcr.io/daki-l/xianyu2-playwright:v<Playwright版本>` 基础镜像，不再随每次业务镜像构建下载。仅在以下内容改变时，工作流才重建基础镜像：

- `Dockerfile.playwright-base`；
- `Dockerfile` 中的 Playwright 基础镜像引用；
- `pom.xml` 中的 `com.microsoft.playwright:playwright` 版本。

三处版本必须完全一致，CI 会在构建前校验。升级 Playwright 时应同步更新 `pom.xml`、`Dockerfile.playwright-base` 和 `Dockerfile` 的基础镜像标签，并确认 GHCR 中 `xianyu2-playwright` 包的读取权限与业务镜像一致。

### Linux 生产安装与在线更新

生产安装使用两份环境文件：项目目录的 `.env` 仅保存数据库、JWT 等私密配置；`/etc/xianyu2/release.env` 仅保存由更新代理管理的不可变镜像 digest。不要将 GitHub Token、镜像 digest 或数据库密码写入对方文件。更新代理就绪后，管理后台管理员可随时从页面手动提交更新请求；请求仍需经过代理的验签、备份和健康检查。agent v4 起，默认由 root 代理自动安装已验签的 host package、更新 systemd 单元并继续同一个目标版本的部署；应用容器始终只能写入 `update/request`，绝不应获得宿主机 root 目录权限。

宿主机需要 Docker Engine、Docker Compose v2、`curl`、`jq`、`flock`、`sha256sum`、GNU `timeout`、GNU `tar`、`find` 与 Cosign。请仅从 [Sigstore 官方安装说明](https://docs.sigstore.dev/cosign/system_config/installation/) 选择固定的 Cosign 版本，并先核对该版本官方发布的 SHA-256、再执行 `cosign version`。更新代理不会自动安装或升级这些宿主机依赖。

```bash
# 每次首次安装固定一个正式 tag；不要 clone 默认分支或直接执行其脚本。
set -Eeuo pipefail
RELEASE_TAG=v2.0.8
RELEASE_API="https://api.github.com/repos/Daki-l/XianYu2/releases/tags/${RELEASE_TAG}"
COSIGN_IDENTITY="https://github.com/Daki-l/XianYu2/.github/workflows/release.yml@refs/tags/${RELEASE_TAG}"
COSIGN_ISSUER='https://token.actions.githubusercontent.com'
WORK_DIR="$(mktemp -d)"
cd "$WORK_DIR"
curl --fail --silent --show-error --proto '=https' --max-redirs 0 \
  -H 'Accept: application/vnd.github+json' "$RELEASE_API" -o release.json

# 只接受精确 tag 的正式 Release，以及唯一的一对 manifest asset。
jq -e --arg tag "$RELEASE_TAG" '
  .tag_name == $tag and .draft == false and .prerelease == false
  and ([.assets[] | select(.name == "release-manifest.json" and (.id | type) == "number")] | length == 1)
  and ([.assets[] | select(.name == "release-manifest.json.bundle" and (.id | type) == "number")] | length == 1)
' release.json >/dev/null

release_asset_id() {
  jq -er --arg name "$1" '
    [.assets[] | select(.name == $name and (.id | type) == "number") | .id]
    | if length == 1 then .[0] else error("asset count") end
  ' release.json
}

download_asset() {
  local name="$1" asset_id url headers body status location hop
  asset_id="$(release_asset_id "$name")"
  url="https://api.github.com/repos/Daki-l/XianYu2/releases/assets/${asset_id}"
  for ((hop = 0; hop <= 3; hop++)); do
    case "$url" in
      https://api.github.com/*|https://github.com/*|https://objects.githubusercontent.com/*|https://release-assets.githubusercontent.com/*) ;;
      *) echo "资产跳转到了不受信任的地址：$url" >&2; return 1 ;;
    esac
    headers="${name}.headers.$$"
    body="${name}.body.$$"
    rm -f "$headers" "$body"
    if ! status="$(curl --fail --silent --show-error --proto '=https' --max-redirs 0 \
      -H 'Accept: application/octet-stream' -H 'User-Agent: XianYu2-bootstrap' \
      --dump-header "$headers" --output "$body" --write-out '%{http_code}' "$url")"; then
      rm -f "$headers" "$body"
      return 1
    fi
    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      mv -f "$body" "$name"
      rm -f "$headers"
      return 0
    fi
    if [[ "$status" =~ ^3[0-9][0-9]$ ]]; then
      location="$(awk 'BEGIN { IGNORECASE = 1 }
        { sub(/\r$/, "") }
        tolower($1) == "location:" { sub(/^[^:]*:[[:space:]]*/, ""); value = $0 }
        END { print value }' "$headers")"
      rm -f "$headers" "$body"
      [[ -n "$location" ]] || return 1
      url="$location"
      continue
    fi
    rm -f "$headers" "$body"
    return 1
  done
  return 1
}
download_asset release-manifest.json
download_asset release-manifest.json.bundle
cosign verify-blob --certificate-identity "$COSIGN_IDENTITY" \
  --certificate-oidc-issuer "$COSIGN_ISSUER" --bundle release-manifest.json.bundle release-manifest.json

MANIFEST_COMMIT="$(jq -er --arg tag "$RELEASE_TAG" '
  if .schemaVersion == 1 and .releaseTag == $tag and (.commitSha | test("^[0-9a-f]{40}$"))
  then .commitSha else error("invalid signed manifest") end
' release-manifest.json)"
TAG_REF="$(curl --fail --silent --show-error --proto '=https' --max-redirs 0 \
  -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/Daki-l/XianYu2/git/ref/tags/${RELEASE_TAG}")"
if [[ "$(jq -r '.object.type' <<<"$TAG_REF")" == tag ]]; then
  TAG_OBJECT="$(jq -er '.object.sha' <<<"$TAG_REF")"
  TAG_REF="$(curl --fail --silent --show-error --proto '=https' --max-redirs 0 \
    -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/Daki-l/XianYu2/git/tags/${TAG_OBJECT}")"
fi
[[ "$(jq -r '.object.type' <<<"$TAG_REF")" == commit ]]
[[ "$(jq -r '.object.sha' <<<"$TAG_REF")" == "$MANIFEST_COMMIT" ]]

HOST_PACKAGE="$(jq -er '.hostPackage.name | select(test("^[A-Za-z0-9._-]+\\.tar\\.gz$"))' release-manifest.json)"
HOST_PACKAGE_SHA="$(jq -er '.hostPackage.sha256 | select(test("^[0-9a-f]{64}$"))' release-manifest.json)"
HOST_PACKAGE_SIZE="$(jq -er '.hostPackage.size | select(type == "number" and . > 0 and floor == .)' release-manifest.json)"
download_asset "$HOST_PACKAGE"
download_asset "${HOST_PACKAGE}.bundle"
[[ "$(stat -c '%s' "$HOST_PACKAGE")" == "$HOST_PACKAGE_SIZE" ]]
printf '%s  %s\n' "$HOST_PACKAGE_SHA" "$HOST_PACKAGE" | sha256sum --check -
cosign verify-blob --certificate-identity "$COSIGN_IDENTITY" \
  --certificate-oidc-issuer "$COSIGN_ISSUER" --bundle "${HOST_PACKAGE}.bundle" "$HOST_PACKAGE"

# 只解出由发布包允许的常规文件和目录；拒绝链接、路径逃逸和额外文件。
PACKAGE_ROOT="xianyu2-host-package-v${RELEASE_TAG#v}"
tar -tzf "$HOST_PACKAGE" | awk -v root="$PACKAGE_ROOT" '
  index($0, root "/") != 1 { exit 1 }
  { path = substr($0, length(root) + 2) }
  path == "" { next }
  path ~ /(^|\/)\.\.($|\/)/ || path ~ /^\// || path ~ /\/\// { exit 1 }
  path == ".env.example" || path == "compose.yaml" || path == "deploy/" || path ~ /^deploy\/(nginx|server|update)(\/|$)/ { next }
  { exit 1 }
'
tar -tvzf "$HOST_PACKAGE" | awk '$1 ~ /^[-d]/ { next } { exit 1 }'
for required_path in .env.example compose.yaml deploy/update/agent-version deploy/update/xianyu2-update-agent deploy/update/xianyu2-update-agent.service deploy/update/xianyu2-update-agent.path deploy/update/update-agent.conf.example deploy/update/backup-mysql deploy/update/install-update-agent.sh deploy/update/install-release.sh; do
  tar -tzf "$HOST_PACKAGE" | grep -Fx "${PACKAGE_ROOT}/${required_path}" >/dev/null
done
STAGE_DIR="$(mktemp -d)"
tar --extract --gzip --file "$HOST_PACKAGE" --directory "$STAGE_DIR" --strip-components=1 \
  --no-same-owner --no-same-permissions --numeric-owner
sudo install -d -m 0755 /opt/xianyu2
sudo cp -R --no-preserve=mode,ownership "$STAGE_DIR/." /opt/xianyu2/
sudo chown -R root:root /opt/xianyu2
cd /opt/xianyu2

# 准备 .env；此文件保存私密配置，不会被更新代理重写。
sudo cp .env.example .env
sudo chmod 0600 .env
sudoedit .env

# 安装来自已验证 host package 的更新代理。
sudo bash deploy/update/install-update-agent.sh
sudoedit /etc/xianyu2/update-agent.conf
# 确认 PROJECT_DIR、COMPOSE_FILE、ENV_FILE、目录路径和数据库备份 hook 路径。
# GitHub Release 下载较慢时，可选地设置 UPDATE_HTTP_PROXY=http://127.0.0.1:7890
# 并保留或按需调整 UPDATE_NO_PROXY=localhost,127.0.0.1,::1,mysql。
# 配置完成后可执行无副作用校验；它不会更新或重启应用。
sudo /usr/local/lib/xianyu2/xianyu2-update-agent --check
# 首次执行只安装文件并生成配置；保存配置后再次执行以检查依赖并启用 systemd Path unit
sudo bash deploy/update/install-update-agent.sh

# 从该正式 Release 完成首次安装
sudo bash deploy/update/install-release.sh "$RELEASE_TAG"
```

安装脚本会创建四类目录：应用仅能写入 `update/request`，应用只读 `update/status` 与 `runtime`，代理私有的 `update/private` 不挂载进容器。更新代理就绪后，管理后台管理员可随时从页面手动提交更新请求。代理会验证 GitHub OIDC Cosign 身份、JAR 哈希、镜像 digest 和 provenance、运行时 fingerprint；日常业务版本替换 JAR，Java/Playwright/系统依赖变化拉取新镜像。安装状态缺失或不匹配时不会覆盖 runtime JAR，而是安全地切换到 Release 的完整镜像基线。包含 Flyway 迁移的既有实例会先执行备份 hook，备份或健康检查失败时不会继续安装。

若 GitHub Release 资源直连较慢，可在 root 所有的 `/etc/xianyu2/update-agent.conf` 中设置 `UPDATE_HTTP_PROXY` 为 HTTP 或 HTTPS CONNECT 代理，并用 `UPDATE_NO_PROXY` 配置不走代理的主机、IP 或 CIDR 列表。该设置只在更新代理启动的 `curl` 和 Cosign 进程中生效，用于 Release 元数据、资产下载和签名校验；不会修改应用页面、应用容器、Compose、Docker daemon、系统全局代理或任何其他容器。更新代理不会将代理地址或凭据写入页面和更新状态；配置文件仅应由 root 读取。含保留字符的账号密码必须 URL 编码，并用单引号包裹完整代理 URL。镜像更新时的 `docker pull` 仍由 Docker daemon 的既有网络策略处理，刻意不使用此专用代理。

对 agent v4 及更新版本，更新界面遇到 Compose、更新代理、systemd 或受管目录的变更时，root 代理会自动下载 host package、验签 Cosign 与 SHA-256、拒绝非法路径/链接、备份旧受管文件并原子替换。它随后使用新代理继续拉取镜像、切换容器和健康检查；网页会持续显示下载字节、速度与 ETA。如果显式将 `AUTO_APPLY_HOST_PACKAGE_UPDATES=false`，代理会停在“需要人工处理”供管理员处理。

agent v3 及以下没有上述自更新能力。它们遇到 `host-package-manual-required` 必须首次以 root 手工验签、安装 v4 host package 并启用新 unit。这是升级链路中唯一次不可省略的 bootstrap；之后不需再为 agent 版本变更手工 SSH。不应通过开放 `/etc`、`/usr/local`或 systemd 的容器权限来绕过这个边界。

旧 agent 的 fallback 操作仍如下：

```bash
sudo /usr/local/lib/xianyu2/xianyu2-update-agent --apply-manual-release vX.Y.Z
```

这个命令会重新执行同一套验签、备份、镜像拉取和健康检查；它不能通过管理后台调用。Flyway 已开始迁移后若健康检查失败，代理不会尝试数据库回退，应按状态文件、备份和容器日志人工恢复。

生产 Compose 命令必须显式加载两份文件：

```bash
docker compose --env-file .env --env-file /etc/xianyu2/release.env -f compose.yaml up -d
```

Windows Docker Desktop 可用 `compose.dev.yaml` 做功能开发和验证，但 systemd 更新代理仅支持 Linux 宿主机。

## 本地开发快速启动

### 环境要求

- Docker Engine 24+ 或 Docker Desktop
- Docker Compose v2
- Linux 生产环境建议 2 核、2 GB 内存起步
- Windows 可使用 Docker Desktop 完成功能测试

### Linux（开发环境）

```bash
chmod +x install.sh
./install.sh --development
```

### Windows PowerShell（开发环境）

```powershell
Copy-Item .env.example .env
notepad .env
docker compose --env-file compose.dev.env -f compose.yaml -f compose.dev.yaml up -d --build
docker compose --env-file compose.dev.env -f compose.yaml -f compose.dev.yaml ps
```

启动前必须修改 `.env` 中的三个示例密钥。`JWT_SECRET` 至少使用 32 个随机字节，数据库密码不得复用。

启动后访问：`http://localhost:12400`

全新数据库首次访问会进入租户账号创建页；已有租户时可从登录页继续注册新租户，密码长度限制为 8 至 72 位。

### 公网 HTTPS（Release 生产环境）

先按上方“Linux 生产安装与在线更新”完成 Release 安装。不要在生产主机上使用 `--build` 或 `compose.dev.yaml`。

1. 将证书保存为：

```text
deploy/nginx/certs/fullchain.pem
deploy/nginx/certs/privkey.pem
```

2. 修改 `.env`：

```dotenv
ALLOWED_ORIGINS=https://shop.example.com
TRUST_PROXY=true
```

3. 启动代理配置：

```bash
docker compose --env-file .env --env-file /etc/xianyu2/release.env -f compose.yaml --profile proxy up -d
```

4. 域名解析到服务器后访问 `https://shop.example.com`。

应用容器只映射 `127.0.0.1:12400`，公网流量统一经过 Nginx。生产环境不得直接开放 MySQL 和 12400 端口。

## 配置说明

复制 `.env.example` 为 `.env` 后按环境修改：

| 变量 | 说明 | 推荐值 |
| --- | --- | --- |
| `DB_NAME` | MySQL 数据库名 | `xianyu2` |
| `DB_USERNAME` | 业务数据库账号 | 独立低权限账号 |
| `DB_PASSWORD` | 业务数据库密码 | 随机强密码 |
| `DB_ROOT_PASSWORD` | MySQL root 密码 | 与业务密码不同 |
| `JWT_SECRET` | 登录令牌签名密钥 | 48 字节以上随机值 |
| `ALLOWED_ORIGINS` | 允许访问的前端来源 | 完整 HTTPS 域名 |
| `TRUST_PROXY` | 是否信任代理头 | 仅 Nginx 部署设为 `true` |
| `UPDATE_RELEASE_API` | 固定 GitHub Release API | 默认官方公开地址；不要改为任意下载 URL |
| `DB_POOL_MAX_SIZE` | 最大数据库连接数 | 单实例默认 `10` |
| `DB_POOL_MIN_IDLE` | 最小空闲连接数 | 默认 `2` |
| `JAVA_OPTS` | JVM 容器内存策略 | 默认值适合小型实例 |

可在 `compose.yaml` 的 `app.environment` 中补充以下调优变量：

| 变量 | 默认值 | 作用 |
| --- | ---: | --- |
| `EXECUTOR_CORE_SIZE` | 4 | 通用业务线程数 |
| `EXECUTOR_MAX_SIZE` | 8 | 通用业务最大线程数 |
| `EXECUTOR_QUEUE_CAPACITY` | 500 | 有界任务队列容量 |
| `DELIVERY_CLAIM_BATCH_SIZE` | 20 | 单轮领取发货任务数 |
| `DELIVERY_DISPATCH_DELAY_MS` | 1000 | 发货调度间隔 |
| `DELIVERY_LEASE_SECONDS` | 120 | 任务处理租约 |
| `DELIVERY_MAX_ATTEMPTS` | 3 | 最大发货尝试次数 |
| `PRINT_RAW_MESSAGE` | false | 原始消息日志开关，生产环境保持关闭 |

调大并发前应同步评估闲鱼接口频率、活跃租户数、MySQL 连接数和服务器内存。优先保持默认值，通过异常待办确认实际瓶颈后再调整。

## 开发构建

### Windows 本地开发

准备 Java 21、Node.js 20+、MySQL 5.7+，然后创建数据库和账号：

```sql
CREATE DATABASE xianyu2 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'xianyu2'@'localhost' IDENTIFIED BY 'replace-with-strong-password';
GRANT ALL PRIVILEGES ON xianyu2.* TO 'xianyu2'@'localhost';
FLUSH PRIVILEGES;
```

后端：

```powershell
$env:DB_PASSWORD = 'replace-with-strong-password'
$env:JWT_SECRET = 'replace-with-at-least-32-random-bytes'
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
Set-Location vue-code
npm ci
npm run dev
```

前端开发地址为 `http://localhost:5173`，接口自动代理到 `http://localhost:12400`。

## 构建与验证

```powershell
Set-Location vue-code
npm ci
npm run type-check
npm run build:spring
Set-Location ..
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

Linux 将 `mvnw.cmd` 替换为 `./mvnw`。

## 目录与职责

```text
src/main/java/com/xianyu2/
├─ controller/          HTTP 接口与工作台聚合
├─ service/             账号、消息、回复、发货、运营编排和持久化任务
├─ service/delivery/    文本与卡密交付策略
├─ websocket/           闲鱼长连接、路由和重连
├─ mapper/              MySQL 数据访问与任务锁定
├─ interceptor/         登录认证边界
├─ backup/              可选择的数据备份
└─ config/              线程池、Web、数据库与 AI 配置

src/main/resources/
├─ db/migration/        Flyway 数据库结构
├─ static/              已构建的 Vue 前端
└─ application.yaml     运行参数

vue-code/src/
├─ api/                 前端接口封装
├─ components/          共用组件与布局
├─ views/               商家业务页面
├─ utils/               请求、提示与确认工具
└─ assets/              简约商业主题

deploy/nginx/            HTTPS、限流和反向代理
compose.yaml             应用、MySQL、Nginx 编排
```

## 日常运维

查看 Release 生产环境状态与日志：

```bash
docker compose --env-file .env --env-file /etc/xianyu2/release.env -f compose.yaml ps
docker compose --env-file .env --env-file /etc/xianyu2/release.env -f compose.yaml logs -f --tail=200 app
docker compose --env-file .env --env-file /etc/xianyu2/release.env -f compose.yaml logs -f --tail=200 mysql
```

开发环境更新本地代码后：

```bash
docker compose --env-file compose.dev.env -f compose.yaml -f compose.dev.yaml up -d --build
```

备份 MySQL：

```bash
docker compose --env-file .env --env-file /etc/xianyu2/release.env -f compose.yaml exec mysql mysqldump -uxianyu2 -p xianyu2 > xianyu2.sql
```

恢复前应先停止应用写入并验证备份文件。业务数据导出不包含 Cookie、AI Key、邮箱密码等敏感配置，灾备流程需单独保存运行环境变量和证书。

## 使用边界

- 闲鱼接口、Cookie 和风控策略可能变化，账号状态与异常待办需要持续关注。
- 自动化频率应符合平台规则，不应用于欺诈、骚扰或绕过平台安全机制。
- 公网部署必须启用 HTTPS、强密码、主机防火墙和定期备份。
- 全新环境使用 MySQL，不提供 SQLite 历史数据自动迁移。

## 许可证与免责声明

本项目采用 [MIT License](LICENSE) 开源。

- 使用行为必须遵守法律法规、闲鱼平台服务协议和账号使用规则。

使用前请阅读 [使用风险与免责声明](DISCLAIMER.md)。

## ⭐ Star History

<a href="docs/assets/star-history-light.png">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/star-history-dark.png" />
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/star-history-light.png" />
    <img alt="XianYu2 Star History Chart" src="docs/assets/star-history-light.png" width="100%" />
  </picture>
</a>
<sub>由 <a href="scripts/gen_star_history.py"><code>scripts/gen_star_history.py</code></a> 生成，<a href=".github/workflows/star-history.yml">GitHub Actions</a> 每日自动更新 · 点击图片查看大图</sub>
