package com.hh.examples.study06.event;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;

import java.util.Map;

public final class EventTools {

    private static final String LAST_OBSERVATION_KEY = "event.lastObservation";

    private EventTools() {
    }

    @Schema(name = "count_text", description = "统计文本字符数量，用于演示 ADK function call 和 function response 事件")
    public static Map<String, Object> countText(
            @Schema(name = "text", description = "需要统计字符数量的文本") String text) {
        return Map.of(
                "text", text,
                "characterCount", text.length());
    }

    @Schema(name = "record_observation", description = "把观察点写入当前 Session state，用于演示 stateDelta 事件")
    public static Map<String, Object> recordObservation(
            @Schema(name = "observation", description = "需要记录的观察点") String observation,
            ToolContext toolContext) {
        toolContext.state().put(LAST_OBSERVATION_KEY, observation);
        return Map.of(
                "status", "recorded",
                "stateKey", LAST_OBSERVATION_KEY,
                "observation", observation);
    }
}
