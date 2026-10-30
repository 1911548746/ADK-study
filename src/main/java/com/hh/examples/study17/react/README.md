# study17.react

## 概念

`ReAct` 是 Reasoning + Acting。Agent 不直接根据模型已有知识给出答案，而是在任务执行过程中反复进行：

```text
判断当前缺少什么信息
  -> Action：选择并调用工具
  -> Observation：读取工具结果
  -> 根据新信息决定下一步
  -> 证据充分后输出 Final Answer
```

传统 ReAct 示例经常让模型输出：

```text
Thought: ...
Action: search(...)
Observation: ...
```

现代 ADK 不需要模型用文本模拟这个协议。Action 使用结构化 `FunctionCall`，Observation 使用结构化 `FunctionResponse`，两者都记录为 `Event`。模型的内部推理不需要暴露；系统可以审计它实际选择了什么工具、传入什么参数、看到了什么结果，以及最终给出了什么结论。

## 该能力解决什么问题

普通单轮问答适合直接生成文本，但不适合需要动态收集证据的任务。例如线上故障诊断时，下一步检查什么取决于上一步观察到了什么：

```text
先查看所有服务
  -> 发现 order-service 异常
  -> 查看它的指标和日志
  -> 发现数据库连接池耗尽
  -> 再检查近期变更
  -> 根据故障类型检索处置手册
```

这不是预先固定的 `SequentialAgent`。顺序由模型根据每次 Observation 动态决定，因此适合诊断、检索、调研、客服排障和多工具任务。

## LangGraph 对照

LangGraph 中常见的 ReAct 图是：

```text
agent node
  -> 有 tool calls：ToolNode -> agent node
  -> 无 tool calls：END
```

对应到 ADK：

| LangGraph | ADK |
|---|---|
| agent node | `LlmAgent` 的一次模型调用 |
| `ToolNode` | `FunctionTool` 执行 |
| conditional edge | ADK LLM Flow 根据模型是否返回 FunctionCall 自动决定 |
| messages state | Session 中的 Events 与 Contents |
| recursion limit | `LlmAgent.maxSteps(...)` |

LangGraph 把循环边显式画在图里；ADK 把常见的模型-工具循环内置在 `LlmAgent` 执行流中。需要固定业务流程时再使用 `SequentialAgent`、`ParallelAgent` 或 `LoopAgent`。

## ADK 实现

ADK Java 没有必须单独实例化的 `ReActAgent` 类型。最小组合是：

```text
LlmAgent
  + instruction：约束决策策略和停止条件
  + FunctionTool：提供可执行 Action
  + Runner：驱动模型与工具反复交互
  + Session / Event：保存 Observation 和执行轨迹
```

本章模拟一次生产故障诊断，提供五个只读工具：

- `list_service_health`：发现候选异常服务。
- `get_service_metrics`：查看资源、错误率和连接池指标。
- `search_service_logs`：获取日志证据。
- `get_recent_deployments`：只在怀疑近期变更时检查发布记录。
- `get_incident_runbook`：根据已确定的故障类型获取处置建议。

工具只提供事实和能力，不决定调用顺序。`LlmAgent` 根据每一轮 Observation 选择下一次 Action。

## 完整代码

```text
src/main/java/com/hh/examples/study17/react/ReActExample.java
src/main/java/com/hh/examples/study17/react/ReActTools.java
```

Agent 的核心配置：

```java
return LlmAgent.builder()
        .name("incident_react_agent")
        .model(OpenAILangChainModelFactory.createChatModel())
        .instruction("根据每次 Observation 判断下一步；证据足够后停止调用工具。")
        .tools(
                FunctionTool.create(ReActTools.class, "listServiceHealth"),
                FunctionTool.create(ReActTools.class, "getServiceMetrics"),
                FunctionTool.create(ReActTools.class, "searchServiceLogs"),
                FunctionTool.create(ReActTools.class, "getRecentDeployments"),
                FunctionTool.create(ReActTools.class, "getIncidentRunbook"))
        .maxSteps(8)
        .build();
```

## 运行方式

先确认 `OpenAILangChainModelFactory` 中的模型支持 Tool Calling，然后在项目根目录编译：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

在 IDE 中运行：

```text
com.hh.examples.study17.react.ReActExample
```

## 运行流程

用户请求只发送一次：

```text
生产环境的订单接口正在大面积超时。请定位最可能的原因，并给出处置建议。
```

`runner.runAsync(...)` 内部可能多次调用模型：

```text
用户消息
  -> LLM 选择 list_service_health
  -> Runner 执行工具并写入 FunctionResponse Event
  -> LLM 读取 Observation，选择下一工具
  -> Runner 再次执行工具
  -> ...
  -> LLM 不再返回 FunctionCall，生成 Final Answer
```

应用只需要订阅一个 `Flowable<Event>`，不需要自己写 `while` 循环。

## 关键源码分析

### Action

模型返回 FunctionCall 后，ADK Event 可以直接读取：

```java
event.functionCalls()
```

案例将每个调用打印为：

```text
[Action 1]
FunctionCall{...}
```

### Observation

Runner 执行工具后会产生 FunctionResponse：

```java
event.functionResponses()
```

案例将其打印为：

```text
[Observation 1]
FunctionResponse{...}
```

FunctionResponse 会进入同一 Session 的事件历史，下一次模型调用可以看到它。这就是 ReAct 中“根据最新 Observation 再推理”的基础。

### 停止条件

正常停止条件是模型认为证据充分，不再返回 FunctionCall，而是返回最终文本。案例还使用：

```java
.maxSteps(8)
```

`maxSteps` 不是“最多调用八次工具”，而是当前 `LlmAgent` 在一次 invocation 内最多执行八个 LLM Flow step。一个 step 大致包含：

```text
构造 LLM 请求
  -> 调用模型一次
  -> 处理模型响应
  -> 若有 FunctionCall，执行对应工具并写入 Observation
  -> 决定是否进入下一 step
```

一次模型响应可以同时包含多个 FunctionCall，因此一个 step 内可能执行多个工具。例如：

```text
Step 1：模型调用 -> list_service_health
Step 2：模型调用 -> get_service_metrics + search_service_logs
Step 3：模型调用 -> get_recent_deployments
Step 4：模型调用 -> get_incident_runbook
Step 5：模型调用 -> 生成 Final Answer
```

这个例子有 5 个 step，却有 5 次工具调用；如果某一步并行调用多个工具，工具总次数可以大于 `maxSteps`。同时，最终回答通常还需要一次模型调用，所以不能把 `maxSteps` 简单理解为“允许收集几次 Observation”。

`maxSteps` 的计数范围是单个 `LlmAgent` 的一次 `runner.runAsync(...)` invocation，不会跨用户轮次累计。下一次 `runAsync(...)` 会从 0 重新计数；多 Agent 系统中，每个配置了 `maxSteps` 的 `LlmAgent` 也各自拥有自己的上限。

如果需要限制一次 Runner invocation 中所有 Agent 的模型调用总数，应使用 `RunConfig.maxLlmCalls(...)`：

```java
RunConfig runConfig = RunConfig.builder()
        .maxLlmCalls(12)
        .build();
```

```text
LlmAgent.maxSteps    = 单个 Agent 的 ReAct/LLM Flow 步数上限
RunConfig.maxLlmCalls = 整次 invocation 的 LLM 调用总预算
```

当前的 `.maxSteps(8)` 是安全上限，不是期望模型一定执行八步。生产系统还应结合 `maxLlmCalls`、超时、工具调用预算、重复调用检测和人工审批；若要严格限制“工具执行总次数”，则需要在 Tool Callback、Plugin 或应用层单独计数和终止。

## ReAct 与 LoopAgent 的区别

```text
ReAct 工具循环：同一个 LlmAgent 在模型调用和工具调用之间反复交互
LoopAgent：按配置重复运行一个或多个完整的子 Agent
```

如果任务是“查资料，观察结果，再决定查什么”，使用 ReAct 式 Tool Calling。若任务是“生成内容，审查，不合格就重写，直到通过”，更适合 `LoopAgent` 或显式工作流。

## 可观察结果

一次典型运行会看到：

```text
[Action 1] list_service_health
[Observation 1] order-service DEGRADED

[Action 2] get_service_metrics
[Observation 2] dbPoolActive=40, dbPoolMax=40, pending=127

[Action 3] search_service_logs
[Observation 3] Timeout waiting for connection from database pool

[Action 4] get_recent_deployments
[Observation 4] maxPoolSize 从 120 调整为 40

[Action 5] get_incident_runbook
[Observation 5] 回滚配置并观察连接池和错误率

[Final Answer]
最可能根因、关键证据、建议动作和不确定性
```

模型可能交换指标和日志的调用顺序，也可能使用不同关键词搜索日志。只要每一步都由 Observation 支撑，这种差异就是 ReAct 的正常动态行为。

## 实验步骤

1. 正常运行，观察工具顺序是否由返回数据逐步收敛到根因。
2. 把 `order-service` 的连接池指标改为正常值，只保留高 CPU，再运行并观察 Agent 是否改变工具选择和结论。
3. 临时移除 `get_recent_deployments` 工具，观察 Agent 是否明确说明缺少变更证据，而不是编造发布记录。
4. 把 `.maxSteps(8)` 调小，观察证据尚未收集完整时 Agent 如何结束。

## 思考题

1. 为什么打印 Action 和 Observation 比要求模型公开完整 Thought 更适合生产审计？
2. 哪些工具可以让 ReAct Agent 自动执行，哪些高风险 Action 必须增加 HITL 审批？
3. 当 Agent 连续重复调用同一工具和参数时，应由 instruction、Plugin 还是应用层终止流程？
