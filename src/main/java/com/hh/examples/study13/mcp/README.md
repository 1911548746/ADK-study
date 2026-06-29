# study13.mcp

## 概念

MCP 是 Model Context Protocol。它解决的问题是：把外部工具、资源、提示词等能力用统一协议暴露给 Agent。

在 ADK 中，MCP 的位置可以理解为一种 `Toolset`：

```text
LlmAgent
  -> McpToolset
  -> MCP Client Transport
  -> MCP Server
  -> tools / resources / prompts
```

和前面章节的 `FunctionTool` 不同，`FunctionTool` 是把 Java 方法直接注册给 Agent；MCP 是让 Agent 通过协议连接一个外部工具服务。这个外部服务可以是本地子进程，也可以是远程 HTTP 服务。

本章覆盖两类集成：

- 本地 `stdio`：ADK 启动一个本地 MCP Server 子进程，通过标准输入输出通信。
- 远程 HTTP：ADK 连接一个已经运行的远程 MCP Server，通过 Streamable HTTP 或 SSE 通信。

## LangGraph 对照

在 LangGraph / LangChain 体系中，MCP 通常会先被转换成 LangChain tools，然后挂到 graph 的 tool node 或 agent node 上。

对应关系：

```text
ADK McpToolset                 ≈ LangChain MCP adapter / tool loader
ADK LlmAgent.tools(toolset)    ≈ agent.bindTools(...)
MCP stdio server               ≈ 本地工具进程
MCP HTTP server                ≈ 远程工具服务
ADK Event functionCalls        ≈ LangGraph tool call / tool result messages
```

关键差异是：ADK 仍然把 MCP 视为 Agent 的工具集合，而不是把 MCP Server 变成图节点。Agent 是否调用 MCP 工具，仍由模型根据 instruction、工具声明和上下文决定。

## ADK 实现

ADK Java 1.4.0 中相关类位于：

```java
com.google.adk.tools.mcp.McpToolset
com.google.adk.tools.mcp.StdioServerParameters
com.google.adk.tools.mcp.StreamableHttpServerParameters
com.google.adk.tools.mcp.SseServerParameters
```

本地 stdio：

```java
StdioServerParameters stdioServer = StdioServerParameters.builder()
        .command("npx.cmd")
        .args(List.of("-y", "@modelcontextprotocol/server-filesystem", workspace))
        .env(Map.of())
        .build();

McpToolset toolset = new McpToolset(stdioServer.toServerParameters());
```

远程 Streamable HTTP：

```java
StreamableHttpServerParameters httpServer = StreamableHttpServerParameters.builder()
        .url("http://localhost:3001/mcp")
        .headers(Map.of())
        .timeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(120))
        .terminateOnClose(false)
        .build();

McpToolset toolset = new McpToolset(httpServer);
```

远程 SSE：

```java
SseServerParameters sseServer = SseServerParameters.builder()
        .url("http://localhost:3001")
        .sseEndpoint("/sse")
        .headers(Map.of())
        .timeout(Duration.ofSeconds(30))
        .sseReadTimeout(Duration.ofSeconds(120))
        .build();

McpToolset toolset = new McpToolset(sseServer);
```

注册给 Agent：

```java
LlmAgent.builder()
        .model(OpenAILangChainModelFactory.createChatModel())
        .instruction("你可以使用 MCP Server 暴露的工具。")
        .tools(toolset)
        .build();
```

## 完整代码

完整代码见：

```text
src/main/java/com/hh/examples/study13/mcp/McpExample.java
```

示例支持三种运行模式：

```text
stdio  本地 stdio MCP 文件系统服务
http   远程 Streamable HTTP MCP 服务
sse    远程 SSE MCP 服务
```

## 运行流程

先编译：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

### 本地 stdio MCP

本地 stdio 示例使用官方文件系统 MCP Server：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study13.mcp.McpExample" `
  -Dexec.args="stdio"
```

运行时会通过 `npx` 启动：

```text
@modelcontextprotocol/server-filesystem
```

允许访问的目录是：

```text
target/study13-mcp-files
```

Agent 会尝试通过 MCP 工具创建并读取 `adk-mcp-note.txt`。

### 远程 Streamable HTTP MCP

先启动一个支持 Streamable HTTP 的 MCP Server，然后运行：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study13.mcp.McpExample" `
  -Dexec.args="http http://localhost:3001/mcp"
```

这里的 URL 要替换成你的 MCP Server 地址。

### 远程 SSE MCP

如果你的远程 MCP Server 使用 SSE 传输：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study13.mcp.McpExample" `
  -Dexec.args="sse http://localhost:3001"
```

示例默认 `sseEndpoint` 为 `/sse`。如果服务端不是这个路径，需要修改 `McpExample.runSseHttpExample(...)`。

## 关键源码分析

`McpToolset` 是 ADK 与 MCP 的边界：

```text
ADK Agent 只知道自己有一组 tools
McpToolset 负责连接 MCP Server、加载工具声明、转发工具调用、接收工具结果
MCP Server 负责真正执行工具逻辑
```

本地 stdio 的特点：

- 生命周期通常由 Agent 应用启动和关闭。
- 适合本地文件系统、命令行工具、本机开发工具。
- 安全边界主要靠允许目录、命令白名单和进程权限控制。

远程 HTTP 的特点：

- MCP Server 独立部署，多个 Agent 应用可以复用。
- 适合企业内部工具平台、搜索服务、知识库服务、工单系统。
- 安全边界主要靠认证、授权、网络隔离、审计和限流。

事件观察：

```java
event.functionCalls()
event.functionResponses()
```

如果你看到 `functionCall` 和 `functionResponse`，说明 Agent 确实调用了 MCP 工具，而不是直接凭模型回答。

## 可观察结果

运行 stdio 示例后重点观察：

- 控制台出现 MCP 工具调用事件。
- `target/study13-mcp-files/adk-mcp-note.txt` 被创建。
- 最终回答会总结文件创建和读取结果。

运行远程 HTTP 示例后重点观察：

- 如果 MCP Server 可用，会出现对应工具的 `functionCall` / `functionResponse`。
- 如果 MCP Server 不可用，程序会在工具加载或调用阶段报连接错误。
- 不同 MCP Server 暴露的工具不同，因此最终回答取决于服务端能力。

## MCP 与 FunctionTool 的边界

优先使用 `FunctionTool` 的场景：

- 工具逻辑就是当前 Java 应用的一部分。
- 工具和 Agent 强绑定，不需要被其他客户端复用。
- 你希望最小化网络、协议和部署复杂度。

优先使用 MCP 的场景：

- 工具能力由其他进程、语言或团队维护。
- 同一套工具需要被多个 Agent 或 IDE 复用。
- 工具需要独立部署、独立权限、独立审计。
- 你希望用标准协议接入文件系统、浏览器、数据库、搜索等外部能力。

## 生产注意事项

- 不要让文件系统 MCP Server 暴露过大的目录。
- 不要把高权限命令行工具直接暴露给模型。
- 远程 MCP 必须做认证、授权、限流和审计。
- 对工具返回的大内容要做截断、分页或外部化。
- MCP Server 的可用性会影响 Agent 的工具调用稳定性。
- `McpToolset` 是资源对象，长生命周期服务中应在应用关闭时正确 close。

## 思考题

1. 哪些工具适合做成 Java `FunctionTool`，哪些更适合做成 MCP Server？
2. 本地 stdio MCP 和远程 HTTP MCP 的安全边界分别在哪里？
3. 如果 MCP 工具返回超大结果，应该在 MCP Server 层处理，还是在 ADK Agent 层处理？
