package com.hh.examples.study18.hitl.fullVersion;

public record ChatRequest(
        String userId,
        String sessionId,
        String message) {
}
