# 01 - Hello Agent

## 原理说明

本示例只展示一个知识点：如何用 ADK Java 创建最小 `LlmAgent`。

`LlmAgent` 是 ADK Java 中最基础、最常用的 LLM Agent 类型。它通过 `name`、`description`、`model` 和 `instruction` 定义一个可运行 Agent 的基本身份与行为规则。

本示例只创建 Agent 对象，不调用模型，不创建 Session，不使用 Runner，也不接入任何 Tool。

## 官方文档链接

- Java Get Started: https://adk.dev/get-started/java/
- ADK Java GitHub: https://github.com/google/adk-java
- Java API Reference: https://adk.dev/api-reference/java/
- LlmAgent API: https://adk.dev/api-reference/java/com/google/adk/agents/LlmAgent.html

## 运行步骤

在项目根目录执行：

```bash
mvn compile
```

如果需要直接运行该示例类，可使用 IDE 运行：

```text
com.hh.examples.study01.basicAgent.HelloAgentExample
```

该示例不会真正请求 Gemini，因此不需要配置 API Key。

## 关键代码讲解

`HelloAgentExample#createAgent` 使用 `LlmAgent.builder()` 创建一个最小 Agent：

- `name`：Agent 的唯一名称。
- `description`：Agent 的用途描述，便于调试和多 Agent 场景识别。
- `model`：指定 Agent 使用的模型名称。
- `instruction`：定义 Agent 的系统行为规则。
- `build`：生成 `LlmAgent` 实例。

`main` 方法只调用 `createAgent()` 并打印创建成功信息，用于验证代码结构和依赖是否正确。

## 与其他示例的区别

本示例只关注 `LlmAgent` 的创建。

- 不包含 Function Tool，对应后续 `02-function-tool`。
- 不包含多个工具，对应后续 `03-multiple-tools`。
- 不包含 Google Search Tool，对应后续 `04-google-search-tool`。
- 不包含 Session 或 Memory，对应后续 `05-memory`、`06-session`。
- 不包含 Workflow 或多 Agent，对应后续 `07` 到 `10`。
- 不包含 MCP、RAG、Evaluation、ADK Web 或 Spring Boot 集成，对应后续 `11` 到 `15`。
