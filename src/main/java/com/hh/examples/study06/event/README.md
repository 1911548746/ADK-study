# study06.event

## 概念

`Event` 是 ADK 运行过程的事实记录。

它不只是“模型回复文本”，还可能记录：

- 用户消息。
- 模型文本输出。
- 模型发起的工具调用。
- 工具执行结果。
- `Session.state` 的变更。
- Artifact 变更。
- 多 Agent 转交。
- 错误、结束原因、usage metadata。

因此，ADK 的事件流更接近一条可审计的运行轨迹。

## LangGraph 对照

在 LangGraph 中，你可以通过 `stream(..., stream_mode="updates")`、tool call、ToolMessage、state update 等机制观察运行过程。

概念对照：

```text
ADK Event                         ≈ LangGraph stream update / message / state update 的统一记录
event.functionCalls()             ≈ AIMessage.tool_calls
event.functionResponses()         ≈ ToolMessage
event.actions().stateDelta()      ≈ node/tool 对 graph state 的 update
event.finalResponse()             ≈ 最终可展示给用户的 AI message
Session.events                    ≈ 当前线程 / session 的运行历史
```

ADK 的差异是：它把这些运行事实统一封装到 `Event` 对象中，而不是只返回最终消息。

## ADK 实现

本示例使用：

- `runner.runAsync(...)` 获取 `Flowable<Event>`。
- `event.functionCalls()` 观察模型想调用哪些工具。
- `event.functionResponses()` 观察工具执行结果。
- `event.actions().stateDelta()` 观察 state 变更。
- `event.finalResponse()` 观察最终回复。
- `sessionService().listEvents(...)` 查看 Session 中持久记录的事件。

本示例只讲 Event，不展开 Streaming、Sub-Agent 或 Workflow。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study06/event/EventExample.java
src/main/java/com/hh/examples/study06/event/EventTools.java
```

模型使用当前项目中的 LangChain4j 适配写法：

```java
.model(OpenAILangChainModelFactory.createChatModel())
```

## 运行流程

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study06.event.EventExample
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用。

## 关键源码分析

消费事件流：

```java
runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
        .blockingForEach(EventExample::printEventSignals);
```

观察工具调用：

```java
event.functionCalls()
```

观察工具返回：

```java
event.functionResponses()
```

观察状态变更：

```java
event.actions().stateDelta()
```

判断最终回复：

```java
event.finalResponse()
```

查看 Session 中已保存的事件：

```java
runner.sessionService()
        .listEvents(runner.appName(), session.userId(), session.id())
        .blockingGet();
```

## 可观察结果

运行后重点观察：

- 一次用户请求可能产生多个 Event。
- 模型决定调用工具时，某个 Event 会带有 `functionCalls`。
- 工具执行完成后，某个 Event 会带有 `functionResponses`。
- 工具写入 state 时，某个 Event 会带有 `stateDelta`。
- 最终可展示给用户的 Event 满足 `finalResponse() == true`。
- `Session.events` 保存的是运行事实，不只是聊天文本。

## 思考题

1. 为什么 ADK 不只返回最终文本，而要暴露完整 Event 流？
2. 哪些 Event 信息适合给前端展示，哪些只适合日志和审计？
3. 如果要做 Agent 评估，为什么 tool call 和 stateDelta 比最终文本更关键？
