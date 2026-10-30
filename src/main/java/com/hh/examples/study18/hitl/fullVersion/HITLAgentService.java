package com.hh.examples.study18.hitl.fullVersion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class HITLAgentService {

    private static final String REQUEST_CONFIRMATION_FUNCTION_NAME = "adk_request_confirmation";
    private static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";

    private final Runner hitlRunner;
    private final ObjectMapper objectMapper;

    public HITLAgentService(@Qualifier("hitlRunner") Runner hitlRunner, ObjectMapper objectMapper) {
        this.hitlRunner = hitlRunner;
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(ChatRequest request) {
        String userId = normalize(request.userId(), "study18-user");
        String sessionId = normalize(request.sessionId(), "study18-session");
        Session session = findOrCreateSession(userId, sessionId);

        RunResult result = run(
                session,
                Content.fromParts(Part.fromText(requireMessage(request.message()))),
                RunConfig.StreamingMode.NONE);
        return toResponse(session, result);
    }

    public ChatResponse confirm(ConfirmRequest request) {
        Session session = findExistingSession(
                requireIdentifier(request.userId(), "userId"),
                requireIdentifier(request.sessionId(), "sessionId"));

        RunResult result = run(
                session,
                confirmationDecision(request.confirmationCallId(), request.approved()),
                RunConfig.StreamingMode.NONE);
        return toResponse(session, result);
    }

    public void streamChat(ChatRequest request, OutputStream outputStream) throws IOException {
        String userId = normalize(request.userId(), "study18-user");
        String sessionId = normalize(request.sessionId(), "study18-session");
        Session session = findOrCreateSession(userId, sessionId);
        stream(session, Content.fromParts(Part.fromText(requireMessage(request.message()))), outputStream);
    }

    public void streamConfirmation(ConfirmRequest request, OutputStream outputStream) throws IOException {
        Session session = findExistingSession(
                requireIdentifier(request.userId(), "userId"),
                requireIdentifier(request.sessionId(), "sessionId"));
        stream(session, confirmationDecision(request.confirmationCallId(), request.approved()), outputStream);
    }

    private ChatResponse toResponse(Session session, RunResult result) {
        return new ChatResponse(
                result.pendingConfirmation() == null ? COMPLETED : PENDING_CONFIRMATION,
                hitlRunner.appName(),
                session.userId(),
                session.id(),
                result.answer(),
                result.pendingConfirmation(),
                result.trace());
    }

    private RunResult run(Session session, Content message, RunConfig.StreamingMode streamingMode) {
        RunConfig runConfig = RunConfig.builder().streamingMode(streamingMode).build();
        List<EventSignal> trace = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        PendingConfirmation[] pending = new PendingConfirmation[1];

        hitlRunner.runAsync(session.userId(), session.id(), message, runConfig)
                .blockingForEach(event -> {
                    trace.add(EventSignal.from(event));
                    updateAnswer(answer, event, streamingMode);
                    if (pending[0] == null) {
                        pending[0] = pendingConfirmation(event);
                    }
                });

        return new RunResult(answer.toString(), trace, pending[0]);
    }

    private void stream(Session session, Content message, OutputStream outputStream) throws IOException {
        RunConfig runConfig = RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build();
        StringBuilder answer = new StringBuilder();
        PendingConfirmation[] pending = new PendingConfirmation[1];

        writeSse(outputStream, "meta", Map.of(
                "appName", hitlRunner.appName(),
                "userId", session.userId(),
                "sessionId", session.id()));

        hitlRunner.runAsync(session.userId(), session.id(), message, runConfig)
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

                        if (pending[0] == null) {
                            pending[0] = pendingConfirmation(event);
                            if (pending[0] != null) {
                                writeSse(outputStream, "confirmation_required", pending[0]);
                            }
                        }
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });

        String status = pending[0] == null ? COMPLETED : PENDING_CONFIRMATION;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", status);
        done.put("answer", answer.toString());
        done.put("appName", hitlRunner.appName());
        done.put("userId", session.userId());
        done.put("sessionId", session.id());
        done.put("confirmation", pending[0]);
        writeSse(outputStream, "done", done);
    }

    private PendingConfirmation pendingConfirmation(Event event) {
        return event.functionCalls().stream()
                .filter(functionCall -> REQUEST_CONFIRMATION_FUNCTION_NAME.equals(
                        functionCall.name().orElse("")))
                .findFirst()
                .map(this::toPendingConfirmation)
                .orElse(null);
    }

    private PendingConfirmation toPendingConfirmation(FunctionCall functionCall) {
        String callId = functionCall.id().orElseThrow(() ->
                new IllegalStateException("ADK 确认 FunctionCall 缺少 id。"));
        return new PendingConfirmation(
                callId,
                REQUEST_CONFIRMATION_FUNCTION_NAME,
                functionCall.args().orElse(Map.of()));
    }

    private Content confirmationDecision(String confirmationCallId, boolean approved) {
        FunctionResponse functionResponse = FunctionResponse.builder()
                .id(requireIdentifier(confirmationCallId, "confirmationCallId"))
                .name(REQUEST_CONFIRMATION_FUNCTION_NAME)
                .response(Map.of("confirmed", approved))
                .build();
        return Content.fromParts(Part.builder().functionResponse(functionResponse).build());
    }

    private Session findOrCreateSession(String userId, String sessionId) {
        Session session = getSession(userId, sessionId);
        if (session != null) {
            return session;
        }
        return hitlRunner.sessionService()
                .createSession(hitlRunner.appName(), userId, Map.of(), sessionId)
                .blockingGet();
    }

    private Session findExistingSession(String userId, String sessionId) {
        Session session = getSession(userId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("找不到待恢复的 Session，请使用首次 /chat 返回的 userId 和 sessionId。");
        }
        return session;
    }

    private Session getSession(String userId, String sessionId) {
        return hitlRunner.sessionService()
                .getSession(hitlRunner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
    }

    private static void updateAnswer(StringBuilder answer, Event event, RunConfig.StreamingMode streamingMode) {
        String text = extractText(event);
        if (text.isBlank()) {
            return;
        }
        if (streamingMode == RunConfig.StreamingMode.NONE && event.finalResponse()) {
            answer.setLength(0);
        }
        answer.append(text);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
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

    private record RunResult(
            String answer,
            List<EventSignal> trace,
            PendingConfirmation pendingConfirmation) {
    }
}
