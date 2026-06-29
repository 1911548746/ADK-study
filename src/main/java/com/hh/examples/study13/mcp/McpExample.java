package com.hh.examples.study13.mcp;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.SseServerParameters;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McpExample {

    private static final String USER_ID = "study13-user";

    public static void main(String[] args) throws Exception {

//        runStdioChromeDevToolsExample();
        runStreamableHttpExample();
//        runSseHttpExample();

    }

    private static void runStdioChromeDevToolsExample() throws Exception {
        StdioServerParameters stdioServer = StdioServerParameters.builder()
                .command(chromeDevToolsMcpCommand())
                .args(chromeDevToolsMcpArgs())
                .env(Map.of())
                .build();

        try (McpToolset toolset = new McpToolset(stdioServer.toServerParameters())) {
            LlmAgent agent = createAgent(
                    "mcp_stdio_filesystem_agent",
                    """
                            你是一个可以通过 MCP 工具控制 Chrome 浏览器的智能体（Agent）。
                            你的唯一目标是：**通过工具真实操作浏览器完成任务，不允许模拟结果。**
                            """,
                    toolset);

            runTurn(agent, """
                    打开 https://www.google.com/，并返回页面标题和正文内容。
                    """);
        }
    }

    private static void runStreamableHttpExample() throws Exception {
        StreamableHttpServerParameters httpServer = StreamableHttpServerParameters.builder()
                .url("https://mcp.tavily.com/mcp/?tavilyApiKey=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")
                .headers(Map.of())
                .timeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .terminateOnClose(false)
                .build();

        try (McpToolset toolset = new McpToolset(httpServer)) {
            LlmAgent agent = createAgent(
                    "mcp_streamable_http_agent",
                    """
                            你是远程 Streamable HTTP MCP 示例 Agent。
                            你可以使用远程 MCP Server 暴露的工具。
                            如果工具不可用，请明确说明远程 MCP 服务是否连接失败或没有相关工具。
                            """,
                    toolset);

            runTurn(agent, "请列出远程 MCP Server 暴露了哪些工具，并调用一个合适的工具做一次简单演示。");
        }
    }

    private static void runSseHttpExample() throws Exception {
        SseServerParameters sseServer = SseServerParameters.builder()
                .url("https://dashscope.aliyuncs.com/api/v1/mcps/mcp-YjlmMDE2ZDMyZGRk/sse")
                .sseEndpoint("/sse")
                .headers(Map.of("Authorization", "Bearer sk-81d58071ea074777ac208844ce153e22"))
                .timeout(Duration.ofSeconds(30))
                .sseReadTimeout(Duration.ofSeconds(120))
                .build();

        try (McpToolset toolset = new McpToolset(sseServer)) {
            LlmAgent agent = createAgent(
                    "mcp_sse_http_agent",
                    """
                            你是远程 SSE MCP 示例 Agent。
                            你可以使用远程 SSE MCP Server 暴露的工具。
                            SSE 是较早常见的远程 MCP 连接方式；如果服务不可用，请说明连接问题。
                            """,
                    toolset);

            runTurn(agent, "请列出远程 SSE MCP Server 暴露了哪些工具，并调用一个合适的工具做一次简单演示。");
        }
    }

    private static LlmAgent createAgent(String name, String instruction, McpToolset toolset) {
        return LlmAgent.builder()
                .name(name)
                .description("演示 ADK 通过 MCP Toolset 接入外部工具服务。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction(instruction)
                .tools(toolset)
                .build();
    }

    private static void runTurn(LlmAgent agent, String userInput) {
        InMemoryRunner runner = new InMemoryRunner(agent);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID)
                .blockingGet();

        System.out.println("用户 > " + userInput);
        System.out.println("\n[事件流]");

        Content userMessage = Content.fromParts(Part.fromText(userInput));
        runner.runAsync(session.userId(), session.id(), userMessage)
                .blockingForEach(event -> {
                    printToolSignals(event);
                    if (event.finalResponse()) {
                        System.out.println("\n最终回答 > " + extractText(event));
                    }
                });
    }

    private static void printToolSignals(Event event) {
        event.functionCalls().forEach(functionCall ->
                System.out.println("functionCall > " + functionCall.name().orElse("unknown")));

        event.functionResponses().forEach(functionResponse ->
                System.out.println("functionResponse > " + functionResponse.name().orElse("unknown")));
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }

    private static String chromeDevToolsMcpCommand() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win") ? "cmd" : "npx";
    }

    private static List<String> chromeDevToolsMcpArgs() {
        List<String> args = new ArrayList<>();
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            args.add("/c");
            args.add("npx");
        }
        args.add("-y");
        args.add("chrome-devtools-mcp@latest");
        return args;
    }

    private static String readArg(String[] args, int index, String fallback) {
        return Optional.ofNullable(args.length > index ? args[index] : null)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }
}
