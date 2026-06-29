package com.hh.examples.study11.springBootIntegration;

import java.util.List;

public record ChatResponse(
        String appName,
        String userId,
        String sessionId,
        String answer,
        List<EventSignal> trace) {
}
