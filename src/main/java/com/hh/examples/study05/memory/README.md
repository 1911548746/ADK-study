# study05.memory

## 概念

`Memory` 是 ADK 中面向跨 Session 的长期记忆服务。

它和 `Session`、`State` 的边界是：

```text
Session = 当前会话容器
State   = 当前 Session 内的结构化状态
Memory  = 跨 Session 可检索的长期信息
```

本示例只关注 `MemoryService` 的写入与检索，不引入 Tool、Workflow 或多 Agent。

## LangGraph 对照

在 LangGraph 中，短期上下文通常由 `thread_id` + checkpointer 保存；跨线程长期记忆通常放在 store、向量库或外部数据库中。

概念对照：

```text
ADK SessionService       ≈ LangGraph checkpointer
ADK Session.state()      ≈ LangGraph thread state
ADK BaseMemoryService    ≈ LangGraph long-term store / 外部记忆存储
ADK addSessionToMemory   ≈ 把一次会话摘要或事件写入长期存储
ADK searchMemory         ≈ 从长期存储检索相关记忆
```

区别是：ADK 把 Memory 作为 Runner 运行时的一等服务暴露出来；它不是 Session 的别名，也不是 State 的持久化版本。

## ADK 实现

本示例使用：

- `runner.memoryService()` 获取 Memory 服务。
- `addSessionToMemory(session)` 把一段 Session 的非空事件写入 Memory。
- `searchMemory(appName, userId, query)` 按用户和查询词检索长期记忆。
- `SearchMemoryResponse.memories()` 查看命中的 `MemoryEntry`。

注意：`InMemoryMemoryService` 是学习和原型用的内存实现，当前检索逻辑偏关键词匹配。本示例故意使用 `LangGraph`、`ADK Memory` 这类英文关键词，便于稳定观察命中结果。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study05/memory/MemoryExample.java
```

模型使用当前项目中的 LangChain4j 适配写法：

```java
.model(OpenAILangChainModelFactory.createChatModel())
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
com.hh.examples.study05.memory.MemoryExample
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用。

## 关键源码分析

写入 Memory：

```java
runner.memoryService()
        .addSessionToMemory(updatedFirstSession)
        .blockingAwait();
```

检索 Memory：

```java
SearchMemoryResponse response = runner.memoryService()
        .searchMemory(runner.appName(), userId, query)
        .blockingGet();
```

读取命中的记忆：

```java
for (MemoryEntry memory : response.memories()) {
    System.out.println(memory.author());
    System.out.println(memory.content());
}
```

## 可观察结果

运行后重点观察：

- 第一段 Session 产生 events。
- 调用 `addSessionToMemory` 后，Memory 中可以检索到第一段 Session 的内容。
- 第二段 Session 是新的 Session，它不继承第一段 Session 的 events。
- 即使第二段 Session 没有历史事件，应用仍然可以通过 `searchMemory` 找到同一用户的长期信息。

## 思考题

1. 哪些内容应该从 Session 写入 Memory，哪些内容不应该写入？
2. 生产环境中 Memory 应该怎样处理过期、删除、权限隔离和审计？
3. 如果要让 Agent 自动使用 Memory，应该在 prompt 注入、Tool 检索、还是 Agent callback 中完成？
