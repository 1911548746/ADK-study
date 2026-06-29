package com.hh.examples.model;

import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

public final class OpenAILangChainModelFactory {

    private static final String BASE_URL = "https://api.siliconflow.cn";
    private static final String API_KEY = "sk-lddiozvjmpxmdsdjqcjtthuvpwxrpdiyepesltiwfzbgiicw";
    private static final String MODEL_NAME = "deepseek-ai/DeepSeek-V4-Flash";

    private OpenAILangChainModelFactory() {
    }

    public static LangChain4j createChatModel() {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .modelName(MODEL_NAME)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(4)
                .build();

        LangChain4j build = LangChain4j.builder()
                .chatModel(chatModel)
                .modelName(MODEL_NAME)
                .build();

        return build;
    }

    public static LangChain4j createStreamingChatModel() {
        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .modelName(MODEL_NAME)
                .timeout(Duration.ofSeconds(90))
                .build();

        return LangChain4j.builder()
                .streamingChatModel(streamingChatModel)
                .modelName(MODEL_NAME)
                .build();
    }

    public static LangChain4j createChatAndStreamingModel() {
        return createChatAndStreamingModel(BASE_URL, API_KEY, MODEL_NAME, Duration.ofSeconds(90), 4);
    }

    public static LangChain4j createChatAndStreamingModel(
            String baseUrl,
            String apiKey,
            String modelName,
            Duration timeout,
            int maxRetries) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();

        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .build();

        return LangChain4j.builder()
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .modelName(modelName)
                .build();
    }

}
