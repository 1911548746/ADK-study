package com.hh.examples.study20.a2a;

import com.google.adk.a2a.executor.AgentExecutorConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.sessions.InMemorySessionService;
import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.server.tasks.BasePushNotificationSender;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.PushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.tasks.TaskStore;
import io.a2a.transport.rest.handler.RestHandler;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exposes an ADK agent through the A2A REST/JSON-RPC boundary.
 *
 * <p>The official ADK Java sample uses Quarkus for the HTTP shell. This example
 * keeps the shell small and uses the A2A SDK request handler plus its REST
 * adapter with the JDK HTTP server, so the lifecycle is visible in one class.
 */
public final class A2AServerExample implements AutoCloseable {

    public static final int DEFAULT_PORT = 8081;
    public static final String SERVICE_PATH = "/a2a/remote/v1";
    public static final String CARD_PATH = "/a2a/remote/.well-known/agent-card.json";

    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final ExecutorService agentExecutor;

    private A2AServerExample(int port) throws IOException {
        BaseAgent agent = A2AProviderAgent.create();
        String serviceUrl = "http://localhost:" + port + SERVICE_PATH;
        AgentCard agentCard = createAgentCard(serviceUrl);

        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore);
        PushNotificationConfigStore pushConfigStore = new InMemoryPushNotificationConfigStore();
        PushNotificationSender pushSender = new BasePushNotificationSender(pushConfigStore);

        agentExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "study20-a2a-agent");
            thread.setDaemon(true);
            return thread;
        });

        com.google.adk.a2a.executor.AgentExecutor adkExecutor =
                new com.google.adk.a2a.executor.AgentExecutor.Builder()
                        .agent(agent)
                        .appName("study20_a2a_provider_app")
                        .artifactService(new InMemoryArtifactService())
                        .sessionService(new InMemorySessionService())
                        .memoryService(new InMemoryMemoryService())
                        .plugins(List.of(new LoggingPlugin()))
                        .agentExecutorConfig(AgentExecutorConfig.builder().build())
                        .build();

        RequestHandler requestHandler = DefaultRequestHandler.create(
                adkExecutor,
                taskStore,
                queueManager,
                pushConfigStore,
                pushSender,
                agentExecutor);
        RestHandler restHandler = new RestHandler(agentCard, requestHandler, agentExecutor);

        httpExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "study20-a2a-http");
            thread.setDaemon(true);
            return thread;
        });
        httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        httpServer.createContext(CARD_PATH, exchange -> sendAgentCard(exchange, restHandler));
        httpServer.createContext(SERVICE_PATH, exchange -> handleJsonRpc(exchange, restHandler));
        httpServer.setExecutor(httpExecutor);
    }

    public static A2AServerExample start(int port) throws IOException {
        A2AServerExample server = new A2AServerExample(port);
        server.httpServer.start();
        System.out.println("A2A server started");
        System.out.println("Agent Card > http://localhost:" + port + CARD_PATH);
        System.out.println("JSON-RPC    > http://localhost:" + port + SERVICE_PATH);
        return server;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        try (A2AServerExample ignored = start(port)) {
            System.out.println("按 Ctrl+C 停止 A2A server。\n");
            Thread.currentThread().join();
        }
    }

    private static AgentCard createAgentCard(String serviceUrl) {
        AgentSkill skill = new AgentSkill.Builder()
                .id("product-catalog")
                .name("Product catalog lookup")
                .description("查询产品名称、价格和库存。")
                .tags(List.of("product", "catalog", "inventory"))
                .examples(List.of("查询无线键盘的价格和库存"))
                .inputModes(List.of("text"))
                .outputModes(List.of("text"))
                .build();

        return new AgentCard.Builder()
                .name("product_catalog_agent")
                .description("提供产品目录、价格和库存查询能力的 A2A Agent。")
                .url(serviceUrl)
                .version("1.0.0")
                .protocolVersion("0.3.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(skill))
                .build();
    }

    private static void sendAgentCard(HttpExchange exchange, RestHandler restHandler) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        RestHandler.HTTPRestResponse response = restHandler.getAgentCard();
        send(exchange, response.getStatusCode(), response.getContentType(), response.getBody());
    }

    private static void handleJsonRpc(HttpExchange exchange, RestHandler restHandler) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        ServerCallContext callContext = new ServerCallContext(
                UnauthenticatedUser.INSTANCE,
                Map.of(),
                java.util.Set.of());
        RestHandler.HTTPRestResponse response = restHandler.sendMessage(requestBody, callContext);
        send(exchange, response.getStatusCode(), response.getContentType(), response.getBody());
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        httpServer.stop(0);
        httpExecutor.shutdownNow();
        agentExecutor.shutdownNow();
    }
}
