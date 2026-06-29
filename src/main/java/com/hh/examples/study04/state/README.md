# study04.state

## 概念

`State` 是 ADK `Session` 内部的一张结构化 Map，用来保存当前会话里的可读写运行状态。

它和 `events` 的职责不同：

```text
events = 发生过什么
state  = 当前是什么状态
```

例如：

- 用户说过“我是小明”会进入 `events`。
- 工具把 `profile.name = 小明` 写入 `state`。
- 后续 Agent / Tool / Workflow 可以直接读取 `state` 中的结构化值。

## LangGraph 对照

在 LangGraph 中，State 通常是 graph state，例如 `MessagesState` 或自定义 `TypedDict` / state schema。

概念对照：

```text
LangGraph State        ≈ ADK Session.state()
LangGraph node update  ≈ ADK EventActions.stateDelta()
LangGraph checkpointer ≈ ADK SessionService
```

区别是：ADK 把 `Session` 作为运行时容器，`state` 是容器中的结构化状态；每次状态变化会通过事件里的 `stateDelta` 留痕。

## ADK 实现

本示例使用：

- `Session.state()` 保存当前会话状态。
- `ToolContext.state()` 在工具中读写 state。
- `EventActions.stateDelta()` 观察本轮工具写入了哪些 state key。
- `FunctionTool` 把 Java 方法暴露给 Agent 调用。

本示例只讲 State，不引入 Memory、Workflow 或多 Agent。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study04/state/StateExample.java
src/main/java/com/hh/examples/study04/state/StateTools.java
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
com.hh.examples.study04.state.StateExample
```

本示例会真实调用模型，因此需要 `OpenAiModelFactory` 中的模型配置可用。

## 关键源码分析

创建带初始 state 的 Session：

```java
runner.sessionService().createSession(
        runner.appName(),
        USER_ID,
        Map.of("profile.name", "未设置", "task.lastTopic", "未设置"),
        "state-demo-session")
```

工具写入 state：

```java
toolContext.state().put("profile.name", name);
```

注意：ADK Java 的 `FunctionTool` 会按参数名 `toolContext` 注入 `ToolContext`。如果 IDE 没有使用 `-parameters` 编译，运行时参数名可能变成 `arg1`，导致 `ToolContext` 注入失败。示例中对 `ToolContext` 参数显式标注 `@Schema(name = "toolContext")`，避免依赖 IDE 编译参数。

观察状态变更事件：

```java
event.actions().stateDelta()
```

读取更新后的 Session state：

```java
runner.sessionService()
        .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
        .blockingGet()
        .state()
```

## 可观察结果

运行后重点观察：

- 初始 state 中 `profile.name` 和 `task.lastTopic` 都是“未设置”。
- 用户告诉姓名后，工具调用会产生 `stateDelta`。
- `Session.state()` 中的 `profile.name` 会变成“小明”。
- 用户告诉学习话题后，`task.lastTopic` 会更新。
- 最后一轮读取 state 时，Agent 会基于当前 Session state 回答。

## 思考题

1. 什么信息应该放进 `state`，什么信息只应该留在 `events`？
2. `state` 和后续要学的 `Memory` 有什么边界？
3. 如果多个 Tool 同时写同一个 state key，生产环境应该如何避免冲突？
