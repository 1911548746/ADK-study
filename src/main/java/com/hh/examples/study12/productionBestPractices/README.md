# study12.productionBestPractices

## 概念

生产化不是“把 Agent 放到线上”这么简单，而是给 ADK 运行时补齐工程边界。

本章解决的问题是：

- 模型、应用名、超时、输入限制等参数不能散落在代码里。
- `Runner` 应作为应用级运行时复用，而不是每个请求临时创建。
- HTTP API 应该有稳定的请求、响应和错误契约。
- Session、Memory、Artifact 服务要可以替换为持久化实现。
- Event trace 应该可观测，但不应该无条件暴露给所有客户端。
- 请求必须有超时、输入校验和错误归一化。

本章仍然使用 `InMemorySessionService`、`InMemoryMemoryService`、`InMemoryArtifactService`，目的是先把生产化边界讲清楚。真正生产环境应把这些服务替换为数据库、Redis、Firestore 或企业内部存储。

## LangGraph 对照

在 LangGraph 中，生产化通常会围绕 compiled graph 做这些事情：

```text
compiled graph / graph executor  -> Spring Bean / service singleton
checkpointer                     -> persistent SessionService
store / vector store             -> MemoryService / RAG backend
callbacks / stream events         -> Event trace / telemetry
RunnableConfig.thread_id          -> userId + sessionId
```

ADK 的差异是：`Runner` 已经把 `App`、`Agent`、`SessionService`、`MemoryService`、`ArtifactService` 串成一个运行时。生产化重点不是再造一个 graph executor，而是把这些运行时服务接入 Spring 的配置、生命周期、异常和观测体系。

## ADK 实现

本章新增：

- `ProductionAgentProperties`：集中读取 `adk.study12.*` 配置。
- `ProductionAgentConfig`：创建 `LlmAgent`、`App`、`Runner`。
- `ProductionAgentService`：封装 `runner.runAsync(...)`，处理 session、timeout、trace、耗时。
- `ProductionAgentController`：只负责 HTTP 入口和异常映射。
- `ProductionErrorResponse`：统一错误响应格式。

核心分层：

```text
HTTP Client
  -> ProductionAgentController
  -> ProductionAgentService
  -> ADK Runner
  -> LlmAgent / SessionService / MemoryService / ArtifactService
```

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study12/productionBestPractices/ProductionAgentProperties.java
src/main/java/com/hh/examples/study12/productionBestPractices/ProductionAgentConfig.java
src/main/java/com/hh/examples/study12/productionBestPractices/ProductionAgentService.java
src/main/java/com/hh/examples/study12/productionBestPractices/ProductionAgentController.java
```

本章还给 `OpenAILangChainModelFactory` 增加了一个带参数的 `createChatAndStreamingModel(...)` 重载，用于演示模型配置外置。

## 配置方式

可以继续使用项目已有的默认模型配置，也可以通过 `application.yml` 或环境变量覆盖：

```yaml
adk:
  study12:
    app-name: study12_production_app
    agent-name: production_best_practices_agent
    default-user-id: study12-user
    default-session-id: study12-session
    max-message-chars: 2000
    request-timeout-seconds: 120
    expose-trace: true
    model:
      base-url: https://api.example.com
      api-key: ${ADK_STUDY12_API_KEY}
      name: your-model-name
      timeout-seconds: 90
      max-retries: 3
```

注意：`app-name` 必须符合 ADK 的 identifier 规则，只能使用字母、数字和下划线，不能使用连字符。

## 运行流程

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn spring-boot:run
```

健康检查：

```powershell
Invoke-RestMethod -Method Get -Uri http://localhost:8080/api/study12/health
```

调用 Agent：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/study12/chat `
  -ContentType 'application/json' `
  -Body '{"userId":"u1","sessionId":"prod-1","message":"ADK 服务上线前最应该补哪些生产护栏？","includeTrace":true}'
```

关闭 trace：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/study12/chat `
  -ContentType 'application/json' `
  -Body '{"userId":"u1","sessionId":"prod-1","message":"为什么生产接口不应该直接返回 ADK Event？","includeTrace":false}'
```

## 关键源码分析

`Runner` 作为 Spring Bean 复用：

```java
@Bean
public Runner study12Runner(App study12App) {
    return Runner.builder()
            .app(study12App)
            .artifactService(new InMemoryArtifactService())
            .sessionService(new InMemorySessionService())
            .memoryService(new InMemoryMemoryService())
            .build();
}
```

Service 层管理 session：

```java
Session existingSession = runner.sessionService()
        .getSession(runner.appName(), userId, sessionId, Optional.empty())
        .blockingGet();
```

请求级超时：

```java
runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
        .timeout(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .blockingForEach(...);
```

稳定 trace 契约：

```java
trace.add(ProductionEventSignal.from(event));
```

这意味着 HTTP 客户端看到的是你定义的 `ProductionEventSignal`，而不是 ADK 内部对象本身。未来即使 ADK Event 结构升级，外部 API 也可以保持稳定。

## 可观察结果

运行后重点观察：

- `/health` 会输出当前 appName、agentName、超时、输入长度限制和是否使用外部模型配置。
- `/chat` 返回 `requestId`、`durationMs`、`answer` 和可选 `trace`。
- 输入空 message 会返回统一错误结构。
- 超过 `max-message-chars` 会被拒绝。
- 同一个 `userId + sessionId` 会复用同一个 ADK Session。
- `includeTrace=false` 时，HTTP 响应不会暴露事件轨迹。

## 生产替换点

当前示例中的内存实现适合学习，不适合生产：

```text
InMemorySessionService   -> MySQL / PostgreSQL / Firestore / Redis-backed SessionService
InMemoryMemoryService    -> 长期记忆库 / 向量库 / 用户画像服务
InMemoryArtifactService  -> 对象存储 / 文件服务 / Blob 存储
```

生产系统还应补充：

- 认证鉴权：`userId` 不应完全信任前端传入。
- 限流熔断：保护模型 API 和工具服务。
- 审计日志：记录 requestId、userId、sessionId、tool call、错误码。
- 指标监控：延迟、token、错误率、工具调用次数。
- 密钥管理：API key 不应硬编码在源码中。
- 测试分层：Controller 测 HTTP 契约，Service 测 session/trace，Agent 测行为。

## 思考题

1. 为什么生产接口不应该直接把 ADK `Event` 原样暴露给调用方？
2. `userId`、`sessionId` 应由前端传入，还是由后端认证系统生成？
3. 如果 `SessionService` 换成数据库实现，哪些代码应该保持不变，哪些代码应该替换？
