# study20.a2a

## 概念

A2A（Agent2Agent）是 Agent 之间的网络通信协议。它解决的不是“如何把一个 Java 方法注册成工具”，而是：当另一个 Agent 已经作为独立服务部署时，如何让不同进程、团队、语言或框架中的 Agent 按统一契约协作。

ADK Java 1.7.0 中的 A2A 集成仍属于 Experimental 能力，示例 API 和底层 A2A SDK 版本应随 ADK 升级重新核对。

本章把一个产品目录 Agent 暴露成 A2A 服务，再由本地协调 Agent 发现 Agent Card，并把远程 Agent 挂到 `subAgents`：

```text
本地 root_agent
    -> RemoteA2AAgent
    -> A2A Client / JSON-RPC
    -> HTTP
    -> A2A AgentExecutor
    -> 远程 product_catalog_agent
```

A2A 的关键边界是：远程 Agent 不是当前 JVM 中的普通对象。请求、响应、任务状态、事件和 Artifact 都要经过协议转换和网络传输。

## 该能力解决什么问题

使用本地 `SubAgent` 足以解决同一应用中的 Agent 拆分；当出现以下情况时，A2A 更合适：

- 专业 Agent 由另一个团队独立维护和发布。
- 调用方和服务方使用不同语言或不同 Agent 框架。
- 希望通过 Agent Card 暴露能力、输入输出模态和技能描述。
- 需要把 Agent 作为微服务独立扩缩容、鉴权、审计和限流。
- 远程 Agent 内部拥有自己的 Session、工具、模型和部署生命周期。

不要因为 Agent 数量变多就自动使用 A2A。单进程内的高频、低延迟协作优先使用本地 `subAgents` 或 `SequentialAgent`、`ParallelAgent`；A2A 要承担网络、序列化、超时、版本兼容和服务治理成本。

## LangGraph 对照

LangGraph 中，本地子图可以直接作为父图的节点或子图调用；跨服务时通常需要自己定义 HTTP/RPC 协议、服务端 endpoint、状态恢复和消息映射。

| LangGraph / 通用实现 | ADK A2A |
|---|---|
| 远程 graph API | `A2A AgentExecutor` + A2A HTTP transport |
| 服务能力描述 | `AgentCard` |
| 远程 graph client | A2A SDK `Client` |
| 远程 graph proxy node | `RemoteA2AAgent` |
| graph state / checkpoint | A2A task、contextId、服务端 Session |
| graph message | A2A `Message` 与 `Part` |

两者都可以表达跨节点协作，但 A2A 重点是跨进程的 Agent-to-Agent 契约；它不等同于一个应用内工作流图。

## ADK 实现

### 依赖

ADK Java 1.7.0 的 A2A 集成单独发布：

```xml
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-a2a</artifactId>
    <version>1.7.0</version>
</dependency>
```

该模块提供 ADK 与 A2A SDK 之间的转换和运行时；底层 Java A2A SDK 提供 `AgentCard`、JSON-RPC Client、HTTP transport 以及服务端 request handler。

### 暴露端

服务端有三层职责：

```text
HTTP transport
    -> RequestHandler：解析 A2A 请求、管理 task / queue
    -> com.google.adk.a2a.executor.AgentExecutor：把 A2A message 交给 ADK Runner
    -> BaseAgent：真正执行模型、工具和 Session
```

`A2AServerExample` 用 JDK `HttpServer` 作为轻量 HTTP 外壳，核心配置是：

```java
com.google.adk.a2a.executor.AgentExecutor executor =
        new com.google.adk.a2a.executor.AgentExecutor.Builder()
                .agent(agent)
                .appName("study20_a2a_provider_app")
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .agentExecutorConfig(AgentExecutorConfig.builder().build())
                .build();
```

这里的 `AgentExecutor` 不是 A2A HTTP Server 本身，它是“收到 A2A 任务后如何用 ADK 执行 Agent”的适配器。HTTP 层还要把 JSON-RPC 请求交给 A2A SDK 的 `RequestHandler`/`RestHandler`。

官方 ADK Java quickstart 使用 Quarkus 自动装配这段 HTTP 传输层；本项目为了便于观察生命周期，在示例中直接用 JDK `HttpServer` 挂载同一个 `RestHandler`。

### Agent Card

客户端不会直接猜测远程 Agent 的名称和能力，而是先请求：

```text
GET /a2a/remote/.well-known/agent-card.json
```

Agent Card 中至少应描述：

- Agent 名称、描述和服务 URL。
- 协议版本与能力，例如是否支持 streaming、push notification。
- 输入输出模态。
- `AgentSkill` 列表，包括技能 ID、标签、示例和输入输出模态。

Agent Card 是服务发现和能力协商的元数据，不是业务回答，也不是模型的系统提示词。

### 消费端

客户端先解析 Agent Card，再构造 A2A SDK Client，最后包装为 ADK `RemoteA2AAgent`：

```java
AgentCard card = new A2ACardResolver(
        new JdkA2AHttpClient(),
        remoteBaseUrl,
        remoteBaseUrl + "/.well-known/agent-card.json")
        .getAgentCard();

Client client = Client.builder(card)
        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
        .clientConfig(new ClientConfig.Builder()
                .setStreaming(card.capabilities().streaming())
                .build())
        .build();

BaseAgent remoteAgent = RemoteA2AAgent.builder()
        .name(card.name())
        .a2aClient(client)
        .agentCard(card)
        .build();
```

包装完成后，父 Agent 的使用方式与本地子 Agent 一致：

```java
LlmAgent.builder()
        .name("a2a_coordinator_agent")
        .subAgents(remoteAgent)
        .build();
```

这只是编程模型相似，运行代价并不相同。`RemoteA2AAgent` 内部会把当前 ADK 上下文转换成 A2A `Message`，通过 Client 发送，然后把 A2A 返回的 task/event/part 再转换成 ADK `Event`。

## 完整代码

- `A2AProviderAgent.java`：产品目录服务 Agent。
- `A2AServerExample.java`：用 `AgentExecutor` 暴露 A2A 服务。
- `A2AConsumerExample.java`：通过 `RemoteA2AAgent` 消费服务。

## 运行方式

先编译：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

需要先确认 `OpenAILangChainModelFactory` 中的模型支持普通文本生成。A2A 服务端和消费端都会执行模型调用，因此两端都需要能访问模型服务。

启动服务端：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study20.a2a.A2AServerExample" `
  -Dexec.args="8081"
```

然后在另一个终端启动消费端：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study20.a2a.A2AConsumerExample" `
  -Dexec.args="http://localhost:8081/a2a/remote"
```

也可以在 IDE 中分别运行两个 `main` 方法。服务端必须先启动，因为消费端启动时会先读取 Agent Card。

先单独检查 Agent Card：

```powershell
Invoke-RestMethod http://localhost:8081/a2a/remote/.well-known/agent-card.json
```

## 运行流程

```text
1. A2AServerExample 创建 ADK BaseAgent
2. AgentExecutor 绑定 Runner、Session、Artifact、Memory 和 Plugin
3. HTTP server 发布 Agent Card 与 message/send endpoint
4. A2AConsumerExample 请求 Agent Card
5. A2A Client 按 JSON-RPC 配置 transport
6. RemoteA2AAgent 作为 root_agent 的 subAgent 注册
7. root_agent 根据用户问题决定 transfer 到远程 Agent
8. RemoteA2AAgent 发送 A2A Message
9. 服务端 RequestHandler 创建/恢复 task 和 context
10. ADK AgentExecutor 调用 Runner.runAsync
11. ADK Event 转成 A2A response，返回客户端
12. RemoteA2AAgent 将响应转回 ADK Event，root_agent 整理结果
```

## 关键源码分析

### `AgentCard` 是发现契约

如果只把 URL 写在客户端代码里，客户端知道“去哪儿发请求”，但不知道远程 Agent 能做什么。Agent Card 把名称、技能、输入输出模态、streaming 能力和协议版本作为公开契约，使客户端可以在连接前做发现与能力判断。

生产系统中还应通过 HTTPS、认证 header、服务注册中心或 API Gateway 管理 Agent Card URL；本示例使用本地明文 HTTP 仅用于学习。

### `RemoteA2AAgent` 是代理，不是 Tool

`RemoteA2AAgent` 继承自 ADK `BaseAgent`，所以它被挂在 `subAgents`。它不会像 `FunctionTool` 那样只执行一个 Java 函数，而是把一段 Agent 工作委托给远程服务，并接收远程 Agent 的事件和最终结果。

```text
FunctionTool：一次函数调用 -> FunctionResponse
RemoteA2AAgent：一次 Agent 委托 -> 多个 Event / Task 状态 -> 最终结果
```

父 Agent 是否委托，仍由模型和 instruction 决定；要强制路由时，应在应用层或工作流层显式选择，而不是只依赖提示词。

### Session 与 contextId

消费端和服务端各有自己的 ADK Session。A2A 网络边界不会把两个 JVM 的 Java 对象直接共享；服务端通过 A2A `contextId`、`taskId` 和消息历史把多轮任务关联起来。

因此：

- `RemoteA2AAgent` 的调用上下文会被序列化成 A2A message。
- 服务端将 A2A context 映射为自己的 Session 标识。
- 两边可以各自持久化 Session，但不能假设能直接读写对方的 state Map。
- 需要共享业务数据时，应使用明确的 A2A message、Artifact、业务 API 或共享数据库。

这与本地 SubAgent 的“同进程共享执行上下文”不同，也是 A2A 的隔离价值所在。

### Events 与 Artifacts

ADK A2A 模块负责在 ADK Event 与 A2A Message/Part/Task event 之间转换。它可以承载文本、函数调用/响应、thought 元数据以及 Artifact 更新，但是否能正确流式传输取决于双方 Agent Card 能力和 transport 配置。

本例关闭了 streaming 与 push notification，先观察最小的同步 `message/send` 路径。生产环境处理长任务时，应进一步配置 streaming、polling、push notification、超时、取消和任务持久化。

### 失败边界

至少要区分以下故障：

```text
Agent Card 获取失败       -> 服务发现 / 地址 / 认证问题
Client build 失败         -> transport 与服务能力不兼容
HTTP / JSON-RPC 失败      -> 网络、协议或网关问题
远程 task FAILED          -> 服务端 Agent、工具或模型失败
本地 root_agent 失败      -> 消费方自己的模型或编排问题
```

不要把所有异常都包装成“远程 Agent 没有回答”。日志中应保留 remote URL、agent card version、contextId、taskId、request ID 和耗时，同时脱敏消息内容。

## A2A、SubAgent、Tool 的选择

| 场景 | 推荐组件 | 原因 |
|---|---|---|
| 当前 JVM 内的专业职责拆分 | 本地 `subAgents` | 延迟低，直接复用 Session/Runner 边界 |
| 一个明确的外部函数能力 | `FunctionTool` | 接口小，结果是一次 FunctionResponse |
| 独立部署的远程 Agent | `RemoteA2AAgent` | 通过 Agent Card 和 A2A 协议协作 |
| 多个固定步骤的本地流程 | `SequentialAgent` / `ParallelAgent` | 代码表达依赖关系，稳定且可控 |
| 远程 Agent 的高风险副作用 | A2A + 服务端 HITL | 审批必须在真正执行副作用的一侧强制生效 |

一个远程 Agent 内部仍然可以使用 MCP、FunctionTool、RAG、Plan 或 ReAct；A2A 只定义 Agent 之间的边界，不替代远程 Agent 内部的执行架构。

## 可观察结果

启动服务端后可以看到：


```text
A2A server started
Agent Card > http://localhost:8081/a2a/remote/.well-known/agent-card.json
JSON-RPC    > http://localhost:8081/a2a/remote/v1
```

消费端启动时先访问 Agent Card，然后 root Agent 可能产生如下轨迹：

```text
用户 > 请查询无线键盘的价格和库存。

[A2A 调用轨迹]
author=a2a_coordinator_agent
author=product_catalog_agent
content=...
author=a2a_coordinator_agent [final]
content=...
```

具体 Event 数量、是否出现中间状态以及最终措辞会受模型行为影响。验证重点是：请求经过了 Agent Card 和远程 HTTP 服务，服务端日志中出现了对应的 ADK 执行，而不是消费端直接调用了目录 Agent 的 Java 方法。

## 生产注意事项

- 使用 HTTPS 和服务间认证，不要把本地明文 endpoint 直接暴露到公网。
- Agent Card 需要版本化；客户端要处理能力变化和协议不兼容。
- `InMemorySessionService`、`InMemoryArtifactService`、`InMemoryTaskStore` 只适合本地学习，生产需要持久化实现。
- 为每个请求传播 trace ID、contextId、taskId 和超时；远程调用必须有取消和重试边界。
- 重试 `message/send` 前要考虑任务是否已经产生副作用，服务端 Tool 应实现幂等键。
- 不要把远程 Agent 返回的文本当作权限判断依据；服务端仍需独立鉴权和业务校验。
- A2A 传输的消息、Artifact、FunctionResponse 可能包含敏感数据，日志和监控必须脱敏。
- 长任务可使用 streaming、polling 或 push notification；本章先使用同步非流式路径降低学习噪音。

## 实验步骤

1. 只启动服务端，访问 Agent Card，观察它如何描述远程能力。
2. 启动消费端，观察 root Agent 与 `product_catalog_agent` 的 Event author 是否不同。
3. 把消费端 instruction 中的强制委托规则去掉，观察模型可能选择本地回答；理解 `RemoteA2AAgent` 提供能力不等于每次都会调用。
4. 修改 Agent Card 的 `streaming` 能力和 ClientConfig，比较同步响应与事件流的差异。
5. 将服务端的 `InMemorySessionService` 替换为持久化 Session Service，并思考 taskId、contextId 与业务 conversationId 的映射。
6. 在服务端 Agent 中加入 HITL Tool，验证审批应由真正拥有副作用的远程服务强制执行。

## 思考题

1. 为什么同一 JVM 内的 `SubAgent` 不应仅因为“未来可能独立部署”就提前改成 A2A？
2. 如果远程 Agent 返回了一个 Artifact，消费端需要把它写入自己的 ArtifactService 吗？两侧的所有权和生命周期应如何定义？
3. 当 root Agent 连续两次委托同一个 task 时，应该用什么字段和业务幂等策略避免远程副作用重复执行？
