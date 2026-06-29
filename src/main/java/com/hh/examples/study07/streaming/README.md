# study07.streaming

## 概念

`Streaming` 是把一次 Agent 执行过程拆成连续事件返回，而不是等整轮执行结束后一次性返回最终文本。

它解决的问题不是“模型一定更快”，而是：

- 用户可以更早看到模型正在生成内容。
- CLI / Web 前端可以边收边渲染。
- 应用可以同时观察文本 chunk、final response、tool call、错误等运行信号。
- 长回复或慢工具调用时，系统不会表现得像卡死。

在 ADK 中，Streaming 仍然通过 `Event` 暴露。也就是说，第 06 章看到的 `Event` 是运行轨迹模型；本章的 `Streaming` 是这个轨迹模型的一种消费方式。

## LangGraph 对照

在 LangGraph 中，常见做法是使用 `stream` / `astream` 并选择不同的 `stream_mode`：

```text
ADK runner.runAsync + StreamingMode.SSE   ≈ LangGraph stream / astream
ADK text Event                            ≈ LangGraph messages token chunk
ADK event.finalResponse()                 ≈ 可展示响应事件
ADK Flowable<Event>                       ≈ 可持续消费的事件迭代器
```

设计差异是：LangGraph 更强调 graph 节点状态如何推进；ADK 更强调 Agent 运行时统一吐出 `Event`，前端、日志、调试器和评估系统都围绕同一种事件结构工作。

## ADK 实现

本示例使用：

- `OpenAiStreamingChatModel` 创建 LangChain4j 的 streaming model。
- `LangChain4j.builder().streamingChatModel(...)` 把 streaming model 交给 ADK。
- `RunConfig.StreamingMode.SSE` 开启服务端事件风格的流式响应。
- `runner.runAsync(...)` 获取 `Flowable<Event>`。
- 从 `Event.content()` 中提取文本 chunk 并立即输出。
- `event.partial()` 和 `event.finalResponse()` 作为辅助观察信号，而不是唯一的流式判断条件。

注意：只设置 `RunConfig.StreamingMode.SSE` 不够。模型适配层也必须提供真正的 `StreamingChatModel`，否则底层模型没有 token 级流式能力。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study07/streaming/StreamingExample.java
src/main/java/com/hh/examples/model/OpenAILangChainModelFactory.java
```

核心模型配置：

```java
OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
        .baseUrl(BASE_URL)
        .apiKey(API_KEY)
        .modelName(MODEL_NAME)
        .timeout(Duration.ofSeconds(90))
        .build();

return LangChain4j.builder()
        .streamingChatModel(streamingChatModel)
        .modelName(MODEL_NAME)
        .build();
```

核心运行配置：

```java
RunConfig runConfig = RunConfig.builder()
        .streamingMode(RunConfig.StreamingMode.SSE)
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
com.hh.examples.study07.streaming.StreamingExample
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用，并且模型服务支持 OpenAI 兼容流式接口。

## 关键源码分析

消费流式事件：

```java
runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
        .blockingForEach(event -> handleEvent(event, streamedAnswer, printedPartial));
```

处理文本 chunk：

```java
if (!text.isBlank()) {
    streamedAnswer.append(text);
    System.out.print(text);
    System.out.flush();
    return;
}
```

处理最终响应：

```java
if (event.finalResponse()) {
    System.out.println("\nfinalResponse > true");
}
```

这里的关键点是：前端通常应该优先渲染带文本内容的事件。当前 ADK Java 1.4.0 + LangChain4j streaming 场景下，SSE 文本 chunk 的 `partial()` 可能为空；由于 `Event.finalResponse()` 的判定会把 `partial != true` 的纯文本事件视为可展示响应，所以不能把它当作“整轮结束只出现一次”的信号。

## 实验步骤

1. 运行 `StreamingExample`。
2. 观察终端是否先出现 `text chunks:`，随后文字逐段输出。
3. 观察 `[累计得到的流式回答]` 是否包含完整拼接后的回答。
4. 把 `RunConfig.StreamingMode.SSE` 改成 `RunConfig.StreamingMode.NONE` 再运行，对比输出节奏。
5. 把 `createStreamingChatModel()` 改回 `createChatModel()` 再运行，观察模型适配层不支持 streaming 时的差异。

## 可观察结果

运行后重点观察：

- 带文本内容的事件会携带增量文本片段。
- 多个文本 chunk 拼接后形成完整回答。
- 在当前版本中，流式文本 chunk 也可能满足 `finalResponse()`；它表示“可展示响应事件”，不一定表示“整轮唯一最终事件”。
- Streaming 和 Event 不是两套机制；Streaming 是对 `Flowable<Event>` 的实时消费。
- 对 CLI 来说，`System.out.flush()` 很重要；对 Web 来说，对应的是把 chunk 立即推给浏览器。

## 思考题

1. 如果 `partial()` 为空，前端还能用哪些信号判断一个事件是否值得展示？
2. 工具调用过程是否应该直接展示给用户？哪些事件更适合进入日志而不是 UI？
3. 在 LangGraph 中你会选择 `messages`、`updates` 还是 `values` 来模拟本章效果？为什么？
