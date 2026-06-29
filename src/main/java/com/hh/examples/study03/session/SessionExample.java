package com.hh.examples.study03.session;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.List;
import java.util.Optional;

public final class SessionExample {

    private static final String USER_ID = "study03-user";

    private SessionExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("session_agent")
                .description("一个只演示 ADK Session 多轮上下文的最小 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是一个用于演示 ADK Session 的简洁助手。
                        如果用户告诉你姓名，请在当前会话中自然使用这个姓名。
                        如果当前会话中没有姓名信息，请明确说明你还不知道用户姓名。
                        """)
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        InMemoryRunner runner = new InMemoryRunner(createAgent(), "study03-session-app");

        Session firstSession = runner.sessionService()
                .createSession(runner.appName(), USER_ID)
                .blockingGet();

        System.out.println("第一轮 Session ID: " + firstSession.id());
        runTurn(runner, firstSession, "我是小明。", runConfig);
        runTurn(runner, firstSession, "我叫什么名字？", runConfig);
        printSessionEvents(runner, firstSession);

        Session secondSession = runner.sessionService()
                .createSession(runner.appName(), USER_ID)
                .blockingGet();

//        Session secondSession = runner.sessionService()
//                .getSession(runner.appName(), firstSession.userId(), firstSession.id(), Optional.empty())
//                .blockingGet();

        System.out.println("\n第二轮 Session ID: " + secondSession.id());
        runTurn(runner, secondSession, "我叫什么名字？", runConfig);
        printSessionEvents(runner, secondSession);
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);

        Content userMessage = Content.fromParts(Part.fromText(userInput));

//        方案一
//        List<Event> events = runner
//                .runAsync(session.userId(), session.id(), userMessage, runConfig)
//                .toList()
//                .blockingGet();

//        String finalAnswer = events.stream()
//                .filter(Event::finalResponse)
//                .map(SessionExample::extractText)
//                .filter(text -> !text.isBlank())
//                .reduce((previous, current) -> current)
//                .orElseGet(() -> events.stream()
//                        .map(SessionExample::extractText)
//                        .filter(text -> !text.isBlank())
//                        .reduce("", String::concat));

//        System.out.println("智能体 > " + finalAnswer);

//        方案二
//        Flowable<Event> events = runner
//                .runAsync(session.userId(), session.id(), userMessage, runConfig);
//
//        events.blockingForEach(event -> {
//            if (event.finalResponse()) {
//                System.out.println("智能体 > " + event.stringifyContent());
//            }
//        });

//        方案三
        String finalAnswer = runner
                .runAsync(session.userId(), session.id(), userMessage, runConfig)
                .filter(Event::finalResponse)
                .map(Event::stringifyContent)
                .blockingFirst();

        System.out.println("智能体 > " + finalAnswer);

    }

    private static void printSessionEvents(InMemoryRunner runner, Session session) {
        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        ListEventsResponse eventsResponse = runner.sessionService()
                .listEvents(runner.appName(), session.userId(), session.id())
                .blockingGet();

        System.out.println("\nSession 观察结果");
        System.out.println("appName: " + updatedSession.appName());
        System.out.println("userId: " + updatedSession.userId());
        System.out.println("sessionId: " + updatedSession.id());
        System.out.println("session.events size: " + updatedSession.events().size());
        System.out.println("listEvents size: " + eventsResponse.events().size());

        eventsResponse.events().forEach(event -> {
            String text = extractText(event);
            if (!text.isBlank()) {
                System.out.println(event.author() + " > " + text);
            }
        });
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
