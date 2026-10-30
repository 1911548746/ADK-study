package com.hh.examples.study17.react;

import com.google.adk.tools.Annotations.Schema;

import java.util.List;
import java.util.Map;

/**
 * Deterministic read-only tools for the ReAct incident-diagnosis example.
 */
public final class ReActTools {

    private ReActTools() {
    }

    @Schema(name = "list_service_health", description = "列出指定环境中各服务的健康状态和基础告警摘要")
    public static Map<String, Object> listServiceHealth(
            @Schema(name = "environment", description = "环境名称，本示例使用 production") String environment) {

        if (!"production".equalsIgnoreCase(environment)) {
            return Map.of(
                    "status", "unsupported_environment",
                    "requestedEnvironment", environment,
                    "supportedEnvironments", List.of("production"));
        }

        return Map.of(
                "environment", "production",
                "observedAt", "2026-07-30T10:15:00+08:00",
                "services", List.of(
                        Map.of(
                                "name", "api-gateway",
                                "status", "HEALTHY",
                                "p95LatencyMs", 82,
                                "errorRatePercent", 0.2),
                        Map.of(
                                "name", "order-service",
                                "status", "DEGRADED",
                                "p95LatencyMs", 2180,
                                "errorRatePercent", 18.4),
                        Map.of(
                                "name", "payment-service",
                                "status", "HEALTHY",
                                "p95LatencyMs", 126,
                                "errorRatePercent", 0.1),
                        Map.of(
                                "name", "inventory-service",
                                "status", "HEALTHY",
                                "p95LatencyMs", 104,
                                "errorRatePercent", 0.3)));
    }

    @Schema(name = "get_service_metrics", description = "读取单个服务最近十五分钟的资源、错误率和连接池指标")
    public static Map<String, Object> getServiceMetrics(
            @Schema(name = "serviceName", description = "服务名称，例如 order-service") String serviceName) {

        if ("order-service".equalsIgnoreCase(serviceName)) {
            return Map.of(
                    "serviceName", "order-service",
                    "window", "last_15_minutes",
                    "cpuPercent", 36,
                    "memoryPercent", 61,
                    "http5xxRatePercent", 18.4,
                    "dbPoolActive", 40,
                    "dbPoolMax", 40,
                    "dbPoolPendingRequests", 127,
                    "dbQueryP95Ms", 43);
        }

        if (isKnownHealthyService(serviceName)) {
            return Map.of(
                    "serviceName", serviceName,
                    "window", "last_15_minutes",
                    "cpuPercent", 28,
                    "memoryPercent", 54,
                    "http5xxRatePercent", 0.2,
                    "status", "within_normal_range");
        }

        return unknownService(serviceName);
    }

    @Schema(name = "search_service_logs", description = "搜索指定服务最近十五分钟的日志，并返回聚合后的高频错误")
    public static Map<String, Object> searchServiceLogs(
            @Schema(name = "serviceName", description = "服务名称") String serviceName,
            @Schema(name = "keyword", description = "日志搜索关键词，例如 timeout、database 或 error") String keyword) {

        if ("order-service".equalsIgnoreCase(serviceName)) {
            return Map.of(
                    "serviceName", "order-service",
                    "keyword", keyword,
                    "window", "last_15_minutes",
                    "matchedLines", 324,
                    "topPatterns", List.of(
                            Map.of(
                                    "count", 301,
                                    "level", "ERROR",
                                    "message", "Timeout waiting for connection from database pool after 3000ms"),
                            Map.of(
                                    "count", 23,
                                    "level", "WARN",
                                    "message", "Order request exceeded downstream deadline")));
        }

        if (isKnownHealthyService(serviceName)) {
            return Map.of(
                    "serviceName", serviceName,
                    "keyword", keyword,
                    "matchedLines", 0,
                    "topPatterns", List.of());
        }

        return unknownService(serviceName);
    }

    @Schema(name = "get_recent_deployments", description = "查询指定服务最近的部署和配置变更记录")
    public static Map<String, Object> getRecentDeployments(
            @Schema(name = "serviceName", description = "服务名称") String serviceName) {

        if ("order-service".equalsIgnoreCase(serviceName)) {
            return Map.of(
                    "serviceName", "order-service",
                    "deployments", List.of(
                            Map.of(
                                    "version", "2.4.1",
                                    "deployedAt", "2026-07-30T09:42:00+08:00",
                                    "changeSummary", "数据库连接池 maxPoolSize 从 120 调整为 40",
                                    "status", "completed"),
                            Map.of(
                                    "version", "2.4.0",
                                    "deployedAt", "2026-07-27T16:10:00+08:00",
                                    "changeSummary", "订单查询接口增加索引提示",
                                    "status", "completed")));
        }

        if (isKnownHealthyService(serviceName)) {
            return Map.of(
                    "serviceName", serviceName,
                    "deployments", List.of(),
                    "message", "最近二十四小时无部署或配置变更");
        }

        return unknownService(serviceName);
    }

    @Schema(
            name = "get_incident_runbook",
            description = "按故障类型查询只读处置手册，支持 db-connection-pool-exhaustion、high-cpu、dependency-timeout")
    public static Map<String, Object> getIncidentRunbook(
            @Schema(name = "incidentType", description = "根据已有证据判断出的故障类型") String incidentType) {

        if ("db-connection-pool-exhaustion".equalsIgnoreCase(incidentType)) {
            return Map.of(
                    "incidentType", "db-connection-pool-exhaustion",
                    "immediateActions", List.of(
                            "暂停继续发布并通知订单服务负责人",
                            "回滚导致连接池缩小的配置变更",
                            "观察 dbPoolPendingRequests、HTTP 5xx 和 p95 latency 是否恢复"),
                    "verification", List.of(
                            "连接池 active 持续低于 max",
                            "pending requests 回落到 0",
                            "错误率恢复到 1% 以下"),
                    "approvalRequired", true);
        }

        return Map.of(
                "incidentType", incidentType,
                "status", "runbook_not_found",
                "supportedIncidentTypes", List.of(
                        "db-connection-pool-exhaustion",
                        "high-cpu",
                        "dependency-timeout"));
    }

    private static boolean isKnownHealthyService(String serviceName) {
        return List.of("api-gateway", "payment-service", "inventory-service")
                .stream()
                .anyMatch(service -> service.equalsIgnoreCase(serviceName));
    }

    private static Map<String, Object> unknownService(String serviceName) {
        return Map.of(
                "status", "service_not_found",
                "serviceName", serviceName,
                "knownServices", List.of(
                        "api-gateway",
                        "order-service",
                        "payment-service",
                        "inventory-service"));
    }
}
