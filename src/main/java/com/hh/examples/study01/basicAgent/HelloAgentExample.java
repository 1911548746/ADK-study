package com.hh.examples.study01.basicAgent;

import com.google.adk.agents.LlmAgent;
import com.hh.examples.model.OpenAILangChainModelFactory;

public final class HelloAgentExample {

    private HelloAgentExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("hello_agent")
                .description("A minimal ADK Java hello agent.")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("You are a concise assistant. Say hello in a friendly way.")
                .build();
    }

}
