package com.hh.examples.study12.productionBestPractices;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/study12")
public class ProductionAgentController {

    private final ProductionAgentService agentService;
    private final ProductionAgentProperties properties;

    public ProductionAgentController(
            ProductionAgentService agentService,
            ProductionAgentProperties properties) {
        this.agentService = agentService;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "appName", properties.appName(),
                "agentName", properties.agentName(),
                "requestTimeoutSeconds", properties.requestTimeout().toSeconds(),
                "maxMessageChars", properties.maxMessageChars(),
                "externalModelConfig", properties.hasExternalModelConfig());
    }

    @PostMapping("/chat")
    public ProductionChatResponse chat(@RequestBody ProductionChatRequest request) {
        return agentService.chat(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProductionErrorResponse> handleBadRequest(
            IllegalArgumentException exception,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProductionErrorResponse.of(
                        normalizeRequestId(requestId),
                        "bad_request",
                        exception.getMessage()));
    }

    @ExceptionHandler(ProductionAgentException.class)
    public ResponseEntity<ProductionErrorResponse> handleAgentError(ProductionAgentException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ProductionErrorResponse.of(
                        exception.requestId(),
                        exception.errorCode(),
                        exception.getMessage()));
    }

    private static String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId.trim();
    }
}
