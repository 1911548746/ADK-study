package com.hh.examples.study04.state;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StateTools {

    private static final String NAME_KEY = "profile.name";
    private static final String LAST_TOPIC_KEY = "task.lastTopic";

    private StateTools() {
    }

    @Schema(name = "remember_name", description = "把用户姓名写入当前 ADK Session state")
    public static Map<String, Object> rememberName(
            @Schema(name = "name", description = "用户姓名") String name,
            ToolContext toolContext) {
        toolContext.state().put(NAME_KEY, name);
        return Map.of(
                "status", "saved",
                "stateKey", NAME_KEY,
                "name", name);
    }

    @Schema(name = "remember_topic", description = "把用户当前关注的话题写入当前 ADK Session state")
    public static Map<String, Object> rememberTopic(
            @Schema(name = "topic", description = "用户当前关注的话题") String topic,
            ToolContext toolContext) {
        toolContext.state().put(LAST_TOPIC_KEY, topic);
        return Map.of(
                "status", "saved",
                "stateKey", LAST_TOPIC_KEY,
                "topic", topic);
    }

    @Schema(name = "read_profile_state", description = "读取当前 ADK Session state 中保存的用户资料和任务状态")
    public static Map<String, Object> readProfileState(ToolContext toolContext) {
        Map<String, Object> stateSnapshot = new LinkedHashMap<>();
        stateSnapshot.put(NAME_KEY, toolContext.state().get(NAME_KEY));
        stateSnapshot.put(LAST_TOPIC_KEY, toolContext.state().get(LAST_TOPIC_KEY));
        return stateSnapshot;
    }
}
