package com.hh.examples.study18.hitl.fullVersion;

import java.util.Map;

/**
 * Data required by a client to render an approval UI and resume the same ADK session.
 */
public record PendingConfirmation(
        String confirmationCallId,
        String functionName,
        Map<String, Object> arguments) {
}
