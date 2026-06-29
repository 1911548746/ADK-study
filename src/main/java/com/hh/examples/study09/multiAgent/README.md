# study09.multiAgent

## 概念

`Multi-Agent` 不是简单地“有多个 Agent”，而是多个 Agent 按某种协作结构共同完成一个任务。

本章演示 fan-out / fan-in 模式：

```text
用户请求
  ↓
ParallelAgent 并行运行多个 worker
  ↓
worker 通过 outputKey 写入 Session state
  ↓
SequentialAgent 保证 summary_agent 最后运行
  ↓
summary_agent 读取 state 并汇总最终答案
```

它解决的问题是：同一个问题需要多个专业视角同时分析，但最终用户只需要一个统一答案。

## LangGraph 对照

在 LangGraph 中，这通常对应并行分支加汇聚节点：

```text
ADK ParallelAgent       ≈ 多个并行 worker node
ADK LlmAgent.outputKey  ≈ worker node 写 graph state
ADK SequentialAgent     ≈ 先 fan-out，再 fan-in 的固定执行顺序
ADK summary_agent       ≈ reducer / aggregator node
Session state           ≈ graph state
```

关键差异是：LangGraph 通常显式定义边和 reducer；ADK Java 提供 `ParallelAgent`、`SequentialAgent` 这类 workflow agent，用 Agent 组合表达执行结构。

## ADK 实现

本示例使用：

- `ParallelAgent` 并行运行 `architecture_worker`、`risk_worker`、`langgraph_worker`。
- 每个 worker 使用 `outputKey(...)` 把最终输出写入 `Session.state`。
- `SequentialAgent` 先运行并行团队，再运行 `summary_agent`。
- `summary_agent` 调用 `read_parallel_results` 工具读取并行结果。
- `event.actions().stateDelta()` 观察 worker 写入了哪些 state key。
- `event.author()` 观察当前事件由哪个 Agent 产生。

本章不使用 `transfer_to_agent`。第 08 章的 SubAgent 转交是“多个 Agent 中选一个执行”；本章是“多个 Agent 都执行，然后汇总”。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study09/multiAgent/MultiAgentExample.java
src/main/java/com/hh/examples/study09/multiAgent/MultiAgentTools.java
src/main/java/com/hh/examples/study09/multiAgent/SequentialOutputKeyExample.java
```

核心结构：

```java
ParallelAgent parallelWorkers = ParallelAgent.builder()
        .name("parallel_analysis_team")
        .subAgents(
                createArchitectureWorker(),
                createRiskWorker(),
                createLangGraphWorker())
        .build();

return SequentialAgent.builder()
        .name("parallel_then_summary_workflow")
        .subAgents(parallelWorkers, createSummaryAgent())
        .build();
```

worker 输出到 state：

```java
.outputKey(MultiAgentTools.ARCHITECTURE_RESULT_KEY)
```

汇总 Agent 读取 state：

```java
.tools(FunctionTool.create(MultiAgentTools.class, "readParallelResults"))
```

## SequentialAgent 与 outputKey 传递

本章还提供了一个顺序执行示例：

```text
src/main/java/com/hh/examples/study09/multiAgent/SequentialOutputKeyExample.java
```

它演示另一种常见模式：

```text
用户请求
  ↓
requirement_agent
  outputKey = requirement_brief
  ↓
design_agent
  instruction 中注入 {requirement_brief}
  outputKey = design_plan
  ↓
review_agent
  instruction 中注入 {design_plan}
  outputKey = review_report
```

关键点是：`outputKey(...)` 会把当前 `LlmAgent` 的最终输出写入 `Session.state`，而后续 Agent 的 `instruction` 可以用 `{stateKey}` 引用这个值。

例如：

```java
LlmAgent.builder()
        .name("design_agent")
        .instruction("""
                以下内容来自上一个 Agent 的 outputKey：requirement_brief。

                [上游需求摘要]
                {requirement_brief}

                请严格基于上游需求摘要设计方案。
                """)
        .outputKey("design_plan")
        .build();
```

这里 `{requirement_brief}` 会在 `design_agent` 调用模型前，由 ADK 从当前 Session state 中替换成上一步 Agent 的输出。

注意：instruction 占位符中的 state key 需要是合法标识符，推荐使用：

```text
requirement_brief
design_plan
review_report
```

不要使用带点号的 key，例如：

```text
multiAgent.architectureResult
```

这种 key 可以作为普通 state key 使用，但不适合直接写成 instruction 占位符。

## 运行流程

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study09.multiAgent.MultiAgentExample
```

如果要观察顺序 outputKey 注入，运行：

```text
com.hh.examples.study09.multiAgent.SequentialOutputKeyExample
```

本示例会真实调用模型。并行阶段会同时调用多个 worker，因此比单 Agent 示例更容易触发模型服务的并发、速率或超时限制。

## 关键源码分析

`ParallelAgent` 只负责编排，不直接生成 LLM 内容：

```java
ParallelAgent.builder()
        .subAgents(workerA, workerB, workerC)
        .build();
```

`SequentialAgent` 保证汇总阶段在并行阶段之后：

```java
SequentialAgent.builder()
        .subAgents(parallelWorkers, summaryAgent)
        .build();
```

`outputKey` 把 worker 的最终输出写入 `Session.state`，因此事件中可以观察到：

```java
event.actions().stateDelta()
```

汇总 Agent 不依赖“猜测前面 Agent 说了什么”，而是通过工具读取明确的 state key：

```java
read_parallel_results
```

## 实验步骤

1. 运行 `MultiAgentExample`。
2. 观察事件中是否出现 `architecture_worker`、`risk_worker`、`langgraph_worker`。
3. 观察 worker 事件是否出现 `stateDelta`，并写入三个 `multiAgent.*Result` key。
4. 观察 `summary_agent` 是否调用 `read_parallel_results`。
5. 观察最终输出是否综合了三个 worker 的结果。

## 可观察结果

运行后重点观察：

- 多个 worker 都会运行，不是只选择一个。
- worker 的输出通过 `outputKey` 进入 Session state。
- `summary_agent` 在 worker 之后运行。
- 最终回答由 `summary_agent` 生成，而不是由某个 worker 直接给出。
- Workflow agent 负责结构，LLM agent 负责语义推理。

## 思考题

1. 哪些任务适合并行 worker，哪些任务必须顺序执行？
2. worker 输出写入 `Session.state` 和直接依赖消息历史，各有什么优缺点？
3. 如果某个 worker 失败或超时，汇总 Agent 应该继续汇总部分结果，还是终止整轮流程？
