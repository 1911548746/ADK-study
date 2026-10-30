package com.hh.examples.study15.skill;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.InMemorySkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;

public final class SkillExample {

    private static final String APP_NAME = "study15_skill_app";
    private static final String USER_ID = "study15-user";
    private static final String SESSION_ID = "skill-demo-session";

    private SkillExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("skill_enabled_agent")
                .description("演示 ADK SkillToolset 的 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是一个学习型助手。

                        你已经接入了一组可按需加载的 Skills。
                        当用户的问题明显需要某个 Skill 时：
                        1. 先使用工具查看可用 Skills。
                        2. 再加载最匹配的 Skill。
                        3. 严格按照加载到的 Skill 指令完成回答。

                        不要臆造未加载的 Skill 内容。
                        回答时请说明你使用了哪个 Skill。
                        """)
                .tools(new SkillToolset(createSkillSource()))
                .build();
    }

    public static SkillSource createSkillSource() {
        return InMemorySkillSource.builder()
                .skill("incident-summary")
                .frontmatter(Frontmatter.builder()
                        .name("incident-summary")
                        .description("将故障、事故或线上问题整理成结构化复盘摘要。")
                        .license("internal-study")
                        .compatibility("ADK Java 1.4.0")
                        .allowedTools("none")
                        .metadata(Map.of("chapter", "15", "domain", "ops"))
                        .build())
                .instructions("""
                        你正在使用 incident-summary Skill。

                        请把用户提供的故障信息整理为以下结构：
                        1. 事件概述：一句话说明发生了什么。
                        2. 影响范围：说明影响的用户、服务或功能。
                        3. 初步原因：只基于用户提供的信息归纳，不要编造。
                        4. 处置动作：列出已经做过或应该马上做的动作。
                        5. 后续改进：给出 2 到 3 条可执行改进建议。

                        语气要像工程复盘，不要像客服回复。
                        """)
                .addResource("template.md", """
                        # Incident Summary Template

                        - 事件概述：
                        - 影响范围：
                        - 初步原因：
                        - 处置动作：
                        - 后续改进：
                        """)
                .skill("release-note")
                .frontmatter(Frontmatter.builder()
                        .name("release-note")
                        .description("将零散变更整理为面向用户或团队的发布说明。")
                        .license("internal-study")
                        .compatibility("ADK Java 1.4.0")
                        .allowedTools("none")
                        .metadata(Map.of("chapter", "15", "domain", "delivery"))
                        .build())
                .instructions("""
                        你正在使用 release-note Skill。

                        请把用户提供的变更内容整理为发布说明：
                        1. 标题：简短说明本次发布主题。
                        2. 新增能力：列出新增功能。
                        3. 修复问题：列出 bug fix。
                        4. 使用提醒：说明用户需要注意的行为变化。

                        如果用户没有提供某类信息，就写“无明确提及”。
                        """)
                .addResource("example.md", """
                        # Release Note Example

                        ## 新增能力
                        - 支持导出任务结果。

                        ## 修复问题
                        - 修复长文本显示截断问题。
                        """)
                .build();
    }

    public static void main(String[] args) {
        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        runTurn(runner, session, """
                你具备哪些skill技能？
                """);
    }

    private static void runTurn(InMemoryRunner runner, Session session, String userInput) {
        System.out.println("\n用户 > " + userInput);
        System.out.println("\n[事件流]");

        Content userMessage = Content.fromParts(Part.fromText(userInput));
        StringBuilder finalAnswer = new StringBuilder();

        runner.runAsync(session.userId(), session.id(), userMessage)
                .blockingForEach(event -> handleEvent(event, finalAnswer));

        System.out.println("\n[最终回答]");
        System.out.println(finalAnswer);
    }

    private static void handleEvent(Event event, StringBuilder finalAnswer) {
        if (!event.functionCalls().isEmpty()) {
            System.out.println("\nfunctionCalls > " + event.functionCalls());
            return;
        }

        if (!event.functionResponses().isEmpty()) {
            System.out.println("\nfunctionResponses > " + event.functionResponses());
            return;
        }

        if (event.finalResponse()) {
            String text = event.stringifyContent();
            finalAnswer.setLength(0);
            finalAnswer.append(text);
            System.out.println("\nfinalResponse > " + text);
        }
    }
}
