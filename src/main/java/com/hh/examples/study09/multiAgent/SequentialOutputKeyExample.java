package com.hh.examples.study09.multiAgent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;
import java.util.Optional;

public final class SequentialOutputKeyExample {

    private static final String APP_NAME = "study09_sequential_output_key_app";
    private static final String USER_ID = "study09-user";
    private static final String SESSION_ID = "sequential-output-key-demo-session";

    private static final String REQUIREMENT_BRIEF_KEY = "requirement_brief";
    private static final String DESIGN_PLAN_KEY = "design_plan";
    private static final String REVIEW_REPORT_KEY = "review_report";

    private SequentialOutputKeyExample() {
    }

    public static BaseAgent createAgent() {
        return SequentialAgent.builder()
                .name("sequential_output_key_workflow")
                .description("按顺序执行多个 Agent，并用 outputKey 将上游输出注入下游 instruction。")
                .subAgents(
                        createRequirementAgent(),
                        createDesignAgent(),
                        createReviewAgent())
                .build();
    }

    private static LlmAgent createRequirementAgent() {
        return LlmAgent.builder()
                .name("requirement_agent")
                .description("提炼用户需求，输出给下一个 Agent 使用。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是需求提炼 Agent。
                        请把用户输入整理成一份简短需求摘要，只输出：
                        1. 目标
                        2. 约束
                        3. 期望输出
                        不要给实现方案。
                        """)
                .outputKey(REQUIREMENT_BRIEF_KEY)
                .build();
    }

    private static LlmAgent createDesignAgent() {
        return LlmAgent.builder()
                .name("design_agent")
                .description("读取 requirement_agent 的 outputKey，生成方案设计。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是方案设计 Agent。

                        以下内容来自上一个 Agent 的 outputKey：requirement_brief。

                        [上游需求摘要]
                        {requirement_brief}

                        请严格基于上游需求摘要，设计一个 ADK Multi-Agent 顺序流程。
                        输出必须包含：
                        1. Agent 划分
                        2. 每个 Agent 的职责
                        3. 每一步应该写入的 outputKey
                        不要做质量评审。
                        """)
                .outputKey(DESIGN_PLAN_KEY)
                .build();
    }

    private static LlmAgent createReviewAgent() {
        return LlmAgent.builder()
                .name("review_agent")
                .description("读取 design_agent 的 outputKey，评审并输出最终建议。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是评审 Agent。

                        以下内容来自上一个 Agent 的 outputKey：design_plan。

                        [上游方案设计]
                        {design_plan}

                        请只基于上游方案设计进行评审。
                        输出最终答案，包含：
                        1. 这个顺序流程是否合理
                        2. outputKey 传递是否清晰
                        3. 需要补充的风险或改进建议
                        """)
                .outputKey(REVIEW_REPORT_KEY)
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
                我想学习 ADK 中 SequentialAgent 的 outputKey 传递机制。
                请设计一个三步流程：先提炼需求，再设计方案，最后评审方案。
                重点展示上一步 Agent 的输出如何进入下一步 Agent 的 instruction。
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
                .blockingForEach(SequentialOutputKeyExample::printEvent);
    }

    private static void printEvent(Event event) {
        System.out.println("\n[event]");
        System.out.println("author: " + event.author());

        if (!event.actions().stateDelta().isEmpty()) {
            System.out.println("stateDelta: " + event.actions().stateDelta().keySet());
        }

        String text = extractText(event);
        if (!text.isBlank()) {
            System.out.println("content: " + text);
        }
    }

    private static void printFinalState(InMemoryRunner runner, Session session) {
        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        System.out.println("\n[SequentialAgent outputKey 写入的 Session state]");
        printStateValue(updatedSession, REQUIREMENT_BRIEF_KEY);
        printStateValue(updatedSession, DESIGN_PLAN_KEY);
        printStateValue(updatedSession, REVIEW_REPORT_KEY);
    }

    private static void printStateValue(Session session, String key) {
        Object value = session.state().get(key);
        System.out.println("\n" + key + " =");
        System.out.println(value);
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
