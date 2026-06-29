package com.hh.examples.study09.multiAgent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;
import java.util.Optional;

public final class MultiAgentExample {

    private static final String APP_NAME = "study09-multi-agent-app";
    private static final String USER_ID = "study09-user";
    private static final String SESSION_ID = "multi-agent-demo-session";

    private MultiAgentExample() {
    }

    public static BaseAgent createAgent() {
        ParallelAgent parallelWorkers = ParallelAgent.builder()
                .name("parallel_analysis_team")
                .description("并行运行多个专业分析 Agent。")
                .subAgents(
                        createArchitectureWorker(),
                        createRiskWorker(),
                        createLangGraphWorker())
                .build();

        return SequentialAgent.builder()
                .name("parallel_then_summary_workflow")
                .description("先并行分析，再由汇总 Agent 统一整理最终答案。")
                .subAgents(parallelWorkers, createSummaryAgent())
                .build();
    }

    private static LlmAgent createArchitectureWorker() {
        return LlmAgent.builder()
                .name("architecture_worker")
                .description("从 ADK 架构边界角度分析问题。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 ADK 架构分析 worker。
                        请只从 Agent、Runner、Session、Event、Tool 的职责边界分析用户问题。
                        输出 2 到 3 条结论，不要做最终汇总。
                        """)
                .outputKey(MultiAgentTools.ARCHITECTURE_RESULT_KEY)
                .build();
    }

    private static LlmAgent createRiskWorker() {
        return LlmAgent.builder()
                .name("risk_worker")
                .description("从工程风险和边界条件角度分析问题。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是工程风险分析 worker。
                        请只分析实现这个 Agent 流程时可能出现的风险、失败模式和边界条件。
                        输出 2 到 3 条结论，不要做最终汇总。
                        """)
                .outputKey(MultiAgentTools.RISK_RESULT_KEY)
                .build();
    }

    private static LlmAgent createLangGraphWorker() {
        return LlmAgent.builder()
                .name("langgraph_worker")
                .description("从 LangGraph 对照角度分析问题。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 LangGraph 对照分析 worker。
                        请只说明这个流程在 LangGraph 中大致对应什么节点、边和 state 设计。
                        输出 2 到 3 条结论，不要做最终汇总。
                        """)
                .outputKey(MultiAgentTools.LANGGRAPH_RESULT_KEY)
                .build();
    }

    private static LlmAgent createSummaryAgent() {
        return LlmAgent.builder()
                .name("summary_agent")
                .description("读取多个 worker 的 state 输出，并给出最终汇总。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 Multi-Agent 汇总 Agent。
                        你必须先调用 read_parallel_results 读取并行 worker 写入 Session state 的结果。
                        然后综合 architecture_worker、risk_worker、langgraph_worker 的结果，输出最终答案。
                        最终答案包含三部分：推荐实现、关键风险、LangGraph 对照。
                        不要重新发散分析，重点做归纳和取舍。
                        """)
                .tools(FunctionTool.create(MultiAgentTools.class, "readParallelResults"))
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        runTurn(runner, session, """
                我想设计一个 ADK 多 Agent 流程：
                多个分析 Agent 同时运行，最后由一个主 Agent 汇总结果。
                请给出适合学习项目的实现建议。
                """, runConfig);

        printFinalState(runner, session);
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput.strip());
        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(MultiAgentExample::printEvent);
        System.out.println("end");
    }

    private static void printEvent(Event event) {
        System.out.println("\n[event]");
        System.out.println("author: " + event.author());

        if (!event.functionCalls().isEmpty()) {
            System.out.println("functionCalls: " + event.functionCalls());
        }

        if (!event.functionResponses().isEmpty()) {
            System.out.println("functionResponses: " + event.functionResponses());
        }

        if (!event.actions().stateDelta().isEmpty()) {
            System.out.println("stateDelta: " + event.actions().stateDelta().keySet());
        }

        String text = extractText(event);
        if (!text.isBlank()) {
            System.out.println("content: " + text);
        }

        if (event.finalResponse() && !text.isBlank()) {
            System.out.println("智能体 > " + event.stringifyContent());
        }
    }

    private static void printFinalState(InMemoryRunner runner, Session session) {
        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        System.out.println("\n[并行 worker 写入的 Session state]");
        printStateValue(updatedSession, MultiAgentTools.ARCHITECTURE_RESULT_KEY);
        printStateValue(updatedSession, MultiAgentTools.RISK_RESULT_KEY);
        printStateValue(updatedSession, MultiAgentTools.LANGGRAPH_RESULT_KEY);
    }

    private static void printStateValue(Session session, String key) {
        Object value = session.state().get(key);
        System.out.println(key + " = " + value);
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }
}
