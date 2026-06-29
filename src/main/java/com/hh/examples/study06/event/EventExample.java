package com.hh.examples.study06.event;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;

public final class EventExample {

    private static final String APP_NAME = "study06-event-app";
    private static final String USER_ID = "study06-user";
    private static final String SESSION_ID = "event-demo-session";

    private EventExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("event_agent")
                .description("一个只演示 ADK Event 运行轨迹的最小 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是一个用于演示 ADK Event 的简洁助手。
                        当用户要求统计字符数量时，必须调用 count_text。
                        当用户要求记录观察点时，必须调用 record_observation。
                        回复要简洁，并说明你已经完成了相应动作。
                        """)
                .tools(
                        FunctionTool.create(EventTools.class, "countText"),
                        FunctionTool.create(EventTools.class, "recordObservation"))
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

        runTurn(runner, session, "请统计这段文本的字符数量：ADK Event", runConfig);
        runTurn(runner, session, "请记录观察点：Event 是 ADK 运行轨迹的事实记录。", runConfig);
        printPersistedEvents(runner, session);
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);
        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(EventExample::printEventSignals);
    }

    private static void printEventSignals(Event event) {
        System.out.println("\n[event]");
        System.out.println("id: " + event.id());
        System.out.println("invocationId: " + event.invocationId());
        System.out.println("author: " + event.author());
        System.out.println("timestamp: " + event.timestamp());

        String text = extractText(event);
        if (!text.isBlank()) {
            System.out.println("content: " + text);
        }

        event.partial().ifPresent(value -> System.out.println("partial: " + value));
        event.turnComplete().ifPresent(value -> System.out.println("turnComplete: " + value));
        event.finishReason().ifPresent(value -> System.out.println("finishReason: " + value));
        event.errorCode().ifPresent(value -> System.out.println("errorCode: " + value));
        event.errorMessage().ifPresent(value -> System.out.println("errorMessage: " + value));
        event.usageMetadata().ifPresent(value -> System.out.println("usageMetadata: " + value));
        event.modelVersion().ifPresent(value -> System.out.println("modelVersion: " + value));

        if (!event.functionCalls().isEmpty()) {
            System.out.println("functionCalls: " + event.functionCalls());
        }

        if (!event.functionResponses().isEmpty()) {
            System.out.println("functionResponses: " + event.functionResponses());
        }

        if (!event.actions().stateDelta().isEmpty()) {
            System.out.println("stateDelta: " + event.actions().stateDelta());
        }

        if (!event.actions().artifactDelta().isEmpty()) {
            System.out.println("artifactDelta: " + event.actions().artifactDelta());
        }

        event.actions().transferToAgent().ifPresent(value -> System.out.println("transferToAgent: " + value));
        event.actions().escalate().ifPresent(value -> System.out.println("escalate: " + value));
        event.actions().endInvocation().ifPresent(value -> System.out.println("endInvocation: " + value));

        if (event.finalResponse()) {
            System.out.println("finalResponse: true");
            System.out.println("智能体 > " + event.stringifyContent());
        }
    }

    private static void printPersistedEvents(InMemoryRunner runner, Session session) {
        ListEventsResponse response = runner.sessionService()
                .listEvents(runner.appName(), session.userId(), session.id())
                .blockingGet();

        System.out.println("\nSession 中持久记录的 Event 数量: " + response.events().size());
        response.events().forEach(event ->
                System.out.println(event.author() + " | final=" + event.finalResponse() + " | text=" + extractText(event)));
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
