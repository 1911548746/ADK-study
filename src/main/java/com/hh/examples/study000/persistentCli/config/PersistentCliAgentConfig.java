package com.hh.examples.study000.persistentCli.config;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.runner.Runner;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LoadMemoryTool;
import com.google.adk.tools.mcp.McpToolset;
import com.hh.examples.model.OpenAILangChainModelFactory;
import com.hh.examples.study000.persistentCli.service.MySqlMemoryService;
import com.hh.examples.study000.persistentCli.service.MySqlSessionService;
import com.hh.examples.study000.persistentCli.service.SnipService;
import com.hh.examples.study000.persistentCli.service.ToolResultExternalizationService;
import com.hh.examples.study000.persistentCli.summarizer.SimpleEventSummarizer;
import com.hh.examples.study000.persistentCli.support.PersistentCliProperties;
import com.hh.examples.study000.persistentCli.tools.ContextManagedTools;
import com.hh.examples.study000.persistentCli.tools.SnipTools;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.hh.examples.study000.persistentCli.mapper")
public class PersistentCliAgentConfig {

    @Bean
    public LlmAgent persistentCliAgent(
            ToolResultExternalizationService externalizationService,
            SnipService snipService) {
        ContextManagedTools.configure(externalizationService);
        SnipTools.configure(snipService);

        return LlmAgent.builder()
                .name("persistent_cli_agent")
                .description("基于命令行的 MySQL 持久化 ADK 对话 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是 study000 的命令行对话智能体。
                        你的回复要简洁、自然，优先帮助用户理解 ADK 的 Session、Memory、Event 与持久化边界。
                        当用户询问自己的姓名、偏好、长期事实或历史信息时，你应该调用记忆工具检索长期 Memory。
                        当工具返回 externalized=true 时，说明你只看到了预览，不是完整内容。
                        如果用户问题依赖完整文件、完整日志或完整搜索结果，你必须继续调用 read_externalized_result 分片读取；
                        如果你只基于预览回答，必须明确说明“以下判断仅基于预览内容”。
                        当对话历史变长，且旧 Event 已经不再支持当前任务时，你可以主动调用 snip_messages
                        将不再需要的旧 Event 移出后续上下文。snip 前必须用 reason 保留关键信息。
                        不要把系统实现细节强行塞进每次回答；只有用户问到 ADK 机制时才解释。
                        """)
                // LoadMemoryTool 会通过 Runner 注入的 BaseMemoryService 检索长期记忆。
                .tools(
                        new LoadMemoryTool(),
                        FunctionTool.create(ContextManagedTools.class, "readTextFile"),
                        FunctionTool.create(ContextManagedTools.class, "readExternalizedResult"),
                        FunctionTool.create(SnipTools.class, "snipMessages"))
                .build();
    }

    @Bean
    public App persistentCliApp(LlmAgent persistentCliAgent) {
        return App.builder()
                .name(PersistentCliProperties.APP_NAME)
                .rootAgent(persistentCliAgent)
                .eventsCompactionConfig(EventsCompactionConfig.builder()
                        .compactionInterval(10)
                        .overlapSize(1)
                        .summarizer(new SimpleEventSummarizer(OpenAILangChainModelFactory.createChatModel()))
                        .build())
                .build();
    }

    @Bean
    public Runner persistentCliRunner(
            App persistentCliApp,
            MySqlSessionService sessionService,
            MySqlMemoryService memoryService) {
        return Runner.builder()
                .app(persistentCliApp)
                .artifactService(new InMemoryArtifactService())
                .sessionService(sessionService)
                .memoryService(memoryService)
                .build();
    }
}
