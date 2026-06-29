package com.hh.examples.study11.springBootIntegration;

public record ChatRequest(
        String userId,
        String sessionId,
        String message) {
}
