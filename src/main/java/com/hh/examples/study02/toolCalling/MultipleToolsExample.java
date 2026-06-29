package com.hh.examples.study02.toolCalling;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import com.hh.examples.model.OpenAILangChainModelFactory;

public final class MultipleToolsExample {

    private MultipleToolsExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("multiple_tools_agent")
                .description("一个只演示多个 FunctionTool 注册的最小 ADK Java Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("你可以根据用户问题选择 make_greeting、count_characters、getWeather 工具。")
                .tools(
                        FunctionTool.create(SimpleTextTools.class, "makeGreeting"),
                        FunctionTool.create(SimpleTextTools.class, "countCharacters"),
                        FunctionTool.create(SimpleTextTools.class, "getWeather"))
                .build();
    }

}
