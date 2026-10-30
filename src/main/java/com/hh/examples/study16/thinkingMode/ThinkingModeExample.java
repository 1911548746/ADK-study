package com.hh.examples.study16.thinkingMode;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates a reasoning-capable OpenAI-compatible model used through ADK.
 *
 * <p>Run normally to display the reasoning text and final answer returned by the provider.
 * Run with {@code --adk} to compare it with an ADK Runner execution, whose standard Event
 * does not currently carry the reasoning text from the LangChain4j adapter.</p>
 */
public final class ThinkingModeExample {

    private static final String APP_NAME = "study16-thinking-mode-app";
    private static final String USER_ID = "study16-user";
    private static final String SESSION_ID = "thinking-mode-session";
    private static final String QUESTION = """
            有三个箱子，标签分别是“苹果”“橘子”“混合”，但三个标签全都贴错了。
            你只能从一个箱子里拿出一个水果。应从哪个箱子取，才能据此正确重贴三个标签？
            给出结论，并在三条以内说明可验证的依据。
            """;

    private ThinkingModeExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel chatModel = OpenAILangChainModelFactory.createThinkingChatModel();

//        if (args.length > 0 && "--adk".equals(args[0])) {
            runWithAdk(chatModel);
//            return;
//        }
//
//        displayThinkingAndAnswer(chatModel);
    }

    public static LlmAgent createAgent(OpenAiChatModel chatModel) {
        LangChain4j adkModel = LangChain4j.builder()
                .chatModel(chatModel)
                .modelName(chatModel.defaultRequestParameters().modelName())
                .build();

        return LlmAgent.builder()
                .name("thinking_mode_agent")
                .description("使用支持 reasoning 的模型回答需要严谨推导的问题。")
                .model(adkModel)
                .instruction("""
                        你是一个严谨的中文问题求解助手。
                        遇到需要推导的问题时，应先在内部充分分析，再给出最终回答。
                        对用户只输出结论和不超过三条、可独立验证的关键依据。
                        """)
                .build();
    }

    private static void runWithAdk(OpenAiChatModel chatModel) {
        List<Plugin> plugins = List.of(
                new LoggingPlugin("study16_thinking_mode")
        );

        InMemoryRunner runner = new InMemoryRunner(createAgent(chatModel), APP_NAME, plugins);

        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        Content userMessage = Content.fromParts(Part.fromText(QUESTION));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        String[] finalAnswer = {""};
        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(event -> finalAnswer[0] = latestText(event, finalAnswer[0]));

        System.out.println("\n[ADK Runner 最终回答]");
        System.out.println(finalAnswer[0]);
        System.out.println("\n提示：ADK 的 LangChain4j 适配层当前只将 AiMessage.text() 映射为 Event 文本。");
        System.out.println("如需查看模型返回的思考内容，请不带参数运行本示例。");
    }

    private static String latestText(Event event, String previousText) {
        String text = event.content().map(Content::text).orElse("");
        return text.isBlank() ? previousText : text;
    }

    private static void displayThinkingAndAnswer(OpenAiChatModel chatModel) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(QUESTION))
                .build();
        ChatResponse response = chatModel.chat(request);
        AiMessage message = response.aiMessage();
        String thinking = message.thinking();

        System.out.println("\n[思考过程]");
        System.out.println(thinking == null || thinking.isBlank()
                ? "当前响应未包含 reasoning_content。"
                : thinking);
        System.out.println("\n[最终回答]");
        System.out.println(message.text());

        if (thinking == null || thinking.isBlank()) {
            System.out.println("\n请检查模型是否为推理模型，以及 OpenAI 兼容接口是否返回 reasoning_content。");
        }
    }

}
