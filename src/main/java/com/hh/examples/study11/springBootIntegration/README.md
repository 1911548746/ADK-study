# study11.springBootIntegration

## 概念

Spring Boot 集成的重点不是“把 Agent 写进 Controller”，而是把 ADK 运行时变成标准后端服务的一部分。

本章演示最小分层：

```text
HTTP Client
  ↓
Spring MVC Controller
  ↓
Application Service
  ↓
ADK Runner
  ↓
Agent / Session / Events
```

它解决的问题是：

- 让 Agent 能通过 HTTP API 被业务系统调用。
- 让 `Runner`、`SessionService`、`MemoryService` 作为 Spring Bean 管理。
- 把 HTTP 入参出参与 ADK 运行逻辑隔离。
- 为后续配置管理、持久化、监控和测试打基础。

## LangGraph 对照

如果用 LangGraph + Spring Boot，通常会把 compiled graph 或 graph service 注册成 Spring Bean，然后 Controller 调用它。

概念对照：

```text
ADK Runner                  ≈ LangGraph compiled graph / graph executor
ADK SessionService          ≈ checkpointer / thread persistence
ADK MemoryService           ≈ long-term store
Spring Service              ≈ 业务应用层，管理 userId/sessionId/input/output
Spring Controller           ≈ HTTP adapter
```

差异是：ADK 的 `Runner` 已经把 Agent、Session、Artifact、Memory、Plugin 等运行时服务串在一起；Spring Boot 主要负责把这个运行时接入 Web、配置、日志和依赖注入体系。

## ADK 实现

本示例使用：

- `@Configuration` 创建 `LlmAgent`、`App`、`Runner` Bean。
- `InMemorySessionService` 管理本地会话。
- `InMemoryMemoryService` 作为本地 Memory 占位。
- `InMemoryArtifactService` 作为本地 Artifact 占位。
- `@Service` 封装 `runner.runAsync(...)`。
- `@RestController` 暴露 HTTP API。

本章刻意不复用 `study000.persistentCli` 的 MySQL 持久化配置，避免把“Spring Boot 集成”与“生产级持久化”混在一起。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study11/springBootIntegration/SpringBootAgentConfig.java
src/main/java/com/hh/examples/study11/springBootIntegration/SpringBootAgentService.java
src/main/java/com/hh/examples/study11/springBootIntegration/SpringBootAgentController.java
```

`pom.xml` 新增：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

Runner Bean：

```java
@Bean
public Runner study11Runner(App study11App) {
    return Runner.builder()
            .app(study11App)
            .artifactService(new InMemoryArtifactService())
            .sessionService(new InMemorySessionService())
            .memoryService(new InMemoryMemoryService())
            .build();
}
```

HTTP API：

```java
@PostMapping("/chat")
public ChatResponse chat(@RequestBody ChatRequest request) {
    return agentService.chat(request);
}
```

流式 HTTP API：

```java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public ResponseEntity<StreamingResponseBody> streamChat(@RequestBody ChatRequest request)
```

## 运行流程

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn spring-boot:run
```

健康检查：

```powershell
Invoke-RestMethod -Method Get -Uri http://localhost:8080/api/study11/health
```

调用 Agent：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/study11/chat `
  -ContentType 'application/json' `
  -Body '{"userId":"u1","sessionId":"s1","message":"Spring Boot 集成 ADK 时 Controller 和 Runner 分别负责什么？"}'
```

流式调用 Agent：

```powershell
curl.exe -N `
  -X POST http://localhost:8080/api/study11/chat/stream `
  -H "Content-Type: application/json" `
  -d "{\"userId\":\"u1\",\"sessionId\":\"s1\",\"message\":\"请用流式方式解释 ADK Runner 的职责。\"}"
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用。

## 关键源码分析

Controller 只做 HTTP adapter：

```java
@RestController
@RequestMapping("/api/study11")
public class SpringBootAgentController
```

Service 管理业务层会话和 ADK 调用：

```java
Session session = findOrCreateSession(userId, sessionId);
runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
```

响应中返回 `trace`，用于观察本轮事件：

```text
author
finalResponse
functionCalls
functionResponses
stateDeltaKeys
```

这样前端或调用方不仅能拿到最终答案，也能看到 Agent 本轮运行轨迹。

## 可观察结果

运行后重点观察：

- 同一个 `sessionId` 会复用同一个 ADK Session。
- Controller 不直接创建 Agent，也不直接拼接事件。
- Runner 作为 Spring Bean 被复用，而不是每次请求临时 new。
- HTTP 响应中的 `trace` 来自 ADK Event 流。
- `/chat/stream` 返回 SSE 事件：`meta`、`token`、`trace`、`done`。
- Spring Boot 只是应用壳，Agent 运行语义仍由 ADK Runner 管理。

## 思考题

1. 为什么生产系统不应该在 Controller 中直接 new Runner？
2. `userId` 和 `sessionId` 应该由前端传入，还是由后端认证系统生成？
3. 如果要接入 MySQL SessionService，本章的哪些代码应该保留，哪些应该替换？
