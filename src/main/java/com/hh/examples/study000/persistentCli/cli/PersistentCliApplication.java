package com.hh.examples.study000.persistentCli.cli;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.hh.examples.study000.persistentCli.cli.command.CommandResult;
import com.hh.examples.study000.persistentCli.cli.command.PersistentCliCommandHandler;
import com.hh.examples.study000.persistentCli.entity.ConversationEntity;
import com.hh.examples.study000.persistentCli.service.ConversationArchiveService;
import com.hh.examples.study000.persistentCli.service.MemoryWritePolicy;
import com.hh.examples.study000.persistentCli.support.PersistentCliProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Scanner;

@Component
public class PersistentCliApplication {

    private final Runner runner;
    private final ConversationArchiveService archiveService;
    private final MemoryWritePolicy memoryWritePolicy;
    private final PersistentCliCommandHandler commandHandler;

    public PersistentCliApplication(
            Runner persistentCliRunner,
            ConversationArchiveService archiveService,
            MemoryWritePolicy memoryWritePolicy,
            PersistentCliCommandHandler commandHandler) {
        this.runner = persistentCliRunner;
        this.archiveService = archiveService;
        this.memoryWritePolicy = memoryWritePolicy;
        this.commandHandler = commandHandler;
    }

    public void run() {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        String userId = PersistentCliProperties.DEFAULT_USER_ID;
        Session session = commandHandler.createNewSession(userId);
        ConversationEntity conversation = commandHandler.findOrCreateConversation(userId, session);

        commandHandler.printWelcome(session);

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("\n你 > ");
                System.out.flush();
                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();
                if (input.isBlank()) {
                    continue;
                }

                if (input.startsWith("/")) {
                    CommandResult result = commandHandler.handle(input, userId, session, conversation);
                    if (result.exit()) {
                        break;
                    }
                    session = result.session();
                    conversation = result.conversation();
                    continue;
                }

                runTurn(session, conversation, input, runConfig);
            }
        }
    }

    private void runTurn(
            Session session,
            ConversationEntity conversation,
            String userInput,
            RunConfig runConfig) {
        // 完整聊天记录属于业务留档，先入库；它不参与 ADK event compaction。
        archiveService.saveUserMessage(
                conversation.getId(),
                runner.appName(),
                session.userId(),
                session.id(),
                userInput);

        Content userMessage = Content.fromParts(Part.fromText(userInput));
        StringBuilder partialAnswer = new StringBuilder();
        String[] finalAnswer = {""};
        boolean[] printedPartial = {false};
        boolean[] sawExternalizedToolResult = {false};
        boolean[] readExternalizedResult = {false};

        System.out.print("\n智能体 > ");
        try {
            runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                    .blockingForEach(event -> {
                        if (containsExternalizedToolResult(event)) {
                            sawExternalizedToolResult[0] = true;
                        }
                        if (callsExternalizedResultReader(event)) {
                            readExternalizedResult[0] = true;
                        }

                        String text = extractText(event);
                        if (event.partial().orElse(false) && !text.isBlank()) {
                            printedPartial[0] = true;
                            partialAnswer.append(text);
                            System.out.print(text);
                            System.out.flush();
                        }

                        if (event.finalResponse()) {
                            finalAnswer[0] = event.stringifyContent();
                            if (!printedPartial[0]) {
                                System.out.print(finalAnswer[0]);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            boolean hasUsableAnswer = !finalAnswer[0].isBlank() || !partialAnswer.isEmpty();
            if (!hasUsableAnswer) {
                System.out.println();
                System.out.println("[error] 模型连接中断，本轮没有拿到有效回复，请稍后重试。原因: "
                        + rootCauseMessage(e));
                return;
            }
            System.out.println();
            System.out.println("[warn] 本轮回复已生成，但后续连接出现抖动，已继续保存可用回复。原因: "
                    + rootCauseMessage(e));
        }

        String assistantText = !finalAnswer[0].isBlank() ? finalAnswer[0] : partialAnswer.toString();
        archiveService.saveAssistantMessage(
                conversation.getId(),
                runner.appName(),
                session.userId(),
                session.id(),
                assistantText);

        // 如果模型只拿到了大工具结果预览，但没有继续读取完整片段，给操作者一个上下文完整性提示。
        if (sawExternalizedToolResult[0] && !readExternalizedResult[0]) {
            System.out.println("[context] 本轮存在外部化工具结果，智能体未继续读取完整片段；回答可能只基于预览内容。");
        }

        // Memory 写入是应用层策略：Runner 不会自动决定哪些对话应该进入长期记忆。
        if (memoryWritePolicy.shouldRemember(userInput)) {
            Session updatedSession = runner.sessionService()
                    .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                    .blockingGet();
            runner.memoryService()
                    .addSessionToMemory(updatedSession)
                    .blockingAwait();
            System.out.println("[memory] 已尝试写入长期记忆，可用 /memory 关键词 检索观察。");
        }
    }

    private String extractText(Event event) {
        return event.content()
                .map(Content::text)
                .orElse("");
    }

    private boolean containsExternalizedToolResult(Event event) {
        return event.functionResponses().stream()
                .map(FunctionResponse::response)
                .flatMap(Optional::stream)
                .anyMatch(response -> Boolean.TRUE.equals(response.get("externalized")));
    }

    private boolean callsExternalizedResultReader(Event event) {
        return event.functionCalls().stream()
                .map(FunctionCall::name)
                .flatMap(Optional::stream)
                .anyMatch("read_externalized_result"::equals);
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
