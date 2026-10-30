package com.hh.examples.study19.plan;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Planner;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlannerAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.PlanningContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.planner.SupervisorPlanner;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;
import io.reactivex.rxjava3.core.Single;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Demonstrates ADK's PlannerAgent with an LLM-driven SupervisorPlanner.
 */
public final class PlanModeExample {

    private static final String APP_NAME = "study19_plan_mode_app";
    private static final String USER_ID = "study19_user";
    private static final String SESSION_ID = "study19_plan_mode_session";

    private static final String RESEARCH_KEY = "research_notes";
    private static final String IMPLEMENTATION_KEY = "implementation_plan";
    private static final String REVIEW_KEY = "review_report";

    private static final String SUPERVISOR_INSTRUCTION = """
            你是一个工作流规划器，只负责选择下一步应该运行的子 Agent。
            每次只能输出以下格式之一，不要输出解释、Markdown 或其他文字：
            - research_agent
            - implementation_agent
            - reviewer_agent
            - DONE

            依赖关系必须严格遵守：
            1. 没有 research_notes 时，先运行 research_agent。
            2. 有 research_notes 但没有 implementation_plan 时，运行 implementation_agent。
            3. 有 research_notes 和 implementation_plan 但没有 review_report 时，运行 reviewer_agent。
            4. 有 review_report 后输出 DONE。
            不要重复运行已经完成并写入 outputKey 的 Agent。
            """;

    private PlanModeExample() {
    }

    public static BaseAgent createAgent() {
        LlmAgent researchAgent = LlmAgent.builder()
                .name("research_agent")
                .description("收集 ADK 方案的事实、约束和风险。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是资料研究 Agent。
                        根据用户请求，整理实现该方案必须考虑的事实、约束和风险。
                        只输出研究结论，不要写最终方案。
                        """)
                .outputKey(RESEARCH_KEY)
                .build();

        LlmAgent implementationAgent = LlmAgent.builder()
                .name("implementation_agent")
                .description("基于研究结果设计可落地的 ADK 实现方案。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是实现方案 Agent。
                        请读取上游 research_agent 写入的 research_notes：

                        [研究结果]
                        {research_notes}

                        基于研究结果设计实现步骤、组件职责和失败处理策略。
                        不要重复研究，也不要输出最终评审结论。
                        """)
                .outputKey(IMPLEMENTATION_KEY)
                .build();

        LlmAgent reviewerAgent = LlmAgent.builder()
                .name("reviewer_agent")
                .description("审查研究结果和实现方案，生成最终交付内容。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是最终评审 Agent。
                        请基于以下两份上游结果给出最终回答：

                        [研究结果]
                        {research_notes}

                        [实现方案]
                        {implementation_plan}

                        请指出方案是否完整、主要风险和建议的下一步。
                        输出面向用户的最终答案，不要提及内部规划器。
                        """)
                .outputKey(REVIEW_KEY)
                .build();

        Planner planner = new TracingPlanner(new SupervisorPlanner(
                OpenAILangChainModelFactory.createChatModel(),
                SUPERVISOR_INSTRUCTION,
                16));

        return PlannerAgent.builder()
                .name("plan_mode_coordinator")
                .description("先根据状态规划下一步，再调度专业 Agent 完成任务。")
                .subAgents(researchAgent, implementationAgent, reviewerAgent)
                .planner(planner)
                .maxIterations(6)
                .build();
    }

    public static void main(String[] args) {
        List<Plugin> plugins = List.of(new LoggingPlugin());
        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME, plugins);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        String userInput = "请设计一个 ADK 多 Agent 方案：并行收集资料，整理实现步骤，最后给出风险评审。";
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        System.out.println("用户 > " + userInput);
        System.out.println("\n[Planner 与 Agent 执行轨迹]");

        runner.runAsync(
                        session.userId(),
                        session.id(),
                        Content.fromParts(Part.fromText(userInput)),
                        runConfig)
                .blockingForEach(PlanModeExample::printEvent);

        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        System.out.println("\n[Plan 输出写入的 Session state]");
        System.out.println(RESEARCH_KEY + " = " + updatedSession.state().get(RESEARCH_KEY));
        System.out.println(IMPLEMENTATION_KEY + " = " + updatedSession.state().get(IMPLEMENTATION_KEY));
        System.out.println(REVIEW_KEY + " = " + updatedSession.state().get(REVIEW_KEY));
    }

    private static void printEvent(Event event) {
        String text = extractText(event);
        System.out.println("\n[event] author=" + event.author());
        if (!event.actions().stateDelta().isEmpty()) {
            System.out.println("stateDelta=" + event.actions().stateDelta().keySet());
        }
        if (!text.isBlank()) {
            System.out.println("content=" + text);
        }
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .collect(Collectors.joining()))
                .orElse("");
    }

    /** Logs planner decisions, which are not ordinary LLM content Events. */
    private static final class TracingPlanner implements Planner {

        private final Planner delegate;

        private TracingPlanner(Planner delegate) {
            this.delegate = delegate;
        }

        @Override
        public void init(PlanningContext context) {
            delegate.init(context);
        }

        @Override
        public Single<PlannerAction> firstAction(PlanningContext context) {
            return trace("firstAction", delegate.firstAction(context));
        }

        @Override
        public Single<PlannerAction> nextAction(PlanningContext context) {
            return trace("nextAction", delegate.nextAction(context));
        }

        private Single<PlannerAction> trace(String phase, Single<PlannerAction> actionSingle) {
            return actionSingle.doOnSuccess(action -> {
                if (action instanceof PlannerAction.RunAgents runAgents) {
                    String names = runAgents.agents().stream()
                            .map(BaseAgent::name)
                            .collect(Collectors.joining(", "));
                    System.out.println("\n[Plan " + phase + "] RUN_AGENTS: " + names);
                } else {
                    System.out.println("\n[Plan " + phase + "] " + action.getClass().getSimpleName());
                }
            });
        }
    }
}
