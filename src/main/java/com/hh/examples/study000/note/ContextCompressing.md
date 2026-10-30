# Context Compressing 笔记

## 1. 要解决什么问题

对话 Agent 如果一直把全部历史 `Event` 放进模型上下文，会遇到几个问题：

- 上下文越来越长，请求变慢、费用升高。
- 工具结果过大时，单轮就可能撑爆上下文。
- 长期运行的 `InMemoryRunner` 会让内存中的 Session / Event 越积越多。
- 模型上下文窗口有限，历史过长后会超限。

因此真实项目里通常需要把数据分层：

| 数据 | 作用 | 是否参与压缩 |
|---|---|---|
| `session_events` | ADK 运行轨迹，给 Runner 恢复上下文 | 可以压缩、过滤、Snip |
| `messages` | 业务留档、前端展示 | 不压缩、不删除 |
| `memory` | 长期记忆，按需检索 | 不直接塞入全部上下文 |
| 工具大结果 | 文件、日志、网页等大内容 | 外部化，只保留预览 |

## 2. ADK 内置 Events Compaction

当前 demo 中配置在 `App` 上：

```java
App.builder()
        .name(PersistentCliProperties.APP_NAME)
        .rootAgent(persistentCliAgent)
        .eventsCompactionConfig(EventsCompactionConfig.builder()
                .compactionInterval(10)
                .overlapSize(1)
                .summarizer(new SimpleEventSummarizer(OpenAILangChainModelFactory.createChatModel()))
                .build())
        .build();
```

`eventsCompactionConfig` 是 App / Session 级别的上下文压缩配置。  
如果 rootAgent 是 `SequentialAgent`，里面有多个 `LlmAgent`，这些子 Agent 产生的 events 会进入同一条 Session event 流，默认不会按 Agent 分开压缩。

## 3. Sliding Window Compaction

`compactionInterval + overlapSize` 对应 ADK Java 的 sliding-window compaction。

推荐中小型对话 Agent 起点：

```java
.compactionInterval(12)
.overlapSize(2)
```

含义：

- `compactionInterval`：累计约多少个 invocation 后触发压缩。
- `overlapSize`：压缩时保留最近多少个 invocation 的原始 events。

注意：这里不是简单按 Event 条数计算，而是按 `invocationId` 计数。

例如同一轮中：

```text
UserMessage
FunctionCall
FunctionResponse
AIMessage
```

如果它们属于同一个 `invocationId`，在 sliding-window compaction 里通常算 1 个 invocation，不是 4 条 event。

因此正常情况下，`overlapSize` 不会只保留半截工具调用链，例如只留下 `FunctionCall` 和 `FunctionResponse`。

### 3.1 Sliding Window 的摘要累积风险

实测 ADK Java 的 sliding-window 路径时，新的压缩不会把已有 compaction summary 再压成一条更大的 summary。

例如：

```text
第一次压缩：旧 Event 区间 -> 摘要 A
第二次压缩：后续 Event 区间 + 上一段 overlap 原始 Event -> 摘要 B
第三次压缩：再后续 Event 区间 + 上一段 overlap 原始 Event -> 摘要 C

后续模型请求：摘要 A + 摘要 B + 摘要 C + 最近原始 Event + 最新用户输入
```

这不是“摘要 B 包含摘要 A”的递归摘要，而是多个历史区间的**分段摘要列表**。

优点：

- 已生成的摘要不会被后续压缩反复改写，信息损失更容易观察。
- 每条摘要覆盖的历史区间清晰，压缩节奏由 invocation 数稳定控制。

风险：

- 摘要 A、B、C 会持续累积并一起进入模型上下文。
- 会话足够长时，即使原始 Event 已被压缩，摘要 Event 的总 token 仍会持续增长。
- 多个分段摘要之间可能产生重复、表述不一致或早期结论过时的问题。

因此 sliding-window 更适合中等长度、对话和工具输出长度较稳定的会话。超长会话应考虑 token-based 模式、额外的二级摘要、Session rollover，或将稳定事实写入 memory 后开启新 Session。

## 4. Token-based Compaction：Token Threshold 与 Event Retention

`EventsCompactionConfig` 还有：

```java
.tokenThreshold(...)
.eventRetentionSize(...)
```

这两个参数对应 ADK 的 token-based tail-retention compaction。它不按固定轮次压缩，而是在 Session 上下文的 token 规模达到阈值后压缩较早历史。

- `tokenThreshold`：触发压缩的上下文 token 阈值。它应明显小于模型的上下文窗口，为 system prompt、当前用户输入、工具结果和模型输出预留空间。
- `eventRetentionSize`：压缩后原样保留在上下文末尾的 Event 数量。

`eventRetentionSize` 的单位是 **Event**，不是对话轮次。例如一轮工具调用可能产生：

```text
UserMessage
FunctionCall
FunctionResponse
AIMessage
```

这已经占用 4 条 Event。工具密集场景中，`eventRetentionSize(30)` 可能只对应约 7 到 10 轮交互，因此不宜设置得过小。

token-based 的典型过程如下：

```text
已有摘要 A + 之后新增的 100 条原始 Event
        |
        | 上下文 token 超过 tokenThreshold
        v
摘要 A + 较早的 70 条原始 Event -> summarizer -> 新摘要 B
最近 30 条原始 Event                         -> 原样保留

下一次模型请求：新摘要 B + 最近 30 条原始 Event + 最新用户输入
```

这里的“100 条”和“70 条”只是示例。真实触发时机由 token 数决定，不是固定新增多少条 Event；并且上一条 compaction Event 的摘要内容会作为下一次摘要输入的一部分，被合并进新摘要，而不是与新摘要持续并列累积。

推荐值可以从这里开始：

```java
.tokenThreshold(12000)
.eventRetentionSize(30)
```

工具调用较多时可以放宽：

```java
.tokenThreshold(16000)
.eventRetentionSize(50)
```

但是在当前 ADK Java Runner 的自动 compaction 路径中，如果已经配置了：

```java
compactionInterval + overlapSize
```

就会走 sliding-window compaction。不要期待它和 `tokenThreshold + eventRetentionSize` 共同叠加生效。

因此建议二选一：

- 想按轮次稳定压缩：使用 `compactionInterval + overlapSize`。
- 想按上下文长度控制总 token：使用 `tokenThreshold + eventRetentionSize`。

不要同时配置两套触发参数并期待它们取长补短；应明确选择一种 compactor 策略。

## 5. FunctionCall 与 FunctionResponse 不要拆散

工具调用上下文通常是一组协议链：

```text
UserMessage
FunctionCall
FunctionResponse
AIMessage
```

不建议只删除 `FunctionResponse`，留下：

```text
UserMessage
FunctionCall
AIMessage
```

这样会产生几个风险：

- 模型看到有工具调用，但看不到工具返回。
- OpenAI / LangChain4j 这类 tool calling 协议可能要求 tool call 必须有对应 tool response。
- 后续模型可能重复调用工具。
- compaction summarizer 看到的是残缺历史，摘要质量会变差。

更好的做法：

- 保留 `FunctionResponse`，但把大结果替换成摘要、预览、`resultId`。
- 如果要 Snip，就把 `FunctionCall + FunctionResponse` 成对处理。
- 如果要压缩，就把整段工具调用链压成一条 compaction event。

## 6. 大工具结果外部化

对于文件读取、日志分析、网页搜索这类工具，最危险的是单个 `FunctionResponse` 过大。

当前 demo 的策略是：

```text
工具结果 > 50KB
完整内容写入 .persistent-cli/tool-results
FunctionResponse 只保留 2KB 预览 + resultId
模型需要全文时再调用 read_externalized_result 分片读取
```

这样做的好处：

- 不破坏 FunctionCall / FunctionResponse 配对。
- 避免一个工具结果直接撑爆上下文。
- 模型明确知道自己看到的是预览，不是完整内容。

## 7. 硬滑动窗口

如果只想给 Runner 最近 N 条 events，可以在 `MySqlSessionService.loadEvents(...)` 中做过滤。

伪代码：

```java
List<Event> events = loadAllEvents();

int windowSize = 30;
if (events.size() > windowSize) {
    events = events.subList(events.size() - windowSize, events.size());
}
```

但这种方式风险较大：

- 旧 events 直接消失，模型可能丢失关键事实。
- 如果按 event 条数裁剪，容易拆散工具调用链。
- 没有摘要兜底，模型不知道历史发生过什么。

更好的方式是组合使用：

```text
最近 events 原样保留
较早 events 压缩成 summary
长期事实写入 memory
完整聊天记录留在 messages
大工具结果外部化
```

## 8. Snip 与 Compaction 的区别

| 能力 | 谁触发 | 行为 |
|---|---|---|
| Compaction | ADK 自动触发 | 把一段旧 events 摘要成 compaction event |
| Snip | 模型主动调用工具 | 把模型认为不再需要的旧 events 移出后续上下文 |
| 硬滑动窗口 | 代码规则 | 只返回最近 N 条 events |
| 外部化 | 工具返回阶段 | 大结果落磁盘，event 里只留预览 |

Compaction 更像“自动总结”。  
Snip 更像“模型主动整理上下文”。  
外部化更像“阻止超大内容进入上下文”。

## 9. 当前 demo 的推荐配置

中小型对话 Agent 可以先用：

```java
.eventsCompactionConfig(EventsCompactionConfig.builder()
        .compactionInterval(12)
        .overlapSize(2)
        .summarizer(new SimpleEventSummarizer(OpenAILangChainModelFactory.createChatModel()))
        .build())
```

后续根据现象调整：

| 现象 | 调整 |
|---|---|
| 上下文增长太快、回复变慢 | 降低 `compactionInterval` |
| 模型忘记最近细节 | 提高 `overlapSize` |
| 摘要成本太高 | 提高 `compactionInterval` 或换更便宜 summarizer |
| 工具结果太大 | 优先做工具结果外部化 |
| 长期事实丢失 | 写入 memory，而不是依赖 event 历史 |
| 会话很长，compaction summary 也不断增加 | 切换 token-based，或加入二级摘要 / Session rollover |

## 10. 最重要的原则

不要把所有历史都塞进模型上下文。

更合理的结构是：

```text
短期上下文：最近 events
中期上下文：sliding-window 下可能是多个分段 summary；token-based 下更接近一个滚动 summary
长期记忆：memory
完整留档：messages
大内容：artifact / externalized result
```

上下文管理不是单个参数能解决的问题，而是一套分层策略。
