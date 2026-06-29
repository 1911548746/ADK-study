package com.hh.examples;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.study01.basicAgent.HelloAgentExample;
import com.hh.examples.study02.toolCalling.MultipleToolsExample;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Scanner;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AgentCliRunner {

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
//                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        InMemoryRunner runner = new InMemoryRunner(MultipleToolsExample.createAgent());

        Session session = runner
                .sessionService()
                .createSession(runner.appName(), "user123")
                .blockingGet();

        try (Scanner scanner = new Scanner(System.in, UTF_8)) {
            while (true) {
                System.out.print("\n你 > ");
                String userInput = scanner.nextLine();
                if ("exit".equalsIgnoreCase(userInput)) {
                    break;
                }

                Content userMsg = Content.fromParts(Part.fromText(userInput));
                Flowable<Event> events = runner.runAsync(session.userId(), session.id(), userMsg, runConfig);

                System.out.print("\n智能体 > ");
                events.blockingForEach(event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent());
                    }
                });

//                System.out.print("\n智能体 > ");
//                events.blockingForEach(event -> {
//                    System.out.println("事件: " + event);
//                    System.out.println("content: " + event.content());
//
//                    String text = extractText(event);
//
//                    if (event.partial().orElse(false) && !text.isBlank()) {
//                        System.out.print(text);
//                        System.out.flush();
//                    }
//
//                    if (event.finalResponse() && !text.isBlank()) {
//                        System.out.print(text);
//                        System.out.flush();
//                    }
//
//                });

            }
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
