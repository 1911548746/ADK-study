# study19.plan

## 概念

本章学习 ADK 的 Plan 模式：先根据任务和当前状态决定下一步，再执行被选中的 Agent，并在执行结果写入 Session state 后重新规划。

ADK 中没有一个通用的 `planMode=true` 开关。对应的组合是：

```text
PlannerAgent
  + Planner 策略
  + 多个可调度的子 Agent
  + Session state / Events
```

本章使用 `SupervisorPlanner`。它会让一个 LLM 读取可用 Agent、当前 state、近期 Events 和原始用户请求，然后返回下一步要运行的 Agent 名称，或者返回 `DONE`。`PlannerAgent` 再把这个规划结果转成 `PlannerAction` 并执行。

因此，这里的“计划”不是强制让模型输出完整的内部思考过程，而是一个可执行的调度决策：

```text
PlannerAction.RunAgents(research_agent)
  -> research_agent 写入 research_notes
  -> PlannerAction.RunAgents(implementation_agent)
  -> implementation_agent 写入 implementation_plan
  -> PlannerAction.RunAgents(reviewer_agent)
  -> reviewer_agent 写入 review_report
  -> PlannerAction.Done
```

## 该能力解决什么问题

固定流程可以使用 `SequentialAgent`，独立任务可以使用 `ParallelAgent`，但有些任务需要根据中间结果决定下一步。例如：

- 资料足够时跳过某个研究 Agent。
- 发现风险后追加审查 Agent。
- 某个结果缺失时重新安排生产 Agent。
- 不同用户请求选择不同的专业 Agent。

这类问题需要“动态调度”，而不是只把执行顺序写死在代码中。

## LangGraph 对照

LangGraph 中可以用一个 supervisor/planner node 读取 graph state，返回下一个节点，再通过 conditional edge 路由：

```text
planner node
  -> conditional edge
  -> worker node
  -> graph state update
  -> planner node
```

对应关系如下：

| LangGraph | ADK |
|---|---|
| supervisor/planner node | `Planner` / `SupervisorPlanner` |
| graph state | Session state |
| conditional edge | `PlannerAction.RunAgents` 选择的 Agent |
| `END` | `PlannerAction.Done` |
| graph recursion limit | `PlannerAgent.maxIterations(...)` |
| node execution events | ADK `Event` |

ADK 把常见的调度循环封装在 `PlannerAgent` 中；开发者主要实现或选择规划策略，而不必手写 RxJava 的执行循环。

## ADK 实现

### 依赖

Planner 模块独立发布，需要在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-planners</artifactId>
    <version>1.7.0</version>
</dependency>
```

### PlannerAgent

`PlannerAgent` 本身是一个 `BaseAgent`，但它不负责生成业务答案。它负责维护规划循环：

1. 创建 `PlanningContext`。
2. 调用 `planner.init(...)`。
3. 调用 `firstAction(...)`。
4. 执行 `RunAgents` 中的一个或多个子 Agent。
5. 将子 Agent 产生的 Events 和 state 更新交回 Planner。
6. 调用 `nextAction(...)`，直到 `Done` 或达到 `maxIterations`。

### SupervisorPlanner

`SupervisorPlanner` 是 LLM 驱动的规划器。它不是业务 Agent，也不会直接执行工具；它只返回下一步的调度动作。

本示例用一个严格的状态依赖约束规划：

```text
没有 research_notes
  -> research_agent

有 research_notes，没有 implementation_plan
  -> implementation_agent

有前两项，没有 review_report
  -> reviewer_agent

有 review_report
  -> DONE
```

三个子 Agent 使用 `outputKey` 把结果写入 state，后续 Agent 的 `instruction` 用 `{research_notes}` 和 `{implementation_plan}` 读取上游结果。Planner 读取的是执行后的 state，因此每轮规划都能看到最新状态。

### 计划决策与 Event 的区别

Planner 的 `firstAction` / `nextAction` 返回值不是普通 `Event`，因此只订阅 `runner.runAsync(...)` 时，通常看不到“规划器决定运行谁”的完整结构。示例中的 `TracingPlanner` 包装真实 Planner，在返回 `PlannerAction` 时打印：

```text
[Plan firstAction] RUN_AGENTS: research_agent
[Plan nextAction] RUN_AGENTS: implementation_agent
[Plan nextAction] RUN_AGENTS: reviewer_agent
[Plan nextAction] Done
```

随后仍会看到 `research_agent`、`implementation_agent`、`reviewer_agent` 的 ADK Events。生产系统可以用类似方式记录 planner decision，但不要把规划器的内部 prompt 或敏感 state 无过滤地写入日志。

## 完整代码

```text
src/main/java/com/hh/examples/study19/plan/PlanModeExample.java
```

运行入口中的核心结构：

```java
Planner planner = new SupervisorPlanner(
        OpenAILangChainModelFactory.createChatModel(),
        supervisorInstruction,
        16);

return PlannerAgent.builder()
        .name("plan_mode_coordinator")
        .subAgents(researchAgent, implementationAgent, reviewerAgent)
        .planner(planner)
        .maxIterations(6)
        .build();
```

本示例使用同一个模型工厂创建四个模型实例：三个业务 Agent 各一个，规划器一个。它们职责不同：业务 Agent 生成业务结果，SupervisorPlanner 只决定下一步运行哪个 Agent。

## 其他 Planner 类型

`google-adk-planners` 还提供：

| Planner | 是否需要 LLM | 适用场景 |
|---|---:|---|
| `SequentialPlanner` | 否 | 固定顺序流水线 |
| `ParallelPlanner` | 否 | 所有子 Agent 互不依赖，统一并行 |
| `LoopPlanner` | 否 | 固定循环执行，直到次数上限或升级事件 |
| `GoalOrientedPlanner` | 否 | 根据 Agent 的 input/output state key 解析依赖图 |
| `P2PPlanner` | 否 | state 变化触发相关 Agent 重新激活 |
| `SupervisorPlanner` | 是 | 由 LLM 动态选择下一步 Agent |

如果执行顺序和依赖关系完全确定，优先使用 `SequentialAgent`、`ParallelAgent` 或对应的确定性 Planner。只有当调度决策确实依赖运行时信息时，才让 LLM 参与规划，否则会增加成本、延迟和不确定性。

## Plan 模式与 ReAct 的区别

两者都可能出现“观察结果后再决定下一步”，但决策对象不同：

```text
ReAct:
  LlmAgent -> 选择 FunctionTool -> 得到 FunctionResponse -> 再调用 LLM

Plan 模式:
  PlannerAgent -> 选择子 Agent -> 子 Agent 运行并写入 state/events -> 再规划
```

ReAct 主要解决“下一步调用哪个工具”；Plan 模式主要解决“下一步让哪个 Agent 或工作流承担哪一段任务”。两者可以嵌套：一个 PlannerAgent 调度多个 LlmAgent，而每个 LlmAgent 内部又可以通过 Tool Calling 形成 ReAct 循环。

## 运行方式

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study19.plan.PlanModeExample
```

该示例需要模型支持普通文本生成。运行时规划器会额外发起模型调用，因此总调用次数通常高于三个业务 Agent 的调用次数。

## 运行流程

```text
用户请求
  -> SupervisorPlanner 读取可用 Agent 和当前 state
  -> 选择 research_agent
  -> research_agent 生成 research_notes
  -> SupervisorPlanner 再次规划
  -> 选择 implementation_agent
  -> implementation_agent 读取 {research_notes}
  -> 写入 implementation_plan
  -> SupervisorPlanner 再次规划
  -> 选择 reviewer_agent
  -> reviewer_agent 读取两份上游结果
  -> 写入 review_report
  -> SupervisorPlanner 返回 DONE
```

注意：规划器每次返回的是下一步动作，不等同于一次性生成一份不可变的完整计划。中间 state 或 Event 变化后，它可以重新决定后续动作，这也是动态 Plan 模式与静态 `SequentialAgent` 的核心差异。

## 关键源码分析

### `PlannerAction`

Planner 主要返回四类动作：

- `RunAgents`：运行一个或多个子 Agent。
- `Done`：结束规划，不额外生成结果。
- `DoneWithResult`：结束并直接产生一个结果文本。
- `NoOp`：本轮不运行 Agent，继续请求下一步动作。

### `maxIterations`

```java
.maxIterations(6)
```

它限制的是 `PlannerAgent` 的规划循环迭代次数，不是单个 Agent 的工具调用次数，也不是整次 Runner invocation 的总模型调用预算。被调度的 `LlmAgent` 仍然有自己的 `.maxSteps(...)`，整次运行还可以用 `RunConfig.maxLlmCalls(...)` 设置全局 LLM 调用上限。

### `outputKey` 与 state

`outputKey` 是本示例中子 Agent 和 Planner 之间的共享契约：

```java
.outputKey("research_notes")
```

它将子 Agent 的最终输出写入当前 Session state。Planner 读取 `PlanningContext.state()` 判断下一步是否具备前置条件；下游 Agent 则通过 instruction 占位符读取上游内容。

### 可观察性

建议分别记录三类信息：

1. Planner decision：选择了哪个 Agent，原因可记录为结构化元数据。
2. Agent Event：谁产生了什么状态更新或工具调用。
3. Invocation outcome：最终成功、失败、超时或达到预算。

不要仅根据最终文本判断计划是否正确；应结合 PlannerAction、Agent author、stateDelta 和错误事件恢复出完整执行轨迹。

## 实验步骤

1. 正常运行，观察 `research_agent`、`implementation_agent`、`reviewer_agent` 的选择顺序。
2. 把 `SUPERVISOR_INSTRUCTION` 中的顺序改成允许 reviewer 提前运行，观察缺少 state 时 instruction 的行为，并思考为什么生产环境应由依赖图或应用层校验兜底。
3. 将 `.maxIterations(6)` 改为 `2`，观察计划尚未完成时如何提前终止。
4. 把 `SupervisorPlanner` 替换为 `new SequentialPlanner()`，比较 LLM 动态规划与确定性流水线的调用成本和稳定性。
5. 在某个子 Agent 中加入 Tool Calling，观察 Plan 模式如何调度 Agent，而 Agent 内部如何继续执行 ReAct。

## 思考题

1. 哪些决策应该交给 LLM Planner，哪些决策必须由代码中的依赖图、权限校验或 HITL 强制约束？
2. Planner 重复选择同一个 Agent 时，应通过 instruction、`maxIterations`、Plugin 还是自定义 Planner 终止？
3. 当一个 PlannerAgent 调度另一个 PlannerAgent 时，应该如何分别设置规划循环上限和全局 `maxLlmCalls`？
