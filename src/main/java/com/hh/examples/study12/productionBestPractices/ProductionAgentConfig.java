package com.hh.examples.study12.productionBestPractices;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.hh.examples.model.OpenAILangChainModelFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ProductionAgentConfig {

    @Bean
    public ProductionAgentProperties productionAgentProperties(Environment environment) {
        return ProductionAgentProperties.from(environment);
    }

    @Bean
    public LlmAgent study12Agent(ProductionAgentProperties properties) {
        return LlmAgent.builder()
                .name(properties.agentName())
                .description("一个演示 ADK 生产化边界和服务护栏的 Agent。")
                .model(createModel(properties))
                .instruction("""
                        你是 ADK 生产实践示例 Agent。
                        回答要偏工程化，重点解释生产系统中的配置、Runner 生命周期、Session 持久化、错误处理、观测性和超时边界。
                        如果用户询问具体实现，请先说明设计原则，再给出简洁可执行的建议。
                        不要声称当前示例已经具备完整生产级能力；需要区分示例护栏和真实生产基础设施。
                        """)
                .build();
    }

    @Bean
    public App study12App(LlmAgent study12Agent, ProductionAgentProperties properties) {
        return App.builder()
                .name(properties.appName())
                .rootAgent(study12Agent)
                .build();
    }

    @Bean
    public Runner study12Runner(App study12App) {
        return Runner.builder()
                .app(study12App)
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .build();
    }

    private static BaseLlm createModel(ProductionAgentProperties properties) {
        if (!properties.hasExternalModelConfig()) {
            return OpenAILangChainModelFactory.createChatAndStreamingModel();
        }

        ProductionAgentProperties.ModelProperties model = properties.model();
        return OpenAILangChainModelFactory.createChatAndStreamingModel(
                model.baseUrl(),
                model.apiKey(),
                model.name(),
                model.timeout(),
                model.maxRetries());
    }
}
