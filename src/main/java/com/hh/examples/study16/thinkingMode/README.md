# study16.thinkingMode

## 概念

“思考模式”不是 ADK 的一个 `RunConfig` 开关，而是底层模型能力。推理模型会为复杂任务投入更多推导 token；部分 OpenAI 兼容接口还会在响应中返回 `reasoning_content`。应用应把它分成三件事看：

```text
模型是否具备推理能力       -> 由 modelName 决定
服务端是否返回 reasoning    -> 由模型供应商和接口决定
应用是否读取 reasoning      -> 由 LangChain4j / ADK 适配层决定
```

本章使用 LangChain4j 的 `OpenAiChatModel.returnThinking(true)`。它让 LangChain4j 尝试把 OpenAI 兼容响应中的 `reasoning_content` 解析到 `AiMessage.thinking()`；这不是让普通模型“突然会思考”的提示词技巧。

本章为了观察协议字段，会在控制台直接展示模型返回的 reasoning 文本，效果类似 DeepSeek 网页端的“思考过程”。生产系统若要保留或展示它，应单独设计权限、脱敏、留存和成本策略；不要把它当作稳定的业务审计结论。

## LangGraph 对照

LangGraph 没有通用的“开启思考模式”图节点。通常也是在模型初始化时传入供应商专属 reasoning 参数，再由节点决定如何处理模型响应：

```text
ADK + reasoning-capable model      ≈ LangGraph node + reasoning-capable chat model
AiMessage.thinking()               ≈ provider response 的 reasoning / additional kwargs
ADK Event 最终文本                 ≈ graph state 中面向下游节点的 answer
```

如果需要“先分析，再审查，再重写”的强制流程，应使用 `SequentialAgent`、`LoopAgent` 或 LangGraph 的显式节点和边，而不是依赖模型是否返回 reasoning 文本。

## ADK 实现

本章的模型由公共的 `OpenAILangChainModelFactory.createThinkingChatModel()` 创建，复用项目已配置的默认 DeepSeek 模型，仅额外开启 `.returnThinking(true)`。

本章包含三个独立观察点：

- `ThinkingModeExample` 默认模式：直接调用底层 `OpenAiChatModel`，依次打印 `AiMessage.thinking()` 和 `AiMessage.text()`。
- `ThinkingModeExample --adk`：把同一个模型包装为 ADK `LangChain4j` 模型，再交给 `LlmAgent` 和 `InMemoryRunner`，用于比较 ADK Event 的表现。
- `StreamingThinkingModeExample`：直接消费 `OpenAiStreamingChatModel` 的 thinking 和 answer 两种流式回调。

后两个路径刻意分开，因为当前项目使用的 `google-adk-langchain4j 1.4.0` 适配层会将 `AiMessage.text()` 映射为 ADK 的 `Event`，不会把 `AiMessage.thinking()` 或 LangChain4j 的 `onPartialThinking(...)` 一并映射出去。也就是说：模型可以在底层返回 reasoning，但 ADK Agent 的标准 Event 消费者只会得到最终回答。

核心模型配置：

```java
OpenAiChatModel.builder()
        .baseUrl(BASE_URL)
        .apiKey(API_KEY)
        .modelName(MODEL_NAME)
        .returnThinking(true)
        .build();
```

注意：本章没有设置 `sendThinking(true)`。它用于在后续请求中回传前一轮 `AiMessage.thinking()`，而 ADK 当前适配层不会保留这一字段；一般业务对话也没有回传内部推理的必要。

## 完整代码

```text
src/main/java/com/hh/examples/study16/thinkingMode/ThinkingModeExample.java
src/main/java/com/hh/examples/study16/thinkingMode/StreamingThinkingModeExample.java
```

## 运行方式

本示例直接使用 `OpenAILangChainModelFactory` 中已经配置的默认 DeepSeek 模型，无需额外环境变量。若以后切换模型供应商或模型名称，只需在该公共工厂中统一修改配置，并确保目标模型和兼容接口支持返回 `reasoning_content`。

编译：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

在 IDE 中运行：

```text
com.hh.examples.study16.thinkingMode.ThinkingModeExample
```

运行非流式思考展示：

```text
com.hh.examples.study16.thinkingMode.ThinkingModeExample
```

运行 ADK 对比模式：

```text
com.hh.examples.study16.thinkingMode.ThinkingModeExample --adk
```

运行“先思考、后回答”的流式案例：

```text
com.hh.examples.study16.thinkingMode.StreamingThinkingModeExample
```

## 运行流程

非流式展示：

```text
OpenAILangChainModelFactory
  -> OpenAiChatModel(returnThinking=true)
  -> ChatResponse
  -> AiMessage.thinking() -> [思考过程]
  -> AiMessage.text()     -> [最终回答]
```

流式展示：

```text
OpenAiStreamingChatModel(returnThinking=true)
  -> onPartialThinking(...) -> [思考过程] 逐 token 输出
  -> onPartialResponse(...) -> [正式回答] 逐 token 输出
  -> onCompleteResponse(...)
```

## 可观察结果

非流式模式应依次看到：

```text
[思考过程]
...
[最终回答]
...
```

流式模式应按供应商发送事件的顺序看到：

```text
[思考过程]
...实时输出 reasoning_content...
[正式回答]
...实时输出 answer text...
```

DeepSeek 的 reasoning 流通常会先发出 `reasoning_content`，再发普通回答 token，因此本例能呈现“先思考、后回答”。如果供应商不返回思考流，则正式回答仍会正常输出，但控制台会提示未收到 reasoning 内容。

如果没有看到思考内容，通常意味着以下情况之一：

- 所选模型不是推理模型。
- 供应商虽然支持推理，却不在 OpenAI 兼容响应中返回 `reasoning_content`。
- 网关或中转服务过滤了该字段。
- 当前模型或接口不支持本章设置的 reasoning 参数。

这不必然代表模型没有进行推导，只表示应用没有得到可读取或可流式读取的 reasoning 字段。

## 关键源码分析

`returnThinking(true)` 是响应解析开关：

```java
.returnThinking(true)
```

非流式读取的是：

```java
ChatResponse response = chatModel.chat(request);
String thinking = response.aiMessage().thinking();
```

流式读取的关键是 LangChain4j 专门提供的回调：

```java
public void onPartialThinking(PartialThinking partialThinking) {
    System.out.print(partialThinking.text());
}

public void onPartialResponse(String partialResponse) {
    System.out.print(partialResponse);
}
```

ADK 侧使用的仍然是标准 Agent 入口：

```java
LlmAgent.builder()
        .model(adkModel)
        .instruction("先在内部充分分析，再给出最终回答")
        .build();
```

这说明“模型推理能力”与“Agent 编排能力”是两个层次：前者决定单次模型调用的推导能力，后者决定工具、状态、子 Agent、工作流如何协作。

## 思考题

1. 为什么需要把“模型实际推理”和“供应商返回 reasoning 文本”当作两项不同能力？
2. 在需要严格审计的场景中，为什么显式工作流步骤通常比保存 reasoning 文本更可靠？
3. 如果业务要实现多轮反思和修正，应优先选择 `LoopAgent`，还是依赖 `sendThinking(true)`？为什么？
