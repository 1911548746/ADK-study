package com.hh.examples.study02.toolCalling;

import com.google.adk.tools.Annotations.Schema;
import java.util.Map;

public final class SimpleTextTools {

    private SimpleTextTools() {
    }

    @Schema(name = "get_weather", description = "获取指定城市的天气信息")
    public static Map<String, Object> getWeather(
            @Schema(name = "city", description = "需要查询天气的城市名称") String city) {
        return Map.of("city", city, "weather", "雨天");
    }

    @Schema(name = "make_greeting", description = "根据姓名生成一句简短中文问候语")
    public static Map<String, Object> makeGreeting(
            @Schema(name = "name", description = "需要问候的用户姓名") String name) {
        return Map.of("greeting", "你好，" + name + "。");
    }

    @Schema(name = "count_characters", description = "统计文本中的字符数量")
    public static Map<String, Object> countCharacters(
            @Schema(name = "text", description = "需要统计长度的文本") String text) {
        return Map.of("characterCount", text.length());
    }
}
