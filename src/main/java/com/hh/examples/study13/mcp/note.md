# MCP Tool 挂载位置对比

## 背景

MCP Tool 有两种常见挂载方式：

```text
方式一：挂到 ADK Agent 层

LlmAgent
  -> McpToolset
  -> MCP Server


方式二：挂到模型适配层

ChatModel / ChatClient
  -> SyncMcpToolCallbackProvider
  -> McpSyncClient
  -> MCP Server
```

二者都能让模型调用 MCP Server 暴露的工具，但它们属于不同抽象层，适用场景也不同。

## 方式一：ADK Agent 层挂载

ADK 原生 MCP 集成通常写成：

```java
McpToolset toolset = new McpToolset(serverParameters);

LlmAgent agent = LlmAgent.builder()
        .name("mcp_agent")
        .model(OpenAILangChainModelFactory.createChatModel())
        .instruction("你可以使用 MCP Server 暴露的工具。")
        .tools(toolset)
        .build();
```

此时 MCP 是 ADK Agent 的 `Toolset`。

调用链是：

```text
User
  -> ADK Runner
  -> LlmAgent
  -> McpToolset
  -> MCP Client Transport
  -> MCP Server
```

### 特点

- MCP 工具属于 ADK 的工具体系。
- `Runner` 能在 `Event` 中观察到工具调用。
- 可以通过 `event.functionCalls()` 和 `event.functionResponses()` 验证调用过程。
- 能更自然地参与 ADK 的 Session、State、Memory、Callbacks、Multi-Agent 编排。
- 更适合本项目这种以 ADK 为主线的 Agent 应用。

### 推荐场景

- 应用核心是 ADK `Runner + Agent`。
- 希望工具调用进入 ADK Event 流，便于调试、审计和可视化。
- 后续要结合 Multi-Agent、Workflow Agent、Session State 或 Tool Callback。
- 希望 MCP 和 `FunctionTool`、`LoadMemoryTool` 等 ADK Tool 处在同一个工具层。

## 方式二：ChatModel 层挂载

Spring AI MCP 集成常见写法是：

```java
McpSyncClient mcpClient = ...;

SyncMcpToolCallbackProvider provider =
        new SyncMcpToolCallbackProvider(mcpClient);

ChatResponse response = chatModel.call(new Prompt(
        userMessage,
        ChatOptions.builder()
                .toolCallbacks(provider.getToolCallbacks())
                .build()));
```

或者通过 `ChatClient`：

```java
String content = chatClient.prompt(userMessage)
        .toolCallbacks(new SyncMcpToolCallbackProvider(mcpClient))
        .call()
        .content();
```

此时 MCP Tool 是 Spring AI `ChatModel` 的 tool callback，而不是 ADK `LlmAgent` 的 tool。

调用链是：

```text
User
  -> ChatClient / ChatModel
  -> SyncMcpToolCallbackProvider
  -> McpSyncClient
  -> MCP Server
```

### 特点

- 工具调用发生在 Spring AI ChatModel 层。
- 适合纯 Spring AI 应用。
- 不需要 ADK `Runner`、`SessionService`、`Event` 体系。
- 如果把这种 ChatModel 再包进 ADK，ADK 未必能完整感知内部 MCP tool call 的语义。
- ADK 看到的可能只是模型调用结果，而不是标准 ADK 工具调用事件。

### 推荐场景

- 项目本身使用 Spring AI `ChatClient` / `ChatModel`，没有使用 ADK Agent。
- 只需要一次模型调用加工具回调，不需要 ADK 的 Agent 生命周期。
- 希望沿用 Spring AI 的 ToolCallback、Advisor、ChatOptions 等生态。

## 核心区别

| 对比项 | ADK `McpToolset` | `McpSyncClient + SyncMcpToolCallbackProvider` |
|---|---|---|
| 所属框架层 | ADK Agent 层 | Spring AI / ChatModel 层 |
| 挂载位置 | `LlmAgent.tools(...)` | `ChatClient.toolCallbacks(...)` 或 `ChatOptions` |
| 工具所有者 | ADK Agent | ChatModel / ChatClient |
| 调用调度 | ADK Runner / LLM Flow | Spring AI 模型调用流程 |
| ADK Event 可观测性 | 强，能看到 `functionCalls` / `functionResponses` | 弱，通常不会形成标准 ADK Tool Event |
| Session / State 参与 | 自然参与 ADK Session / State | 需要额外手动桥接 |
| Multi-Agent 协作 | 更自然 | 不自然，除非外层再封装 |
| 适用项目 | ADK Agent 应用 | Spring AI Chat 应用 |

## 为什么不建议混用

不要把同一批 MCP 工具同时挂到：

```text
LlmAgent.tools(...)
ChatModel.toolCallbacks(...)
```

这样会带来几个问题：

- 工具声明可能重复。
- 模型可能看到两套语义相近的工具。
- 工具调用到底由 ADK 处理还是由 ChatModel 处理会变得模糊。
- Event、日志、审计链路会不一致。
- 后续排查问题时，很难判断工具结果来自哪个工具层。

一个清晰的原则是：

```text
如果应用主运行时是 ADK，MCP 挂到 LlmAgent。
如果应用主运行时是 Spring AI，MCP 挂到 ChatClient / ChatModel。
```

## 与本项目的关系

本项目的学习主线是 ADK：

```text
Agent
Runner
Session
State
Memory
Event
Tool
Workflow
Multi-Agent
```

因此第 13 章优先采用：

```java
LlmAgent.builder()
        .tools(new McpToolset(...))
        .build();
```

这能让 MCP 调用进入 ADK 的事件流，方便和前面章节学习过的 Event、Session、Multi-Agent 机制串起来。

`McpSyncClient + SyncMcpToolCallbackProvider` 更适合作为补充知识：当我们离开 ADK，单独使用 Spring AI ChatClient 构建应用时，它就是非常自然的 MCP 接入方式。

## 记忆口诀

```text
McpToolset 挂 Agent：ADK 看得见，适合 Agent 系统。
ToolCallback 挂 Model：Spring AI 看得见，适合 ChatClient 系统。
```
