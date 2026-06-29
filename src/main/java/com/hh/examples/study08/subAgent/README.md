# study08.subAgent

## 概念

`Sub-Agent` 是把一个 Agent 的能力拆给更专门的子 Agent，由父 Agent 负责选择是否转交。

它解决的问题是职责分离：

- 父 Agent 负责理解用户意图和路由。
- 子 Agent 负责一个更窄、更稳定的专业任务。
- 每个子 Agent 可以拥有独立的 `instruction`、`description`、模型配置和工具集合。
- 运行过程仍然通过统一的 `Event` 暴露，便于观察转交流程。

本章只讲父子 Agent delegation，不展开复杂团队协作、并行执行或 Workflow。

## LangGraph 对照

在 LangGraph 中，类似能力通常通过 router node、conditional edge 或 Command goto 实现：

```text
ADK root agent + subAgents(...)       ≈ LangGraph router node + worker nodes
transfer_to_agent                    ≈ conditional edge / Command goto
event.actions().transferToAgent()    ≈ graph 运行路径切换信号
sub-agent instruction                ≈ worker node 的局部提示词和职责边界
```

关键差异是：LangGraph 的路由通常由代码里的图结构显式定义；ADK 的 `LlmAgent` 在发现有可转交目标时，会把 `transfer_to_agent` 作为工具注入给模型，让模型根据子 Agent 的 `description` 和指令选择目标。

## ADK 实现

本示例使用：

- `LlmAgent.builder().subAgents(...)` 注册子 Agent。
- 父 Agent `sub_agent_coordinator` 只负责路由。
- `adk_architecture_agent` 负责解释 ADK 核心架构。
- `langgraph_compare_agent` 负责做 ADK 与 LangGraph 的设计对照。
- `event.actions().transferToAgent()` 观察实际转交目标。
- `event.author()` 观察当前事件由哪个 Agent 产生。

子 Agent 设置了：

```java
.disallowTransferToParent(true)
.disallowTransferToPeers(true)
```

这样本章只观察“父 Agent 转交给子 Agent”，避免子 Agent 再转回父 Agent 或转给同级 Agent，降低学习噪声。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study08/subAgent/SubAgentExample.java
```

核心注册方式：

```java
return LlmAgent.builder()
        .name("sub_agent_coordinator")
        .model(OpenAILangChainModelFactory.createChatModel())
        .instruction("根据用户问题选择最合适的子 Agent，并使用 transfer_to_agent。")
        .subAgents(adkArchitectureAgent, langGraphCompareAgent)
        .build();
```

## 运行流程

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study08.subAgent.SubAgentExample
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用。

## 关键源码分析

父 Agent 注册子 Agent：

```java
.subAgents(adkArchitectureAgent, langGraphCompareAgent)
```

当父 Agent 有可转交目标时，ADK 会把内置 `transfer_to_agent` 工具加入本轮模型请求。模型选择转交后，事件中可以观察到：

```java
event.actions().transferToAgent()
```

观察当前事件来源：

```java
event.author()
```

一次典型运行中，你会看到：

```text
author: sub_agent_coordinator
functionCalls: [transfer_to_agent(...)]
transferToAgent: adk_architecture_agent
author: adk_architecture_agent
content: ...
```

这说明父 Agent 没有自己完成回答，而是把控制权交给了更专门的子 Agent。

## 实验步骤

1. 运行 `SubAgentExample`。
2. 观察第一个问题是否转交给 `adk_architecture_agent`。
3. 观察第二个问题是否转交给 `langgraph_compare_agent`。
4. 修改父 Agent 的 `description` 或路由规则，观察模型是否会改变转交目标。
5. 去掉子 Agent 的 `disallowTransferToParent(true)`，观察子 Agent 是否可能继续转交。

## 可观察结果

运行后重点观察：

- 父 Agent 会产生 `transfer_to_agent` 工具调用事件。
- `transferToAgent` 记录实际转交目标。
- 子 Agent 的事件 `author` 会变成子 Agent 名称。
- 子 Agent 使用自己的 instruction 回答，而不是继续使用父 Agent 的路由指令。
- Sub-Agent 是 Agent 职责拆分机制，不等于固定流程编排。

## 思考题

1. 什么场景适合用 Sub-Agent，而不是给一个 Agent 写更长的 instruction？
2. 在 LangGraph 中，哪些路由逻辑更适合写成代码条件，哪些适合交给模型判断？
3. 如果子 Agent 可以继续转交给 parent 或 peer，系统会获得什么灵活性，又会带来什么风险？
