package com.hh.examples.study12.productionBestPractices;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ProductionAgentService {

    private final Runner runner;
    private final ProductionAgentProperties properties;

    public ProductionAgentService(
            @Qualifier("study12Runner") Runner runner,
            ProductionAgentProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    public ProductionChatResponse chat(ProductionChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        String userId = normalize(request.userId(), properties.defaultUserId());
        String sessionId = normalize(request.sessionId(), properties.defaultSessionId());
        String message = requireMessage(request.message());
        boolean includeTrace = request.includeTrace() == null
                ? properties.exposeTrace()
                : request.includeTrace();

        Session session = findOrCreateSession(userId, sessionId);
        Content userMessage = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        List<ProductionEventSignal> trace = new ArrayList<>();
        StringBuilder answer = new StringBuilder();

        try {
            runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                    .timeout(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .blockingForEach(event -> handleEvent(event, includeTrace, trace, answer));
        } catch (RuntimeException e) {
            throw new ProductionAgentException(requestId, "agent_execution_failed", e);
        }

        return new ProductionChatResponse(
                requestId,
                runner.appName(),
                session.userId(),
                session.id(),
                elapsedMs(startedAt),
                answer.toString(),
                includeTrace ? List.copyOf(trace) : List.of());
    }

    private void handleEvent(
            Event event,
            boolean includeTrace,
            List<ProductionEventSignal> trace,
            StringBuilder answer) {
        if (includeTrace) {
            trace.add(ProductionEventSignal.from(event));
        }

        if (event.finalResponse()) {
            String text = extractText(event);
            if (!text.isBlank()) {
                answer.setLength(0);
                answer.append(text);
            }
        }
    }

    private Session findOrCreateSession(String userId, String sessionId) {
        Session existingSession = runner.sessionService()
                .getSession(runner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();

        if (existingSession != null) {
            return existingSession;
        }

        return runner.sessionService()
                .createSession(runner.appName(), userId, Map.of(), sessionId)
                .blockingGet();
    }

    private String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        String trimmed = message.trim();
        if (trimmed.length() > properties.maxMessageChars()) {
            throw new IllegalArgumentException(
                    "message 长度不能超过 " + properties.maxMessageChars() + " 个字符");
        }

        return trimmed;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }

    private static long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
