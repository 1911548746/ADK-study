package com.hh.examples.study20.a2a;

import com.google.adk.a2a.agent.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.model.OpenAILangChainModelFactory;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.AgentCard;

import java.util.Map;

/** Consumes an A2A Agent as if it were an ADK sub-agent. */
public final class A2AConsumerExample {

    private static final String USER_ID = "study20-user";
    private static final String SESSION_ID = "study20-a2a-session";

    private A2AConsumerExample() {
    }

    public static void main(String[] args) {
        String remoteBaseUrl = args.length > 0
                ? args[0]
                : "http://localhost:" + A2AServerExample.DEFAULT_PORT + "/a2a/remote";

        BaseAgent remoteAgent = createRemoteAgent(remoteBaseUrl);
        LlmAgent rootAgent = LlmAgent.builder()
                .name("a2a_coordinator_agent")
                .description("决定何时把产品问题交给远程目录 Agent 的本地协调 Agent。")
                .model(OpenAILangChainModelFactory.createChatModel())
                .instruction("""
                        你是本地协调 Agent。
                        产品名称、价格、库存等目录问题必须委托给 product_catalog_agent，不能自己编造。
                        远程 Agent 返回结果后，用中文简洁地整理给用户。
                        如果用户的问题与产品目录无关，直接回答，并说明你只能协助目录查询。
                        """)
                .subAgents(remoteAgent)
                .build();

        InMemoryRunner runner = new InMemoryRunner(rootAgent);
        Session session = runner.sessionService()
                .createSession(runner.appName(), USER_ID, Map.of(), SESSION_ID)
                .blockingGet();

        String userInput = "请查询无线键盘的价格和库存。";
        System.out.println("用户 > " + userInput);
        System.out.println("\n[A2A 调用轨迹]");

        runner.runAsync(
                        session.userId(),
                        session.id(),
                        Content.fromParts(Part.fromText(userInput)),
                        RunConfig.builder().streamingMode(RunConfig.StreamingMode.NONE).build())
                .blockingForEach(A2AConsumerExample::printEvent);
    }

    private static BaseAgent createRemoteAgent(String remoteBaseUrl) {
        String agentCardUrl = remoteBaseUrl + "/.well-known/agent-card.json";
        AgentCard agentCard = new A2ACardResolver(
                new JdkA2AHttpClient(),
                remoteBaseUrl,
                agentCardUrl)
                .getAgentCard();

        Client a2aClient = Client.builder(agentCard)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .clientConfig(new ClientConfig.Builder()
                        .setStreaming(agentCard.capabilities().streaming())
                        .build())
                .build();

        return RemoteA2AAgent.builder()
                .name(agentCard.name())
                .description(agentCard.description())
                .a2aClient(a2aClient)
                .agentCard(agentCard)
                .build();
    }

    private static void printEvent(Event event) {
        String text = event.content()
                .map(Content::text)
                .orElse("");
        System.out.println("author=" + event.author()
                + (event.finalResponse() ? " [final]" : ""));
        if (!text.isBlank()) {
            System.out.println("content=" + text);
        }
        event.functionCalls().forEach(call ->
                System.out.println("functionCall=" + call.name().orElse("unknown")));
        event.functionResponses().forEach(response ->
                System.out.println("functionResponse=" + response.name().orElse("unknown")));
    }
}
