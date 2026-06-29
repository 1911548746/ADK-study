package com.hh.examples.study000.persistentCli.summarizer;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.summarizer.BaseEventSummarizer;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SimpleEventSummarizer implements BaseEventSummarizer {

    private static final String PROMPT_TEMPLATE = """
            请把下面这段 ADK Agent 事件历史压缩成简短摘要。

            要求：
            1. 保留用户明确表达的目标、偏好、姓名、约束和关键事实。
            2. 保留智能体已经给出的重要结论、决策和未完成事项。
            3. 忽略无意义寒暄、重复表达和底层实现噪声。
            4. 输出中文，控制在 200 字以内。
            5. 只输出摘要正文，不要输出标题、项目符号或解释。

            事件历史：
            {event_history}
            """;

    private final BaseLlm model;

    public SimpleEventSummarizer(BaseLlm model) {
        this.model = model;
    }

    @Override
    public Maybe<Event> summarizeEvents(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return Maybe.empty();
        }

        String prompt = PROMPT_TEMPLATE.replace("{event_history}", formatEvents(events));
        LlmRequest request = LlmRequest.builder()
                .model(model.model())
                .contents(List.of(Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromText(prompt)))
                        .build()))
                .build();

        // ADK 只要求 summarizer 返回一个带 compaction action 的 Event。
        // 这个 Event 会由框架追加到 Session events 中，后续上下文组织会优先使用它。
        return model.generateContent(request, false)
                .firstElement()
                .flatMap(response -> Maybe.fromOptional(response.content()
                        .map(content -> content.toBuilder().role("model").build())
                        .map(summaryContent -> buildCompactionEvent(events, summaryContent))));
    }

    private Event buildCompactionEvent(List<Event> events, Content summaryContent) {
        EventCompaction compaction = EventCompaction.builder()
                .startTimestamp(events.get(0).timestamp())
                .endTimestamp(events.get(events.size() - 1).timestamp())
                .compactedContent(summaryContent)
                .build();

        return Event.builder()
                .id(Event.generateEventId())
                .invocationId(Event.generateEventId())
                .author("user")
                .actions(EventActions.builder()
                        .compaction(compaction)
                        .build())
                .build();
    }

    private String formatEvents(List<Event> events) {
        List<String> lines = new ArrayList<>();
        for (Event event : events) {
            String text = eventText(event);
            if (text == null || text.isBlank()) {
                continue;
            }
            lines.add("- " + Optional.ofNullable(event.author()).orElse("unknown")
                    + ": " + text.replace("\n", " ").trim());
        }
        return String.join("\n", lines);
    }

    private String eventText(Event event) {
        String text = event.stringifyContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (event.actions() == null || event.actions().compaction().isEmpty()) {
            return text;
        }
        return event.actions()
                .compaction()
                .map(compaction -> compaction.compactedContent().text())
                .orElse(text);
    }
}
