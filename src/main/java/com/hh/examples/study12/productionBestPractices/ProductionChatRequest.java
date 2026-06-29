package com.hh.examples.study12.productionBestPractices;

public record ProductionChatRequest(
        String userId,
        String sessionId,
        String message,
        Boolean includeTrace) {
}
