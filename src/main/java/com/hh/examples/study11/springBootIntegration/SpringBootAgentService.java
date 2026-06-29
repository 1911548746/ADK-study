package com.hh.examples.study11.springBootIntegration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SpringBootAgentService {

    private final Runner study11Runner;
    private final ObjectMapper objectMapper;

    public SpringBootAgentService(Runner study11Runner, ObjectMapper objectMapper) {
        this.study11Runner = study11Runner;
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(ChatRequest request) {
        String userId = normalize(request.userId(), "study11-user");
        String sessionId = normalize(request.sessionId(), "study11-session");
        String message = requireMessage(request.message());

        Session session = findOrCreateSession(userId, sessionId);
        Content userMessage = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        List<EventSignal> trace = new ArrayList<>();
        StringBuilder answer = new StringBuilder();

        study11Runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(event -> {
                    trace.add(EventSignal.from(event));
                    if (event.finalResponse()) {
                        String text = extractText(event);
                        if (!text.isBlank()) {
                            answer.setLength(0);
                            answer.append(text);
                        }
                    }
                });

        return new ChatResponse(
                study11Runner.appName(),
                session.userId(),
                session.id(),
                answer.toString(),
                trace);
    }

    public void streamChat(ChatRequest request, OutputStream outputStream) throws IOException {
        String userId = normalize(request.userId(), "study11-user");
        String sessionId = normalize(request.sessionId(), "study11-session");
        String message = requireMessage(request.message());

        Session session = findOrCreateSession(userId, sessionId);
        Content userMessage = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        StringBuilder answer = new StringBuilder();
        writeSse(outputStream, "meta", Map.of(
                "appName", study11Runner.appName(),
                "userId", session.userId(),
                "sessionId", session.id()));

        study11Runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(event -> {
                    try {
                        EventSignal signal = EventSignal.from(event);
                        if (!signal.functionCalls().isEmpty()
                                || !signal.functionResponses().isEmpty()
                                || !signal.stateDeltaKeys().isEmpty()) {
                            writeSse(outputStream, "trace", signal);
                        }

                        String text = extractText(event);
                        if (!text.isBlank()) {
                            answer.append(text);
                            writeSse(outputStream, "token", Map.of("text", text));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        writeSse(outputStream, "done", Map.of(
                "answer", answer.toString(),
                "appName", study11Runner.appName(),
                "userId", session.userId(),
                "sessionId", session.id()));
    }

    private Session findOrCreateSession(String userId, String sessionId) {
        Session existingSession = study11Runner.sessionService()
                .getSession(study11Runner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();

        if (existingSession != null) {
            return existingSession;
        }

        return study11Runner.sessionService()
                .createSession(study11Runner.appName(), userId, Map.of(), sessionId)
                .blockingGet();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return message.trim();
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }

    private void writeSse(OutputStream outputStream, String eventName, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        String payload = "event: " + eventName + "\n"
                + "data: " + json + "\n\n";
        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
}
