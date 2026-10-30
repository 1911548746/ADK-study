package com.hh.examples.study18.hitl.fullVersion;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import com.hh.examples.model.OpenAILangChainModelFactory;
import com.hh.examples.study18.hitl.RefundTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

@Configuration
public class HITLAgentConfig {

    public static final String APP_NAME = "study18_hitl_app";

    @Bean
    public LlmAgent hitlAgent() {
        return LlmAgent.builder()
                .name("refund_hitl_agent")
                .description("在执行退款前请求人工确认的 HITL Agent。")
                .model(OpenAILangChainModelFactory.createChatAndStreamingModel())
                .instruction("""
                        你是订单退款助手。
                        当用户要求退款时，必须调用 request_refund，不能自行承诺退款结果。
                        如果工具返回 pending_human_confirmation，说明正在等待人工审批，不得把退款描述为已完成。
                        如果工具返回 rejected_by_human，说明退款被拒绝且没有执行。
                        只有工具返回 refund_executed 时，才能确认退款已完成。
                        """)
                .tools(FunctionTool.create(RefundTools.class, "requestRefund"))
                .maxSteps(4)
                .build();
    }

    @Bean
    public App hitlApp(@Qualifier("hitlAgent") LlmAgent hitlAgent) {

        List<LoggingPlugin> plugins = List.of(new LoggingPlugin());

        return App.builder()
                .name(APP_NAME)
                .rootAgent(hitlAgent)
                .plugins(plugins)
                .build();
    }

    @Bean
    public Runner hitlRunner(@Qualifier("hitlApp") App hitlApp) {
        return Runner.builder()
                .app(hitlApp)
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .build();
    }
}
