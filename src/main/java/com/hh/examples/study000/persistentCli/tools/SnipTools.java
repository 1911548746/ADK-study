package com.hh.examples.study000.persistentCli.tools;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.hh.examples.study000.persistentCli.service.SnipService;

import java.util.List;
import java.util.Map;

public final class SnipTools {

    private static SnipService snipService;

    private SnipTools() {
    }

    public static void configure(SnipService service) {
        snipService = service;
    }

    @Schema(
            name = "snip_messages",
            description = """
                    将指定 eventIds 对应的历史 Event 移出后续模型上下文。
                    这不会删除数据库原始 session_events 或业务 messages，只影响之后 Runner 读取上下文。
                    Use this when:
                    - Your context is getting full and you need to make room
                    - Earlier messages contain large tool outputs you no longer need in full
                    - You want to compact a long exploration sequence into a summary

                    Guidelines:
                    - Only snip messages you're confident you won't need verbatim again
                    - The summary replacement preserves key facts, such as file paths, decisions, and errors
                    - You cannot un-snip; the original content is gone from future model context

                    reason 应概括被移除内容的关键信息和删除理由。
                    """)
    public static Map<String, Object> snipMessages(
            @Schema(name = "eventIds", description = "要移出后续模型上下文的 eventId 列表") List<String> eventIds,
            @Schema(name = "reason", description = "对被移除内容的简短摘要和删除原因") String reason,
            ToolContext toolContext) {
        ensureConfigured();
        return snipService.snipMessages(
                toolContext.invocationContext().appName(),
                toolContext.userId(),
                toolContext.sessionId(),
                toolContext.eventId(),
                eventIds,
                reason);
    }

    private static void ensureConfigured() {
        if (snipService == null) {
            throw new IllegalStateException("SnipTools 尚未完成 Spring Bean 配置");
        }
    }
}
