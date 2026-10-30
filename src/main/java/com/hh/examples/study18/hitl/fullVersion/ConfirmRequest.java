package com.hh.examples.study18.hitl.fullVersion;

public record ConfirmRequest(
        String userId,
        String sessionId,
        String confirmationCallId,
        boolean approved) {
}
