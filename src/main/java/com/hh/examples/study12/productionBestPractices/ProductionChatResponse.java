package com.hh.examples.study12.productionBestPractices;

import java.util.List;

public record ProductionChatResponse(
        String requestId,
        String appName,
        String userId,
        String sessionId,
        long durationMs,
        String answer,
        List<ProductionEventSignal> trace) {
}
