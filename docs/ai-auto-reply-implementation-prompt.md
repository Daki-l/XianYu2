# AI 自动回复可靠性与会话展示改造——本地 AI 执行提示词

你现在位于本项目代码仓库中。你的任务是**直接修改代码并完成必要验证**，不是继续输出审查报告，也不是重新设计一套系统。

本次修改基于已经完成的事故分析，目标是在**当前单实例、个人维护项目**的前提下，以尽量小的改动解决已经真实发生的问题。

---

## 一、执行原则

1. **先读现有代码，再修改。**
2. 以当前仓库真实实现为准；如果本文中的类名、方法名、字段名与当前代码略有差异，按实际代码对应关系处理。
3. 优先：
   - 小范围修改；
   - 复用现有数据表和状态；
   - 保持现有 API 和调用关系；
   - 易维护、易验证；
   - 解决已经真实发生的问题。
4. 不要主动引入：
   - Redis；
   - MQ；
   - 分布式锁；
   - 微服务；
   - 新任务框架；
   - fencing token；
   - 为极低概率情况设计的大量新状态；
   - 与本次目标无关的大规模重构。
5. 不要求理论上的严格 exactly-once。当前产品规则接受极端崩溃窗口中的“发送结果不确定”，此类情况禁止自动重发，必要时人工核对。
6. 不要为了边界理论完整性增加大量测试、数据库字段或兜底机制。
7. 不修改与本次问题无关的业务行为，不做大面积格式化。
8. 不通过吞异常、返回伪成功、修改测试预期或弱化断言来隐藏问题。
9. 不要只告诉我“建议怎么改”。**直接完成代码修改。**
10. 如果某个问题必须依赖真实平台历史 JSON 或外部接口数据才能确认，不要猜。先完成能够确定的修改，并在最终结果中列出需要人工确认的最少事项。

---

## 二、本次必须解决的问题

### 1. AI 错误文本被发送给买家

当前已确认的问题链路是：

```text
AIServiceImpl 捕获异常
-> 将异常转换成类似“AI回复生成失败：504 ...”的非空字符串
-> AIReplyStrategy 将非空字符串视为成功回复
-> AutoReplyServiceImpl 调用 sendMessage
-> 错误文本被真正发送给买家
-> 本地又保存成 LOCAL_AI / content_type=888
```

必须改为：

```text
AI 504 / 超时 / 网络异常 / 配置不可用 / 空回答
-> 明确失败
-> 自动回复任务 state=-1
-> 记录适当的错误信息
-> 不调用闲鱼发送接口
-> 不保存 LOCAL_AI/888
```

禁止继续使用“错误字符串也是 replyContent”的语义。

不要通过：

```text
replyContent.startsWith("AI回复生成失败")
```

之类的字符串判断补丁解决。

应在 AI 结果语义上区分成功和失败。

重点检查并修改实际相关代码，例如：

- `AIServiceImpl`
- `RAGReplyResult` 或当前 AI 返回模型
- `AIReplyStrategy`
- `AutoReplyServiceImpl`

成功内容才允许进入真正的发送路径。

---

## 三、修复同一个自动回复任务重复执行

当前事故的直接原因已经确认：

```text
state=0
-> 到期后 claim
-> state=2
-> AI 调用超过 lease 时间
-> lease_expire_time 过期
-> recoverDueTasks 每秒扫描重新发现 state=2
-> 同一任务再次被 claim
-> 多个 AI 调用并发运行
```

### 必须修改 `findDue`

运行期间扫描器只应该找：

```sql
state = 0
AND scheduled_time <= NOW(...)
```

以及现有确实仍有意义的待执行条件。

移除：

```sql
state = 2
AND lease_expire_time < NOW(...)
```

这种运行期重新领取正在执行任务的逻辑。

### 必须修改 claim 条件

claim 应只允许待执行任务进入执行状态，例如：

```sql
UPDATE ...
SET state = 2,
    ...
WHERE id = ?
  AND state = 0
```

或当前项目中等价的原子条件。

如果 UPDATE 返回 0：

```text
说明任务已经被其他正常路径领取或状态已经变化
-> 当前执行路径直接退出
-> 不调用 AI
```

不要通过简单增加 `leaseSeconds` 来解决重复执行。

当前只需要保证**单实例项目正常运行时不会明显重复执行同一个任务**，不要扩展成多实例分布式任务设计。

重点检查：

- `AutoReplyDelayServiceImpl`
- `recoverDueTasks`
- `dispatchTask`
- `executeClaimedTask`
- `XianyuGoodsAutoReplyRecordMapper`
- 实际 claim SQL

如果当前同时存在“内存延迟执行”和“数据库扫描执行”两个正常入口，确保它们最终都通过同一个 `state=0 -> state=2` 条件 claim 即可，不需要做复杂并发证明。

---

## 四、保留 15 秒等待与连续消息取消

产品规则：

```text
买家发消息
-> 创建 state=0 自动回复任务
-> scheduled_time = 当前时间 + 可配置等待时间（当前约 15 秒）

等待期间买家再次发消息
-> 取消旧的、仍在等待中的任务
-> 为最新消息重新创建等待任务
-> 重新计时
```

保持这个行为。

旧任务取消继续使用现有 `state=-2` 或当前等价机制。

优先只取消仍处于等待阶段的旧任务。

不要为了 15 秒边界附近的毫秒级 cancel/claim 竞争设计复杂状态机或大量额外测试。

---

## 五、AI 120 秒必须是真实调用超时

检查当前：

- Spring AI 配置；
- HTTP Client；
- 模型客户端；
- Future / CompletableFuture；
- 相关超时配置。

要求：

```text
AI 请求最多约 120 秒
-> 超时后调用失败
-> task state=-1
-> 不发送消息
```

关键是确认 120 秒是否真正作用于**底层 AI/HTTP 请求**。

如果当前只是：

```java
future.get(120, TimeUnit.SECONDS)
```

但底层 HTTP 请求仍继续运行，则需要把真实网络/模型客户端 timeout 配正确。

不要为了超时增加复杂多层兜底。

如果当前真实 HTTP 客户端超时已经能够终止请求，保持简单实现即可。

如果代码中存在非常明显的：

```text
业务层已经判超时
-> 旧 AI 调用后来返回
-> 仍继续 sendMessage
```

路径，则用现有结构中的最小修改阻止。

---

## 六、应用重启后取消所有未完成 AI 回复

应用启动后，将遗留：

```text
state=0
state=2
```

的自动回复任务统一更新为：

```text
state=-2
```

原因可记录为：

```text
服务重启，AI回复已取消
```

要求：

- 不重新进入待执行队列；
- 不恢复 AI 请求；
- 不重新发送；
- 不自动重试。

优先增加一个简单清晰的 Mapper 更新方法完成。

不要设计重启恢复任务系统。

---

## 七、后台 AI 状态不能写入真实聊天消息表

`xianyu_chat_message` 只能保存：

- 平台真实存在的聊天消息；
- 后台真正发送成功的消息；
- AI 真正发送成功的消息。

以下内容不是闲鱼真实聊天消息：

```text
商家（AI回复倒计时）
商家（AI回复中...）
商家（AI回复失败）
商家（AI回复已取消）
```

禁止把这些状态插入 `xianyu_chat_message`。

继续使用现有 `xianyu_goods_auto_reply_record` 作为状态数据来源。

现有状态含义继续复用：

```text
state=0   等待
state=2   AI生成中
state=1   成功
state=-1  失败
state=-2  取消
```

不为本次任务额外设计复杂状态机。

---

## 八、把 AI 状态合并到后台会话时间线

检查当前：

```text
/api/msg/context
ChatMessageServiceImpl
MsgDTO
workspace.vue
ContextDialog.vue
```

或实际对应实现。

目标是后台上下文能够同时返回：

```text
真实聊天消息
+
AI 虚拟状态项
```

可以复用或增加类似字段：

```text
timelineType
autoReplyRecordId
scheduledTime
statusReason
replyOrigin
```

实际命名按项目现有 DTO 风格处理。

建议状态：

```text
真实消息  -> MESSAGE
state=0   -> AI_PENDING
state=2   -> AI_PROCESSING
state=-1  -> AI_FAILED
state=-2  -> AI_CANCELLED
state=1   -> 不生成状态项
```

成功后只显示真正发送成功的 AI 聊天消息。

### 查询规则

保持真实消息原有分页和排序。

仅在首次上下文加载（例如 `offset=0`）时，将当前会话需要展示的 AI 状态记录一起返回。

AI 状态项不要占用真实消息分页数量。

### 分页

先检查前端当前 offset 的计算方式。

如果当前代码使用：

```text
offset += 返回数组长度
```

而数组中加入了 AI 状态项，则做一个**最小兼容修改**，让 load-more 仍按真实消息数量推进。

不要为了这一点重写整个分页协议。

### 倒计时和刷新

`AI_PENDING`：

```text
scheduledTime - 浏览器当前时间
```

由前端本地每秒计算倒计时。

不要每秒请求后端，也不要每秒写数据库。

当当前会话存在：

```text
AI_PENDING
AI_PROCESSING
```

时，可以按现有设计约每 2 秒刷新一次上下文。

没有活动 AI 任务时保持现有刷新行为。

---

## 九、区分 AI / 后台 / App 三种商家回复

后台会话必须显示：

```text
商家（AI回复）
商家（后台回复）
商家（App回复）
```

根据现有：

```text
message_source
reply_origin
sender_user_id
```

等字段实现。

目标语义：

```text
message_source=LOCAL_AI
或 reply_origin=AI
-> 商家（AI回复）
```

```text
后台人工真正发送的消息
-> reply_origin=BACKEND
-> 商家（后台回复）
```

```text
message_source=PLATFORM
且 sender 是本账号
且没有 AI/BACKEND 来源
-> 商家（App回复）
```

买家消息继续显示买家昵称。

重点检查：

- `SentMessageSaveServiceImpl`
- 后台人工发送保存路径
- AI 成功保存路径
- `ChatMessagePersistenceService`
- DTO 转换
- Vue 展示

---

## 十、修复本地消息与平台历史的重复展示

现有流程可能是：

```text
后台 AI / 人工回复
-> 本地保存 LOCAL_AI / LOCAL

后续平台历史同步
-> 同一条消息再次以 PLATFORM 保存
```

因此会形成双来源副本。

继续基于现有：

```text
dedupe_fingerprint
duplicate_status
duplicate_of_id
message_source
reply_origin
```

去重机制做**最小改进**。

目标：

1. 同一条真实商家消息最终只展示一次。
2. 不要因为消息正文相同，就把用户真的连续发送的多条相同文本全部合并。
3. 如果存在多个相同内容候选，可结合真实发送时间进行最近邻的一对一匹配。
4. 一个本地候选只能匹配一个平台消息。
5. `/api/msg/context` 最终只返回应该展示的主体，例如继续过滤 `duplicate_status != 0` 的副本。
6. 如果本地 AI/后台消息最终由 PLATFORM 记录作为展示主体，要保留或传播：
   - `AI`
   - `BACKEND`

   来源信息，不能最终误显示成 `App回复`。

不要把本次任务扩大为全新的消息去重系统。

---

## 十一、修复平台历史消息时间

检查：

- `syncContextMessages`
- `PlatformHistoryMessageParser`
- `complete_msg`
- 平台历史原始 JSON
- 当前 `message_time` 的赋值逻辑

当前已经观察到历史同步后多条旧消息的 `message_time` 集中在同步时刻，这会造成：

```text
旧消息
-> 看起来像刚刚发送
-> 被排到会话末尾
```

必须找到平台消息本身真实的发送时间字段。

可能存在的字段名包括：

```text
createdAt
createTime
sendTime
messageTime
timestamp
```

但不要根据名字猜测。

必须根据当前代码、已有日志、原始 `complete_msg` 或真实平台 JSON 确认。

如果当前仓库中无法获得足够样本：

- 不要伪造字段；
- 完成其他修改；
- 最终只列出这一项需要人工提供真实 JSON 样本确认。

禁止继续把“本次同步时间”作为历史消息真实发送时间。

---

## 十二、错误信息展示

AI 失败时允许后台显示简洁原因。

优先复用：

```text
last_error_code
last_error_message
```

或当前已有字段。

不要将明显敏感内容直接返回 Vue，例如：

- API Key；
- Authorization；
- 完整请求体；
- 完整异常堆栈。

只做简单合理的错误信息整理，不建立复杂错误分类系统。

---

## 十三、旧生产重复数据

不要在应用启动时自动删除旧聊天记录。

不要让正常应用代码隐式清理历史生产数据。

如果仓库已经存在数据修复脚本机制，可以单独准备一个**可人工执行、执行前可备份/回滚**的旧 504 重复数据清理脚本。

如果没有现成安全脚本机制，本次可以只在最终结果中给出需要单独处理的说明，不要为了历史数据清理扩大主代码改动。

---

## 十四、重点修改位置

优先检查以下实际文件/模块：

```text
src/main/java/com/xianyu2/event/chatMessageEvent/lister/ChatMessageEventAutoReplyListener.java
src/main/java/com/xianyu2/service/reply/AutoReplyDelayServiceImpl.java
src/main/java/com/xianyu2/mapper/XianyuGoodsAutoReplyRecordMapper.java
src/main/java/com/xianyu2/service/reply/AIReplyStrategy.java
src/main/java/com/xianyu2/service/impl/AIServiceImpl.java
src/main/java/com/xianyu2/service/impl/AutoReplyServiceImpl.java
src/main/java/com/xianyu2/service/impl/ChatMessageServiceImpl.java
src/main/java/com/xianyu2/service/ChatMessagePersistenceService.java
src/main/java/com/xianyu2/service/impl/SentMessageSaveServiceImpl.java
vue-code/src/views/messages/workspace.vue
vue-code/src/views/messages/components/ContextDialog.vue
```

以及实际关联的：

- DTO；
- Mapper XML / SQL；
- Entity；
- 配置文件；
- 已有测试。

不要机械地修改所有文件。

先根据调用链确认哪些文件真的需要改，只修改必要位置。

---

## 十五、必要验证范围

本次验证以核心真实事故为主，不追求穷举。

### 必须验证

#### 1. AI 504 / 异常

确认：

```text
任务失败
不调用闲鱼发送
不保存 LOCAL_AI/888
后台可以看到失败状态
```

#### 2. AI 超时

确认：

```text
任务失败
不发送
state=2 不会因 lease 过期重新被扫描领取
同一任务不会出现原事故中的反复执行
```

#### 3. AI 正常成功

确认：

```text
只发送一次
保存一条真实 AI 回复
任务 state=1
AI 状态项消失
显示为 商家（AI回复）
```

#### 4. 买家等待期再次发消息

确认：

```text
旧等待任务取消
新任务重新计时
```

#### 5. 应用启动

确认：

```text
遗留 state=0/state=2
-> state=-2
-> 不恢复 AI
-> 不发送
```

#### 6. 商家来源

至少确认：

```text
AI发送     -> 商家（AI回复）
后台发送   -> 商家（后台回复）
App发送    -> 商家（App回复）
```

#### 7. 历史同步

确认：

```text
本地 + PLATFORM 不会明显双份展示
来源不会明显丢失
消息排序不再使用错误的同步时间
```

如果确实修改了 `/api/msg/context` 分页，再增加一个正常加载更多验证即可。

### 不要求

不要为了本次修改额外编写大量：

- 毫秒级 cancel/claim 竞争测试；
- 多实例测试；
- 极端 JVM 崩溃测试；
- 网络半开连接故障注入；
- 大量状态组合测试；
- 与真实事故无直接关系的边界测试。

---

## 十六、执行方式

请按以下顺序自行完成，不需要每一步都向我询问：

```text
1. 阅读相关代码和已有测试
2. 确认真实调用链
3. 修改后端自动回复失败语义
4. 修改任务扫描和 claim
5. 增加启动取消遗留任务
6. 实现后台 AI 时间线状态
7. 修复 AI / BACKEND / APP 来源展示
8. 修复双来源去重和平台历史时间
9. 更新必要前端
10. 运行与本次修改直接相关的测试和静态/编译检查
11. 修复发现的直接回归
12. 输出最终实施结果
```

如果某一步发现分析文档与真实代码不一致：

- 以真实代码为准；
- 判断是否影响本次核心目标；
- 选择改动最小、符合产品规则的实现；
- 不要因为局部差异重新进行整个架构设计。

---

## 十七、完成后的输出格式

完成代码修改和验证后，只输出以下内容：

### 1. 修改结果

简要说明本次核心问题是否已经解决。

### 2. 修改文件

列出实际修改过的文件，并说明每个文件的核心改动。

### 3. 核心行为变化

至少说明：

```text
504/异常现在如何处理
重复 claim 如何被阻止
120 秒超时如何实现
重启遗留任务如何处理
AI 状态如何展示
AI/BACKEND/APP 如何区分
历史重复和时间如何处理
```

### 4. 验证结果

列出实际执行过的：

- 测试；
- 编译；
- lint；
- 静态检查；
- 其他与本次修改直接相关的验证。

明确写出通过或失败。

### 5. 未完成事项

只列真正因为：

- 缺少真实平台 JSON；
- 缺少运行环境；
- 缺少外部接口；
- 或明确超出本次范围

而无法完成的事项。

不要把理论上的低概率风险扩写成大量待办。

---

## 最终目标

完成以后，系统应满足：

```text
买家发消息
-> 等待约 15 秒
-> AI 开始生成

AI 成功
-> 真实发送一次
-> 保存真实 AI 消息
-> 标记 商家（AI回复）

AI 504 / 异常 / 超时
-> 不向买家发送任何错误文本
-> 不保存假的 AI 消息
-> 后台显示失败

AI 执行超过原 lease 时间
-> 不再被 recoverDueTasks 重复领取

服务重启
-> 未完成任务全部取消
-> 不恢复、不自动重发

后台会话
-> 可以看到 AI 等待 / 生成 / 失败 / 取消状态
-> 这些状态不是闲鱼真实消息

商家消息
-> 正确区分 AI回复 / 后台回复 / App回复

历史同步
-> 不再因为错误同步时间导致旧消息跑到末尾
-> 本地与平台双来源消息不再明显重复展示
```

**现在直接开始检查当前仓库并实施修改，不要再输出一轮方案审查。**
