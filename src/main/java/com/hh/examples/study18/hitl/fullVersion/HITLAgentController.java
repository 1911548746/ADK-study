package com.hh.examples.study18.hitl.fullVersion;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

@RestController
@RequestMapping("/api/study18")
public class HITLAgentController {

    private final HITLAgentService agentService;

    public HITLAgentController(HITLAgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "appName", HITLAgentConfig.APP_NAME);
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        return responseFor(agentService.chat(request));
    }

    @PostMapping("/chat/confirm")
    public ResponseEntity<ChatResponse> confirm(@RequestBody ConfirmRequest request) {
        return responseFor(agentService.confirm(request));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamChat(@RequestBody ChatRequest request) {
        StreamingResponseBody responseBody = outputStream -> agentService.streamChat(request, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(responseBody);
    }

    @PostMapping(value = "/chat/confirm/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamConfirmation(@RequestBody ConfirmRequest request) {
        StreamingResponseBody responseBody = outputStream -> agentService.streamConfirmation(request, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(responseBody);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "bad_request",
                        "message", exception.getMessage()));
    }

    private static ResponseEntity<ChatResponse> responseFor(ChatResponse response) {
        HttpStatus status = "PENDING_CONFIRMATION".equals(response.status())
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
