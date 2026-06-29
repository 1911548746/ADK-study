package com.hh.examples.study05.memory;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MemoryExample {

    private static final String APP_NAME = "study05-memory-app";
    private static final String USER_ID = "study05-user";

    private MemoryExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("memory_agent")
                .description("一个只演示 ADK Memory 写入与检索的最小 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是一个用于演示 ADK Memory 的简洁助手。
                        当前示例重点不是让你主动管理长期记忆，而是通过 Runner 的 MemoryService 观察跨 Session 检索。
                        回复要简洁。
                        """)
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);

        Session firstSession = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), "memory-source-session")
                .blockingGet();

        System.out.println("第一段 Session ID: " + firstSession.id());
        runTurn(
                runner,
                firstSession,
                "请记住我的长期偏好：我最喜欢的框架是 LangGraph，我正在学习 ADK Memory。",
                runConfig);

        Session updatedFirstSession = runner.sessionService()
                .getSession(runner.appName(), firstSession.userId(), firstSession.id(), Optional.empty())
                .blockingGet();

        System.out.println("\n写入 Memory 前，第一段 Session events 数量: " + updatedFirstSession.events().size());
        runner.memoryService()
                .addSessionToMemory(updatedFirstSession)
                .blockingAwait();
        System.out.println("已调用 memoryService.addSessionToMemory(firstSession)。");

        printMemorySearch(runner, USER_ID, "LangGraph");

        Session secondSession = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), "memory-new-session")
                .blockingGet();

        System.out.println("\n第二段 Session ID: " + secondSession.id());
        System.out.println("第二段 Session events 数量: " + secondSession.events().size());
        System.out.println("第二段 Session 不继承第一段 Session 的 events。");

        printMemorySearch(runner, USER_ID, "ADK Memory");
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);

        Content userMessage = Content.fromParts(Part.fromText(userInput));
        List<Event> events = runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .toList()
                .blockingGet();

        String finalAnswer = events.stream()
                .filter(Event::finalResponse)
                .map(Event::stringifyContent)
                .filter(text -> !text.isBlank())
                .reduce((previous, current) -> current)
                .orElse("");

        System.out.println("智能体 > " + finalAnswer);
    }

    private static void printMemorySearch(InMemoryRunner runner, String userId, String query) {
        SearchMemoryResponse response = runner.memoryService()
                .searchMemory(runner.appName(), userId, query)
                .blockingGet();

        System.out.println("\nMemory search query: " + query);
        System.out.println("Memory 命中数量: " + response.memories().size());

        for (MemoryEntry memory : response.memories()) {
            System.out.println("- author: " + memory.author());
            System.out.println("  timestamp: " + memory.timestamp());
            System.out.println("  content: " + extractText(memory.content()));
        }
    }

    private static String extractText(Content content) {
        return content.parts()
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }
}
