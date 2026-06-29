# study09.multiAgent 讨论笔记

## 1. 本章要解决的问题

第 08 章 `SubAgent` 讲的是：

```text
多个子 Agent 中选择一个执行
```

第 09 章 `MultiAgent` 讲的是：

```text
多个 Agent 都参与执行，并把结果汇总成一个最终答案
```

因此，本章示例采用 fan-out / fan-in 模式：

```text
用户请求
  ↓
ParallelAgent 并行运行多个 worker
  ↓
worker 把结果写入 Session state
  ↓
SequentialAgent 保证 summary_agent 最后运行
  ↓
summary_agent 读取多个 worker 的结果并汇总
```

## 2. 为什么使用 ParallelAgent 和 SequentialAgent

`ParallelAgent` 和 `SequentialAgent` 是 workflow agent。

它们不负责调用模型，不负责理解自然语言，也不负责生成回答。它们只负责控制子 Agent 如何运行。

```text
ParallelAgent   = 并行运行子 Agent
SequentialAgent = 按顺序运行子 Agent
LoopAgent       = 循环运行子 Agent
LlmAgent        = 调用模型、工具和提示词的智能节点
```

所以它们不需要指定 `model`。

需要指定 `model` 的是内部真正做语义推理的 `LlmAgent`：

```java
LlmAgent worker = LlmAgent.builder()
        .model(OpenAILangChainModelFactory.createChatModel())
        .instruction("...")
        .build();
```

本章结构是：

```text
SequentialAgent
  ├─ ParallelAgent
  │   ├─ architecture_worker  LlmAgent
  │   ├─ risk_worker          LlmAgent
  │   └─ langgraph_worker     LlmAgent
  └─ summary_agent            LlmAgent
```

含义是：

1. `SequentialAgent` 保证先执行并行分析，再执行汇总。
2. `ParallelAgent` 保证多个 worker 同时分析。
3. worker 负责各自专业视角的输出。
4. `summary_agent` 负责统一整理最终答案。

## 3. outputKey 和 Session state 的作用

本章让每个 worker 使用 `outputKey(...)`：

```java
.outputKey(MultiAgentTools.ARCHITECTURE_RESULT_KEY)
```

它的作用是把 worker 的最终输出写入 `Session.state`。

这样汇总 Agent 不需要从一堆自然语言事件中猜测前面 Agent 说了什么，而是读取明确的 state key：

```text
multiAgent.architectureResult
multiAgent.riskResult
multiAgent.langGraphResult
```

这种方式更适合工程化 fan-in：

```text
worker 输出自然语言结果
  ↓
ADK 写入 Session state
  ↓
summary_agent 调用 read_parallel_results
  ↓
summary_agent 汇总结构化输入
```

## 4. 共享 Session / Events 的真实含义

多个 Agent 在同一个 `runner.runAsync(...)` 调用链中运行时，它们共享同一个 Session。

因此，所有 Agent 产生的事件都会进入同一个 Session events：

```text
architecture_worker 的事件
risk_worker 的事件
langgraph_worker 的事件
summary_agent 的事件
stateDelta
tool call
final response
```

可以通过 `event.author()` 观察每个事件来自哪个 Agent。

这说明：

```text
Session = 共享运行空间
Events  = 共享执行历史 / 审计轨迹
State   = 共享结构化工作区
```

## 5. 一个重要误区：共享 Events 不等于实时互相监听

容易误解的一点是：

```text
多个 Agent 共享 Session events
```

不等于：

```text
多个 Agent 会实时订阅其他 Agent 的最新 event，并立即根据它行动
```

在 `ParallelAgent` 中，多个 worker 通常是基于同一轮用户输入和已有上下文并行启动。

它们不会默认像聊天室或消息队列一样实时监听彼此刚刚产生的 event。

也就是说：

```text
worker A 输出 event
worker B 不会自动看到这个新 event 并立刻调整自己的行为
```

更准确的理解是：

```text
共享 events = 统一记录运行轨迹
不是实时 pub-sub / actor mailbox
```

## 6. 如果需要实时协作，应该如何建模

如果流程真的需要“Agent 根据其他 Agent 的最新输出继续行动”，需要显式建模。

一种方式是使用 `LoopAgent`：

```text
LoopAgent
  └─ SequentialAgent
      ├─ observer_agent 读取最新 state/events
      ├─ worker_a 根据观察结果行动
      ├─ worker_b 根据观察结果行动
      └─ coordinator 判断是否继续
```

另一种方式是在应用层做事件分发：

```text
订阅 runner.runAsync 的 Event 流
  ↓
把关键 event 写入 state / 数据库 / 消息队列
  ↓
后续 Agent 显式读取这些消息
```

这类设计已经超出普通 fan-out/fan-in，接近 actor、blackboard 或 multi-agent conversation pattern。

ADK 不会替你隐式完成这件事；它更鼓励你把协作边界显式写出来。

## 7. LoopAgent 的适用场景

`LoopAgent` 适合重复执行一组步骤：

```text
draft → review → revise → review → revise
```

或者：

```text
plan → execute → evaluate → improve → evaluate
```

Java 写法类似：

```java
LoopAgent.builder()
        .name("review_loop")
        .subAgents(draftAgent, reviewAgent)
        .maxIterations(3)
        .build();
```

`LoopAgent` 本身也不需要 `model`，因为它只是 workflow agent。

真正调用模型的是它内部的 `LlmAgent`。

## 8. 与 LangGraph 的对照

本章 fan-out / fan-in 在 LangGraph 中通常对应：

```text
ParallelAgent       ≈ 多个并行 node
outputKey           ≈ node 写 graph state
SequentialAgent     ≈ 固定执行顺序
summary_agent       ≈ reducer / aggregator node
Session state       ≈ graph state
Events              ≈ streaming updates / traces / messages
```

如果要做实时协作，在 LangGraph 中通常也不会只靠消息历史自然发生，而是需要：

```text
显式 state
条件边
Command goto
循环边
外部消息队列
```

这和 ADK 的思想是一致的：复杂协作要显式建模。

## 9. 本章最终结论

第 09 章推荐记住三句话：

```text
SubAgent transfer = 多个 Agent 中选一个执行。
ParallelAgent + SequentialAgent = 多个 Agent 都执行，并控制汇总顺序。
共享 events 是运行轨迹，不是实时消息总线。
```

本章示例采用 `ParallelAgent + outputKey + summary_agent`，是因为它清晰表达了：

```text
并行分析
结构化交接
统一汇总
```

这是学习 ADK Multi-Agent 时最稳定、最容易观察、也最接近工程实践的模式。

### 类型
LlmAgent：最常用，负责调用模型、工具、transfer、outputKey。

SequentialAgent：按顺序执行子 Agent。

ParallelAgent：并行执行子 Agent。

LoopAgent：循环执行子 Agent，有 maxIterations()。

BaseAgent：抽象基类，自己不直接使用，除非你要自定义 Agent。
