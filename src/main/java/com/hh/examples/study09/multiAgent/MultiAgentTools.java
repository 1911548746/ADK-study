package com.hh.examples.study09.multiAgent;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MultiAgentTools {

    public static final String ARCHITECTURE_RESULT_KEY = "multiAgent.architectureResult";
    public static final String RISK_RESULT_KEY = "multiAgent.riskResult";
    public static final String LANGGRAPH_RESULT_KEY = "multiAgent.langGraphResult";

    private MultiAgentTools() {
    }

    @Schema(name = "read_parallel_results", description = "读取并行 worker Agent 写入 Session state 的分析结果")
    public static Map<String, Object> readParallelResults(ToolContext toolContext) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(ARCHITECTURE_RESULT_KEY, toolContext.state().get(ARCHITECTURE_RESULT_KEY));
        results.put(RISK_RESULT_KEY, toolContext.state().get(RISK_RESULT_KEY));
        results.put(LANGGRAPH_RESULT_KEY, toolContext.state().get(LANGGRAPH_RESULT_KEY));
        return results;
    }
}
