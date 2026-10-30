package com.hh.examples.study07.streaming;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;
import com.hh.examples.study02.toolCalling.SimpleTextTools;

import java.util.Map;

public final class StreamingWithToolExample {

    private static final String APP_NAME = "study07-streaming-app";
    private static final String USER_ID = "study07-user";
    private static final String SESSION_ID = "streaming-demo-session";

    private StreamingWithToolExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("streaming_agent")
                .description("一个友善的助手")
                .model(OpenAILangChainModelFactory.createChatAndStreamingModel())
                .instruction("""
                        你是 ADK Tool Streaming 示例 Agent。
                        当用户询问天气时，必须调用 get_weather。
                        当用户要求问候时，必须调用 make_greeting。
                        回答要简洁，并说明你是否调用了工具。
                        """)
                .tools(
                        FunctionTool.create(SimpleTextTools.class, "getWeather"),
                        FunctionTool.create(SimpleTextTools.class, "makeGreeting"))
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

        runTurn(runner, session, "你好呀，我是小明", runConfig);
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


        if (!event.functionCalls().isEmpty()) {
//            System.out.println("\nfunctionCalls > " + event.functionCalls());
            return;
        }

        if (!event.functionResponses().isEmpty()) {
//            System.out.println("\nfunctionResponses > " + event.functionResponses());
            return;
        }

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
