# study000 MyBatis-Plus 持久化 CLI Agent

## 概念

本示例把前面学过的 Session、Memory、Event 和完整聊天记录放到同一个命令行 Agent 中观察。

这四类数据不是一回事：

- `Session`：ADK Runner 续跑所需的运行上下文。
- `Event`：ADK 每次执行产生的事实轨迹，包括模型回复、tool call、stateDelta、compaction event。
- `Memory`：跨 Session 的长期记忆，给 Agent 个性化检索使用。
- `Conversation / Message`：业务留档和前端展示用的完整聊天记录，不参与压缩。

## LangGraph 对照

在 LangGraph 中：

- `checkpointer` 负责保存图运行状态。
- `messages` 是每轮发给模型的上下文，可滑动窗口或总结压缩。
- 业务侧 `conversation/messages` 通常另存一套完整留档。
- 长期记忆一般放在外部 Store 或向量库中。

在 ADK Java 中：

- `BaseSessionService` 对应 Runner 的 Session 持久化能力。
- `BaseMemoryService` 对应长期 Memory 存储和检索能力。
- `Event` 是 Runner 执行过程中的统一轨迹对象。
- `EventsCompactionConfig` 通过追加 compaction event 的方式压缩模型上下文。

## ADK 实现

本示例不使用 `InMemoryRunner`，而是通过 `Runner.builder()` 注入：

- `MySqlSessionService`
- `MySqlMemoryService`
- `InMemoryArtifactService`

Agent 注册了 `LoadMemoryTool`。当模型需要长期记忆时，会通过该工具调用 `BaseMemoryService.searchMemory(...)`。

Agent 还注册了两个用于演示上下文管理第一层的工具：

- `read_text_file`：读取本地文本文件。若结果超过 50KB，完整内容写入 `.persistent-cli/tool-results`，FunctionResponse 只保留 2KB 预览和 `resultId`。
- `read_externalized_result`：根据 `resultId`、`offset`、`limit` 分片读取已经外部化的大工具结果。该工具最多返回 16KB，不会再次嵌套外部化。

Agent 还注册了一个用于演示 Snip 机制的工具：

- `snip_messages`：由模型主动传入 eventIds 和 reason，将这些 Event 从后续 Runner 上下文中过滤掉。

Snip 不会删除 `session_events` 和 `messages` 中的原始记录。它只是把模型选择的 Event 标记为 `snipped=true`，后续 `MySqlSessionService.loadEvents(...)` 返回给 Runner 前会过滤这些 Event。

为了让模型能像 Claude Code 一样看到可 Snip 的消息 ID，本示例在读取 Session events 给 Runner 时，会给 user-role 内容临时注入 `[event_id:...]` 标签。这个标签只存在于运行时上下文，不会写回 `session_events.event_json`。

Memory 写入不是 Runner 自动完成的。本示例在每轮对话结束后用 `MemoryWritePolicy` 判断用户输入是否值得长期保存，然后调用：

```java
runner.memoryService()
        .addSessionToMemory(updatedSession)
        .blockingAwait();
```

## 完整代码

核心代码位置：

- `cli/PersistentCliApplication`：命令行交互入口。
- `config/PersistentCliAgentConfig`：Spring Bean 配置，创建 Agent、App、Runner。
- `config/Study000SchemaInitializer`：MySQL 建表初始化。
- `service/MySqlSessionService`：MySQL 版 `BaseSessionService`。
- `service/MySqlMemoryService`：MySQL 版 `BaseMemoryService`。
- `service/ConversationArchiveService`：完整聊天记录留档。
- `service/ToolResultExternalizationService`：大工具结果外部化，避免超大 FunctionResponse 直接进入上下文。
- `tools/ContextManagedTools`：演示 `read_text_file` 和 `read_externalized_result`。
- `support/PersistentCliJson`：JSON 工具。
- `support/PersistentCliProperties`：示例常量。
- `cli/PersistentCliMain`：推荐的普通 main 启动入口。
- `PersistentCliAgentTest`：`@SpringBootTest` 启动入口，用于观察 Bean 注入，不推荐作为交互式控制台首选入口。

## 运行流程

先在 MySQL 中创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS persistentCli
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

数据库连接配置在 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/persistentCli?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: root
```

推荐在 IDE 中运行普通 main：

```text
src/main/java/com/hh/examples/study000/persistentCli/cli/PersistentCliMain.java
```

如果只想观察 `@SpringBootTest` 方式，也可以运行：

```text
src/test/java/com/hh/examples/study000/persistentCli/cli/PersistentCliAgentTest.java
```

注意：部分 IDE 的 JUnit 控制台不会把键盘输入转发给 `System.in`，因此交互式命令行更推荐使用 `PersistentCliMain`。

## 命令

命令统一使用 `/` 前缀：

```text
/new                  新建 Session
/resume <sessionId>   恢复 Session
/delete <sessionId>   删除 Session 及完整聊天记录
/sessions             查看 Session 列表
/events               查看数据库中的全部 ADK Events
/current events       查看 Runner 当前会使用的上下文 Events
/snips                查看当前 Session 的 Snip 记录
/state                查看当前 Session State
/memory <query>       检索长期 Memory
/help                 查看帮助
/exit                 退出
```

普通文本输入会发送给 Agent。

## 数据库表

表名不加 study000 前缀：

- `sessions`：ADK Session 元信息和普通 session state。
- `session_events`：ADK Event JSON 和常用索引字段。
- `memories`：长期记忆。
- `conversations`：业务会话。
- `messages`：完整聊天记录。

## 关键源码分析

`MySqlSessionService.appendEvent(...)` 是最关键的方法。

Runner 每产生一个 Event，就会调用 SessionService 保存它。这个方法中做了三件事：

1. 读取 `event.actions().stateDelta()`。
2. 把当前 Session 的 state 快照写回 `sessions.state_json`。
3. 把完整 Event JSON 写入 `session_events`。

本示例为了保持持久化层简单，没有单独实现 `app:` / `user:` 跨 Session state 表。
如果运行中出现这些前缀的 state key，它们也只会作为当前 Session state 保存在 `sessions.state_json`。
`temp:` state 会被视为临时状态，不写入数据库。

`messages` 表不来自 ADK Event，而来自业务侧的 `ConversationArchiveService`。这能保证即使 ADK 做 event compaction，业务聊天记录也不会被压缩或丢失。

Snip 不会删除原始 Event。它只更新 `session_events.snipped`、`snipped_at`、`snip_event_id` 和 `snip_reason`。Snip 工具调用自身产生的 FunctionResponse event 会继续保留在历史中，作为“这之前的部分内容已被清理”的边界痕迹。

`MySqlMemoryService.addSessionToMemory(...)` 只抽取用户侧、且符合长期记忆策略的文本。它会用 hash 去重，避免每次调用都重复写入同一条事实。

## 可观察结果

可以按下面步骤观察：

1. 输入：`请记住我叫小明，我喜欢 LangGraph`
2. 执行：`/memory 小明`
3. 执行：`/events`
4. 执行：`/current events`
5. 执行：`/sessions`
6. 退出后重新运行测试方法。
7. 执行：`/resume <sessionId>`
8. 输入：`我叫什么？`

随着多轮对话增加，`session_events` 中会出现 compaction event；但 `messages` 仍然保留完整聊天记录。

可以用下面步骤观察大工具结果外部化：

1. 准备一个超过 50KB 的文本日志文件。
2. 输入：`请读取这个日志并分析：D:\path\to\large.log`
3. 观察 `session_events` 中的 FunctionResponse：其中应包含 `externalized=true`、`preview` 和 `resultId`，而不是完整日志。
4. 如果智能体没有继续调用 `read_externalized_result`，CLI 会打印上下文完整性提示。
5. 查看 `.persistent-cli/tool-results`，可以看到完整结果文件和 metadata JSON。

可以用下面步骤观察 Snip：

1. 多轮对话后执行 `/events`，复制若干旧 Event 的 `eventId`。
2. 直接告诉智能体：`请把不再相关的旧事件 snip 掉，reason 写明摘要。`
3. 执行 `/snips`，确认出现 snip 边界记录。
4. 再执行 `/events`，可以看到这些 Event 仍在数据库中，但 `snipped=true`。
5. 执行 `/current events`，确认这些 snipped Event 已经不在 Runner 当前上下文中。

## 思考题

1. 如果 memory 数据量很大，`LIKE` 检索应该如何升级为向量检索？
2. Memory 写入策略应该由规则判断、LLM 判断，还是二者结合？
3. 企业项目中，Session 持久化和完整聊天记录是否应该放在同一个库中？
