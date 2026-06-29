package com.hh.examples.study000.persistentCli.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hh.examples.study000.persistentCli.entity.SessionEventEntity;
import com.hh.examples.study000.persistentCli.mapper.SessionEventMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SnipService {

    private final SessionEventMapper sessionEventMapper;

    public SnipService(SessionEventMapper sessionEventMapper) {
        this.sessionEventMapper = sessionEventMapper;
    }

    public Map<String, Object> snipMessages(
            String appName,
            String userId,
            String sessionId,
            String snipEventId,
            List<String> eventIds,
            String reason) {
        List<String> normalizedIds = normalizeEventIds(eventIds);
        if (normalizedIds.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snippedCount", 0);
            result.put("snippedEventIds", List.of());
            result.put("missingEventIds", List.of());
            result.put("summary", "No event ids provided");
            return result;
        }

        List<SessionEventEntity> matchedRows = sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEventEntity>()
                .eq(SessionEventEntity::getAppName, appName)
                .eq(SessionEventEntity::getUserId, userId)
                .eq(SessionEventEntity::getSessionId, sessionId)
                .in(SessionEventEntity::getEventId, normalizedIds));

        List<String> matchedIds = matchedRows.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getSnipped()))
                .map(SessionEventEntity::getEventId)
                .toList();

        if (!matchedIds.isEmpty()) {
            sessionEventMapper.update(null, new LambdaUpdateWrapper<SessionEventEntity>()
                    .eq(SessionEventEntity::getAppName, appName)
                    .eq(SessionEventEntity::getUserId, userId)
                    .eq(SessionEventEntity::getSessionId, sessionId)
                    .in(SessionEventEntity::getEventId, matchedIds)
                    .set(SessionEventEntity::getSnipped, true)
                    .set(SessionEventEntity::getSnippedAt, LocalDateTime.now())
                    .set(SessionEventEntity::getSnipEventId, snipEventId)
                    .set(SessionEventEntity::getSnipReason, resolvedReason(reason, matchedIds.size())));
        }

        List<String> missingIds = normalizedIds.stream()
                .filter(eventId -> matchedRows.stream().noneMatch(row -> eventId.equals(row.getEventId())))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snippedCount", matchedIds.size());
        result.put("snippedEventIds", matchedIds);
        result.put("missingEventIds", missingIds);
        result.put("summary", resolvedReason(reason, matchedIds.size()));
        result.put("boundary", "Snip 工具调用本身会作为 FunctionResponse event 留在历史中，表示这之前的部分内容已被清理。");
        result.put("effect", "这些 event 会从后续 Runner 上下文中移除，但 session_events 与 messages 表仍保留完整原始记录。");
        return result;
    }

    public List<SessionEventEntity> listSnippedEvents(String appName, String userId, String sessionId) {
        return sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEventEntity>()
                .eq(SessionEventEntity::getAppName, appName)
                .eq(SessionEventEntity::getUserId, userId)
                .eq(SessionEventEntity::getSessionId, sessionId)
                .eq(SessionEventEntity::getSnipped, true)
                .orderByAsc(SessionEventEntity::getId));
    }

    public void clearSessionSnips(String appName, String userId, String sessionId) {
        sessionEventMapper.update(null, new LambdaUpdateWrapper<SessionEventEntity>()
                .eq(SessionEventEntity::getAppName, appName)
                .eq(SessionEventEntity::getUserId, userId)
                .eq(SessionEventEntity::getSessionId, sessionId)
                .set(SessionEventEntity::getSnipped, false)
                .set(SessionEventEntity::getSnippedAt, null)
                .set(SessionEventEntity::getSnipEventId, null)
                .set(SessionEventEntity::getSnipReason, null));
    }

    private List<String> normalizeEventIds(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String eventId : eventIds) {
            if (eventId == null || eventId.isBlank()) {
                continue;
            }
            String normalized = eventId.trim();
            if (seen.add(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String resolvedReason(String reason, int count) {
        return reason == null || reason.isBlank()
                ? "Snipped " + count + " messages"
                : reason;
    }
}
