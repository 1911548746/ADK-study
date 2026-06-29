package com.hh.examples.study10.ragAgent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;

public final class RagAgentExample {

    private static final String APP_NAME = "study10-rag-agent-app";
    private static final String USER_ID = "study10-user";
    private static final String SESSION_ID = "rag-agent-demo-session";

    private RagAgentExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("rag_agent")
                .description("一个使用本地知识库检索工具回答 ADK 学习问题的 RAG Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 ADK RAG 学习助手。

                        回答规则：
                        1. 回答任何 ADK 学习问题前，必须先调用 search_adk_knowledge。
                        2. 只能基于工具返回的 hits.content 回答。
                        3. 回答中必须用 [sourceId] 标注依据来源。
                        4. 如果 hitCount 为 0，必须说明“当前知识库没有足够依据”，不要编造答案。
                        5. 回答要简洁，先给结论，再说明依据。
                        """)
                .tools(FunctionTool.create(RagKnowledgeTools.class, "searchAdkKnowledge"))
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        runTurn(runner, session, "RAG Agent 应该如何避免编造答案？", runConfig);
        runTurn(runner, session, "ADK 中 ParallelAgent 和 LlmAgent 的职责边界是什么？", runConfig);
        runTurn(runner, session, "这个知识库里有没有 Kubernetes Ingress 的配置规范？", runConfig);
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);
        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(RagAgentExample::printEvent);
    }

    private static void printEvent(Event event) {
        System.out.println("\n[event]");
        System.out.println("author: " + event.author());

        if (!event.functionCalls().isEmpty()) {
            System.out.println("functionCalls: " + event.functionCalls());
        }

        if (!event.functionResponses().isEmpty()) {
            System.out.println("functionResponses: " + event.functionResponses());
        }

        String text = extractText(event);
        if (!text.isBlank()) {
            System.out.println("content: " + text);
        }

        if (event.finalResponse() && !text.isBlank()) {
            System.out.println("智能体 > " + event.stringifyContent());
        }
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }
}
