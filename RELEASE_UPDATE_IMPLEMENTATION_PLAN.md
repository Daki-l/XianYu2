# 统一 Release 在线更新实施计划

## 1. 目标与边界

将 XianYu2 的正式发布和在线更新统一为 GitHub Release 驱动的机制。本文所有“实施项”都是后续需要落地的代码、工作流、宿主机文件或测试，不是用户需要事先手工完成的条件。

- `main` 推送只运行 CI，不通过 GitHub Actions SSH 部署任何服务器；非 Release 构建使用不可发布的 `0.0.0-dev+<commit>` 版本，不得伪装为正式 semver。
- 首个新发行版本使用全新 tag `v2.0.8`（不得复用已有 `v2.0.7-fork.1`）。
- 创建 `vX.Y.Z` tag 后，Actions 从同一份构建产物发布 GitHub Release JAR、校验文件、manifest 和不可变 Docker 镜像。
- 已安装实例从管理后台触发更新；全新主机先由宿主机安装脚本完成经过验签的引导安装，之后才使用管理后台更新。宿主机更新代理执行实际更新，应用容器不持有 Docker Socket 或 GitHub Token。
- 业务改动走 JAR 更新；Java、Playwright、系统库等容器运行时变化走镜像更新。Compose 结构、systemd、目录权限或其他宿主机契约变化不能随镜像生效，必须明确要求人工处理。
- 应用重启可接受。浏览器管理后台和闲鱼 WebSocket 必须在应用恢复后自动恢复。
- 不提供面向用户的历史版本回退功能；Flyway 迁移保持仅向前执行。
- 执行安装或更新必须显式指定不可变的 `vX.Y.Z` tag。`/releases/latest` 仅可用于后台提示“有新版本”，不能作为安装脚本、代理或 Compose 的执行输入；缺少 tag 必须失败，不能静默回退到最新 Release。

发布渠道已具备的前提：仓库 `Daki-l/XianYu2`、`xianyu2` 和 `xianyu2-playwright` GHCR Package 已公开，匿名用户可拉取。这不是本计划的待办，但每次正式发布仍需进行匿名访问验证。

## 2. 现状与缺口

### 2.1 可复用基础

| 能力 | 现有位置 | 本次用途 |
| --- | --- | --- |
| 版本检查、更新请求和状态读取 | `SystemUpdateService`、`/api/system/update/*` | 复用 API 外壳，扩展 manifest、安装状态与错误语义 |
| 管理后台更新入口 | `UpdateDialog.vue`、`AppLayout.vue` | 改造成可穿越重启的更新进度与恢复体验 |
| 闲鱼 WebSocket 重连 | `WebSocketConfig`、`XianyuWebSocketClient` | 验证应用重启后恢复，不另造连接机制 |
| Compose 与健康检查 | `compose.yaml`、`scripts/ci/smoke-test-container.sh` | 增加 runtime/update 挂载，作为更新成功判定 |
| 迁移检查和容器烟测 | `.github/workflows/deploy-production.yml`、`scripts/ci/check-migration-versions.sh` | 保留为 CI/Release 前置校验 |

### 2.2 必须实现的缺口

| 缺口 | 现状 | 对应阶段 |
| --- | --- | --- |
| 单一版本来源 | `pom.xml`、`application.yaml`、`package.json`、Dockerfile 分别写死 `2.0.7` | A |
| 可下载 Release JAR | 当前 Release 无 JAR、哈希或 manifest | B |
| 同一 JAR 的镜像与 Release | Dockerfile 在镜像内自行构建 JAR | A、B |
| 发布顺序 | `.github/workflows/publish-container.yml` 在 Release 发布后才推镜像，并发布 `latest` | B |
| 容器运行时 JAR | 容器只运行镜像内 `/app/app.jar` | C |
| 已安装状态 | 没有本地记录 runtime 指纹、镜像 digest、JAR 哈希 | C、D |
| 宿主机更新代理 | 当前没有监听请求并操作 Compose 的服务 | D |
| 更新类型分流 | 不区分 JAR、镜像与人工主机升级 | D |
| 重启期间的前端恢复 | 请求过期时间固定为 10 分钟，慢下载可能被误清理 | E |
| 公开安装和发布文档 | README 仍描述 `latest` 和旧的自动部署行为 | F |

## 3. 参考实现与目标架构

本方案参考 sub2api 的 Release 检查、下载来源约束、SHA-256 校验、原子替换、操作锁和重启恢复思路，不复制其 Go 代码。XianYu2 的发布单元是 Spring Boot JAR，且依赖 Playwright Chromium 和 Java 运行时，因此须额外保留镜像更新分支。

```text
main push
  -> CI：迁移检查、测试、构建和烟测
  -> 不创建 Release，不接触任何安装实例

vX.Y.Z tag
  -> 测试 -> 构建唯一 JAR -> 推送版本镜像 -> 获取 digest -> 烟测
  -> 生成 JAR、SHA256SUMS.txt、release-manifest.json
  -> 最后创建/发布 GitHub Release

管理后台“更新”
  -> 应用原子写入 request.json
  -> systemd Path 启动 xianyu2-update-agent（宿主机）
  -> 校验 manifest、已安装状态和更新类型
  -> jar：原子替换 runtime/app.jar；image：按 digest 更新 release.env；manual：明确提示人工处理
  -> 重建 app，等待 Flyway、Docker health 和 /actuator/health
  -> 写只读 status.json、installed.json
  -> 浏览器恢复访问、校验版本、刷新静态资源；闲鱼连接按既有逻辑重连
```

### 3.1 Release manifest 与本地状态契约

`release-manifest.json` 必须具有版本化 schema。公共字段为 `schemaVersion`、`releaseTag`、`version`、`commitSha`、`publishedAt`、`platform`、`minimumAgentVersion`、`runtimeFingerprint`、`updateType`。`jar` 类型必须包含 JAR 名称/大小/SHA-256；`image` 类型必须包含 JAR 信息和镜像仓库/digest；`host-package-manual-required` 必须包含阻塞原因、人工步骤和最低所需的更新代理版本。每个 Release 均发布版本化 host package（Compose、示例环境文件、Nginx/服务器配置与更新代理），manifest 必须含其资产名、大小、SHA-256 与签名 bundle；操作员只能验证后安装该包，不能从默认分支或未固定链接直接执行脚本。首期只支持 `linux/amd64`。manifest、JAR 与 host package 的 Cosign bundle 作为同名的独立 Release asset 发布，镜像的签名和 provenance 以 digest 为键存于 registry；不能把未签名的 manifest 自身声明当作信任来源。

manifest 另含 `database.requiresBackup`、`database.migrationRange` 和 `database.backupRequiredReason`。Release workflow 以本 tag 与上一个正式 tag 的 Flyway 目录差异生成该字段；无法可靠识别时必须保守地设为 `true`。代理仅在 `requiresBackup=true` 时执行预先配置的备份命令，命令缺失或失败必须终止更新。

允许的 `updateType` 为：

| 类型 | 代理行为 | 使用场景 |
| --- | --- | --- |
| `jar` | 下载、校验并原子替换 runtime JAR，再重建 app | runtime 指纹相同的日常业务更新 |
| `image` | 将受管镜像引用固定为 manifest digest，清除 runtime JAR 后拉取并重建 app | Java、Playwright、系统依赖、入口脚本或镜像基线变化，且宿主机契约不变 |
| `host-package-manual-required` | 不从后台下载、不重启，写入需要人工执行的说明 | Docker、操作系统、Compose、systemd、更新代理、目录权限或其他宿主机依赖变化 |

本地 `installed.json` 由代理原子写入，保存 schema 版本、应用版本、JAR SHA-256、runtime fingerprint、当前镜像 digest、启动来源（`runtime` 或 `baseline`）和完成时间。首次安装也必须在镜像基线通过健康检查后写入完整的 baseline 状态，不能留下“已安装但状态未知”的空白。文件缺失、字段不完整或 fingerprint 不匹配属于未知状态，代理不得猜测执行 JAR 覆盖，必须按镜像更新或报出需要人工确认的状态。

Release workflow 用 GitHub OIDC 生成 keyless Cosign 签名和证明材料，分别覆盖 manifest/JAR、host package 与镜像 digest；代理使用固定的仓库、workflow 路径、请求的精确 tag 与 commit identity 验证签名后才信任 manifest。代理必须先验证签名与 `schemaVersion`，再读取或根据任意 manifest 业务字段作决策；未知 schema 只能写出安全的人工处理提示，不能因字段缺失而变成普通下载失败。签名 manifest 是 Release 资产选择的唯一信任根：JAR 与 host package 的名称、大小和哈希必须从已验签 manifest 读取，不能由 Release Notes、`browser_download_url` 或未签名的 `SHA256SUMS.txt` 决定。GitHub 在创建 Release 后才分配 asset ID，因此 asset ID 不能也不应写入预先签名的 manifest；代理只能在 Release API 的资产列表中，按 manifest 给出的名称找到唯一一项后，临时把该 API 返回的 ID 当作下载句柄。bundle 名固定为 `${assetName}.bundle`，下载后仍必须对对应内容以固定 identity 验签；`SHA256SUMS.txt` 只作人工诊断和发布复验，不是执行信任根。首次安装文档须给出经过校验的 `cosign` 二进制的来源、版本、校验和与身份验证步骤；更新代理、systemd unit 或其配置 schema 的升级属于 `host-package-manual-required`，并只能安装该 Release 中经验证的 host package。

代理只接受固定 GitHub Release API、GitHub asset ID 和 `ghcr.io/daki-l/xianyu2@sha256:...`。资产下载只允许 HTTPS、最多有限次重定向和 GitHub 官方的 asset 域名；不得信任 manifest 中的任意 URL。严格验证签名、tag、commit、平台、资产名称、大小、哈希和 digest；更新决策及 Compose 配置中均不得使用 `latest`。

manifest 的信任引导也必须固定：先以精确 tag 的 Release API 响应检查 `tag_name`、非 draft/非 prerelease、且 `release-manifest.json` 与其 `.bundle` 各唯一一份；随后只从该 API 资产列表解析它们的临时下载 ID，验证 manifest 的 Cosign identity 后，才允许读取并选择其他资产。验证后的 manifest 必须再与 Git tag-ref API 解析出的 commit 一致。首次安装和更新代理使用同一套规则，不能让文档中的 bootstrap 命令成为比代理更弱的下载路径。

`request.json`、`status.json` 与 `installed.json` 也属于版本化宿主机协议，不能只靠实现约定。`request.json` 至少包含 UUID `taskId`、`releaseTag`、`version` 与创建时间；`status.json` 至少包含相同 `taskId`、目标版本、状态枚举、进度、`taskStartedAt`、`stageStartedAt`、`updatedAt`、阶段超时和可显示的错误摘要；`installed.json` 使用上述完整字段。状态枚举、终态集合和字段含义由后端、前端和代理共享测试样例锁定。读取端遇到未知 schema、未知状态或不属于当前任务的状态必须保守展示“需要人工检查”，不得把它当成成功或删除请求。更改这些协议、固定仓库/工作流/OIDC identity、允许下载域名或信任根时，必须走 `host-package-manual-required`，不能由普通 JAR 或镜像更新悄悄改变。

`minimumAgentVersion` 也有跨 Release 约束：metadata 只能要求不高于本 tag host package 内的 agent 版本；若它高于上一正式 Release 的 agent 版本，`hostContractRequired` 必须为 `true`，从而让旧 agent 在验签后先显示兼容 schema 的 `MANUAL_REQUIRED` 提示，而不是尝试普通 JAR/镜像更新后才报错。首个正式 Release 没有上一 agent，必须同样设为 `host-package-manual-required`。正常 `jar`/`image` Release 不得提高最低 agent 版本。这样所有 agent 升级都有一个可验证的 host package 和明确的人工入口。

### 3.2 更新目录与权限契约

“应用只创建请求、代理独占状态”不能在同一个容器可写目录中实现。更新目录必须拆分为下列同一宿主机文件系统中的子目录；生产 Compose 只挂载前三项，代理私有目录绝不挂载进容器。

| 宿主机目录 | 容器路径与权限 | 写入者 | 用途 |
| --- | --- | --- | --- |
| `update/request` | `/app/update/request:rw` | 应用、代理 | 应用原子创建 `request.json`；代理原子认领后移走 |
| `update/status` | `/app/update/status:ro` | 仅代理 | `agent.ready`、`status.json`，应用只读展示 |
| `runtime` | `/app/runtime:ro` | 仅代理 | `app.jar`、`installed.json`；入口脚本只读校验并选择 JAR |
| `update/in-progress`、`update/archive`、下载临时目录和锁 | 不挂载 | 仅代理 | 认领任务、归档、锁和下载中间文件 |

代理用与 `request` 同文件系统的原子 rename 将请求移入私有 `in-progress`。镜像须固定应用 UID/GID，安装脚本按该 UID/GID 创建可写的 `request` 目录；状态和 runtime 对容器一律只读。代理认领前后都必须以 `lstat` 验证请求是该应用 UID/GID 创建的单链接普通文件，拒绝符号链接、目录、设备文件、硬链接、意外权限和超出目录边界的路径；校验、认领和归档不得跟随应用可控链接。这样即使应用进程被利用，也不能伪造状态、篡改 `installed.json` 或替换运行时 JAR。

## 4. 实施步骤

### 4.1 实现交付物映射

下表列出每个阶段必须产生或调整的代码、配置和测试。新增文件名是实现约定，开发时不得以手工服务器操作替代这些交付物。

| 阶段 | 代码与配置交付物 | 自动化验证 |
| --- | --- | --- |
| A | `pom.xml`、`application.yaml`、Vite 版本注入、`Dockerfile`、release artifact build-context 及版本校验脚本 | tag 与 JAR、前端、OCI label、manifest 的版本和 SHA-256 一致 |
| B | 重命名后的 CI workflow、替代 `publish-container.yml` 的 Release workflow、签名/证明与 Release 产物脚本 | 创建 Release 前完成测试、签名验证和镜像 digest 烟测；创建后立即完成匿名下载复验，失败即删除该 Release 并使工作流失败 |
| C | 容器启动脚本、`Dockerfile`、`compose.yaml`、分权的 request/status/runtime mount 和受管镜像引用配置 | 基线启动、JAR 替换、镜像更新、未知状态、旧 runtime 冲突和容器侧越权写入 |
| D | `xianyu2-update-agent`、systemd `.path`/`.service`、受限代理配置、备份 hook 与 shell 测试 | 锁、签名/下载校验、三种更新类型、备份、Compose/健康检查失败恢复 |
| E | `SystemUpdateService`、更新 API 测试、`UpdateDialog.vue`、`AppLayout.vue`、请求状态协议 | 代理心跳、慢下载、重启恢复、多浏览器和 WebSocket 恢复 |
| F | `README.md`、`README.en.md`、首次安装/故障恢复文档、许可证元数据 | 未登录的全新安装和已有实例演练 |
| G | 后端单元测试、代理 shell 测试、Docker 集成测试、浏览器测试 | 测试 Release 的端到端门禁 |

### 阶段 A：版本、构建产物与容器输入

1. 以 tag `vX.Y.Z` 作为唯一正式发布版本来源；为 `main`/PR 的非发布构建定义 `0.0.0-dev+<commit>`，两者都通过同一个版本注入入口生成，移除 Dockerfile、Compose、Spring 与前端中的独立固定版本。
2. 让 Maven 写入可读取的 build-info/manifest，Vite 通过 `APP_VERSION` 注入版本；`/api/system/version`、侧栏、JAR 文件名、OCI label 和 Release manifest 必须一致。
3. 在 Release workflow 中先构建前端与后端 JAR 一次，并将该 JAR 放入独立的 `release-artifact` build context；不得依赖根构建上下文的 `target/`，因为它受 `.dockerignore` 排除。
4. 改造 Dockerfile，使 Release 镜像通过 Buildx named build context 复制该 artifact，而不是在镜像内重新打包 JAR；本地开发使用独立的显式构建路径，不得与 Release 产物混淆。
5. 在构建镜像前、镜像内和从最终镜像导出时三次比对 JAR SHA-256，新增发布校验：tag、Maven、前端、JAR 文件名、OCI label、manifest 与镜像内 JAR 任一不一致即失败。
6. 运行时指纹只覆盖会改变容器运行契约的输入：JRE 基线、Playwright 浏览器层、Dockerfile 安装的系统库、入口脚本及明确的运行时环境变量。它不得把普通 Java 业务依赖或项目版本变化误判成镜像更新；`jar` 仅在本 tag 的运行时指纹与上一正式 Release manifest 相同且没有宿主机契约变化时允许。

验收：以 `v2.0.8` 构建时，所有版本入口一致；从 Release 下载的 JAR 与最终镜像内 JAR SHA-256 相同。

### 阶段 B：CI 与正式 Release 工作流

1. 保留并整理 `.github/workflows/deploy-production.yml` 为 `main` / PR 的纯 CI：迁移检查、Maven 测试、镜像构建和健康烟测；名称与说明不再表达生产部署，CI 镜像只能使用 `ci-<commit>` 之类的不可发布标识。
2. 退役或替换 `.github/workflows/publish-container.yml`，禁止 `release: published` 后置发布和 `latest` 标签。
3. 新建 tag/受控 `workflow_dispatch` Release 工作流，严格按“测试 -> 构建唯一 JAR -> 推送版本镜像 -> 读取 digest -> 按 digest 烟测 -> 生成 JAR/host package/SHA-256/manifest/签名/证明 -> 创建并发布 GitHub Release”执行。tag 自动触发时，宿主机升级分类必须来自同一 tag 中已提交的不可变 `release-metadata/vX.Y.Z.json`，至少含 `releaseTag`、`hostContractRequired` 和不高于同 tag agent 的 `minimumAgentVersion`；人工类型还必须有非空的原因和步骤。工作流还必须读取上一正式 tag 中的 host package agent 版本：没有上一正式 tag 时，metadata 必须声明 `hostContractRequired=true`；有上一 tag 时，`minimumAgentVersion` 高于上一 agent 版本也必须声明 `hostContractRequired=true`。新增确定性的宿主机契约检测脚本：至少比较 `compose.yaml`、生产 Compose 覆盖、`deploy/update/`、安装脚本、容器用户、bind mount、端口、受管环境变量及 systemd/权限契约；脚本检测到的变更必须令 metadata 的 `hostContractRequired=true`，metadata 声明此值但脚本未发现变更可保守地按人工升级处理。缺失、tag 不匹配、agent 版本规则不满足或与检测结果冲突时失败。不得用 `workflow_dispatch` 的临时输入覆盖分类，因为 tag push 已可能先启动发布。开始前必须拒绝已存在同 tag 的 GitHub Release，防止重跑把不同产物混入同一发行版。
4. 镜像只推送版本 tag 和不可变 digest；Playwright 基础镜像继续以 Playwright 版本单独管理，并验证两者公开可拉取。
5. 创建 Release 前的任一步失败均不得创建正式 GitHub Release；已推送的不可变镜像只作为不可引用的构建产物，后续用全新 tag 重试。GitHub Release asset 只有创建后才可由匿名请求验证，因此在匿名下载、哈希和 Cosign 复验完成前，该 Release 只能视为待确认的公开资产；复验失败必须立即删除该 Release、令工作流失败，并同样用全新 tag 重试，不能把失败的 Release 转为后续更新来源。
6. `workflow_dispatch` 只接受存在的、受保护的 `vX.Y.Z` tag，且只可重跑该 tag 已固定的元数据，不能接受可改变更新类型或人工说明的临时输入；工作流校验 tag 解析后唯一指向 commit、该 commit 可从 `main` 到达、tag/metadata/manifest/镜像标签/证明中的 commit SHA 完全一致。Release tag 不可移动，正式发布前必须通过保护分支的 CI。
7. 创建 Release 前完成所有本地资产签名验证及 GHCR 匿名拉取验证。GitHub Release asset 在发布前不可能由匿名用户下载，因此在 `gh release create` 后立即用无凭据请求 Release API 和下载 JAR/manifest/bundle 复验；复验步骤不得向 `curl`、Docker 或资产下载命令传递 `GITHUB_TOKEN`、`GH_TOKEN`、认证 header 或现存 registry login。失败则删除该 Release、令工作流失败并要求用全新 tag 重新发布。不得把未通过该复验的 Release 作为可更新版本。

8. 所有 `uses:` 的 GitHub Actions（包括 GitHub 官方与第三方 action）必须固定到完整 commit SHA，并在注释中保留可读版本；依赖更新只可通过受审查的 PR 提交。Release workflow 还必须以 `actionlint` 和静态检查验证：不接受 tag 名称以外的 `workflow_dispatch` 输入、不把 token 注入匿名复验、也不把未固定的 action 或 `latest` 传给发布步骤。

发布前仓库配置（一次性人工前提）：为 `v*` 设置 GitHub tag ruleset，限制创建者并禁止移动、强推和删除 tag；`main` 分支保护要求本计划中的 CI 成功；Actions 工作流授予 `contents: write`、`packages: write`、`id-token: write` 和 `attestations: write`；两个 GHCR package 维持公开可拉取。仓库公开前还须审计完整 Git 历史、GitHub Actions 日志、Actions secrets/variables、Environments、Deploy keys、Webhooks 和已删除 workflow 的引用，确认不存在服务器地址、Tailscale/SSH 凭据、Token 或私人运行信息；发现历史凭据时先撤销并清理历史，再公开。Actions 只能校验 tag 指向 `main` 的 commit，不能替代仓库 ruleset 对 tag 创建权限的约束。

验收：普通 `main` 提交不改变服务器版本；Release 页面出现时，JAR、`SHA256SUMS.txt`、manifest、版本镜像和烟测结果已全部存在。

### 阶段 C：runtime JAR、Compose 与安装状态

1. 镜像保留只读基线 JAR（如 `/opt/xianyu2/app.jar`），新增启动脚本只读检查独立 runtime 目录；`release.env` 同时传入 `APP_IMAGE` 与 `APP_IMAGE_DIGEST`，只有 `runtime/app.jar` 的 SHA-256、fingerprint 和 `APP_IMAGE_DIGEST` 都同 `installed.json` 匹配时才运行它，否则运行镜像基线 JAR。
2. 按 3.2 的四目录权限契约使用受管 host bind mount：`runtime` 与 `update/status` 对容器为只读，只有 `update/request` 对应用可写；私有 `in-progress`、归档、锁和下载目录不挂载入容器。请求认领源和目标必须在同一文件系统；所有目录不与业务数据、日志或数据库 volume 混用。
3. `image` 更新必须先以候选 digest 预拉取镜像，确认 pull 成功后才原子写入 `release.env`；随后归档旧 runtime JAR、清空活动 runtime JAR，并由新镜像基线启动。候选镜像拉取、`release.env` 写入或容器启动前的 Compose pull 失败时，必须保留或恢复旧 `release.env` 与旧 runtime JAR，且不能把新状态写入 `installed.json`。一旦已发出 app 容器启动命令，代理无法可靠证明 Flyway 是否运行，必须保留失败状态与人工恢复信息，不得自动声称已恢复旧版本。健康检查成功才写入新的 `installed.json`。`jar` 更新仅在当前 digest 与 fingerprint 已匹配时替换 runtime JAR。这样运行时 JAR 不会在任何镜像更新后静默覆盖新的基线。
4. Compose 生产入口改为显式的受管 `/etc/xianyu2/release.env`，其中只含 `APP_IMAGE=ghcr.io/daki-l/xianyu2@sha256:...` 与同值的 `APP_IMAGE_DIGEST=sha256:...`；生产 `compose.yaml` 必须以 `${APP_IMAGE:?release.env is required}` 强制要求该值，不能回退到 `xianyu2:dev`、无 digest 的 tag 或其他开发镜像。代理仅原子写入该文件，绝不重写项目目录中含数据库密码和 JWT 的 `.env`。生产 Compose 命令固定同时加载 `.env` 与 `/etc/xianyu2/release.env`；本地构建移动到明确的开发 Compose 覆盖文件和开发 env 文件，移除生产配置中的 `build:` 和 `2.0.7` 默认镜像名。任何需要修改生产 Compose 文件、mount、端口或容器用户的发行版一律分类为 `host-package-manual-required`，不得伪装为 image 更新。
5. 首次安装先按 3.1 的信任引导验证固定版本的 host package、manifest、Cosign identity、tag commit 和 `cosign` 自身来源，再由安装脚本生成 `release.env`、3.2 中的目录和 `installed.json`，不能依赖用户 shell 中临时导出的 `APP_IMAGE`。host package 解包前先列出并校验其路径只在预期前缀内，拒绝绝对路径、`..`、符号链接、设备文件和意外顶层文件；使用不保留归属/权限的解包方式写入临时目录，再由 root 按白名单原子安装。它不得包含或覆盖现有 `.env`、数据库 volume、证书或用户数据。首次安装是宿主机引导步骤，不可能由尚未运行的管理后台触发。
6. 容器内应用只拥有 `request` 目录的最小写入权限，对 `status` 和 `runtime` 只读，不能挂载 Docker Socket。

验收：删除 runtime volume 后可由镜像基线启动；JAR 更新、镜像更新、状态文件丢失和旧 runtime JAR 遗留均有确定且安全的结果。

### 阶段 D：宿主机更新代理

1. 实现独立的 `xianyu2-update-agent` 及 systemd `.path`/`.service`，监听宿主机 `update/request/request.json`；其受限配置文件固定项目目录、Compose 文件、Release API、允许的镜像仓库、签名 identity、`/etc/xianyu2/release.env` 路径和备份 hook。Docker daemon 等同宿主机高权限，不能把 Docker group 误称为安全沙箱：agent 可由 root 运行，但 agent、unit、配置、Compose 文件、`.env` 和 `release.env` 必须为 root 所有且容器用户不可写，unit 以只读系统文件系统运行，仅能写入明确列出的 runtime/update 目录和 `/etc/xianyu2`。应用可控输入只限经 tag 校验的请求文件，绝不进入 shell、Compose 参数或配置路径。
2. 应用只可在 request 目录原子创建 `request.json`，代理用同文件系统的原子 rename 认领为私有 `in-progress/<taskId>.json`，并独占写入只读挂载中的 `status.json`、`installed.json` 与终态归档。应用和前端不得删除、重写或把活跃任务标记为失败；只有代理可完成或清理任务。整个实例只有一个待处理槽：一旦 `status.json` 表示某个 task 处于非终态，即使 request 已被认领而不存在，重复点击也必须返回同一 task；不同目标版本必须返回冲突，不能覆盖或另写 request。只有该 task 已终态，或代理按超时规则明确失败后，才能创建下一任务。
3. 通过 `flock`、任务 ID 和认领文件保证一个实例一次只执行一个更新，重复点击复用现有任务。每个状态必须带有阶段名、阶段开始时间、最后心跳和该阶段超时；代理启动时扫描未终态任务：锁仍被持有则不干预，锁未持有时也只能在最后心跳超过该阶段超时后标记为可重试失败。代理重启本身不得将仍在允许时限内的任务直接判为失败，也不得自动并行重放任务。若锁暂时不可得，代理不得认领或改写 request/status；Path unit 重新触发后只处理仍有效的唯一请求。每个命令、阶段和整笔任务都必须有上界，整笔任务上界不得大于 systemd `TimeoutStartSec`；unit 必须显式设置并测试 `StartLimitIntervalSec`/`StartLimitBurst`、`Restart=on-failure` 与重启间隔，使它们至少容纳一次“心跳仍有效”的恢复检查，避免 systemd 在心跳仍有效时进入 StartLimit 或杀死代理后让状态永久悬挂。
4. 拉取并验证 Release manifest、Cosign blob 签名、镜像签名和 provenance，使用 GitHub tag-ref API 将 Release `tag_name`（含 annotated tag 解引用）解析到 commit 后，与 manifest `releaseTag`/`commitSha` 比对；签名 certificate identity 必须严格匹配固定 workflow 路径、tag 与 OIDC issuer。验签后先拒绝未知 manifest schema；若已知 schema 声明 `minimumAgentVersion` 高于本机 agent，则普通 `jar`/`image` 任务必须停止且给出“先安装经验证 host package”的人工诊断，不能下载或重建容器。兼容 schema 的 `host-package-manual-required` 仍须在旧 agent 上写入 `MANUAL_REQUIRED` 与说明。再结合 `installed.json` 判定 `jar`、`image` 或 `host-package-manual-required`。未知状态不执行 JAR 覆盖。
5. JAR 下载到 runtime 同一文件系统的临时文件，校验签名、大小和 SHA-256 后再原子替换；在下载和校验成功前保留旧 JAR。
6. 镜像更新仅使用 manifest digest。代理先独立预拉取候选 digest，再原子更新不含密钥的 `release.env` 中 `APP_IMAGE` 与 `APP_IMAGE_DIGEST`，并执行受管的 app 重建；不自动更新 MySQL/Nginx 等其他服务。保存旧 `release.env` 与 runtime JAR 的受限临时副本：候选 pull、文件切换或 app 容器启动前的 Compose pull 失败时恢复旧运行输入；一旦已尝试启动新容器，代理不能可靠判定 Flyway 是否执行，必须停止自动恢复并提供明确的人工恢复诊断。
7. 当 manifest 的 `database.requiresBackup=true` 时，先执行可验证的备份 hook，并将备份文件路径、SHA-256、完成时间写入 task/status 元数据；hook 缺失、未输出可验证文件或失败均终止更新。Flyway 失败不尝试版本回退，状态中给出恢复和人工排障信息。
8. `host-package-manual-required` 必须是可完成的流程而非永久死路：后台只显示人工说明且不重启；manifest 含 host package 时，操作员先验证并安装同一 Release 的该 package，再完成 Release Notes 中的宿主机变更，并在主机上运行受限的显式确认命令（例如代理的 `--apply-manual-release vX.Y.Z`）。该命令复用相同的验签、digest、备份和健康检查，但只能由宿主机管理员调用，绝不能由 HTTP API 触发。若该 Release 同时升级 agent/systemd，人工步骤须先安装满足 `minimumAgentVersion` 的新 agent；旧 agent 仍必须能把兼容 schema 的人工提示写为 `MANUAL_REQUIRED`，不能因版本过低而把提示吞成普通失败。
9. 代理在每个长步骤写入心跳、阶段、百分比、开始/结束时间、目标版本与可诊断错误到 `status.json`；成功后才更新 `installed.json`。
10. 以 Docker health 与独立的 `/actuator/health` HTTP 探测联合判定成功，设定合理的总超时和单步超时；失败后保留日志、原镜像/JAR 位置和失败状态供人工恢复。应用容器已被重建且可能已执行 Flyway 时，禁止假定可以安全自动回退；状态必须明确报告服务未恢复的风险，而不是声称已回滚。

验收：哈希错误、错误平台、重复请求、下载超时、Compose 失败、Flyway 失败、健康检查失败和未知安装状态均不会破坏已保留的运行文件或备份，且后台可看到原因；已触发迁移的失败按“需要人工恢复”而非“自动回滚”处理。

### 阶段 E：后端 API、前端恢复与闲鱼连接

1. 后端版本检查只通过固定 Release API 获取正式 release tag 与 manifest asset ID，用于展示和创建请求；它不拥有 Cosign 私钥或验证工具，不应把未验签 manifest 作为执行依据。草稿、预发布、非法 tag、不可信 asset API URL、低于已安装版本的 tag 和与当前活跃任务冲突的目标版本必须拒绝或仅展示为不可执行提示；完整的 manifest/签名/identity/平台验证由代理在执行前完成。
2. 保留 `request.json`/`status.json` 接口，但按 3.2 分别配置可写请求路径和只读状态路径；将当前固定 10 分钟的过期清理改为只读代理心跳与阶段超时。慢速镜像/JAR 下载不能被误判为僵尸任务，应用端不得删除活跃请求。后端提交接口以 agent 的非终态状态为准，不以 request 文件是否存在作为唯一忙碌判断；写入后读取到旧终态 status 时，必须按 taskId 优先展示新 request，防止前端错误停止轮询。
3. `UpdateDialog` 在连接中断时继续有限退避轮询；只有代理终态失败才显示失败，防止用户在重启窗口重复提交。
4. `AppLayout` 为非发起者增加轻量恢复探测。服务重新可用且版本改变时刷新页面以取得新静态资源，并保留登录失效时的正常跳转。
5. 验证 Spring 启动后的闲鱼账户初始化、WebSocket 重连和失败诊断，不新增绕过平台风控的请求行为。
6. 提供仅由受保护宿主机配置控制的更新入口开关，首发时默认关闭或仅允许指定管理员；它只能限制 HTTP 发起更新，不得绕过代理的所有校验。受控实例完成首发验证后，再显式开启给全部管理员。

验收：更新发起者和普通已登录用户均能跨越应用重启恢复；慢速更新不会因 10 分钟清理而中断；闲鱼连接恢复或给出可诊断错误；入口开关关闭时不存在可绕过的更新请求 API。

### 阶段 F：文档、安装与发布验收

1. 重写中英文 README 的安装、升级和镜像说明：使用固定版本/digest 和受管 `release.env`，不描述 `latest` 或 GitHub Actions 服务器自动部署。
2. 增加宿主机 systemd 代理、Cosign 前置依赖及其验证、3.2 的目录权限、数据库备份 hook、签名 host package 的人工安装和显式确认命令，以及故障恢复说明；首次安装脚本必须要求显式 `vX.Y.Z` 参数，并拒绝缺失、`latest`、草稿、预发布或未签名 Release。明确公开渠道无需 GitHub Token，也不应执行默认分支中的安装脚本。
3. 将项目许可证、Maven 元数据、OCI 标签和中英文 README 同步为 MIT；第三方组件仍适用其各自许可证。
4. 对全新安装实例和已有实例分别演练 JAR 更新、镜像更新和人工主机升级提示。

验收：未登录用户可按文档完成安装、检查和更新；文档不要求 GitHub Token、不引用已移除的服务器部署脚本，也不建议 `latest`。

### 阶段 G：自动化测试与发布门禁

1. 后端测试覆盖版本解析、manifest schema/asset ID 验证、权限校验、重复任务、状态文件损坏、心跳和超时；验证应用端永不删除活跃任务。
2. 代理测试覆盖锁与认领恢复、阶段心跳未超时的重启恢复、超时任务的单次失败处理、Cosign identity/provenance 拒绝、错误哈希、资产重定向拒绝、三种更新类型、未知 installed state、`release.env` 原子更新、人工确认入口、host package 哈希/签名拒绝、容器用户无法写受保护宿主机文件，以及 Compose 失败恢复。另须覆盖：任务已认领且 request 不存在时的重复提交不会创建第二任务、不同 tag 的并发提交被拒绝、manifest 同名资产重复/缺失被拒绝、bootstrap 与 agent 采用相同的资产选择规则、未知 manifest schema 在字段解析前被阻断、旧 agent 对兼容的人工升级正确写入 `MANUAL_REQUIRED`、普通更新不得借 `minimumAgentVersion` 绕过人工升级，以及 systemd 在有效心跳、阶段超时、总超时和 StartLimit 四种情况下的实际重启行为。请求文件的符号链接、硬链接、非普通文件、错误 UID/GID、异常权限和认领期间替换也必须被拒绝。
3. Docker 集成测试覆盖基线初始化、JAR 更新、镜像更新后清除 runtime JAR、旧 runtime 冲突、Flyway 正向迁移、备份 hook 缺失/失败、健康检查失败，以及容器无法写入 status/runtime 或代理私有目录。
4. Release workflow 集成测试覆盖 named build context、镜像内与 Release JAR SHA-256 一致、tag/commit 约束、签名验证、运行时指纹分类、宿主机契约检测与 metadata 冲突、首个 Release/提高 `minimumAgentVersion` 时强制人工分类、已存在 Release 拒绝和未完成烟测时不创建 Release；并覆盖匿名复验失败时删除刚创建的 Release、匿名复验中没有认证可用、安装入口拒绝缺失或 `latest` tag，以及所有 action 已固定到完整 commit SHA。
5. 浏览器测试覆盖更新期间的断线、恢复、版本刷新和多客户端场景；后端验证重启后的闲鱼 WebSocket 恢复。
6. 以受控正式首发 Release 演练完整流程，再显式开启正式后台更新入口；任何正式发布使用全新 semver tag。不得发布后替换 tag 或 Release asset 充当“测试修订”。

验收：现有和全新实例均从同一个公开 Release 安全更新，`main` 推送不改变任何安装实例。

## 5. 影响范围

| 范围 | 影响 |
| --- | --- |
| 业务模块 | 商品、消息、风控、订单、买家等业务功能不改动 |
| 数据库 | 更新机制本身不新增业务表；Release 可携带新的正向 Flyway 迁移，更新前须备份 |
| 后端 | 版本、manifest、请求状态、心跳、安装状态和错误语义调整 |
| 前端 | 更新弹窗、全局恢复探测、版本刷新和状态展示调整 |
| Docker/Compose | Dockerfile 输入、启动脚本、runtime/update volume 与固定 digest 调整 |
| 宿主机 | 新增 systemd agent、受限 Compose 权限、分权更新目录、备份命令和人工宿主机升级入口 |
| CI/CD | `main` 校验与 tag Release 分离；彻底不含 GitHub Actions 服务器直连部署 |
| 文档和许可 | 安装升级说明、MIT 声明和第三方许可说明调整 |

## 6. 非目标与约束

- 不提供管理员选择历史版本或一键回退；不自动回退 Flyway。
- 不允许应用容器挂载 Docker Socket，也不在应用、前端、日志或 `.env` 保存 GitHub Token。
- 不以 `latest` 作为拉取或更新依据。
- 不自动执行 `apt`、Docker Engine、操作系统或 systemd 等宿主机级升级；此类 Release 使用 `host-package-manual-required`。
- 不自动改写生产 `compose.yaml`、更新代理配置、systemd unit 或目录权限；任何依赖这些改动的 Release 使用 `host-package-manual-required`，由宿主机管理员完成后显式确认应用该版本。
- 不恢复任何特定服务器、Tailscale、SSH 或 GitHub Actions 自动服务器部署流程。

## 7. 首发切换与证据门槛

首个 `v2.0.8` 是正式的不可变公开发行，不存在可以发布后再覆盖的“试运行 tag”。因此，端到端演练分为发布前的 CI/本地隔离测试与发布后的受控安装验证：前者必须在创建 tag 前完成；后者在受控测试实例上使用该正式 Release 进行，并且在验证通过前保持管理后台更新入口关闭或仅对受控管理员开放。若正式 Release 发布后发现产品更新缺陷，只能以更高的新 semver 修复，不能移动 tag、替换 Release asset 或声称可回退 Flyway。

创建第一个 tag 前必须确认以下前置事项均已留有可复核证据：

1. `main` 已合入所有实现、`release-metadata/v2.0.8.json` 已随同代码通过 CI，且 tag 只指向该通过 CI 的 commit。
2. GitHub ruleset、`main` 分支保护、Actions 权限和两个 GHCR Package 的匿名拉取已实际配置并由未登录会话验证；仓库公开审计及凭据撤销记录已完成。Release workflow 的每个 action 已固定为完整 commit SHA，且以 `actionlint`/静态检查留有通过证据。
3. Linux 测试环境实际运行 Maven、代理 shell 测试、host package 测试、Docker/Compose 烟测和浏览器恢复测试；本机缺少的 Java、Docker、`jq` 或 `actionlint` 不能以“未执行”替代通过证据。首发的 metadata 必须通过“无上一 tag 时强制 `hostContractRequired=true`”和 agent 版本规则的验证。
4. 首次安装文档在未登录用户、全新目录和最小权限环境下完成一次验证，包含 Cosign 安装校验、host package 安全解包、agent `--check`、初始镜像健康检查与 `installed.json` 基线写入。
5. 对 JAR、镜像、需要备份的 Flyway 更新和 `host-package-manual-required` 各保留一次成功与一次失败演练记录；失败记录必须证明旧运行输入/备份未被静默破坏，并写明 Flyway 失败后的人工恢复责任。

Release workflow、CI 和测试实例的日志链接、Release 的 tag/commit、镜像 digest、JAR SHA-256、manifest SHA-256、Cosign 验证输出及安装状态摘要组成该版本的发布证据包。证据不完整时不得创建正式 tag；发布后若匿名复验失败，立即删除未验证的 Release 并以全新 tag 重新发布，绝不复用或移动原 tag。
