# 统一 Release 在线更新实施计划

## 1. 目标与边界

将 XianYu2 的正式版本发布和部署统一为 GitHub Release 驱动的更新机制。

- `main` 分支推送只执行 CI 校验，不再通过 GitHub Actions SSH 自动部署任何服务器。
- 创建 `vX.Y.Z` tag 后，Actions 生成唯一版本的 JAR、校验文件、Release manifest 与不可变 Docker 镜像。
- 现有安装实例和未来用户安装实例均在管理后台触发更新，由宿主机更新代理执行。
- 普通业务版本更新 JAR；运行环境变化时拉取 manifest 指定的镜像 digest。
- 应用重启可接受；浏览器管理后台与闲鱼平台 WebSocket 需在服务恢复后自动恢复。
- 不提供用户可见的历史版本回退界面。

本计划以公开 GitHub Release 与公开 GHCR 镜像为发行渠道。应用和宿主机更新代理均可匿名读取版本信息与下载发布产物，不保存 GitHub 访问凭据。

## 2. 当前状态

### 2.1 已有能力

| 能力 | 当前实现 | 状态 |
| --- | --- | --- |
| 版本检查 | `src/main/java/com/xianyu2/service/SystemUpdateService.java` 调用 `UPDATE_RELEASE_API` | 可复用，但仅读取最新 Release 信息 |
| 更新请求 | `SystemUpdateService` 向 `/app/update/request.json` 写入任务 | 可复用，但缺少宿主机代理 |
| 更新状态 | `/api/system/update/status` 读取 `status.json` | 可复用 |
| 管理后台更新界面 | `vue-code/src/components/layout/UpdateDialog.vue` | 可复用，已有任务进度轮询 |
| 管理后台版本入口 | `vue-code/src/components/layout/AppLayout.vue` | 可复用 |
| 闲鱼 WebSocket 重连 | `WebSocketConfig`、`XianyuWebSocketClient` | 已有基础逻辑，需做更新后恢复验证 |
| 容器健康检查 | `compose.yaml`、`deploy/server/deploy-xianyu2.sh` | 可复用 |
| Flyway 迁移检查 | `.github/workflows/deploy-production.yml`、`scripts/ci/check-migration-versions.sh` | 可复用 |

### 2.2 当前缺口

| 缺口 | 原因 |
| --- | --- |
| GitHub Actions 直连服务器部署 | 已移除；后续更新仅由安装实例本机的更新代理执行 |
| Release JAR | 当前 `.github/workflows/publish-container.yml` 仅发布 Docker 镜像 |
| 宿主机更新代理 | 旧代理在提交 `3479ba3` 中随旧项目发布配置一并删除 |
| runtime JAR | 容器始终运行镜像内 `/app/app.jar`，没有可持久替换的发布目录 |
| 版本唯一来源 | `pom.xml`、`application.yaml`、`package.json`、Dockerfile 中存在独立版本号 |
| 运行环境判断 | 当前没有区分“JAR 可更新”与“必须换镜像”的 Release 元数据 |
| 发布渠道公开配置 | GitHub 仓库和两个 GHCR Package 均需在 GitHub 中设置为公开 |

## 3. 参考实现

本方案参考 sub2api 的发布和在线更新设计，不直接复制其代码：

| sub2api 能力 | 参考位置 | XianYu2 对应做法 |
| --- | --- | --- |
| Release 版本检查 | `backend/internal/service/update_service.go` | 后端读取 GitHub Release 与 manifest |
| 平台匹配资产 | `getArchiveName()` | 固定首期支持 `linux/amd64`，manifest 明确架构 |
| 下载来源约束 | `validateDownloadURL()` | 更新代理仅允许 GitHub Release/API 域名 |
| SHA-256 校验 | `verifyChecksum()` | 校验 JAR 与 manifest 中的哈希、大小 |
| 原子安装 | 临时文件 + rename | 先下载到 runtime 临时文件，再原子替换 JAR |
| 更新状态与锁 | 管理端更新接口和操作锁 | 保留 `request.json` / `status.json`，宿主机使用 `flock` |
| 进程恢复 | systemd/Docker 重启策略 | 更新代理重建 app 容器并等待 `/actuator/health` |

差异：sub2api 更新的是 Go 单二进制；XianYu2 更新的是 Spring Boot JAR。XianYu2 还依赖 Playwright Chromium 与 Java 运行时，因此必须保留“镜像更新”分支。

## 4. 目标架构

```text
main push
  -> CI: 测试、迁移检查、构建烟测
  -> 不发布任何安装实例

vX.Y.Z tag / Release
  -> 构建前端与 XianYu2 JAR
  -> 生成 SHA256SUMS.txt、release-manifest.json
  -> 发布 GitHub Release
  -> 发布 ghcr.io/daki-l/xianyu2:vX.Y.Z 和不可变 digest

管理后台“更新”
  -> 应用写 /app/update/request.json
  -> systemd Path 启动宿主机 xianyu2-update-agent
  -> 读取 Release manifest
  -> runtime 指纹相同：下载、校验、替换 runtime/app.jar
  -> runtime 指纹不同：拉取 manifest 指定镜像 digest
  -> 重建 app 容器，等待 Flyway 与 /actuator/health
  -> 写 /app/update/status.json
  -> 浏览器恢复连接、校验版本并刷新页面
```

### 4.1 Release manifest

Release 附件 `release-manifest.json` 至少包含：

```json
{
  "version": "2.0.8",
  "platform": "linux/amd64",
  "runtimeFingerprint": "jre-21-playwright-1.61.0",
  "jar": {
    "name": "xianyu2-v2.0.8.jar",
    "sha256": "...",
    "size": 0
  },
  "image": {
    "repository": "ghcr.io/daki-l/xianyu2",
    "digest": "sha256:..."
  }
}
```

更新代理必须校验版本、平台、资产名称、大小、哈希与允许的下载域名；不能使用 `latest` 或任意 URL。

## 5. 实施步骤

### 阶段 A：统一版本与构建产物

1. 以正式 Git tag `vX.Y.Z` 作为 Release 唯一版本来源。
2. 调整 Maven 构建，将发布版本写入 JAR 的 build-info 或 manifest。
3. 调整 Vite 配置，发布构建从 `APP_VERSION` 注入前端版本，不依赖固定 `package.json` 值。
4. 调整 Spring `app.version`，从发布构建信息读取，避免与前端、JAR 文件名不一致。
5. Docker 镜像标签、OCI label 和 Release manifest 使用同一版本。
6. 添加构建校验：后端版本、前端版本、JAR 文件名与 tag 不一致时发布失败。

验收：本地和 CI 中请求 `/api/system/version`、侧栏版本号、JAR 文件名、镜像 tag 均显示相同版本。

### 阶段 B：拆分 CI 与正式 Release

1. 将 `.github/workflows/deploy-production.yml` 改为只做 `main` / PR 校验：迁移检查、Maven 测试、镜像烟测。
2. 删除所有服务器直连部署 Job、Tailscale 接入、SSH 配置和服务器部署命令。
3. 新建或重构 Release workflow：仅由 tag 或明确的 workflow dispatch 触发。
4. Release workflow 运行完整测试和迁移检查后，再构建 JAR、校验文件、manifest、Playwright 基础镜像和正式应用镜像。
5. 将 JAR、`SHA256SUMS.txt`、manifest 上传到 GitHub Release；镜像用版本 tag 与 digest 发布。
6. 保留镜像烟测，确保 Release 的 JAR 和镜像来自同一提交。

验收：普通 `main` 提交不触碰任何安装实例；创建 tag 后才出现可下载的 GitHub Release 和版本镜像。

### 阶段 C：运行时 JAR 容器布局

1. 在镜像中保留只读基线 JAR，例如 `/opt/xianyu2/app.jar`。
2. 新增容器启动脚本：首次启动将基线 JAR 初始化到持久目录；后续优先运行 runtime JAR。
3. 在 Compose 中增加 app runtime volume，不与 `app-data` 或日志目录混用。
4. 为所有受支持的 Compose 安装方式采用相同 runtime JAR 约定。
5. 更新宿主机镜像时，启动脚本需识别新的基线版本，避免旧 runtime JAR 永久覆盖新镜像。

验收：删除 runtime volume 后容器仍能以镜像基线启动；替换 runtime JAR 后重建容器会运行新版本。

### 阶段 D：宿主机更新代理

1. 恢复旧 `deploy/self-update` 思路，重命名为 `xianyu2-update-agent`，去除 XianYuSmart 与旧仓库引用。
2. 提供 systemd `.path` 与 `.service` 文件，监听 `${UPDATE_HOST_DIR}/request.json`。
3. 使用 `flock` 保证一个实例一次只执行一个更新任务。
4. 从 Release API 和 manifest 获取资产信息，强制校验目标版本和允许域名。
5. 下载到临时目录，验证大小和 SHA-256 后才原子替换 runtime JAR。
6. runtime 指纹一致时重建 app 容器；不一致时使用 manifest 的镜像 digest 更新 `APP_IMAGE` 后拉取并重建。
7. 在更新期间写入可由容器应用用户读取的 `status.json`，包含阶段、进度、消息、版本与时间。
8. 使用 `/actuator/health` 和 Docker health 状态判定成功；失败写入明确诊断状态。
9. 不暴露历史版本回退接口。安装过程中仍保留原 JAR 直到新文件校验完成，以避免下载中断损坏现有版本。

验收：请求文件触发一次且仅一次更新；并发点击不会并行更新；下载、校验、重启、健康检查均能在界面展示。

### 阶段 E：公开 Release 分发

1. 在 GitHub 中将仓库、应用镜像和 Playwright 基础镜像均设置为公开可读。
2. 更新代理使用匿名 GitHub Release API 和 Release asset 下载地址，不接受用户提交的下载 URL。
3. 容器 `.env`、数据库、前端和操作日志不保存 GitHub Token。
4. 发布验收时使用未登录的环境验证 Release 元数据、JAR 下载与 `docker pull` 均可访问。
5. 在安装文档中说明公开发布渠道、允许的下载域名和镜像仓库。

验收：不需要 GitHub Token；任意新安装实例可匿名读取 Release 并下载所需 JAR 与镜像。

### 阶段 F：客户端自动恢复

1. 保留 `UpdateDialog` 的任务状态轮询；应用不可访问时继续轮询，不将短暂网络失败标记为更新失败。
2. 更新完成或服务恢复后读取 `/api/system/version`；版本变化时刷新页面加载新前端静态资源。
3. 在 `AppLayout` 增加全局轻量恢复探测，覆盖非发起更新的已登录浏览器页面。
4. 验证后端闲鱼 WebSocket 初始化和重连流程：应用重启后已启用账号恢复连接，连接失败按现有延迟重试。
5. 更新界面明确提示短暂服务不可用，避免用户在重启期间重复提交操作。

验收：更新发起者和普通已登录用户在服务恢复后自动重新加载；闲鱼账号连接状态恢复或显示可诊断错误。

### 阶段 G：测试与现有实例切换

1. 后端单测覆盖版本解析、manifest 校验、权限拒绝、重复更新任务和状态文件异常。
2. 为更新代理添加 shell 测试，覆盖错误哈希、错误版本、下载超时、并发锁和镜像分流。
3. Docker 集成测试覆盖基线 JAR 初始化、JAR 更新、运行环境变更时的镜像更新与健康检查。
4. 在现有安装实例部署 systemd 代理、runtime 目录和更新目录挂载，但先不开放自动更新入口。
5. 使用测试 Release 完成一次 JAR 更新和一次镜像更新验证。
6. 验证通过后启用后台更新入口；GitHub Actions 不再部署任何服务器。

验收：现有与全新安装实例均从同一 Release 更新；`main` 推送不再改变任何服务器运行版本。

## 6. 影响范围

| 范围 | 影响 |
| --- | --- |
| 业务模块 | 商品、消息、风控、订单、买家等业务逻辑不改动 |
| 数据库 | 更新机制本身不新增表或迁移；正式版本仍可包含 Flyway 正向迁移 |
| 后端 | 版本信息、更新任务、manifest 校验与恢复状态需要调整 |
| 前端 | 更新弹窗、版本入口、全局恢复探测需要调整 |
| Docker | Dockerfile、启动脚本、Compose volume 与运行目录需要调整 |
| 服务器 | 新增 systemd 服务、更新目录、凭据文件和安装步骤 |
| CI/CD | main 校验与 tag Release 发布分离；无 GitHub Actions 直连服务器部署 |
| 文档 | README、部署说明、私有 Token 配置和公开发布说明需要更新 |

## 7. 风险与约束

1. 应用更新必然重启。Spring Boot、Flyway、健康检查期间会短暂不可用，前端与闲鱼连接必须恢复。
2. Flyway 为前向迁移。虽不提供版本回退，正式升级前仍应保留数据库备份流程。
3. runtime JAR 不能覆盖 Playwright、JRE、系统库或 Compose 结构变化，必须依赖 manifest 触发镜像更新。
4. 单个安装实例只能由本机更新代理管理运行版本；GitHub Actions 不得继续部署服务器。
5. 公开发布不依赖 GitHub Token，应用容器不得持有发布渠道凭据。
6. 当前许可证为 PolyForm Noncommercial 1.0.0；将来公开前需要单独确认许可证和历史凭据审计，不属于本更新机制的实现范围。

## 8. 非目标

- 不提供管理员手动选择旧版本或一键回退界面。
- 不允许应用容器挂载 Docker Socket。
- 不使用 `latest` 作为更新依据。
- 不在本次改造中自动将仓库由私有改为公开。

