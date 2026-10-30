package com.hh.examples.study14.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;
import com.hh.examples.study02.toolCalling.SimpleTextTools;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PluginExample {

    private static final String APP_NAME = "study14_plugin_app";
    private static final String USER_ID = "study14-user";
    private static final String SESSION_ID = "plugin-demo-session";

    private PluginExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("plugin_demo_agent")
                .description("一个用于演示 Runner Plugin 生命周期的 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 ADK Plugin 示例 Agent。
                        当用户询问天气时，必须调用 get_weather。
                        当用户要求问候时，必须调用 make_greeting。
                        回答要简洁，并说明你是否调用了工具。
                        """)
                .tools(
                        FunctionTool.create(SimpleTextTools.class, "getWeather"),
                        FunctionTool.create(SimpleTextTools.class, "makeGreeting"))
                .build();
    }

    public static Runner createRunner(BaseAgent agent) {
        List<Plugin> plugins = List.of(
                new LoggingPlugin("study14_logging_plugin")
//                , new RunTimingPlugin()
//                , new EventCounterPlugin()
        );

        return Runner.builder()
                .agent(agent)
                .appName(APP_NAME)
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .plugins(plugins)
                .build();
    }

    public static void main(String[] args) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        Runner runner = createRunner(createAgent());
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        runTurn(runner, session, "请查询北京天气，然后用一句话问候小明。", runConfig);

        runner.close().blockingAwait();
    }

    private static void runTurn(
            Runner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);
        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(PluginExample::printEvent);
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

        String text = extractText(event);
        if (!text.isBlank()) {
            System.out.println("content: " + text);
        }

        if (event.finalResponse() && !text.isBlank()) {
            System.out.println("智能体 > " + text);
        }
    }

    private static String extractText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }

    private static String extractText(Content content) {
        return content.parts()
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce("", String::concat))
                .orElse("");
    }

    private static final class RunTimingPlugin extends BasePlugin {

        private static final String START_NANOS_KEY = "study14.plugin.startNanos";

        private RunTimingPlugin() {
            super("study14_run_timing_plugin");
        }

        @Override
        public Maybe<Content> onUserMessageCallback(
                InvocationContext invocationContext,
                Content userMessage) {
            System.out.println("[RunTimingPlugin] userMessage = " + extractText(userMessage));
            return Maybe.empty();
        }

        @Override
        public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
            invocationContext.callbackContextData().put(START_NANOS_KEY, System.nanoTime());
            System.out.println("[RunTimingPlugin] beforeRun invocationId=" + invocationContext.invocationId());
            return Maybe.empty();
        }

        @Override
        public Completable afterRunCallback(InvocationContext invocationContext) {
            return Completable.fromAction(() -> {
                Object startedAt = invocationContext.callbackContextData().get(START_NANOS_KEY);
                if (startedAt instanceof Long startNanos) {
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    System.out.println("[RunTimingPlugin] afterRun durationMs=" + durationMs);
                }
            });
        }

        @Override
        public Completable close() {
            return Completable.fromAction(() ->
                    System.out.println("[RunTimingPlugin] close"));
        }
    }

    private static final class EventCounterPlugin extends BasePlugin {

        private static final String EVENT_COUNT_KEY = "study14.plugin.eventCount";

        private EventCounterPlugin() {
            super("study14_event_counter_plugin");
        }

        @Override
        public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
            System.out.println("[EventCounterPlugin] beforeAgent agent=" + agent.name());
            return Maybe.empty();
        }

        @Override
        public Maybe<Event> onEventCallback(
                InvocationContext invocationContext,
                Event event) {
            int count = Optional.ofNullable(invocationContext.callbackContextData().get(EVENT_COUNT_KEY))
                    .filter(Integer.class::isInstance)
                    .map(Integer.class::cast)
                    .orElse(0) + 1;
            invocationContext.callbackContextData().put(EVENT_COUNT_KEY, count);

            System.out.println("[EventCounterPlugin] onEvent #" + count
                    + " author=" + event.author()
                    + " final=" + event.finalResponse());
            return Maybe.empty();
        }

        @Override
        public Maybe<Map<String, Object>> beforeToolCallback(
                BaseTool tool,
                Map<String, Object> input,
                ToolContext toolContext) {
            System.out.println("[EventCounterPlugin] beforeTool tool=" + tool.name()
                    + " input=" + input);
            return Maybe.empty();
        }

        @Override
        public Maybe<Map<String, Object>> afterToolCallback(
                BaseTool tool,
                Map<String, Object> input,
                ToolContext toolContext,
                Map<String, Object> output) {
            System.out.println("[EventCounterPlugin] afterTool tool=" + tool.name()
                    + " output=" + output);
            return Maybe.empty();
        }

        @Override
        public Completable afterRunCallback(InvocationContext invocationContext) {
            return Completable.fromAction(() -> {
                Object eventCount = invocationContext.callbackContextData().get(EVENT_COUNT_KEY);
                System.out.println("[EventCounterPlugin] afterRun eventCount=" + eventCount);
            });
        }
    }
}
