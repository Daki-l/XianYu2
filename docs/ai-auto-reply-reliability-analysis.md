# AI 自动回复可靠性与会话展示改造分析

> 文档用途：供不了解本仓库、也不能访问生产数据库的审查者理解问题、验证方案，并提出实现层面的质疑。本文区分了已由代码和生产数据确认的事实、设计决策及仍需在实现时核实的事项。

## 1. 目标和边界

本次改造只处理闲鱼 AI 自动回复的三个问题：

1. AI 发生 504、超时或其他异常时，错误文本被当作回复发给买家。
2. 一条买家消息对应的自动回复任务会被重复执行，导致同一错误文本多次发给买家。
3. 系统会话无法区分 AI 回复、后台页面回复和闲鱼 App 回复，也无法仅在后台展示 AI 等待、生成中和失败状态。

确定的产品规则：

- 买家发消息后，先等待可配置的 15 秒。等待期内买家继续发言时，旧等待任务取消，重新计时。
- 15 秒到期后才请求 AI。单次 AI 请求最多允许 120 秒。
- AI 失败、超时或服务重启后，绝不向买家发送错误文本，也不自动重试或恢复回复。
- 应用重启时，所有未完成自动回复任务直接取消，不重新调用 AI。
- AI 倒计时、生成中、失败、已取消仅出现在本系统会话时间线，不是闲鱼消息，不能写入真实聊天消息表。
- 对话仍需完整展示买家和商家双方消息；商家消息必须标明其来源。

非目标：

- 不引入 Redis、MQ、分布式任务系统或多实例协调。
- 不实现失败后的自动重试；以后如需重试，只允许人工操作。
- 不删除现有生产消息记录；旧重复数据另行备份并通过受控脚本处理。

当前生产部署观察到只有一个应用容器 `xianyu2-app-1`。以下方案以单应用实例为前提；如果未来运行多个应用副本，必须重新设计任务锁与恢复策略。

## 2. 现有组件和数据模型

### 2.1 主要代码路径

```text
闲鱼 WebSocket 买家消息
  -> ChatMessageEventAutoReplyListener
  -> AutoReplyDelayServiceImpl.submitDelayTask
  -> xianyu_goods_auto_reply_record（state=0，scheduled_time=当前时间+延时）
  -> 调度线程 / 每秒恢复扫描
  -> AutoReplyDelayServiceImpl.dispatchTask / executeClaimedTask
  -> AutoReplyServiceImpl.executeAutoReply
  -> AIReplyStrategy
  -> AIServiceImpl.chatByRAGWithFixedMaterial
  -> WebSocketService.sendMessage
  -> SentMessageSaveService.saveAiAssistantReply
  -> xianyu_chat_message
```

相关文件：

- `src/main/java/com/xianyu2/event/chatMessageEvent/lister/ChatMessageEventAutoReplyListener.java`
- `src/main/java/com/xianyu2/service/reply/AutoReplyDelayServiceImpl.java`
- `src/main/java/com/xianyu2/mapper/XianyuGoodsAutoReplyRecordMapper.java`
- `src/main/java/com/xianyu2/service/reply/AIReplyStrategy.java`
- `src/main/java/com/xianyu2/service/impl/AIServiceImpl.java`
- `src/main/java/com/xianyu2/service/impl/AutoReplyServiceImpl.java`
- `src/main/java/com/xianyu2/service/impl/ChatMessageServiceImpl.java`
- `src/main/java/com/xianyu2/service/ChatMessagePersistenceService.java`
- `vue-code/src/views/messages/workspace.vue`
- `vue-code/src/views/messages/components/ContextDialog.vue`

### 2.2 真实聊天消息表

表：`xianyu_chat_message`

本次相关字段：

| 字段 | 现有含义 |
|---|---|
| `id` | 本地消息 ID |
| `xianyu_account_id`、`s_id` | 闲鱼账号和会话 ID |
| `pnm_id` | 平台或本地生成的消息标识 |
| `content_type` | 平台/本地消息类型；AI 本地回复目前为 `888`，后台手动回复为 `999` |
| `msg_content` | 文本内容 |
| `sender_user_id` | 消息发送者闲鱼用户 ID |
| `message_time` | 用于排序的毫秒时间戳 |
| `message_source` | `PLATFORM`、`LOCAL_AI`、`LOCAL` 等来源 |
| `dedupe_fingerprint`、`duplicate_status`、`duplicate_of_id` | 跨来源去重审计字段 |
| `reply_origin` | 已用于标注 `AI` 的回复来源 |

`xianyu_chat_message` 只能保存真实存在于闲鱼或已实际发出的商家消息。它不应保存“AI 回复中”或“AI 回复失败”之类的后台状态。

### 2.3 自动回复任务表

表：`xianyu_goods_auto_reply_record`

本次相关字段：

| 字段 | 现有含义 |
|---|---|
| `id` | 自动回复任务 ID |
| `xianyu_account_id`、`s_id`、`pnm_id` | 任务所属账号、会话、触发消息 |
| `buyer_message` | 被送给 AI 的买家消息 |
| `reply_content` | 当前最终回复内容 |
| `state` | 当前使用：`0` 待执行、`1` 成功、`2` 已领取/执行中、`-1` 失败、`-2` 取消 |
| `scheduled_time` | 延迟结束、应开始 AI 生成的时间 |
| `attempt_count` | 被领取次数 |
| `lease_owner`、`lease_expire_time` | 当前任务租约信息 |
| `last_error_code`、`last_error_message` | 已存在但当前自动回复失败路径未充分写入的错误字段 |

该表已经足以承载后台会话中的 AI 状态卡片，不需要新建“假聊天消息”。

## 3. 已确认的生产事实

以下数据来自生产主机 `e445` 的 MySQL `xianyu2` 库和 `xianyu2-app-1` 容器检查。为了避免泄露买家身份，文档不记录用户名和完整消息文本。

### 3.1 错误确实被发给了买家

会话 `66568531091@goofish` 的自动回复记录 `id=18`：

| 字段 | 值 |
|---|---|
| 创建时间 | `2026-09-09 19:55:59.107` |
| 计划执行时间 | `2026-09-09 19:56:14.105` |
| 最终状态 | `1`（当前代码认为成功） |
| 领取次数 | `15` |
| `reply_content` | `AI回复生成失败：504 - error code: 504` |

同一会话中至少有 7 条 `LOCAL_AI / content_type=888` 的 504 文本，保存时间分布在 `20:31:31`、`20:54:27`、`20:56:29`、`20:58:28`。这证明错误文本不是仅写入日志，而是经过了本地“AI 已发送消息”保存路径。

代码证据：

1. `AIServiceImpl` 的多个 `catch` 块将异常转换成非空字符串 `AI回复生成失败：` + 异常信息，而不是返回失败结果。
2. `AIReplyStrategy.execute` 只检查 `replyContent` 是否非空；非空即构造成功的 `ReplyResult`。
3. `AutoReplyServiceImpl.executeAutoReply` 遍历成功 `ReplyResult`，对文本直接调用 `webSocketService.sendMessage`；返回成功后调用 `sentMessageSaveService.saveAiAssistantReply`，并将任务更新为 `state=1`。

结论：504 在现有语义中是“回复正文”，不是“生成失败”。这是错误文本发送给买家的直接根因。

### 3.2 用户没有新发消息，任务仍被重复领取

`AutoReplyDelayServiceImpl.recoverDueTasks` 使用 `@Scheduled(fixedDelay = 1000)` 每秒执行一次。其查询 `XianyuGoodsAutoReplyRecordMapper.findDue` 当前条件是：

```sql
(state = 0 AND scheduled_time <= NOW(3)
 AND (next_retry_time IS NULL OR next_retry_time <= NOW(3)))
OR (state = 2 AND lease_expire_time < NOW(3))
```

领取 SQL 将任务写为 `state=2`，租约设置为当前时间加 120 秒，并将 `attempt_count` 加一：

```sql
UPDATE xianyu_goods_auto_reply_record
SET state = 2,
    lease_owner = :workerId,
    lease_expire_time = DATE_ADD(NOW(3), INTERVAL :leaseSeconds SECOND),
    attempt_count = attempt_count + 1
WHERE id = :id
  AND (state = 0 OR (state = 2 AND lease_expire_time < NOW(3)))
```

因此现有实际流程为：

```text
买家仅触发一次任务
  -> 任务被领取，state=2，租约 120 秒
  -> AI HTTP 调用迟迟未返回，任务行没有状态变化
  -> 120 秒后租约过期
  -> 每秒恢复扫描认为原工作线程已经死亡
  -> 同一任务被再次领取，并启动另一条 AI 调用线程
  -> 多个旧线程和新线程陆续返回 504
  -> 每条 504 均被当作正常回复发送
```

任务被再次领取不依赖买家再次发消息。它完全由每秒恢复扫描和过期租约触发。生产记录 `attempt_count=15` 是这一结论的直接证据。

### 3.3 闲鱼 App 消息为什么会在系统中出现

工作台首次选中一个会话时，`workspace.vue` 会调用 `syncContextMessages` 获取完整平台历史。后端 `ChatMessageServiceImpl.syncContextMessages`：

1. 调用 `webSocketService.listConversationHistory`。
2. 使用 `PlatformHistoryMessageParser` 解析结果。
3. 将每条历史消息保存到 `xianyu_chat_message`。

历史解析器目前不会排除本账号发送者。这本身是合理的：完整会话需要显示买家和商家双方消息。生产日志也确认，平台回流中 `senderUserId` 等于本账号时，自动回复监听器会正确跳过，不会把商家自己发的消息再次当作买家消息触发自动回复。

但是“完整历史”会与系统本地保存的 `LOCAL_AI/888`、`LOCAL/999` 形成双来源副本：

```text
后台 AI 或后台人工发送
  -> 本地先保存 LOCAL_AI 或 LOCAL
  -> 后续完整历史同步
  -> 平台又返回同一条商家消息，保存为 PLATFORM / content_type=1
```

现有跨来源去重按“账号、会话、内容指纹、发送者”寻找候选；只有候选恰好一条才会合并。504 文本被重复发送后，本地存在多条内容完全相同的候选，因此没有唯一候选，去重主动放弃，副本全部保留。

生产会话中，同步写入的一批平台历史记录的 `message_time` 全集中于同一秒 `2026-09-09 21:22:54`，而不是其真实发送时间。这会使历史消息看起来像刚刚重新发出，并排到会话末尾。当前解析器允许从外层历史模型取 `createdAt/createTime/sendTime/messageTime/timestamp`；实现时必须检查原始 `complete_msg`，确认真正的消息发送时间字段，禁止把“本次历史拉取时间”当作消息发送时间。

## 4. 推荐的目标设计

### 4.1 任务状态机

不新增自动重试，不新增运行期“租约恢复”。沿用现有状态值即可：

| 状态 | 名称 | 对买家 | 后台会话展示 |
|---|---|---|---|
| `0` | 等待倒计时 | 不发送 | `商家（AI回复倒计时） N 秒` |
| `2` | AI 生成中 | 不发送 | `商家（AI回复中...）` |
| `1` | 成功 | 发送真实 AI 文本 | 仅显示真实 `商家（AI回复）` 消息 |
| `-1` | AI 失败/超时 | 不发送 | `商家（AI回复失败）` 与安全错误原因 |
| `-2` | 已取消 | 不发送 | `商家（AI回复已取消）` 与取消原因 |

状态转换：

```text
买家新消息
  -> 创建 state=0、scheduled_time=当前时间+15秒
  -> 到期后原子 claim
  -> state=2，开始 AI 调用（最大 120 秒）
  -> AI 成功且闲鱼发送成功
  -> 同步保存一条真实 LOCAL_AI/888 聊天消息
  -> state=1

AI 504、AI 超时、AI 空回答、AI 配置不可用
  -> state=-1，记录错误码和错误原因
  -> 不发送消息，不保存 LOCAL_AI/888

应用启动
  -> 将遗留 state=0、state=2 的记录统一改为 state=-2
  -> 原因：服务重启，AI回复已取消
  -> 不进入待执行队列，不调用 AI
```

关键决策：移除 `findDue` 中 `state=2 AND lease_expire_time < NOW(3)` 的运行期领取条件。每秒扫描只领取 `state=0` 且 `scheduled_time` 已到的任务。这样单实例应用正常运行时，AI 调用无论多慢，都不会同时启动第二个执行者。

AI 的 120 秒必须是实际 HTTP/模型客户端超时，而不是仅在业务线程外层等待 120 秒后丢弃结果。否则底层请求仍可能继续运行并在稍后发送。实现时应检查当前 Spring AI 客户端和 HTTP 客户端配置，确保超时能够取消或终止实际请求。

### 4.2 后台会话时间线，不制造假聊天消息

`/api/msg/context` 当前返回 `List<MsgDTO>`。建议扩展为兼容的统一时间线项：真实消息仍保留现有字段，新增字段例如：

| 字段 | 用途 |
|---|---|
| `timelineType` | `MESSAGE`、`AI_PENDING`、`AI_PROCESSING`、`AI_FAILED`、`AI_CANCELLED` |
| `autoReplyRecordId` | 状态项稳定 ID，前端用作 key |
| `scheduledTime` | 前端计算倒计时 |
| `statusReason` | 失败或取消原因，只在后台显示 |
| `replyOrigin` | `AI`、`BACKEND`、`APP` 等来源 |

后端上下文查询逻辑：

1. 先查询原有真实消息，保持现有分页、去重和排序。
2. 当 `offset=0` 时，再查询当前会话的 `state IN (0, 2, -1, -2)` 自动回复记录，映射为时间线状态项并合并排序。
3. 状态项不计入真实消息分页数，避免“加载更多”重复或漏消息。
4. `state=1` 不产生状态项；成功后只显示真实发送后的 `LOCAL_AI/888` 消息。

前端展示逻辑：

- 倒计时由 `scheduledTime - 当前浏览器时间` 每秒计算。不要每秒请求接口或更新数据库。
- 当前选中会话存在 `AI_PENDING` 或 `AI_PROCESSING` 项时，每 2 秒刷新一次上下文；无活动任务时保持当前刷新行为。
- 状态项使用稳定 key，如 `task-<autoReplyRecordId>`；任务成功或失败后原状态项自然被新返回的数据替换。
- 失败原因只展示在后台 UI，进行长度限制和 HTML 转义，避免把上游响应中的敏感内容直接渲染。

### 4.3 商家消息来源标签

不要从历史解析阶段过滤本账号消息；应在保存和展示阶段保留来源。

| 数据条件 | 标题 |
|---|---|
| `message_source=LOCAL_AI` 或 `reply_origin=AI` | `商家（AI回复）` |
| `message_source=LOCAL` 且后台人工回复，或 `reply_origin=BACKEND` | `商家（后台回复）` |
| `message_source=PLATFORM`、发送者是本账号、没有回复来源标记 | `商家（App回复）` |
| 发送者不是本账号 | 买家昵称 |

当前 AI 本地消息已能设定 `reply_origin=AI`。后台人工消息需在保存时设为 `BACKEND`；跨来源去重将本地消息标记为重复、保留平台消息为展示主体时，也必须把 `AI` 或 `BACKEND` 传播到平台主体，否则平台回流会被错误显示为 `App回复`。

### 4.4 去重与排序修复

本次状态化改造会停止再生成多条相同的 504 假回复，但不能依赖这一点解决所有双来源重复。实施时必须：

1. 检查真实平台历史原始 JSON，找出消息自身的发送时间；禁止使用同步批次的时间戳。
2. 对同一账号、会话、商家发送者、内容指纹的本地与平台消息做一对一匹配。
3. 若有多个候选，按真实发送时间的最近邻匹配，并确保一个候选最多匹配一次；不能仅因内容相同将多个合法“好的”消息合并。
4. 数据库仍可保留副本作审计，但 `/api/msg/context` 只能返回 `duplicate_status=0` 的展示主体。
5. 为现有生产 504 重复记录准备独立、可回滚的数据修复脚本。脚本执行前备份，不在应用启动时隐式删除消息。

## 5. 需要修改的代码位置

| 模块 | 修改责任 |
|---|---|
| `AIServiceImpl`、`RAGReplyResult` | 为 AI 生成定义成功/失败语义；异常不得填入可发送的 `replyContent` |
| `AIReplyStrategy` | 仅处理真正生成成功的 AI 内容；失败返回 `ReplyResult.fail()` 或等价错误结果 |
| `AutoReplyServiceImpl` | 失败时写任务错误，不调用发送和本地已发送消息保存；成功路径同步保存真实消息后再完成任务 |
| `AutoReplyDelayServiceImpl` | 保留 15 秒延迟和 `state=0` 恢复扫描；移除运行期对 `state=2` 的重领；增加启动取消遗留任务 |
| `XianyuGoodsAutoReplyRecordMapper` | 修改 `findDue`；增加设置错误、取消遗留任务、按会话查询状态任务的方法 |
| `ChatMessageServiceImpl`、DTO | 返回真实消息与 AI 状态项组成的时间线，且不破坏现有消息分页 |
| `SentMessageSaveServiceImpl` | 后台人工回复设置 `reply_origin=BACKEND`；AI 成功保存路径必须避免异步可见性竞态 |
| `ChatMessagePersistenceService` | 将 AI/后台来源可靠传播到平台回流的展示主体；改善双来源一对一去重 |
| `workspace.vue`、`ContextDialog.vue` | 渲染时间线状态项、倒计时、来源标题与活动任务刷新 |

## 6. 验收标准

### 必须自动化验证

1. AI 调用阻塞超过 120 秒：同一任务只被领取一次，`attempt_count=1`，未向买家发送任何文本，任务显示失败。
2. AI 返回 504：任务为失败，错误原因仅在后台上下文中显示，不生成 `LOCAL_AI/888`，不调用闲鱼发送接口。
3. 买家消息后 15 秒内：会话显示正确倒计时；买家继续发言时旧倒计时取消并重新开始。
4. AI 正常成功：等待状态变为生成中，发送成功后状态项消失，仅留一条 `商家（AI回复）` 真实消息。
5. 应用重启：遗留 `state=0`、`state=2` 记录变为已取消；重启后没有 AI 调用、没有闲鱼发送。
6. 后台页面手动发消息、闲鱼 App 发消息、AI 发消息分别显示 `商家（后台回复）`、`商家（App回复）`、`商家（AI回复）`。
7. AI 状态项不会写入 `xianyu_chat_message`，不会进入平台历史同步、自动回复监听器或去重流程。

### 上线前人工核对

1. 检查 AI 客户端的 120 秒是否为真实网络超时，而非仅业务层计时。
2. 检查单容器部署假设；若存在多个应用副本，不得直接使用本文的“运行期不恢复 state=2”策略。
3. 备份 `xianyu_chat_message` 和 `xianyu_goods_auto_reply_record`，审查旧 504 重复记录的数据修复 SQL。
4. 用真实闲鱼测试会话验证一次成功、一次 504、一次应用重启。

## 7. 残余风险和明确取舍

- 本方案刻意选择“服务重启后不重发”，优先避免骚扰买家。代价是重启期间正在生成的回复会丢失，后台会明确显示已取消。
- 外部闲鱼发送调用无法天然做到严格的 exactly-once：若闲鱼已收到消息但应用在记录成功前崩溃，系统可能只能知道“结果不确定”。由于产品规则禁止重启自动重发，应优先标记为人工核对，而不是尝试再发。
- 平台历史 JSON 的真实消息时间字段必须通过样本核实；没有该字段时，应宁可不按同步时间重排历史消息，也不能伪造为刚刚发送。
