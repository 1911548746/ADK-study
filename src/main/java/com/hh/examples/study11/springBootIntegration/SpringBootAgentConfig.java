package com.hh.examples.study11.springBootIntegration;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import com.hh.examples.model.OpenAILangChainModelFactory;
import com.hh.examples.study02.toolCalling.SimpleTextTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SpringBootAgentConfig {

    public static final String APP_NAME = "study11_spring_boot_app";

    @Bean
    public LlmAgent study11Agent() {
//        return LlmAgent.builder()
//                .name("spring_boot_integration_agent")
//                .description("一个用于演示 ADK 与 Spring Boot 集成边界的 Agent。")
//                .model(OpenAILangChainModelFactory.createChatAndStreamingModel())
//                .instruction("""
//                        你是 ADK Spring Boot 集成示例 Agent。
//                        回答要简洁，重点解释 Agent、Runner、Session、Service、Controller 的职责边界。
//                        如果用户问实现建议，请优先强调：Runner 放在 Spring Bean 中复用，Session 由业务层管理，Controller 只做 HTTP 入参出参。
//                        """)
//                .build();

        return LlmAgent.builder()
                .name("hello_agent")
                .description("A minimal ADK Java hello agent.")
                .model(OpenAILangChainModelFactory.createChatAndStreamingModel())
                .instruction("You are a concise assistant. Say hello in a friendly way.")
                .tools(
                        FunctionTool.create(SimpleTextTools.class, "getWeather"))
                .build();
    }

    @Bean
    public App study11App(LlmAgent study11Agent) {

        List<LoggingPlugin> plugins = List.of(new LoggingPlugin());

        return App.builder()
                .name(APP_NAME)
                .rootAgent(study11Agent)
                .plugins(plugins)
                .build();
    }

    @Bean
    public Runner study11Runner(App study11App) {
        return Runner.builder()
                .app(study11App)
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .build();
    }
}
