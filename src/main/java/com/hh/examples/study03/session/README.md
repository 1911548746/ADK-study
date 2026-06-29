# study03.session

## 概念

`Session` 是 ADK 运行时保存当前会话上下文的核心对象。

它通常包含：

- `appName`：应用名。
- `userId`：用户标识。
- `sessionId`：一次会话的唯一标识。
- `state`：当前会话的结构化状态。
- `events`：当前会话内的事件历史。

本示例只关注 `Session` 与 `events`，不展开 `state`、`Memory`、`Tool` 或 `Workflow`。

## LangGraph 对照

在 LangGraph 中，你通常会用 `thread_id` 加 `checkpointer` 保存一次对话线程的状态。

概念上可以这样对照：

```text
ADK SessionService  ≈ LangGraph checkpointer
ADK Session.id      ≈ LangGraph thread_id
ADK Session.events  ≈ LangGraph messages / state history
ADK Runner          ≈ graph.invoke / graph.stream 的执行入口
```

区别在于，ADK 把 `SessionService` 显式放在运行时边界中；`Runner` 每次执行都要知道用户和会话，因此多用户、多会话、审计和回放的边界更清楚。

## ADK 实现

本示例使用：

- `InMemoryRunner`：内置内存运行器。
- `InMemorySessionService`：由 `InMemoryRunner` 内部提供。
- `createSession`：创建新会话。
- `runAsync`：在指定 `sessionId` 下执行一轮用户输入。
- `getSession`：读取更新后的 Session。
- `listEvents`：查看当前 Session 内的事件历史。

示例会创建两个 Session：

1. 第一个 Session 中，用户先说“我是小明”，再问“我叫什么名字？”
2. 第二个 Session 中，用户直接问“我叫什么名字？”

预期观察点是：第一个 Session 可以利用当前会话历史；第二个 Session 是新的上下文，不应该继承第一个 Session 的事件历史。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study03/session/SessionExample.java
```

## 运行流程

在项目根目录执行编译：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study03.session.SessionExample
```

本示例会真实调用模型，因此需要 `OpenAiModelFactory` 中的模型配置可用。

## 关键源码分析

创建 Session：

```java
Session firstSession = runner.sessionService()
        .createSession(runner.appName(), USER_ID)
        .blockingGet();
```

在指定 Session 中运行一轮输入：

```java
runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
```

读取当前 Session 的事件历史：

```java
runner.sessionService()
        .listEvents(runner.appName(), session.userId(), session.id())
        .blockingGet();
```

这里的核心设计是：`Agent` 不直接保存对话历史；历史由 `SessionService` 管理，`Runner` 在执行时把当前 Session 上下文交给 Agent。

## 可观察结果

运行后重点观察：

- 两个 Session 的 `sessionId` 不同。
- 第一个 Session 的事件数量包含两轮用户输入与模型输出。
- 第二个 Session 的事件数量只包含自己的那一轮输入与输出。
- 第二个 Session 不应天然知道第一个 Session 中“小明”这个信息。

## 思考题

1. 如果同一个 `userId` 创建多个 Session，哪些信息应该共享，哪些信息不应该共享？
2. 为什么 ADK 不把对话历史直接塞进 `LlmAgent`，而是放到 `SessionService`？
3. 如果生产环境要支持重启后恢复会话，`InMemorySessionService` 应该替换成什么样的实现？
