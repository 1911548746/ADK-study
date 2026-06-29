package com.hh.examples.study07.streaming;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;

public final class StreamingExample {

    private static final String APP_NAME = "study07-streaming-app";
    private static final String USER_ID = "study07-user";
    private static final String SESSION_ID = "streaming-demo-session";

    private StreamingExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("streaming_agent")
                .description("一个只演示 ADK Streaming Response 的最小 Agent。")
                .model(OpenAILangChainModelFactory.createStreamingChatModel())
                .instruction("""
                        你是一个用于演示 ADK Streaming 的中文助手。
                        回答时分成 3 个短段落，每段 1 到 2 句话。
                        内容要解释 Streaming 对命令行、Web 前端和可观测性的价值。
                        不要调用工具，不要输出 Markdown 表格。
                        """)
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        runTurn(runner, session, "请解释 ADK Streaming 为什么不是简单地更快返回文本。", runConfig);
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);
        System.out.println("\n[事件流]");

        Content userMessage = Content.fromParts(Part.fromText(userInput));
        StringBuilder streamedAnswer = new StringBuilder();
        boolean[] printedTextStream = {false};

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(event -> handleEvent(event, streamedAnswer, printedTextStream));

        System.out.println("\n\n[事件流结束]");
        System.out.println("\n\n[累计得到的流式回答]");
        System.out.println(streamedAnswer);
    }

    private static void handleEvent(
            Event event,
            StringBuilder streamedAnswer,
            boolean[] printedTextStream) {

        String text = extractText(event);

        if (!text.isBlank()) {
            if (!printedTextStream[0]) {
                System.out.println("text chunks:");
                printedTextStream[0] = true;
            }
            streamedAnswer.append(text);
            System.out.print(text);
            System.out.flush();
            return;
        }

        if (!event.functionCalls().isEmpty()) {
            System.out.println("\nfunctionCalls > " + event.functionCalls());
        }

        if (!event.functionResponses().isEmpty()) {
            System.out.println("\nfunctionResponses > " + event.functionResponses());
        }

//        if (event.finalResponse()) {
//            System.out.println("\nfinalResponse > true");
//        }
    }

    private static String extractText(Event event) {
        return event.content()
                .map(Content::text)
                .orElse("");
    }
}
