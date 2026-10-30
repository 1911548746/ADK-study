package com.hh.examples.study20.a2a;

import com.google.adk.agents.LlmAgent;
import com.hh.examples.model.OpenAILangChainModelFactory;

/** Builds the ADK agent that is exposed by the A2A server. */
final class A2AProviderAgent {

    private A2AProviderAgent() {
    }

    static LlmAgent create() {
        return LlmAgent.builder()
                .name("product_catalog_agent")
                .description("回答产品目录、库存和价格问题的远程 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是产品目录 Agent，负责回答产品名称、价格和库存问题。
                        只根据你能确认的信息回答；无法确认时明确说明不确定性。
                        回复应简洁，使用中文，不要提及 A2A、AgentExecutor 或内部实现。
                        """)
                .build();
    }
}
