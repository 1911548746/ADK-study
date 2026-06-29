package com.hh.examples.study04.state;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.Map;
import java.util.Optional;

public final class StateExample {

    private static final String USER_ID = "study04-user";

    private StateExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("state_agent")
                .description("一个只演示 ADK Session state 读写的最小 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是一个用于演示 ADK Session state 的简洁助手。
                        当用户告诉你姓名时，必须调用 remember_name 保存姓名。
                        当用户告诉你当前关注的话题时，必须调用 remember_topic 保存话题。
                        当用户询问你记住了什么、我叫什么、当前话题是什么时，必须调用 read_profile_state 读取 state。
                        回复要简洁，并说明你使用的是当前 Session state。
                        """)
                .tools(
                        FunctionTool.create(StateTools.class, "rememberName"),
                        FunctionTool.create(StateTools.class, "rememberTopic"),
                        FunctionTool.create(StateTools.class, "readProfileState"))
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        InMemoryRunner runner = new InMemoryRunner(createAgent(), "study04-state-app");

        Session session = runner.sessionService()
                .createSession(
                        runner.appName(),
                        USER_ID,
                        Map.of("profile.name", "未设置", "task.lastTopic", "未设置"),
                        "state-demo-session")
                .blockingGet();

        printState(runner, session, "初始 state");
        runTurn(runner, session, "我是小明。", runConfig);
        printState(runner, session, "保存姓名后");
        runTurn(runner, session, "我现在正在学习 ADK State。", runConfig);
        printState(runner, session, "保存话题后");
        runTurn(runner, session, "你从 state 里记住了什么？", runConfig);
        printState(runner, session, "读取 state 后");
    }

    private static void runTurn(
            InMemoryRunner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);

        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(event -> {
                    if (!event.actions().stateDelta().isEmpty()) {
                        System.out.println("stateDelta > " + event.actions().stateDelta());
                    }

                    if (!event.functionCalls().isEmpty()) {
                        System.out.println("functionCalls > " + event.functionCalls());
                    }

                    if (!event.functionResponses().isEmpty()) {
                        System.out.println("functionResponses > " + event.functionResponses());
                    }

                    if (event.finalResponse()) {
                        System.out.println("智能体 > " + event.stringifyContent());
                    }
                });
    }

    private static void printState(InMemoryRunner runner, Session session, String title) {
        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        System.out.println("\n[" + title + "]");
        System.out.println("sessionId: " + updatedSession.id());
        System.out.println("state:");
        updatedSession.state().forEach((key, value) ->
                System.out.println("  " + key + " = " + value));
    }
}
