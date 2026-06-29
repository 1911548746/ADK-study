package com.hh.examples.study10.ragAgent;

import com.google.adk.tools.Annotations.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RagKnowledgeTools {

    private static final List<KnowledgeChunk> KNOWLEDGE_BASE = List.of(
            new KnowledgeChunk(
                    "doc-session-state",
                    "Session 与 State 的边界",
                    """
                            Session 是一次用户会话的运行容器，保存当前会话的事件历史和结构化 state。
                            State 是 Session 内的共享结构化工作区，适合保存当前任务进度、工具结果摘要和 workflow 中间产物。
                            State 不应该直接当作长期知识库使用，跨 Session 的事实应进入 Memory 或外部存储。
                            """),
            new KnowledgeChunk(
                    "doc-memory",
                    "Memory 的定位",
                    """
                            Memory 面向跨 Session 的长期记忆和检索，适合保存用户偏好、长期事实和经过筛选的会话摘要。
                            Runner 不会自动决定哪些内容应该进入 Memory；生产系统通常需要写入策略、去重和权限控制。
                            LoadMemoryTool 可以让 Agent 在需要长期信息时主动检索 MemoryService。
                            """),
            new KnowledgeChunk(
                    "doc-rag",
                    "RAG Agent 的最小结构",
                    """
                            RAG Agent 通常由检索器、知识片段、上下文注入和带引用回答组成。
                            Agent 不应该把模型参数知识当作唯一来源，而应先检索外部知识，再基于命中的片段回答。
                            如果检索结果不足，Agent 应明确说明知识库中没有足够依据，而不是编造答案。
                            """),
            new KnowledgeChunk(
                    "doc-workflow",
                    "Workflow Agent 与 LLM Agent",
                    """
                            SequentialAgent、ParallelAgent 和 LoopAgent 是 workflow agent，负责控制子 Agent 的执行顺序、并行和循环。
                            LlmAgent 才负责模型推理、工具调用、instruction 和 outputKey。
                            复杂系统中常用 workflow agent 编排流程，用 LlmAgent 执行语义任务。
                            """),
            new KnowledgeChunk(
                    "doc-events",
                    "Events 的作用",
                    """
                            Event 是 ADK 运行过程的事实记录，可以包含模型输出、工具调用、工具结果、stateDelta、transferToAgent 和错误信息。
                            多个 Agent 在同一个 Runner 调用链中运行时，它们的事件会进入同一个 Session events。
                            Events 更像审计轨迹和上下文历史，不等于实时消息总线。
                            """));

    private RagKnowledgeTools() {
    }

    @Schema(name = "search_adk_knowledge", description = "从本地 ADK 学习知识库中检索与问题相关的片段，用于 RAG 回答")
    public static Map<String, Object> searchAdkKnowledge(
            @Schema(name = "query", description = "用户问题或需要检索的关键词") String query) {

        List<String> queryTerms = tokenize(query);
        List<Map<String, Object>> hits = KNOWLEDGE_BASE.stream()
                .map(chunk -> toScoredHit(chunk, queryTerms))
                .filter(hit -> ((Integer) hit.get("score")) > 0)
                .sorted(Comparator.comparing((Map<String, Object> hit) -> (Integer) hit.get("score")).reversed())
                .limit(3)
                .toList();

        return Map.of(
                "query", query,
                "hitCount", hits.size(),
                "hits", hits,
                "answerPolicy", "只能基于 hits 中的 content 回答；如果 hitCount 为 0，说明知识库没有足够依据。");
    }

    private static Map<String, Object> toScoredHit(KnowledgeChunk chunk, List<String> queryTerms) {
        String searchableText = (chunk.title() + " " + chunk.content()).toLowerCase(Locale.ROOT);
        int score = 0;
        Set<String> matchedTerms = queryTerms.stream()
                .filter(term -> searchableText.contains(term.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        for (String term : matchedTerms) {
            score += term.length() > 3 ? 2 : 1;
        }

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("sourceId", chunk.sourceId());
        hit.put("title", chunk.title());
        hit.put("score", score);
        hit.put("matchedTerms", new ArrayList<>(matchedTerms));
        hit.put("content", chunk.content().strip());
        return hit;
    }

    private static List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+", " ");

        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            terms.add(token);
            if (containsHan(token)) {
                addChineseKeywords(token, terms);
            }
        }
        return terms;
    }

    private static boolean containsHan(String token) {
        return token.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static void addChineseKeywords(String token, List<String> terms) {
        List<String> keywords = List.of(
                "session", "state", "memory", "rag", "agent", "workflow", "event",
                "检索", "知识", "记忆", "会话", "状态", "工具", "并行", "汇总", "引用", "编造");
        for (String keyword : keywords) {
            if (token.contains(keyword.toLowerCase(Locale.ROOT))) {
                terms.add(keyword);
            }
        }
    }

    private record KnowledgeChunk(String sourceId, String title, String content) {
    }
}
