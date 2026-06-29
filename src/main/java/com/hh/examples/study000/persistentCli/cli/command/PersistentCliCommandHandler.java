package com.hh.examples.study000.persistentCli.cli.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.adk.events.Event;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.hh.examples.study000.persistentCli.entity.ConversationEntity;
import com.hh.examples.study000.persistentCli.entity.SessionEventEntity;
import com.hh.examples.study000.persistentCli.mapper.SessionEventMapper;
import com.hh.examples.study000.persistentCli.service.ConversationArchiveService;
import com.hh.examples.study000.persistentCli.service.SnipService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PersistentCliCommandHandler {

    private final Runner runner;
    private final ConversationArchiveService archiveService;
    private final SessionEventMapper sessionEventMapper;
    private final SnipService snipService;

    public PersistentCliCommandHandler(
            Runner persistentCliRunner,
            ConversationArchiveService archiveService,
            SessionEventMapper sessionEventMapper,
            SnipService snipService) {
        this.runner = persistentCliRunner;
        this.archiveService = archiveService;
        this.sessionEventMapper = sessionEventMapper;
        this.snipService = snipService;
    }

    public CommandResult handle(
            String input,
            String userId,
            Session currentSession,
            ConversationEntity currentConversation) {
        if ("/exit".equalsIgnoreCase(input)) {
            System.out.println("已退出。");
            return new CommandResult(currentSession, currentConversation, true);
        }
        if ("/help".equalsIgnoreCase(input)) {
            printHelp();
            return new CommandResult(currentSession, currentConversation, false);
        }
        if ("/new".equalsIgnoreCase(input)) {
            Session session = createNewSession(userId);
            ConversationEntity conversation = findOrCreateConversation(userId, session);
            System.out.println("已创建新 Session: " + session.id());
            return new CommandResult(session, conversation, false);
        }
        if (input.startsWith("/resume")) {
            return resumeSession(input, userId, currentSession, currentConversation);
        }
        if (input.startsWith("/delete")) {
            return deleteSession(input, userId, currentSession, currentConversation);
        }
        if ("/sessions".equalsIgnoreCase(input)) {
            printSessions(userId);
            return new CommandResult(currentSession, currentConversation, false);
        }
        if ("/events".equalsIgnoreCase(input)) {
            printEvents(currentSession);
            return new CommandResult(currentSession, currentConversation, false);
        }
        if ("/current events".equalsIgnoreCase(input)) {
            printCurrentEvents(currentSession);
            return new CommandResult(currentSession, currentConversation, false);
        }
        if ("/snips".equalsIgnoreCase(input)) {
            printSnips(currentSession);
            return new CommandResult(currentSession, currentConversation, false);
        }
        if ("/state".equalsIgnoreCase(input)) {
            printState(currentSession);
            return new CommandResult(currentSession, currentConversation, false);
        }
        if (input.startsWith("/memory")) {
            String query = input.replaceFirst("/memory", "").trim();
            printMemory(userId, query);
            return new CommandResult(currentSession, currentConversation, false);
        }

        System.out.println("未知命令，输入 /help 查看可用命令。");
        return new CommandResult(currentSession, currentConversation, false);
    }

    public Session createNewSession(String userId) {
        return runner.sessionService()
                .createSession(runner.appName(), userId)
                .blockingGet();
    }

    public ConversationEntity findOrCreateConversation(String userId, Session session) {
        return archiveService.findOrCreateConversation(runner.appName(), userId, session.id());
    }

    public void printWelcome(Session session) {
        System.out.println("study000 MySQL 持久化 CLI Agent 已启动");
        System.out.println("当前 Session: " + session.id());
        printHelp();
    }

    public void printHelp() {
        System.out.println("""
                
                可用命令：
                /new                  新建 Session
                /resume <sessionId>   恢复 Session
                /delete <sessionId>   删除 Session
                /sessions             查看 Session 列表
                /events               查看数据库中的全部 ADK Events
                /current events       查看 Runner 当前会使用的上下文 Events
                /snips                查看当前 Session 的 Snip 记录
                /state                查看当前 Session State
                /memory <query>       检索长期 Memory
                /help                 查看帮助
                /exit                 退出
                """);
    }

    private CommandResult resumeSession(
            String input,
            String userId,
            Session currentSession,
            ConversationEntity currentConversation) {
        String sessionId = input.replaceFirst("/resume", "").trim();
        if (sessionId.isBlank()) {
            System.out.println("用法：/resume <sessionId>");
            return new CommandResult(currentSession, currentConversation, false);
        }
        Session session = runner.sessionService()
                .getSession(runner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            System.out.println("未找到 Session: " + sessionId);
            return new CommandResult(currentSession, currentConversation, false);
        }
        ConversationEntity conversation = findOrCreateConversation(userId, session);
        System.out.println("已恢复 Session: " + session.id());
        return new CommandResult(session, conversation, false);
    }

    private CommandResult deleteSession(
            String input,
            String userId,
            Session currentSession,
            ConversationEntity currentConversation) {
        String sessionId = input.replaceFirst("/delete", "").trim();
        if (sessionId.isBlank()) {
            System.out.println("用法：/delete <sessionId>");
            return new CommandResult(currentSession, currentConversation, false);
        }

        Session session = runner.sessionService()
                .getSession(runner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            System.out.println("未找到 Session: " + sessionId);
            return new CommandResult(currentSession, currentConversation, false);
        }

        runner.sessionService()
                .deleteSession(runner.appName(), userId, sessionId)
                .blockingAwait();
        archiveService.deleteConversation(runner.appName(), userId, sessionId);
        System.out.println("已删除 Session 及其完整聊天记录: " + sessionId);

        if (currentSession.id().equals(sessionId)) {
            Session newSession = createNewSession(userId);
            ConversationEntity newConversation = findOrCreateConversation(userId, newSession);
            System.out.println("当前 Session 已被删除，已自动切换到新 Session: " + newSession.id());
            return new CommandResult(newSession, newConversation, false);
        }
        return new CommandResult(currentSession, currentConversation, false);
    }

    private void printSessions(String userId) {
        ListSessionsResponse response = runner.sessionService()
                .listSessions(runner.appName(), userId)
                .blockingGet();
        System.out.println("Session 数量: " + response.sessions().size());
        response.sessions().forEach(session ->
                System.out.println("- " + session.id() + " | updated=" + session.lastUpdateTime()));
    }

    private void printEvents(Session session) {
        List<SessionEventEntity> rows = sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEventEntity>()
                .eq(SessionEventEntity::getAppName, runner.appName())
                .eq(SessionEventEntity::getUserId, session.userId())
                .eq(SessionEventEntity::getSessionId, session.id())
                .orderByAsc(SessionEventEntity::getId));
        long snippedCount = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.getSnipped()))
                .count();

        System.out.println("Event 数量: " + rows.size()
                + " | snipped=" + snippedCount
                + " | active=" + (rows.size() - snippedCount));
        rows.forEach(row -> System.out.println("- eventId=" + row.getEventId()
                + " | author=" + row.getAuthor()
                + " | final=" + row.getFinalResponse()
                + " | partial=" + row.getPartial()
                + " | compaction=" + row.getCompactionEvent()
                + " | snipped=" + Boolean.TRUE.equals(row.getSnipped())
                + " | text=" + abbreviate(row.getContentText())));
    }

    private void printCurrentEvents(Session session) {
        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();
        if (updatedSession == null) {
            System.out.println("当前 Session 不存在: " + session.id());
            return;
        }

        System.out.println("Current Event 数量: " + updatedSession.events().size());
        updatedSession.events().forEach(event -> {
            boolean compaction = event.actions() != null && event.actions().compaction().isPresent();
            System.out.println("- eventId=" + event.id()
                    + " | author=" + event.author()
                    + " | final=" + event.finalResponse()
                    + " | partial=" + event.partial().orElse(false)
                    + " | compaction=" + compaction
                    + " | text=" + abbreviate(eventContentText(event)));
        });
    }

    private void printSnips(Session session) {
        List<SessionEventEntity> rows = snipService.listSnippedEvents(
                runner.appName(),
                session.userId(),
                session.id());
        System.out.println("Snip 数量: " + rows.size());
        rows.forEach(row -> System.out.println("- eventId=" + row.getEventId()
                + " | snipEventId=" + row.getSnipEventId()
                + " | snippedAt=" + row.getSnippedAt()
                + " | reason=" + abbreviate(row.getSnipReason())));
    }

    private void printState(Session session) {
        Session updatedSession = runner.sessionService()
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();
        if (updatedSession == null || updatedSession.state().isEmpty()) {
            System.out.println("当前 Session State 为空。");
            return;
        }
        updatedSession.state().forEach((key, value) ->
                System.out.println(key + " = " + value));
    }

    private void printMemory(String userId, String query) {
        SearchMemoryResponse response = runner.memoryService()
                .searchMemory(runner.appName(), userId, query)
                .blockingGet();
        System.out.println("Memory 命中数量: " + response.memories().size());
        for (MemoryEntry memory : response.memories()) {
            System.out.println("- " + memory.author() + " | " + memory.timestamp()
                    + " | " + memory.content().text());
        }
    }

    private String eventContentText(Event event) {
        String text = event.stringifyContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (event.actions() == null || event.actions().compaction().isEmpty()) {
            return text;
        }
        return event.actions()
                .compaction()
                .map(compaction -> compaction.compactedContent().text())
                .orElse(text);
    }

    private String abbreviate(String text) {
        if (text == null || text.length() <= 80) {
            return text;
        }
        return text.substring(0, 80) + "...";
    }
}
