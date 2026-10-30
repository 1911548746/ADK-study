# study14.plugin

## 概念

Plugin 是挂在 `Runner` 上的横切扩展点。

它解决的问题是：有些逻辑不属于某个具体 Agent，也不应该写进 instruction 或 tool 中，例如：

- 统一日志
- 耗时统计
- 事件计数
- 审计
- 模型调用前后观察
- 工具调用前后观察
- 运行结束后的清理

这些逻辑更适合放在 Runner Plugin 层。

```text
User
  -> Runner
     -> Plugins
     -> Agent
     -> Model / Tools
     -> Events
     -> Plugins
```

## LangGraph 对照

在 LangGraph 中，类似能力通常由：

```text
callbacks
middleware
tracing handler
stream observer
checkpointer hook
```

来完成。

ADK 中的 Plugin 更像是 Runner 级别的 middleware。它不是某个 Agent 的业务能力，而是整个一次 `runner.runAsync(...)` 调用链的观察和干预点。

## ADK 实现

ADK Java 1.4.0 中相关类型：

```java
com.google.adk.plugins.Plugin
com.google.adk.plugins.BasePlugin
com.google.adk.plugins.LoggingPlugin
com.google.adk.plugins.ContextFilterPlugin
```

Runner 支持配置多个 Plugin：

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

Plugin 常见 callback：

```java
onUserMessageCallback
beforeRunCallback
onEventCallback
afterRunCallback
beforeAgentCallback
afterAgentCallback
beforeModelCallback
afterModelCallback
onModelErrorCallback
beforeToolCallback
afterToolCallback
onToolErrorCallback
close
```

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study14/plugin/PluginExample.java
```

本示例配置了三个 Plugin：

```text
LoggingPlugin       ADK 内置日志 Plugin
RunTimingPlugin     自定义运行耗时统计
EventCounterPlugin  自定义事件和工具调用观察
```

Agent 本身仍然只是普通 `LlmAgent`：

```java
LlmAgent.builder()
        .instruction("当用户询问天气时，必须调用 get_weather。")
        .tools(
                FunctionTool.create(SimpleTextTools.class, "getWeather"),
                FunctionTool.create(SimpleTextTools.class, "makeGreeting"))
        .build();
```

这说明 Plugin 不替代 Tool。Tool 是 Agent 能力；Plugin 是运行时横切逻辑。

## 运行流程

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study14.plugin.PluginExample
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用。

## 关键源码分析

Runner 配置多个 Plugin：

```java
List<Plugin> plugins = List.of(
        new LoggingPlugin("study14_logging_plugin"),
        new RunTimingPlugin(),
        new EventCounterPlugin());

Runner.builder()
        .plugins(plugins)
        .build();
```

运行耗时统计：

```java
invocationContext.callbackContextData().put(START_NANOS_KEY, System.nanoTime());
```

`callbackContextData()` 是本次 invocation 内部共享的临时数据区，适合 Plugin 之间或同一个 Plugin 的多个 callback 之间传递运行时数据。

事件观察：

```java
public Maybe<Event> onEventCallback(
        InvocationContext invocationContext,
        Event event)
```

工具调用观察：

```java
public Maybe<Map<String, Object>> beforeToolCallback(
        BaseTool tool,
        Map<String, Object> input,
        ToolContext toolContext)
```

运行结束清理：

```java
public Completable afterRunCallback(InvocationContext invocationContext)
```

Runner 关闭时：

```java
runner.close().blockingAwait();
```

这会触发 Plugin 的 `close()`。

## 可观察结果

运行后重点观察控制台：

- `RunTimingPlugin` 会打印 user message、beforeRun、afterRun 耗时。
- `EventCounterPlugin` 会打印 beforeAgent、onEvent、beforeTool、afterTool。
- 如果模型按 instruction 调用工具，会看到 `get_weather` 或 `make_greeting` 的工具调用日志。
- 主程序仍然会打印 ADK Event 流。
- `runner.close()` 会触发自定义 Plugin 的 close 日志。

## Plugin 与 Callback 的关系

`LlmAgent` 自身也支持 callback：

```java
beforeModelCallback
afterModelCallback
beforeToolCallback
afterToolCallback
```

二者边界可以这样理解：

```text
Agent callback = 某个 Agent 的局部行为扩展
Runner Plugin  = 整个 Runner 调用链的全局横切扩展
```

如果逻辑只属于某个 Agent，放在 Agent callback。

如果逻辑应该覆盖整个应用、所有 Agent、所有工具调用，放在 Runner Plugin。

## 生产使用建议

适合做 Plugin：

- traceId / requestId 注入
- 统一日志
- token / latency / error 指标采集
- 工具调用审计
- 敏感内容过滤
- 模型错误兜底
- 运行时成本统计

不适合做 Plugin：

- Agent 的业务推理逻辑
- 用户可见的工具能力
- 某个单一工具的核心实现
- 需要模型理解的任务说明

## 思考题

1. 为什么日志、耗时、审计更适合放在 Runner Plugin，而不是写进每个 Agent 的 instruction？
2. 如果一个系统里有多个 Agent，哪些逻辑应该做成 Agent callback，哪些应该做成 Plugin？
3. Plugin 是否应该修改模型输入和工具输出？在什么情况下可以，什么情况下不应该？
