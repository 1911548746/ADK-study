# study03.multipleTools

## 原理说明

本示例只展示一个知识点：如何给同一个 ADK Java `LlmAgent` 同时注册多个 `FunctionTool`。

`FunctionTool` 可以把 Java 静态方法暴露给 Agent。多个工具挂载到同一个 Agent 后，Agent 会根据工具描述和用户问题选择合适的工具。

本示例注册两个工具：

- `make_greeting`：根据姓名生成中文问候语。
- `count_characters`：统计文本字符数量。

本示例只创建 Agent，不运行模型，不创建 Session，不使用 Memory，不引入 Workflow。

## 官方文档链接

- Java Get Started: https://adk.dev/get-started/java/
- ADK Java GitHub: https://github.com/google/adk-java
- Java API Reference: https://adk.dev/api-reference/java/
- FunctionTool API: https://adk.dev/api-reference/java/com/google/adk/tools/FunctionTool.html
- Tools 文档: https://adk.dev/tools/

## 运行步骤

在项目根目录执行：

```bash
mvn compile
```

如果当前 Maven 绑定的是 Java 8，需要临时指定 JDK 17：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

也可以在 IDE 中直接运行：

```text
com.hh.examples.study02.toolCalling.MultipleToolsExample
```

预期输出：

```text
ADK multiple tools agent created.
```

## 关键代码讲解

`SimpleTextTools` 中定义了两个静态方法，每个方法都使用 `@Schema` 描述工具名称、用途和参数。

`MultipleToolsExample#createAgent` 中的关键点：

- `FunctionTool.create(SimpleTextTools.class, "makeGreeting")` 注册第一个工具。
- `FunctionTool.create(SimpleTextTools.class, "countCharacters")` 注册第二个工具。
- `.tools(...)` 一次性把多个工具挂载到同一个 `LlmAgent`。
- 工具返回 `Map<String, Object>`，字段名使用英文，便于模型稳定读取。

## 与其他示例的区别

本示例只关注多个 `FunctionTool` 的注册。

- 不展示纯 `LlmAgent` 创建，那个知识点在 `study01.helloAgent`。
- 不展示单个 Function Tool，那个知识点在 `study02.functionTool`。
- 不展示 Google Search Tool，避免和 `study04.googleSearchTool` 混合。
- 不展示 Session、Memory、Workflow、MCP、RAG、Evaluation、ADK Web 或 Spring Boot 集成。
