package com.hh.examples.study17.react;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.util.List;
import java.util.Map;

public final class ReActExample {

    private static final String APP_NAME = "study17-react-app";
    private static final String USER_ID = "study17-user";
    private static final String SESSION_ID = "react-demo-session";

    private ReActExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("incident_react_agent")
                .description("通过观察监控数据并逐步选择工具，诊断线上服务故障的 ReAct Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是生产环境故障诊断 Agent。你必须通过工具观察真实数据，不能凭常识直接猜测故障原因。

                        执行规则：
                        1. 首先调用 list_service_health，environment 固定为 production。
                        2. 根据每次工具返回的 Observation 判断下一步，不要预先假定异常服务或根因。
                        3. 对候选异常服务，至少取得指标和日志两类独立证据。
                        4. 只有当证据显示故障可能与近期变更有关时，才调用 get_recent_deployments。
                        5. 确定最可能的故障类型后，调用 get_incident_runbook 获取处置建议。
                        6. 工具只用于读取信息，不得声称已经执行回滚、扩容或配置变更。
                        7. 信息不足时继续选择合适的工具；证据足够后停止调用工具并给出最终回答。

                        最终回答必须包含：最可能根因、关键证据、建议动作、不确定性。
                        不要输出完整内部思考过程；Action 和 Observation 将由程序根据 ADK Event 单独展示。
                        """)
                .tools(
                        FunctionTool.create(ReActTools.class, "listServiceHealth"),
                        FunctionTool.create(ReActTools.class, "getServiceMetrics"),
                        FunctionTool.create(ReActTools.class, "searchServiceLogs"),
                        FunctionTool.create(ReActTools.class, "getRecentDeployments"),
                        FunctionTool.create(ReActTools.class, "getIncidentRunbook"))
                .maxSteps(8)
                .build();
    }

    public static void main(String[] args) {
        List<Plugin> plugins = List.of(new LoggingPlugin());
        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME,  plugins);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        String userInput = "生产环境的订单接口正在大面积超时。请定位最可能的原因，并给出处置建议。";
        Content userMessage = Content.fromParts(Part.fromText(userInput));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        System.out.println("用户 > " + userInput);
        System.out.println("\n[ReAct 轨迹]");

        ReActTrace trace = new ReActTrace();
        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(trace::accept);

        System.out.println("\n[Final Answer]");
        System.out.println(trace.finalAnswer().isBlank()
                ? "Agent 未生成最终文本，请检查模型调用和事件轨迹。"
                : trace.finalAnswer());
    }

    private static String extractText(Event event) {
        return event.content()
                .map(Content::text)
                .orElse("");
    }

    private static final class ReActTrace {

        private int actionNumber;
        private int observationNumber;
        private String finalAnswer = "";

        private void accept(Event event) {
            event.functionCalls().forEach(functionCall -> {
                actionNumber++;
                System.out.println("\n[Action " + actionNumber + "]");
                System.out.println(functionCall);
            });

            event.functionResponses().forEach(functionResponse -> {
                observationNumber++;
                System.out.println("\n[Observation " + observationNumber + "]");
                System.out.println(functionResponse);
            });

            String text = extractText(event);
            if (!text.isBlank() && event.functionCalls().isEmpty()) {
                finalAnswer = text;
            }
        }

        private String finalAnswer() {
            return finalAnswer;
        }
    }
}
