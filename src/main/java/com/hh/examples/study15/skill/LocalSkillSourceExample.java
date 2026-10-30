package com.hh.examples.study15.skill;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.nio.file.Path;
import java.util.Map;

public final class LocalSkillSourceExample {

    private static final String APP_NAME = "study15_local_skill_app";
    private static final String USER_ID = "study15-user";
    private static final String SESSION_ID = "local-skill-demo-session";
    private static final Path SKILLS_PATH = Path.of(
            "src/main/java/com/hh/examples/study15/skill/skills");

    private LocalSkillSourceExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("local_skill_agent")
                .description("演示 ADK LocalSkillSource 的 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是一个接入本地 Skill 文件的学习型助手。

                        你可以通过 Skill 工具查看和加载本地 skills 目录中的能力说明。
                        当用户的问题需要某个 Skill 时：
                        1. 先查看可用 Skills。
                        2. 加载最匹配的 Skill。
                        3. 如果 Skill 提到了模板或示例资源，再按需加载对应资源。
                        4. 严格按照 Skill 中的 instructions 完成回答。

                        回答时说明你使用了哪个本地 Skill。
                        """)
                .tools(new SkillToolset(createSkillSource()))
                .build();
    }

    public static SkillSource createSkillSource() {
        return new LocalSkillSource(SKILLS_PATH);
    }

    public static void main(String[] args) {
        SkillSource skillSource = createSkillSource();
        System.out.println("\n[本地 Skill 目录]");
        System.out.println(SKILLS_PATH.toAbsolutePath().normalize());

        System.out.println("\n[可用 Skills]");
        skillSource.listFrontmatters()
                .blockingGet()
                .forEach((name, frontmatter) ->
                        System.out.println("- " + name + " : " + frontmatter.description()));

        if (args.length > 0 && "--list-only".equals(args[0])) {
            return;
        }

        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        runTurn(runner, session, """
                帮我整理发布说明：
                新增：支持 Spring Boot 流式响应接口。
                修复：修复 appName 使用短横线导致 ADK 启动失败的问题。
                注意：前端需要改为读取 text/event-stream。
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
