package com.hh.examples.study18.hitl;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public final class HitlExample {

    private static final String APP_NAME = "study18_hitl_app";
    private static final String USER_ID = "study18_user";
    private static final String REQUEST_CONFIRMATION_FUNCTION_NAME = "adk_request_confirmation";
    private static final String REFUND_REQUEST = """
            用户申请取消订单 ORD-20260731-001。
            请为该订单退款 12800 分，原因为“重复扣款”。
            你必须调用 request_refund，不能声称退款已经完成，除非工具明确返回 refund_executed。
            """;

    private HitlExample() {
    }

    public static LlmAgent createAgent() {
        return LlmAgent.builder()
                .name("refund_hitl_agent")
                .description("在执行退款前请求人工确认的 HITL Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
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

    public static void main(String[] args) {
        runScenario(true);
//        runScenario(false);
    }

    private static void runScenario(boolean approved) {
        String sessionId = approved ? "hitl_approved_session" : "hitl_rejected_session";
        InMemoryRunner runner = new InMemoryRunner(createAgent(), APP_NAME);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), sessionId)
                .blockingGet();
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        System.out.println("\n==================================================");
        System.out.println("[HITL 场景] 人工决定：" + (approved ? "批准" : "拒绝"));
        System.out.println("用户 > " + REFUND_REQUEST.strip());

        List<Event> pendingEvents = runner.runAsync(
                        session.userId(),
                        session.id(),
                        Content.fromParts(Part.fromText(REFUND_REQUEST)),
                        runConfig)
                .toList()
                .blockingGet();

        System.out.println("\n[阶段 1：Agent 请求确认]");
        printEvents(pendingEvents);

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        System.out.print("\n请输入人工确认结果（Y/N）：");
        String humanDecisionText = scanner.nextLine();
        approved = humanDecisionText.toLowerCase().contains("y");
        System.out.println("confirmed = " + approved);

        FunctionCall confirmationCall = findConfirmationCall(pendingEvents);
        Content humanDecision = createHumanDecision(confirmationCall, approved);

        System.out.println("\n[阶段 2：人工确认回写到同一 Session]");

        List<Event> resumedEvents = runner.runAsync(
                        session.userId(),
                        session.id(),
                        humanDecision,
                        runConfig)
                .toList()
                .blockingGet();

        printEvents(resumedEvents);
        String finalAnswer = latestText(resumedEvents);
        System.out.println("\n[最终回答]");
        System.out.println(finalAnswer.isBlank() ? "未生成最终文本。" : finalAnswer);
    }

    private static FunctionCall findConfirmationCall(List<Event> events) {
        return events.stream()
                .flatMap(event -> event.functionCalls().stream())
                .filter(functionCall -> REQUEST_CONFIRMATION_FUNCTION_NAME.equals(
                        functionCall.name().orElse("")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "未收到 " + REQUEST_CONFIRMATION_FUNCTION_NAME + "。请确认模型调用了 request_refund。"));
    }

    private static Content createHumanDecision(FunctionCall confirmationCall, boolean approved) {
        String confirmationCallId = confirmationCall.id()
                .orElseThrow(() -> new IllegalStateException("确认 FunctionCall 缺少 id。"));

        FunctionResponse confirmationResponse = FunctionResponse.builder()
                .id(confirmationCallId)
                .name(REQUEST_CONFIRMATION_FUNCTION_NAME)
                .response(Map.of("confirmed", approved))
                .build();

        return Content.fromParts(Part.builder().functionResponse(confirmationResponse).build());
    }

    private static void printEvents(List<Event> events) {
        for (Event event : events) {
            event.functionCalls().forEach(functionCall -> {
                if (REQUEST_CONFIRMATION_FUNCTION_NAME.equals(functionCall.name().orElse(""))) {
                    System.out.println("\n[Pending Confirmation]");
                } else {
                    System.out.println("\n[Tool Call]");
                }
                System.out.println(functionCall);
            });

            event.functionResponses().forEach(functionResponse -> {
                System.out.println("\n[Tool Response]");
                System.out.println(functionResponse);
            });
        }
    }

    private static String latestText(List<Event> events) {
        String answer = "";
        for (Event event : events) {
            String text = event.content().map(Content::text).orElse("");
            if (!text.isBlank() && event.functionCalls().isEmpty()) {
                answer = text;
            }
        }
        return answer;
    }
}
