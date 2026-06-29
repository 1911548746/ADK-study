package com.hh.examples.study12.productionBestPractices;

import org.springframework.core.env.Environment;

import java.time.Duration;

public record ProductionAgentProperties(
        String appName,
        String agentName,
        String defaultUserId,
        String defaultSessionId,
        int maxMessageChars,
        Duration requestTimeout,
        boolean exposeTrace,
        ModelProperties model) {

    private static final String PREFIX = "adk.study12.";

    public static ProductionAgentProperties from(Environment environment) {
        String appName = read(environment, "app-name", "study12_production_app");
        if (!appName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("adk.study12.app-name must be a valid ADK identifier");
        }

        return new ProductionAgentProperties(
                appName,
                read(environment, "agent-name", "production_best_practices_agent"),
                read(environment, "default-user-id", "study12-user"),
                read(environment, "default-session-id", "study12-session"),
                readInt(environment, "max-message-chars", 2000),
                Duration.ofSeconds(readInt(environment, "request-timeout-seconds", 120)),
                readBoolean(environment, "expose-trace", true),
                new ModelProperties(
                        read(environment, "model.base-url", ""),
                        read(environment, "model.api-key", ""),
                        read(environment, "model.name", ""),
                        Duration.ofSeconds(readInt(environment, "model.timeout-seconds", 90)),
                        readInt(environment, "model.max-retries", 3)));
    }

    public boolean hasExternalModelConfig() {
        return !model.baseUrl().isBlank()
                && !model.apiKey().isBlank()
                && !model.name().isBlank();
    }

    private static String read(Environment environment, String key, String fallback) {
        String value = environment.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int readInt(Environment environment, String key, int fallback) {
        String value = read(environment, key, Integer.toString(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + key + " must be an integer");
        }
    }

    private static boolean readBoolean(Environment environment, String key, boolean fallback) {
        return Boolean.parseBoolean(read(environment, key, Boolean.toString(fallback)));
    }

    public record ModelProperties(
            String baseUrl,
            String apiKey,
            String name,
            Duration timeout,
            int maxRetries) {
    }
}
