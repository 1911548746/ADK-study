package com.hh.examples.study12.productionBestPractices;

import java.time.Instant;

public record ProductionErrorResponse(
        String requestId,
        String error,
        String message,
        Instant timestamp) {

    public static ProductionErrorResponse of(String requestId, String error, String message) {
        return new ProductionErrorResponse(requestId, error, message, Instant.now());
    }
}
