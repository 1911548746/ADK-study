package com.hh.examples.study18.hitl.fullVersion;

import java.util.List;

public record ChatResponse(
        String status,
        String appName,
        String userId,
        String sessionId,
        String answer,
        PendingConfirmation confirmation,
        List<EventSignal> trace) {
}
