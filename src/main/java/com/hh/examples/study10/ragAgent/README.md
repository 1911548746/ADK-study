# study10.ragAgent

## 概念

`RAG` 是 Retrieval-Augmented Generation，核心不是“让模型背更多知识”，而是让 Agent 在回答前先检索外部知识，再基于检索结果生成回答。

一个最小 RAG Agent 通常包含：

```text
用户问题
  ↓
检索工具
  ↓
相关知识片段
  ↓
带引用的回答
```

它解决的问题是：

- 降低模型凭参数知识编造答案的概率。
- 让回答基于可检查的外部资料。
- 让知识更新不依赖重新训练模型。
- 能在回答里暴露来源，便于审计。

本章先使用本地内存知识库和 `FunctionTool` 演示 RAG 的核心流程，不引入向量库、Embedding、Vertex AI RAG Engine 或数据库。

## LangGraph 对照

在 LangGraph 中，RAG 通常会拆成检索节点和生成节点：

```text
ADK FunctionTool 检索知识       ≈ LangGraph retriever node / tool node
工具返回 hits                  ≈ graph state 中的 retrieved_docs
LlmAgent 基于 hits 回答         ≈ generator node
sourceId 引用                  ≈ citation / metadata
```

关键差异是：ADK 更自然地把检索器做成 Tool，让 `LlmAgent` 主动决定何时调用；LangGraph 更常见的是把 retrieval 作为图中的显式节点。

## ADK 实现

本示例使用：

- `RagKnowledgeTools.searchAdkKnowledge(...)` 作为本地知识库检索工具。
- `FunctionTool.create(...)` 把检索函数注册给 `LlmAgent`。
- Agent instruction 强制回答前调用 `search_adk_knowledge`。
- 工具返回 `sourceId`、`title`、`score`、`matchedTerms`、`content`。
- Agent 回答时必须引用 `[sourceId]`。
- 如果 `hitCount == 0`，Agent 必须说明知识库没有足够依据。

当前示例是“关键词检索版 RAG”。生产环境通常会把 `searchAdkKnowledge` 替换为：

- 向量数据库检索。
- BM25 / 全文索引。
- Vertex AI Search。
- Vertex AI RAG Retrieval。
- 企业内部文档服务或 MCP Toolset。

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study10/ragAgent/RagAgentExample.java
src/main/java/com/hh/examples/study10/ragAgent/RagKnowledgeTools.java
```

注册检索工具：

```java
return LlmAgent.builder()
        .name("rag_agent")
        .model(OpenAILangChainModelFactory.createChatModel())
        .instruction("回答前必须调用 search_adk_knowledge，并用 sourceId 标注依据。")
        .tools(FunctionTool.create(RagKnowledgeTools.class, "searchAdkKnowledge"))
        .build();
```

工具返回结构：

```text
query
hitCount
hits[]
  sourceId
  title
  score
  matchedTerms
  content
answerPolicy
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
com.hh.examples.study10.ragAgent.RagAgentExample
```

本示例会真实调用模型，因此需要 `OpenAILangChainModelFactory` 中的模型配置可用。

## 关键源码分析

检索工具：

```java
@Schema(name = "search_adk_knowledge", description = "从本地 ADK 学习知识库中检索与问题相关的片段")
public static Map<String, Object> searchAdkKnowledge(String query)
```

Agent 被要求先检索：

```text
回答任何 ADK 学习问题前，必须先调用 search_adk_knowledge。
```

观察工具调用：

```java
event.functionCalls()
event.functionResponses()
```

这能帮助你确认 Agent 是否真的走了 RAG 流程，而不是直接凭模型参数回答。

## RAG 与 Memory 的区别

本项目前面已经学过 `Memory`，它和 RAG 容易混淆。

```text
Memory = 面向用户和会话的长期记忆
RAG    = 面向知识库和资料的外部知识检索
```

典型边界：

- 用户偏好、用户姓名、长期事实：更适合 Memory。
- 产品文档、代码库说明、政策规范、知识库文章：更适合 RAG。
- Memory 关注“这个用户过去告诉过我什么”。
- RAG 关注“外部资料中有哪些依据能回答这个问题”。

生产系统里二者可以同时存在：Agent 先用 Memory 个性化理解用户，再用 RAG 检索业务知识。

## 实验步骤

1. 运行 `RagAgentExample`。
2. 观察每轮是否先出现 `functionCalls: search_adk_knowledge`。
3. 观察 `functionResponses` 中的 `hits` 和 `sourceId`。
4. 检查最终回答是否包含 `[doc-rag]`、`[doc-workflow]` 等引用。
5. 观察超出知识库范围的问题是否触发“没有足够依据”的回答。

## 可观察结果

运行后重点观察：

- Agent 不是直接回答，而是先调用检索工具。
- 工具返回的是候选知识片段，不是最终答案。
- 最终答案由模型基于检索结果生成。
- 回答中带有 `sourceId`，可追踪依据。
- 没有检索命中时，Agent 应该拒绝编造。

## 思考题

1. 为什么 RAG 工具返回结构化 `sourceId` 比只返回一段纯文本更适合生产系统？
2. 哪些知识应该进入 RAG 知识库，哪些应该进入 Memory？
3. 如果检索命中了多个互相冲突的片段，Agent 应该如何回答？
