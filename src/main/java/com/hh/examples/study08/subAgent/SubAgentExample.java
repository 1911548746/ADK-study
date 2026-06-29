package com.hh.examples.study08.subAgent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;
import java.util.Scanner;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class SubAgentExample {

    private static final String APP_NAME = "study08-sub-agent-app";
    private static final String USER_ID = "study08-user";
    private static final String SESSION_ID = "sub-agent-demo-session";

    private SubAgentExample() {
    }

    public static LlmAgent createAgent() {
        LlmAgent adkArchitectureAgent = createAdkArchitectureAgent();
        LlmAgent langGraphCompareAgent = createLangGraphCompareAgent();

        return LlmAgent.builder()
                .name("sub_agent_coordinator")
                .description("根据用户问题选择最合适的 ADK 学习子 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 ADK 学习系统的协调 Agent。
                        你的职责不是直接长篇回答，而是把问题交给最合适的子 Agent。

                        路由规则：
                        - 如果用户问 ADK 架构、Agent、Runner、Session、Event、Tool 的职责，转交给 adk_architecture_agent。
                        - 如果用户要求和 LangGraph 对照、比较设计差异，转交给 langgraph_compare_agent。
                        - 如果问题同时包含两类意图，优先转交给 langgraph_compare_agent。

                        转交时必须使用 transfer_to_agent。
                        """)
                .subAgents(adkArchitectureAgent, langGraphCompareAgent)
                .build();
    }

    private static LlmAgent createAdkArchitectureAgent() {
        return LlmAgent.builder()
                .name("adk_architecture_agent")
                .description("解释 ADK 核心架构与组件职责的子 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 ADK 架构讲解子 Agent。
                        回答要聚焦 ADK 自身设计，说明组件边界和为什么这样设计。
                        不要展开 LangGraph 对照，除非用户明确要求。
                        回复控制在 4 句话以内。
                        """)
                .disallowTransferToParent(true)
                .disallowTransferToPeers(true)
                .build();
    }

    private static LlmAgent createLangGraphCompareAgent() {
        return LlmAgent.builder()
                .name("langgraph_compare_agent")
                .description("把 ADK 能力和 LangGraph 概念做设计对照的子 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 ADK 与 LangGraph 对照讲解子 Agent。
                        回答要先说 LangGraph 中大致对应什么，再说明 ADK 的设计差异。
                        不要写代码，重点解释架构取舍。
                        回复控制在 4 句话以内。
                        """)
                .disallowTransferToParent(true)
                .disallowTransferToPeers(true)
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

//        runTurn(runner, session, "请解释 ADK 里 Agent、Runner、Session 分别负责什么。", runConfig);
//        runTurn(runner, session, "请对照 LangGraph 解释 ADK Sub-Agent 的设计差异。", runConfig);

        try (Scanner scanner = new Scanner(System.in, UTF_8)) {
            while (true) {
                System.out.print("\n你 > ");
                String userInput = scanner.nextLine();
                if ("exit".equalsIgnoreCase(userInput)) {
                    break;
                }

                runTurn(runner, session, userInput, runConfig);
            }
        }

    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

//        System.out.println("\n用户 > " + userInput);
        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(SubAgentExample::printEvent);
    }

    private static void printEvent(Event event) {
        System.out.println("\n[event]");
        System.out.println("author: " + event.author());

        if (!event.functionCalls().isEmpty()) {
            System.out.println("functionCalls: " + event.functionCalls());
        }

        event.actions().transferToAgent()
                .ifPresent(agentName -> System.out.println("transferToAgent: " + agentName));

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
