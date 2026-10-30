# study14.plugin 讨论笔记

## 1. Plugin 的定位

Plugin 是挂在 `Runner` 上的横切扩展点。

它不属于某个具体 Agent，也不是用户可见的工具能力，而是观察或干预一次 `runner.runAsync(...)` 调用链。

可以把它理解成：

```text
Runner 级 middleware
```

典型用途：

```text
日志
监控
审计
耗时统计
错误告警
敏感信息脱敏
成本统计
工具调用治理
```

## 2. Runner 中配置多个 Plugin

ADK Java 的 `Runner` 支持配置多个 Plugin：

```java
Runner.builder()
        .agent(agent)
        .appName(APP_NAME)
        .artifactService(new InMemoryArtifactService())
        .sessionService(new InMemorySessionService())
        .memoryService(new InMemoryMemoryService())
        .plugins(
                new LoggingPlugin("study14_logging_plugin"),
                new RunTimingPlugin(),
                new EventCounterPlugin())
        .build();
```

也可以传入列表：

```java
List<Plugin> plugins = List.of(
        new LoggingPlugin("logging"),
        new AuditPlugin(),
        new MetricsPlugin());

Runner.builder()
        .plugins(plugins)
        .build();
```

这些 Plugin 由 `PluginManager` 统一调度。

## 3. LoggingPlugin 的作用

ADK 内置的 `LoggingPlugin` 是一个通用执行轨迹 logger。

它会观察：

```text
用户输入
Run 开始 / 结束
Agent 开始 / 结束
Model 请求 / 响应 / 错误
Tool 请求 / 响应 / 错误
Event 输出
```

它适合：

```text
学习 ADK 生命周期
开发期调试
验证 Agent 是否调用工具
观察多 Agent 执行顺序
排查模型请求和工具调用
```

它不适合直接作为完整生产监控方案。

原因是它更偏通用文本日志，不负责：

```text
结构化日志
指标聚合
traceId 贯穿
审计落库
敏感信息脱敏
告警推送
成本统计
多租户治理
```

## 4. LoggingPlugin 的 Callback 执行时机

一次 `runner.runAsync(...)` 的典型顺序是：

```text
1. onUserMessageCallback
2. beforeRunCallback
3. beforeAgentCallback
4. beforeModelCallback
5. afterModelCallback / onModelErrorCallback
6. beforeToolCallback
7. afterToolCallback / onToolErrorCallback
8. onEventCallback
9. afterAgentCallback
10. afterRunCallback
```

实际执行中，`beforeModelCallback`、`afterModelCallback`、`beforeToolCallback`、`afterToolCallback`、`onEventCallback` 可能出现多次。

例如工具调用流程通常是：

```text
LLM 请求
  ↓
模型返回 functionCall
  ↓
Tool 执行
  ↓
Tool result 写回模型上下文
  ↓
再次 LLM 请求
  ↓
模型生成最终回答
```

因此一轮用户输入可能产生多次 model callback、多次 tool callback 和多个 event。

## 5. 各 Callback 的含义

### onUserMessageCallback

触发时机：`Runner` 收到用户输入后，正式追加到 Session 前。

`LoggingPlugin` 记录：

```text
USER MESSAGE RECEIVED
invocationId
sessionId
userId
appName
rootAgent
user content
branch 如果存在
```

用途：观察本轮调用入口。

### beforeRunCallback

触发时机：用户输入处理后，Agent 正式运行前。

记录：

```text
INVOCATION STARTING
invocationId
rootAgent
```

用途：标记一次 invocation 开始。

### beforeAgentCallback

触发时机：某个 Agent 开始执行前。

记录：

```text
AGENT STARTING
agentName
invocationId
branch 如果存在
```

用途：观察当前轮到哪个 Agent 执行。

在 `SequentialAgent` 中，子 Agent 会按顺序出现。

在 `ParallelAgent` 中，多个子 Agent 的事件可能交错出现。

### afterAgentCallback

触发时机：某个 Agent 执行完成后。

记录：

```text
AGENT COMPLETED
agentName
invocationId
```

用途：确认某个 Agent 的执行边界结束。

### beforeModelCallback

触发时机：即将调用模型前。

记录：

```text
LLM REQUEST
model
agentName
system instruction 摘要
tools 列表
```

用途：观察真正发送给模型前的请求形态。

这对排查以下问题很有用：

```text
instruction 是否正确
outputKey 注入是否生效
工具是否注册成功
模型是否收到预期 system prompt
```

### afterModelCallback

触发时机：模型成功返回后。

用途：观察模型原始响应。

它比最终 Event 更靠近模型层。

### onModelErrorCallback

触发时机：模型调用异常时。

用途：排查：

```text
模型服务不可用
超时
鉴权失败
限流
模型响应格式异常
```

### beforeToolCallback

触发时机：工具执行前。

记录：

```text
TOOL STARTING
toolName
agentName
functionCallId
args
```

用途：确认模型选择了哪个工具，以及参数是什么。

### afterToolCallback

触发时机：工具成功执行后。

记录：

```text
TOOL COMPLETED
toolName
agentName
functionCallId
output
```

用途：确认工具返回内容。

### onToolErrorCallback

触发时机：工具执行异常时。

记录：

```text
TOOL ERROR
toolName
agentName
functionCallId
args
error message
```

用途：排查工具参数错误、业务异常或外部服务异常。

### onEventCallback

触发时机：ADK 产生一个 `Event` 并准备向外输出时。

记录：

```text
EVENT YIELDED
eventId
author
content
finalResponse
functionCalls
functionResponses
longRunningToolIds
```

用途：观察 ADK Event 流。

这是调试以下字段最直接的位置：

```java
event.author()
event.finalResponse()
event.functionCalls()
event.functionResponses()
event.actions().stateDelta()
```

### afterRunCallback

触发时机：整次 `runner.runAsync(...)` 完成后。

记录：

```text
INVOCATION COMPLETED
invocationId
rootAgent
```

用途：标记一次完整调用结束。

### close

触发时机：`runner.close()` 时。

用途：释放 Plugin 持有的外部资源，例如：

```text
日志缓冲区
指标客户端
数据库连接
异步队列
外部 tracing exporter
```

## 6. 多个 Plugin 的顺序关系

多个 Plugin 按注册顺序执行。

例如：

```java
.plugins(
        new LoggingPlugin("log"),
        new MetricsPlugin(),
        new AuditPlugin())
```

同一个 callback 的执行顺序是：

```text
LoggingPlugin.beforeToolCallback
MetricsPlugin.beforeToolCallback
AuditPlugin.beforeToolCallback
```

但有一个重要规则：

```text
Maybe 类型 callback 中，如果某个 Plugin 返回了值，后续同类 callback 会提前停止。
```

这意味着：

```text
返回 Maybe.empty() = 只观察，不接管流程
返回 Maybe.just(value) = 接管流程，并阻止后续同类 callback 继续执行
```

`LoggingPlugin` 通常只打印日志，返回 `Maybe.empty()`，所以不会中断流程。

自定义 Plugin 如果返回值，需要非常谨慎。

## 7. LoggingPlugin 能覆盖哪些场景

就开发期日志监控而言，`LoggingPlugin` 可以覆盖大多数基础场景。

适合观察：

```text
用户输入是什么
哪个 Agent 开始/结束
模型请求是否发出
模型响应是否回来
工具是否被调用
工具参数是什么
工具结果是什么
是否出现模型/工具错误
Event 是否产出
```

学习和调试阶段，通常先开 `LoggingPlugin` 就够了。

## 8. 什么时候需要自定义 Plugin

`LoggingPlugin` 更像 debug logger，不是完整生产治理方案。

以下场景适合自定义 Plugin。

### 结构化日志

生产日志通常需要 JSON 或固定字段：

```json
{
  "requestId": "...",
  "userId": "...",
  "sessionId": "...",
  "agent": "...",
  "tool": "...",
  "latencyMs": 123,
  "status": "success"
}
```

这类日志适合写成：

```text
StructuredLoggingPlugin
```

### TraceId / RequestId 贯穿

如果 HTTP 请求中有：

```text
X-Request-Id
traceId
tenantId
operatorId
```

并且希望贯穿：

```text
Runner invocation
Agent event
Model call
Tool call
错误日志
审计日志
```

就需要自定义 Plugin。

### 指标监控

常见指标：

```text
每分钟调用量
平均延迟
P95 / P99 延迟
模型错误率
工具错误率
每个工具调用次数
每个 Agent 调用次数
token 用量
```

这些通常要上报到：

```text
Prometheus
Micrometer
OpenTelemetry Metrics
Datadog
Cloud Monitoring
```

适合写成：

```text
MetricsPlugin
```

### 工具调用审计

如果 Agent 能调用高风险工具，例如：

```text
发邮件
查数据库
改订单状态
调用支付接口
操作文件
执行 MCP 工具
```

需要记录：

```text
谁调用的
什么时候调用的
调用了什么工具
参数是什么
结果是什么
是否成功
是否需要人工审批
```

适合写成：

```text
AuditPlugin
```

### 敏感信息脱敏

如果用户输入、工具参数或工具结果中可能包含：

```text
手机号
身份证
邮箱
API key
数据库连接串
客户隐私数据
```

就需要在记录日志前脱敏。

适合写成：

```text
RedactionPlugin
```

### 错误兜底与告警

`LoggingPlugin` 会记录错误，但不会主动告警。

生产系统可能需要：

```text
模型连续失败 5 次后告警
某个工具失败率超过阈值后告警
某个用户触发高风险调用后通知
超时请求推送到告警平台
```

适合写成：

```text
AlertPlugin
```

### 成本统计

如果需要统计：

```text
每个用户花了多少 token
每个 session 成本是多少
每个 Agent 的模型调用次数
哪个工具最贵
```

适合写成：

```text
CostTrackingPlugin
```

### 多租户治理

企业系统常见：

```text
不同 tenant 不同限额
不同用户不同工具权限
不同业务线不同模型策略
```

这类逻辑可以通过 Plugin 做统一前置检查或记录。

但注意：权限判断必须明确、可审计，不要藏得太深。

## 9. Plugin 与 Agent Callback 的区别

`LlmAgent` 自己也支持 callback：

```java
beforeModelCallback
afterModelCallback
beforeToolCallback
afterToolCallback
```

边界是：

```text
Agent callback = 某个 Agent 的局部行为扩展
Runner Plugin  = 整个 Runner 调用链的全局横切扩展
```

如果逻辑只属于某个 Agent，放到 Agent callback。

如果逻辑应该覆盖整个应用、所有 Agent、所有工具调用，放到 Runner Plugin。

## 10. 本章记忆口诀

```text
LoggingPlugin 用于观察。
自定义 Plugin 用于治理。
```

更细一点：

```text
开发调试、看流程：LoggingPlugin 足够。
生产监控、审计、指标、脱敏、告警、成本、租户治理：自定义 Plugin。
```

## 11. 观测型 Plugin 与治理型 Plugin

Plugin 可以分成两类：

```text
观测型 Plugin = 只观察，不改变流程
治理型 Plugin = 可以接管、替换、阻断流程
```

`LoggingPlugin` 属于典型观测型 Plugin。

它的 callback 通常只打印日志，然后返回：

```java
return Maybe.empty();
```

含义是：

```text
我没有接管这个阶段，请 ADK 继续原本流程。
```

治理型 Plugin 则会在必要时返回：

```java
return Maybe.just(value);
```

含义是：

```text
我接管这个阶段的结果。
```

对于 `Maybe` 类型的 callback，`PluginManager` 会按 Plugin 注册顺序执行；一旦某个 Plugin 返回了值，后续同类 callback 会提前停止。

因此：

```text
Maybe.empty()      = 旁路观察
Maybe.just(value) = 接管当前阶段
```

如果还要终止整次调用，可以配合：

```java
context.invocationContext().setEndInvocation(true);
```

或在只有 `InvocationContext` 的 callback 中：

```java
invocationContext.setEndInvocation(true);
```

## 12. 可以在哪些阶段掐断流程

### 输入阶段：onUserMessageCallback / beforeModelCallback

适合处理：

```text
用户输入违规
用户无权限调用模型
tenant 超出额度
上下文中包含敏感信息
请求命中黑名单
```

更推荐在 `beforeModelCallback` 中直接返回一个安全响应，让模型根本不被调用。

示意代码：

```java
@Override
public Maybe<LlmResponse> beforeModelCallback(
        CallbackContext context,
        LlmRequest.Builder requestBuilder) {

    if (isBlockedUser(context.userId())) {
        context.invocationContext().setEndInvocation(true);

        return Maybe.just(LlmResponse.builder()
                .content(Content.fromParts(Part.fromText("当前用户无权限继续本次请求。")))
                .turnComplete(true)
                .build());
    }

    return Maybe.empty();
}
```

### 模型输出阶段：afterModelCallback

适合处理：

```text
模型输出违规
模型泄露敏感信息
模型产生越权建议
模型输出不符合格式
```

可以替换模型响应，并终止后续流程。

示意代码：

```java
@Override
public Maybe<LlmResponse> afterModelCallback(
        CallbackContext context,
        LlmResponse response) {

    String text = extractText(response);

    if (containsViolation(text)) {
        context.invocationContext().setEndInvocation(true);

        return Maybe.just(LlmResponse.builder()
                .content(Content.fromParts(Part.fromText("本次回答未通过安全检查，已终止执行。")))
                .turnComplete(true)
                .build());
    }

    return Maybe.empty();
}
```

这是做模型输出安全审查的核心位置。

### 工具调用前：beforeToolCallback

适合处理：

```text
工具权限
高危工具调用
参数越权
用户是否允许执行
tenant 是否允许访问该工具
```

例如模型想调用高危工具，可以直接阻止工具本体执行。

示意代码：

```java
@Override
public Maybe<Map<String, Object>> beforeToolCallback(
        BaseTool tool,
        Map<String, Object> input,
        ToolContext toolContext) {

    if ("delete_order".equals(tool.name())) {
        toolContext.invocationContext().setEndInvocation(true);

        return Maybe.just(Map.of(
                "blocked", true,
                "reason", "当前用户无权调用 delete_order"));
    }

    return Maybe.empty();
}
```

这里返回的 `Map` 会成为工具调用结果，原工具不会按正常路径继续执行。

### 工具异常后：onToolErrorCallback

适合处理：

```text
工具超时
外部接口失败
MCP Server 不可用
数据库异常
```

可以把异常转换成稳定结果。

示意代码：

```java
@Override
public Maybe<Map<String, Object>> onToolErrorCallback(
        BaseTool tool,
        Map<String, Object> input,
        ToolContext toolContext,
        Throwable error) {

    return Maybe.just(Map.of(
            "success", false,
            "tool", tool.name(),
            "message", "工具调用失败，已返回兜底结果"));
}
```

是否调用 `setEndInvocation(true)` 取决于你的策略：

```text
希望 Agent 基于兜底结果继续回答：不设置 endInvocation
希望整轮直接终止：设置 endInvocation
```

### Event 输出前：onEventCallback

适合处理：

```text
最终 Event 脱敏
Event 审计
替换即将输出给调用方的内容
发现异常后标记终止
```

但它已经比较靠后。

此时模型或工具可能已经执行过了，所以它更像：

```text
出口过滤
```

而不是：

```text
源头阻断
```

如果目标是降低成本或避免危险工具执行，应优先在 `beforeModelCallback` 或 `beforeToolCallback` 阶段处理。

## 13. 掐断流程的推荐策略

可以按问题类型选择拦截点：

```text
输入违规：beforeModelCallback / onUserMessageCallback
模型输出违规：afterModelCallback
工具越权：beforeToolCallback
工具异常：onToolErrorCallback
最终出口过滤：onEventCallback
```

一个经验原则：

```text
越早发现，越早拦截。
越靠后拦截，越像补救。
```

治理型 Plugin 很强，但也要克制使用。

如果太多业务逻辑藏在 Plugin 中，会导致：

```text
Agent 行为变得隐蔽
instruction 看不出真实控制逻辑
排查问题时不知道是谁拦截了流程
测试需要额外覆盖 Plugin 分支
```

因此建议：

```text
安全、权限、限额、审计失败、高危工具调用：适合 Plugin 掐断。
普通业务分支、Agent 推理策略、用户可见任务逻辑：不要藏进 Plugin。
```

## 14. 最终边界

可以这样记：

```text
LoggingPlugin 观察流程。
治理型 Plugin 管住流程。
```

更具体一点：

```text
Maybe.empty() = 我只是看一眼。
Maybe.just(...) = 我来接管这一段。
setEndInvocation(true) = 本次调用到这里为止。
```

## 15. BasePlugin、LoggingPlugin、GlobalInstructionPlugin、PluginManager 的应用场景

ADK 内置的 Plugin 相关类可以分成两层：

```text
业务可直接使用或继承的 Plugin：
BasePlugin
LoggingPlugin
GlobalInstructionPlugin

框架内部用于调度 Plugin 的组件：
PluginManager
```

### BasePlugin

`BasePlugin` 是一个很薄的抽象基类，主要负责保存 Plugin 名称，并实现 `Plugin.getName()`。

它本身不提供具体生命周期逻辑，也不会自动观察或干预 Agent 执行。

典型使用场景：

```text
自定义日志 Plugin
自定义指标 Plugin
自定义审计 Plugin
自定义限流 Plugin
自定义安全拦截 Plugin
```

通常写自定义 Plugin 时继承它即可：

```java
public class AuditPlugin extends BasePlugin {
    public AuditPlugin() {
        super("audit_plugin");
    }
}
```

什么时候需要重写它的方法？

严格来说，不是重写 `BasePlugin` 的方法，而是继承 `BasePlugin` 后重写 `Plugin` 生命周期 callback。

例如：

```text
beforeModelCallback：模型调用前做权限、限额、安全检查
afterModelCallback：模型返回后做内容审查、脱敏或替换
beforeToolCallback：工具执行前做高危工具拦截
afterToolCallback：工具执行后记录审计日志
onEventCallback：Event 输出前做出口过滤
```

### LoggingPlugin

`LoggingPlugin` 是 ADK 内置的观察型 Plugin。

它覆盖了主要生命周期 callback，用来记录：

```text
用户输入
Run 开始 / 结束
Agent 开始 / 结束
模型请求 / 响应 / 错误
工具请求 / 响应 / 错误
Event 输出
```

适合场景：

```text
学习 ADK 执行生命周期
开发期调试 Agent 行为
验证 Multi-Agent 执行顺序
确认 Tool 是否被调用
排查模型请求和工具调用链路
```

它通常返回 `Maybe.empty()`，表示只观察、不接管流程。

生产环境中，`LoggingPlugin` 可以作为基础调试能力，但通常不能完全替代企业级监控。原因是它不负责：

```text
结构化日志落库
TraceId / RequestId 贯穿
指标聚合
敏感信息脱敏
告警推送
成本统计
租户级治理
```

是否需要重写 `LoggingPlugin`？

一般不建议直接继承并重写 `LoggingPlugin`。更推荐单独写自己的 Plugin，例如：

```text
StructuredLoggingPlugin
MetricsPlugin
AuditPlugin
RedactionPlugin
AlertPlugin
CostTrackingPlugin
```

这样职责更清晰，也避免把“观察日志”和“业务治理”混在一起。

### GlobalInstructionPlugin

`GlobalInstructionPlugin` 是一个全局系统提示词注入器。

它会在 `beforeModelCallback` 阶段运行，在真正调用模型前，把一段全局 instruction 追加到 `LlmRequest.config().systemInstruction` 中。

它和 `LlmAgent.instruction(...)` 的边界是：

```text
LlmAgent.instruction        = 某个 Agent 自己的角色提示词
GlobalInstructionPlugin     = Runner 下所有模型调用共享的全局提示词
```

适合场景：

```text
所有 Agent 必须使用中文回答
所有 Agent 必须遵守统一合规规则
所有 Agent 必须遵守统一输出格式
所有 Agent 都要带上统一公司、产品或租户上下文
根据 Session State 动态注入全局提示词
```

示例：

```java
Runner.builder()
        .agent(agent)
        .appName(APP_NAME)
        .sessionService(sessionService)
        .plugins(
                new LoggingPlugin(),
                new GlobalInstructionPlugin("所有回答必须使用中文。"))
        .build();
```

如果不手动配置 `GlobalInstructionPlugin`，ADK 不会默认启用它。

Runner 默认只会创建一个空的 `PluginManager`。也就是说：

```text
不传 plugins(...)：
PluginManager 存在，但里面没有 LoggingPlugin，也没有 GlobalInstructionPlugin。
```

此时 `LlmAgent.instruction(...)` 仍然正常生效，只是没有额外的 Runner 全局 instruction。

什么时候需要重写或替代 `GlobalInstructionPlugin`？

大多数情况下不需要。可以直接传固定字符串，或传一个根据 `CallbackContext` 动态生成 instruction 的函数。

只有以下情况才建议写自己的 Plugin：

```text
只想对部分 Agent 注入全局提示词
需要根据 Agent 名称、用户、租户、权限等级动态注入
不只是追加 instruction，而是要替换、重排或删除已有 system instruction
需要在注入前做复杂校验，失败时直接中断流程
需要把全局 instruction 与审计、限流、安全策略联动
```

### PluginManager

`PluginManager` 是 ADK Runner 内部的插件调度器。

它负责：

```text
保存多个 Plugin
检查 Plugin name 不能重复
按注册顺序执行 callback
对 Maybe 类型 callback 使用第一个返回值短路规则
对 afterRunCallback、close 这类 Completable callback 按顺序全部执行
```

对于 `Maybe` 类型 callback，它的关键规则是：

```text
Plugin A -> Maybe.empty()
Plugin B -> Maybe.just(value)
Plugin C -> 不再执行当前 callback
```

也就是说，第一个返回非空结果的 Plugin 会接管当前阶段，并阻止后续同类 callback 继续执行。

适合把它理解成：

```text
Runner 内部的 Plugin middleware chain
```

什么时候需要重写或替代 `PluginManager`？

普通业务开发几乎不需要。

只有在你要封装自己的 Agent Runtime 或企业级平台层时，才可能考虑类似能力，例如：

```text
给 Plugin 增加优先级
按租户、环境、Agent 类型条件启用 Plugin
允许多个 Plugin 的返回值合并，而不是第一个返回值短路
对 Plugin 执行增加超时、熔断、隔离
统一包装 Plugin 执行链路的 tracing 和 metrics
```

对于当前学习项目，推荐边界是：

```text
写业务扩展：继承 BasePlugin。
看执行日志：使用 LoggingPlugin。
加全局提示词：使用 GlobalInstructionPlugin。
管理插件顺序：交给 Runner 和 PluginManager。
```
