package com.hh.examples.study16.thinkingMode;

import com.hh.examples.model.OpenAILangChainModelFactory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams provider reasoning_content before normal answer tokens.
 *
 * <p>This example consumes LangChain4j directly because google-adk-langchain4j 1.4.0
 * does not map onPartialThinking() into ADK Events.</p>
 */
public final class StreamingThinkingModeExample {

    private static final String QUESTION = """
            有三个箱子，标签分别是“苹果”“橘子”“混合”，但三个标签全都贴错了。
            你只能从一个箱子里拿出一个水果。应从哪个箱子取，才能据此正确重贴三个标签？
            给出结论，并在三条以内说明可验证的依据。
            """;

    private StreamingThinkingModeExample() {
    }

    public static void main(String[] args) {
        OpenAiStreamingChatModel chatModel = OpenAILangChainModelFactory.createThinkingStreamingChatModel();
        streamThinkingThenAnswer(chatModel);
    }

    private static void streamThinkingThenAnswer(OpenAiStreamingChatModel chatModel) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(QUESTION))
                .build();

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        boolean[] thinkingStarted = {false};
        boolean[] answerStarted = {false};

        chatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (!thinkingStarted[0]) {
                    thinkingStarted[0] = true;
                    System.out.println("\n[思考过程]");
                }
                System.out.print(partialThinking.text());
                System.out.flush();
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                if (!answerStarted[0]) {
                    answerStarted[0] = true;
                    System.out.println("\n\n[正式回答]");
                }
                System.out.print(partialResponse);
                System.out.flush();
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completed.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                completed.countDown();
            }
        });

        awaitCompletion(completed, error);

        if (!thinkingStarted[0]) {
            System.out.println("\n\n未收到流式 reasoning_content；请确认当前模型和接口支持思考内容流式返回。");
        }
        System.out.println("\n\n[流式输出结束]");
    }

    private static void awaitCompletion(CountDownLatch completed, AtomicReference<Throwable> error) {
        try {
            if (!completed.await(2, TimeUnit.MINUTES)) {
                throw new IllegalStateException("模型流式响应在两分钟内未结束。");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待模型流式响应时被中断。", exception);
        }

        if (error.get() != null) {
            throw new IllegalStateException("模型流式调用失败。", error.get());
        }
    }
}
